package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.io.IOException;
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
            clientBuilder
                    .endpointOverride(URI.create(dynamoDbEndpoint))
                    .region(Region.of(System.getenv("AWS_REGION")));
        }
        this.ddb = clientBuilder.build();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
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
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", System.getenv("CORS_ALLOWED_ORIGIN"));
        headers.put("Access-Control-Allow-Methods", "POST,OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        try {
            String body = input.getBody();
            if (body == null || body.isEmpty()) {
                return createErrorResponse(400, "요청 본문이 비어 있습니다.", headers);
            }

            Map<String, String> requestData = gson.fromJson(body, Map.class);
            String originalUrl = requestData.get("url");

            String title = requestData.get("title");

            if (originalUrl == null || originalUrl.trim().isEmpty()) {
                return createErrorResponse(400, "URL은 필수 항목입니다.", headers);
            }
            if (!originalUrl.startsWith("http")) {
                originalUrl = "https://" + originalUrl;
            }

            if (isUrlMaliciousWithGemini(originalUrl, context.getLogger()) || isUrlMaliciousWithSafeBrowsing(originalUrl, context.getLogger())) {
                return createErrorResponse(400, "유해 URL이 감지되었습니다.", headers);
            }


            String shortId = null;
            int maxRetries = 5;
            for (int i = 0; i < maxRetries; i++) {
                shortId = UUID.randomUUID().toString().substring(0, 8);

                Map<String, AttributeValue> item = new HashMap<>();
                item.put("shortId", AttributeValue.builder().s(shortId).build());
                item.put("originalUrl", AttributeValue.builder().s(originalUrl).build());
                item.put("createdAt", AttributeValue.builder().s(Instant.now().toString()).build());
                item.put("clickCount", AttributeValue.builder().n("0").build());

                if (title != null && !title.isEmpty()) {
                    item.put("title", AttributeValue.builder().s(title).build());
                }

                PutItemRequest putRequest = PutItemRequest.builder()
                        .tableName(this.tableName)
                        .item(item)
                        .conditionExpression("attribute_not_exists(shortId)")
                        .build();

                try {
                    ddb.putItem(putRequest);
                    break;
                } catch (software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException e) {
                    context.getLogger().log(String.format("shortId 충돌 감지: %s. 재시도 중...", shortId));
                    if (i == maxRetries - 1) {
                        throw new RuntimeException("여러 번 재시도 후에도 고유한 shortId 생성에 실패했습니다.", e);
                    }
                }
            }

            String domain = input.getHeaders().get("Host");
            String stage = input.getRequestContext().getStage();
            String protocol = input.getHeaders().getOrDefault("X-Forwarded-Proto", "https");
            String shortUrl = String.format("%s://%s/%s", protocol, domain, shortId);

            if (!domain.contains("localhost")) {
                shortUrl = String.format("%s://%s/%s/%s", protocol, domain, stage, shortId);
            }

            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("shortId", shortId);
            responseBody.put("shortUrl", shortUrl);

            response.setStatusCode(200);
            response.setHeaders(headers);
            response.setBody(gson.toJson(responseBody));

        } catch (Exception e) {
            context.getLogger().log(String.format("오류: %s", e.getMessage()));
            return createErrorResponse(500, String.format("내부 서버 오류: %s", e.getMessage()), headers);
        }

        return response;
    }

    private boolean isUrlMaliciousWithSafeBrowsing(String urlToCheck, LambdaLogger logger) {
        if (this.safeBrowsingApiKey == null || this.safeBrowsingApiKey.isEmpty()) {
            logger.log("경고: SAFE_BROWSING_API_KEY가 설정되지 않았습니다. Google Safe Browsing 검사를 건너뜁니다.");
            return false;
        }

        String apiUrl = "https://safebrowsing.googleapis.com/v4/threatMatches:find?key=" + this.safeBrowsingApiKey;
        String requestBody = """
            {
              "client": {
                "clientId": "lessurl-serverless",
                "clientVersion": "1.0.0"
              },
              "threatInfo": {
                "threatTypes": ["MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"],
                "platformTypes": ["ANY_PLATFORM"],
                "threatEntryTypes": ["URL"],
                "threatEntries": [
                  {"url": "%s"}
                ]
              }
            }
            """.formatted(urlToCheck);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.log(String.format("Google Safe Browsing API 오류: %s %s", response.statusCode(), response.body()));
                return false;
            }

            String responseBody = response.body();
            logger.log(String.format("Google Safe Browsing API 응답: %s", responseBody));

            if (!responseBody.trim().equals("{}")) {
                 Map<String, Object> responseMap = gson.fromJson(responseBody, Map.class);
                 if (responseMap.containsKey("matches")) {
                    logger.log(String.format("Google Safe Browsing 위협 감지: %s", responseBody));
                    return true;
                 }
            }
            return false;

        } catch (IOException | InterruptedException e) {
            logger.log(String.format("Google Safe Browsing API 호출 중 예외 발생: %s", e.getMessage()));
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isUrlMaliciousWithGemini(String urlToCheck, LambdaLogger logger) {
        if (this.geminiApiKey == null || this.geminiApiKey.isEmpty()) {
            logger.log("경고: GEMINI_API_KEY가 설정되지 않았습니다. 유해 URL 검사를 건너뜜.");
            return false;
        }

        String prompt = String.format(
            "다음 URL을 분석하여 안전한 링크, 피싱 시도 또는 악성 코드 포함 여부를 판단하세요. " +
            "응답은 {\\\"classification\\\": \\\"VALUE\\\"} 형식의 JSON 객체로만 제공하며, " +
            "VALUE는 'SAFE', 'PHISHING', 'MALWARE' 중 하나여야 합니다. URL: %s", urlToCheck);

        String requestBody = """
            {
              "contents": [{
                "parts":[{
                  "text": "%s"
                }]
              }],
              "generationConfig": {
                "responseMimeType": "application/json"
              }
            }
            """.formatted(prompt);

        String apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + this.geminiApiKey;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.log(String.format("Gemini API 오류: %s %s", response.statusCode(), response.body()));
                return false;
            }

            String responseBody = response.body();
            logger.log(String.format("Gemini API 응답: %s", responseBody));

            try {
                Map<String, Object> outerResponse = gson.fromJson(responseBody, Map.class);

                java.util.List<Object> candidates = (java.util.List<Object>) outerResponse.get("candidates");
                if (candidates == null || candidates.isEmpty()) {
                    logger.log("경고: Gemini 응답에 'candidates'가 없습니다. 안전하다고 가정하고 진행합니다.");
                    return false;
                }
                
                Map<String, Object> firstCandidate = (Map<String, Object>) candidates.get(0);
                Map<String, Object> content = (Map<String, Object>) firstCandidate.get("content");
                if (content == null) {
                    logger.log("경고: Gemini 응답에 'content'가 없습니다. 안전하다고 가정하고 진행합니다.");
                    return false;
                }

                java.util.List<Object> parts = (java.util.List<Object>) content.get("parts");
                if (parts == null || parts.isEmpty()) {
                    logger.log("경고: Gemini 응답에 'parts'가 없습니다. 안전하다고 가정하고 진행합니다.");
                    return false;
                }

                Map<String, Object> firstPart = (Map<String, Object>) parts.get(0);
                String innerJsonString = (String) firstPart.get("text");
                if (innerJsonString == null) {
                    logger.log("경고: Gemini 응답에 'text'가 없습니다. 안전하다고 가정하고 진행합니다.");
                    return false;
                }

                Map<String, String> innerResponse = gson.fromJson(innerJsonString, Map.class);
                String classification = innerResponse.get("classification");

                if (classification == null) {
                    logger.log("경고: Gemini 내부 응답에 'classification' 키가 없습니다. 안전하다고 가정하고 진행합니다.");
                    return false;
                }
                
                String result = classification.trim().toUpperCase();
                logger.log(String.format("Gemini 분류 결과: %s", result));
                return !"SAFE".equals(result);

            } catch (JsonSyntaxException | ClassCastException | NullPointerException e) {
                logger.log(String.format("Gemini JSON 응답 파싱 오류: %s", e.getMessage()));
                return false;
            }

        } catch (IOException | InterruptedException e) {
            logger.log(String.format("Gemini API 호출 중 예외 발생: %s", e.getMessage()));
            Thread.currentThread().interrupt();
            return false;
        }
    }


    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message, Map<String, String> headers) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        headers.put("Access-Control-Allow-Origin", System.getenv("CORS_ALLOWED_ORIGIN"));
        headers.put("Access-Control-Allow-Methods", "POST,OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        response.setHeaders(headers);
        Map<String, String> errorBody = new HashMap<>();
        errorBody.put("error", message);
        response.setBody(gson.toJson(errorBody));
        return response;
    }
}