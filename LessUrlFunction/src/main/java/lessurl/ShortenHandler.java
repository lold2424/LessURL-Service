package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ShortenHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient ddb;
    private final Gson gson;
    private final String tableName;
    private final String geminiApiKey;
    private final String safeBrowsingApiKey;
    private final HttpClient httpClient;

    public ShortenHandler() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.tableName = System.getenv("URLS_TABLE");
        this.geminiApiKey = System.getenv("GEMINI_API_KEY");
        this.safeBrowsingApiKey = System.getenv("SAFE_BROWSING_API_KEY");

        String dynamoDbEndpoint = System.getenv("DYNAMODB_ENDPOINT");
        var clientBuilder = DynamoDbClient.builder()
                .httpClient(UrlConnectionHttpClient.create());

        if (dynamoDbEndpoint != null && !dynamoDbEndpoint.isEmpty()) {
            System.out.println("DEBUG: Connecting to Local DynamoDB at " + dynamoDbEndpoint);
            clientBuilder
                    .endpointOverride(URI.create(dynamoDbEndpoint))
                    .region(Region.of(System.getenv("AWS_REGION")));
        } else {
            System.out.println("DEBUG: Connecting to Production AWS DynamoDB");
        }
        this.ddb = clientBuilder.build();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    protected ShortenHandler(DynamoDbClient ddb, Gson gson, String tableName, String geminiApiKey, String safeBrowsingApiKey, HttpClient httpClient) {
        this.ddb = ddb;
        this.gson = gson;
        this.tableName = tableName;
        this.geminiApiKey = geminiApiKey;
        this.safeBrowsingApiKey = safeBrowsingApiKey;
        this.httpClient = httpClient;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        LambdaLogger logger = context.getLogger();
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", System.getenv("CORS_ALLOWED_ORIGIN"));
        
        try {
            String body = input.getBody();
            if (body == null || body.isEmpty()) return createErrorResponse(400, "Body is empty", headers);

            Map<String, String> requestData = gson.fromJson(body, Map.class);
            String originalUrl = requestData.get("url");
            String customAlias = requestData.get("customAlias");
            String visibility = requestData.getOrDefault("visibility", "PRIVATE").toUpperCase();

            if (originalUrl == null || originalUrl.isEmpty()) return createErrorResponse(400, "URL is required", headers);
            if (!originalUrl.startsWith("http")) originalUrl = "https://" + originalUrl;

            if (isUrlMaliciousWithGemini(originalUrl, logger) || isUrlMaliciousWithSafeBrowsing(originalUrl, logger)) {
                return createErrorResponse(400, "유해 URL이 감지되었습니다.", headers);
            }

            String aiTitle = generateTitleWithAi(originalUrl, logger);

            if (customAlias != null && !customAlias.trim().isEmpty()) {
                String alias = customAlias.trim();
                if (!alias.matches("^[a-zA-Z0-9_-]{3,20}$")) return createErrorResponse(400, "Invalid alias format", headers);

                if (isAliasTaken(alias, logger)) {
                    return createErrorResponse(400, "이미 사용 중인 이름입니다.", headers);
                }
            }

            String baseUrl = System.getenv("BASE_URL");
            String shortId = null;
            int maxRetries = 5;
            boolean saved = false;

            for (int i = 0; i < maxRetries; i++) {
                shortId = UUID.randomUUID().toString().substring(0, 8);
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
                            .tableName(this.tableName)
                            .item(item)
                            .conditionExpression("attribute_not_exists(shortId)")
                            .build());
                    logger.log("[Success] Data saved to DynamoDB for: " + shortId);
                    saved = true;
                    break;
                } catch (ConditionalCheckFailedException e) {
                    if (i == maxRetries - 1) throw new RuntimeException("ID creation failed due to collisions");
                }
            }

            if (!saved) throw new RuntimeException("Failed to save URL after retries");

            String finalPath = (customAlias != null && !customAlias.trim().isEmpty()) ? customAlias.trim() : shortId;
            String shortUrl = formatShortUrl(baseUrl, finalPath, input);

            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("shortId", shortId);
            responseBody.put("shortUrl", shortUrl);
            responseBody.put("title", aiTitle);

            response.setStatusCode(200);
            response.setHeaders(headers);
            response.setBody(gson.toJson(responseBody));

        } catch (Exception e) {
            logger.log("[Error] " + e.getMessage());
            return createErrorResponse(500, "Server Error: " + e.getMessage(), headers);
        }
        return response;
    }

    private boolean isAliasTaken(String value, LambdaLogger logger) {
        try {
            GetItemResponse res = ddb.getItem(GetItemRequest.builder()
                    .tableName(this.tableName)
                    .key(Map.of("shortId", AttributeValue.builder().s(value).build()))
                    .build());
            if (res.hasItem()) return true;

            ScanResponse scanRes = ddb.scan(ScanRequest.builder()
                    .tableName(this.tableName)
                    .filterExpression("#a = :v")
                    .expressionAttributeNames(Map.of("#a", "customAlias"))
                    .expressionAttributeValues(Map.of(":v", AttributeValue.builder().s(value).build()))
                    .build());
            
            return scanRes.count() > 0;
        } catch (Exception e) {
            logger.log("isAliasTaken error: " + e.getMessage());
            return false;
        }
    }

    private String generateTitleWithAi(String url, LambdaLogger logger) {
        if (this.geminiApiKey == null || this.geminiApiKey.isEmpty()) return "Untitled Link";
        try {
            String prompt = String.format("사이트 성격을 잘 나타내는 짧은 한국어 제목 하나만 지어줘. 설명 없이 제목만 응답해. URL: %s", url);
            String body = String.format("{\"contents\":[{\"parts\":[{\"text\":\"%s\"}]}]}", prompt);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + this.geminiApiKey)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
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
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + this.geminiApiKey)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return false;
            Map<String, Object> map = gson.fromJson(res.body(), Map.class);
            List<Object> cand = (List<Object>) map.get("candidates");
            Map<String, Object> cont = (Map<String, Object>) ((Map<String, Object>) cand.get(0)).get("content");
            String inner = (String) ((Map<String, Object>) ((List<Object>) cont.get("parts")).get(0)).get("text");
            return !inner.contains("SAFE");
        } catch (Exception e) { return false; }
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int code, String msg, Map<String, String> h) {
        APIGatewayProxyResponseEvent r = new APIGatewayProxyResponseEvent();
        r.setStatusCode(code);
        r.setHeaders(h);
        r.setBody(gson.toJson(Map.of("error", msg)));
        return r;
    }
}
