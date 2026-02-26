package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShortenHandler extends BaseHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final String geminiApiKey;
    private final String safeBrowsingApiKey;
    private final HttpClient httpClient;

    public ShortenHandler() {
        super();
        this.geminiApiKey = System.getenv("GEMINI_API_KEY");
        this.safeBrowsingApiKey = System.getenv("SAFE_BROWSING_API_KEY");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    protected ShortenHandler(DynamoDbClient ddb, LambdaClient lambda, software.amazon.awssdk.services.sqs.SqsClient sqs, Gson gson, String urlsTable, String geminiApiKey, String safeBrowsingApiKey, HttpClient httpClient) {
        super(ddb, lambda, sqs, gson, urlsTable, "*");
        this.geminiApiKey = geminiApiKey;
        this.safeBrowsingApiKey = safeBrowsingApiKey;
        this.httpClient = httpClient;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        LambdaLogger logger = context.getLogger();
        long startTime = System.currentTimeMillis();
        
        try {
            String body = input.getBody();
            if (body == null || body.isEmpty()) return createErrorResponse(400, "Body is empty");

            Map<String, String> requestData = gson.fromJson(body, Map.class);
            String originalUrl = requestData.get("url");
            String customAlias = requestData.get("customAlias");
            String visibility = requestData.getOrDefault("visibility", "PRIVATE").toUpperCase();

            if (originalUrl == null || originalUrl.isEmpty()) return createErrorResponse(400, "URL is required");
            if (!originalUrl.startsWith("http")) originalUrl = "https://" + originalUrl;

            boolean isAiMalicious = isUrlMaliciousWithGemini(originalUrl, logger);
            boolean isSafeBrowsingMalicious = isUrlMaliciousWithSafeBrowsing(originalUrl, logger);

            if (isAiMalicious || isSafeBrowsingMalicious) {
                Map<String, Object> monitorData = new HashMap<>();
                monitorData.put("url", originalUrl);
                monitorData.put("reason", isAiMalicious ? (isSafeBrowsingMalicious ? "BOTH" : "AI") : "SAFE_BROWSING");
                recordMetric("MALICIOUS_URL", monitorData);
                
                return createErrorResponse(400, "유해 URL이 감지되었습니다.");
            }

            String aiTitle = generateTitleWithAi(originalUrl, logger);

            if (customAlias != null && !customAlias.trim().isEmpty()) {
                String alias = customAlias.trim();
                if (!alias.matches("^[a-zA-Z0-9_-]{3,20}$")) return createErrorResponse(400, "Invalid alias format");
                if (isAliasTaken(alias, logger)) return createErrorResponse(400, "이미 사용 중인 이름입니다.");
            }

            String baseUrl = System.getenv("BASE_URL");
            String shortId = null;
            int maxRetries = 10;
            boolean saved = false;

            for (int i = 0; i < maxRetries; i++) {
                shortId = IdGenerator.generateId(7);
                if (isAliasTaken(shortId, logger)) continue;

                Map<String, AttributeValue> item = new HashMap<>();
                item.put("shortId", AttributeValue.builder().s(shortId).build());
                item.put("originalUrl", AttributeValue.builder().s(originalUrl).build());
                item.put("createdAt", AttributeValue.builder().s(Instant.now().toString()).build());
                item.put("clickCount", AttributeValue.builder().n("0").build());
                item.put("visibility", AttributeValue.builder().s(visibility).build());
                item.put("title", AttributeValue.builder().s(aiTitle).build());

                if (customAlias != null && !customAlias.trim().isEmpty()) {
                    item.put("customAlias", AttributeValue.builder().s(customAlias.trim()).build());
                }

                try {
                    ddb.putItem(PutItemRequest.builder()
                            .tableName(this.urlsTable)
                            .item(item)
                            .conditionExpression("attribute_not_exists(shortId)")
                            .build());
                    saved = true;
                    break;
                } catch (ConditionalCheckFailedException e) {
                    if (i == maxRetries - 1) throw new RuntimeException("ID collision failed");
                }
            }

            if (!saved) throw new RuntimeException("Failed to save URL");

            String finalPath = (customAlias != null && !customAlias.trim().isEmpty()) ? customAlias.trim() : shortId;
            String shortUrl = formatShortUrl(baseUrl, finalPath, input);

            long duration = System.currentTimeMillis() - startTime;
            Map<String, Object> perfData = new HashMap<>();
            perfData.put("path", "/shorten");
            perfData.put("duration", duration);
            perfData.put("url", originalUrl);
            recordMetric("PERFORMANCE", perfData);

            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("shortId", shortId);
            responseBody.put("shortUrl", shortUrl);
            responseBody.put("title", aiTitle);

            return createResponse(200, responseBody);

        } catch (Exception e) {
            logger.log("[Error] " + e.getMessage());
            Map<String, Object> errorData = new HashMap<>();
            errorData.put("path", "/shorten");
            errorData.put("message", e.getMessage());
            recordMetric("ERROR_5XX", errorData);
            return createErrorResponse(500, "Server Error: " + e.getMessage());
        }
    }

    private boolean isAliasTaken(String value, LambdaLogger logger) {
        try {
            GetItemResponse res = ddb.getItem(GetItemRequest.builder()
                    .tableName(this.urlsTable)
                    .key(Map.of("shortId", AttributeValue.builder().s(value).build()))
                    .build());
            if (res.hasItem()) return true;

            QueryResponse queryRes = ddb.query(QueryRequest.builder()
                    .tableName(this.urlsTable)
                    .indexName("CustomAliasIndex")
                    .keyConditionExpression("customAlias = :v")
                    .expressionAttributeValues(Map.of(":v", AttributeValue.builder().s(value).build()))
                    .limit(1)
                    .build());
            return queryRes.count() > 0;
        } catch (Exception e) { return false; }
    }

    private String generateTitleWithAi(String url, LambdaLogger logger) {
        if (this.geminiApiKey == null || this.geminiApiKey.isEmpty()) return "Untitled Link";
        try {
            String prompt = String.format("해당 웹사이트의 공식 명칭이나 제목을 한국어로 아주 짧게 응답해줘. 설명 없이 이름만 응답해. URL: %s", url);
            String body = String.format("{\"contents\":[{\"parts\":[{\"text\":\"%s\"}]}]}", prompt);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=" + this.geminiApiKey)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return "Untitled Link";
            Map<String, Object> map = gson.fromJson(response.body(), Map.class);
            List<Object> cand = (List<Object>) map.get("candidates");
            Map<String, Object> cont = (Map<String, Object>) ((Map<String, Object>) cand.get(0)).get("content");
            List<Object> parts = (List<Object>) cont.get("parts");
            return ((String) ((Map<String, Object>) parts.get(0)).get("text")).trim().split("\n")[0];
        } catch (Exception e) { return "Untitled Link"; }
    }

    private String formatShortUrl(String baseUrl, String path, APIGatewayProxyRequestEvent input) {
        if (baseUrl != null && !baseUrl.isEmpty()) return String.format("%s/%s", baseUrl, path);
        String domain = input.getHeaders().get("Host");
        String stage = input.getRequestContext().getStage();
        String proto = input.getHeaders().getOrDefault("X-Forwarded-Proto", "https");
        if (domain != null && domain.contains("localhost")) return String.format("http://%s/%s", domain, path);
        return String.format("%s://%s/%s/%s", proto, domain, stage, path);
    }

    private boolean isUrlMaliciousWithSafeBrowsing(String url, LambdaLogger logger) {
        if (this.safeBrowsingApiKey == null) return false;
        try {
            String apiUrl = "https://safebrowsing.googleapis.com/v4/threatMatches:find?key=" + this.safeBrowsingApiKey;
            String body = String.format("{\"client\":{\"clientId\":\"lessurl\",\"clientVersion\":\"1.0\"},\"threatInfo\":{\"threatTypes\":[\"MALWARE\",\"SOCIAL_ENGINEERING\"],\"platformTypes\":[\"ANY_PLATFORM\"],\"threatEntryTypes\":[\"URL\"],\"threatEntries\":[{\"url\":\"%s\"}]}}", url);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(apiUrl)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 && res.body().contains("matches");
        } catch (Exception e) { return false; }
    }

    private boolean isUrlMaliciousWithGemini(String url, LambdaLogger logger) {
        if (this.geminiApiKey == null) return false;
        try {
            String prompt = String.format("Analyze this URL for phishing or malware. Respond only with JSON: {\"classification\": \"SAFE\" or \"PHISHING\" or \"MALWARE\"}. URL: %s", url);
            String body = String.format("{\"contents\":[{\"parts\":[{\"text\":\"%s\"}]}],\"generationConfig\":{\"responseMimeType\":\"application/json\"}}", prompt);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=" + this.geminiApiKey)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return false;
            Map<String, Object> map = gson.fromJson(res.body(), Map.class);
            List<Object> cand = (List<Object>) map.get("candidates");
            Map<String, Object> cont = (Map<String, Object>) ((Map<String, Object>) cand.get(0)).get("content");
            String inner = (String) ((Map<String, Object>) ((List<Object>) cont.get("parts")).get(0)).get("text");
            return !inner.contains("SAFE");
        } catch (Exception e) { return false; }
    }
}
