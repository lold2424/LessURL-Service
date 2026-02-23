package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class StatsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient ddb;
    private final Gson gson;
    private final String urlsTableName;
    private final String clicksTableName;
    private final String trendInsightsTableName;
    private final String aiAnalyticTableName;
    private final String geminiApiKey;
    private final HttpClient httpClient;

    public StatsHandler() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.urlsTableName = System.getenv("URLS_TABLE");
        this.clicksTableName = System.getenv("CLICKS_TABLE");
        this.trendInsightsTableName = System.getenv("TREND_INSIGHTS_TABLE");
        this.aiAnalyticTableName = System.getenv("AI_ANALYTIC_TABLE");
        this.geminiApiKey = System.getenv("GEMINI_API_KEY");

        String dynamoDbEndpoint = System.getenv("DYNAMODB_ENDPOINT");
        String awsRegion = System.getenv("AWS_REGION");

        var clientBuilder = DynamoDbClient.builder()
                .httpClient(UrlConnectionHttpClient.create());

        if (dynamoDbEndpoint != null && !dynamoDbEndpoint.isEmpty()) {
            clientBuilder
                    .endpointOverride(URI.create(dynamoDbEndpoint))
                    .region(Region.of(awsRegion));
        }
        this.ddb = clientBuilder.build();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    protected StatsHandler(DynamoDbClient ddb, Gson gson, String urlsTableName, String clicksTableName, String trendInsightsTableName, String aiAnalyticTableName, String geminiApiKey, HttpClient httpClient) {
        this.ddb = ddb;
        this.gson = gson;
        this.urlsTableName = urlsTableName;
        this.clicksTableName = clicksTableName;
        this.trendInsightsTableName = trendInsightsTableName;
        this.aiAnalyticTableName = aiAnalyticTableName;
        this.geminiApiKey = geminiApiKey;
        this.httpClient = httpClient;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", System.getenv("CORS_ALLOWED_ORIGIN") != null ? System.getenv("CORS_ALLOWED_ORIGIN") : "*");

        try {
            String shortId = input.getPathParameters().get("shortId");
            if (shortId == null) {
                return createErrorResponse(400, "Short ID is required", headers);
            }

            Map<String, AttributeValue> urlKey = new HashMap<>();
            urlKey.put("shortId", AttributeValue.builder().s(shortId).build());

            GetItemRequest getRequest = GetItemRequest.builder()
                    .tableName(this.urlsTableName)
                    .key(urlKey)
                    .build();

            Map<String, AttributeValue> urlItem = ddb.getItem(getRequest).item();

            if (urlItem == null || urlItem.isEmpty()) {
                return createErrorResponse(404, "URL not found", headers);
            }

            String sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS).toString();

            Map<String, AttributeValue> expressionValues = new HashMap<>();
            expressionValues.put(":id", AttributeValue.builder().s(shortId).build());
            expressionValues.put(":ts", AttributeValue.builder().s(sevenDaysAgo).build());

            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(this.clicksTableName)
                    .keyConditionExpression("shortId = :id AND #ts >= :ts")
                    .expressionAttributeNames(Collections.singletonMap("#ts", "timestamp"))
                    .expressionAttributeValues(expressionValues)
                    .build();

            QueryResponse queryResponse = ddb.query(queryRequest);

            Map<String, Object> calculatedStats = calculateStats(queryResponse.items());
            Map<String, Object> trendStats = getTrendInsights(shortId);
            calculatedStats.putAll(trendStats);

            calculatedStats.put("originalUrl", urlItem.get("originalUrl").s());
            if (urlItem.containsKey("title")) {
                calculatedStats.put("title", urlItem.get("title").s());
            }

            String aiInsight;
            boolean needsNewAnalysis = true;

            if (urlItem.containsKey("aiInsight") && urlItem.containsKey("lastAnalyzed")) {
                try {
                    Instant lastAnalyzed = Instant.parse(urlItem.get("lastAnalyzed").s());
                    if (Duration.between(lastAnalyzed, Instant.now()).toHours() < 24) {
                        aiInsight = urlItem.get("aiInsight").s();
                        needsNewAnalysis = false;
                        context.getLogger().log("Using cached AI insight for: " + shortId);
                    } else {
                        aiInsight = getAiInsight(calculatedStats, context);
                    }
                } catch (Exception e) {
                    aiInsight = getAiInsight(calculatedStats, context);
                }
            } else {
                aiInsight = getAiInsight(calculatedStats, context);
            }

            if (needsNewAnalysis && aiInsight != null && !aiInsight.contains("오류") && !aiInsight.contains("설정되지 않았습니다")) {
                updateCachedInsight(shortId, aiInsight, context);
                saveAiAnalyticHistory(shortId, aiInsight, context);
            }
            
            calculatedStats.put("aiInsight", aiInsight);

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("clicks", Integer.parseInt(urlItem.get("clickCount").n()));
            responseBody.put("stats", calculatedStats);

            response.setStatusCode(200);
            response.setHeaders(headers);
            response.setBody(gson.toJson(responseBody));

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return createErrorResponse(500, "Internal Server Error", headers);
        }

        return response;
    }

    private void updateCachedInsight(String shortId, String insight, Context context) {
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("shortId", AttributeValue.builder().s(shortId).build());

            Map<String, AttributeValueUpdate> updates = new HashMap<>();
            updates.put("aiInsight", AttributeValueUpdate.builder()
                    .value(AttributeValue.builder().s(insight).build())
                    .action(AttributeAction.PUT)
                    .build());
            updates.put("lastAnalyzed", AttributeValueUpdate.builder()
                    .value(AttributeValue.builder().s(Instant.now().toString()).build())
                    .action(AttributeAction.PUT)
                    .build());

            UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                    .tableName(this.urlsTableName)
                    .key(key)
                    .attributeUpdates(updates)
                    .build();

            ddb.updateItem(updateRequest);
            context.getLogger().log("Cached new AI insight for: " + shortId);
        } catch (Exception e) {
            context.getLogger().log("Failed to cache AI insight: " + e.getMessage());
        }
    }

    private Map<String, Object> getTrendInsights(String shortId) {
        Map<String, Object> trendData = new HashMap<>();
        try {
            QueryRequest query = QueryRequest.builder()
                    .tableName(this.trendInsightsTableName)
                    .keyConditionExpression("shortId = :id")
                    .expressionAttributeValues(Map.of(":id", AttributeValue.builder().s(shortId).build()))
                    .build();

            QueryResponse response = ddb.query(query);
            for (Map<String, AttributeValue> item : response.items()) {
                String category = item.get("category").s();
                Map<String, AttributeValue> statsMap = item.get("statsData").m();
                Map<String, Integer> simpleMap = new HashMap<>();
                statsMap.forEach((k, v) -> simpleMap.put(k, Integer.parseInt(v.n())));
                trendData.put(category.toLowerCase() + "Stats", simpleMap);
            }
        } catch (Exception e) {}
        return trendData;
    }

    private void saveAiAnalyticHistory(String shortId, String insight, Context context) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("shortId", AttributeValue.builder().s(shortId).build());
            item.put("generatedAt", AttributeValue.builder().s(Instant.now().toString()).build());
            item.put("insightText", AttributeValue.builder().s(insight).build());
            item.put("analysisType", AttributeValue.builder().s("DAILY_INSIGHT").build());
            item.put("modelInfo", AttributeValue.builder().s("gemini-2.5-flash").build());

            ddb.putItem(PutItemRequest.builder()
                    .tableName(this.aiAnalyticTableName)
                    .item(item)
                    .build());
        } catch (Exception e) {
            context.getLogger().log("Failed to save AI analytic history: " + e.getMessage());
        }
    }

    private String getAiInsight(Map<String, Object> stats, Context context) {
        if (this.geminiApiKey == null || this.geminiApiKey.isEmpty()) {
            return "AI 분석이 설정되지 않았습니다.";
        }

        try {
            String prompt = String.format(
                "당신은 전문 마케팅 분석가입니다. 다음 URL 통계 데이터를 분석하여 사용자층의 특징과 마케팅 인사이트를 정중한 한국어로 한 문장으로 작성해 주세요.\\n" +
                "데이터:\\n" +
                "- 총 클릭: %s\\n" +
                "- 주요 국가: %s\\n" +
                "- 주요 기기: %s\\n" +
                "- 주요 유입 경로: %s\\n" +
                "- 가장 많이 유입된 시간: %s시\\n" +
                "주의: 데이터가 적으면 '충분한 데이터가 쌓이지 않았습니다'라고 답변하세요.",
                stats.get("clicksByReferer") != null ? stats.get("clicksByReferer").toString() : "없음",
                stats.get("countryStats") != null ? stats.get("countryStats").toString() : "알 수 없음",
                stats.get("deviceStats") != null ? stats.get("deviceStats").toString() : "알 수 없음",
                stats.get("topReferer"),
                stats.get("peakHour")
            );

            String requestBody = String.format(
                "{\"contents\": [{\"parts\":[{\"text\": \"%s\"}]}]}",
                prompt.replace("\"", "\\\"")
            );

            String apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + this.geminiApiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                context.getLogger().log("Gemini API Error: " + response.statusCode() + " " + response.body());
                return "분석 중 오류가 발생했습니다.";
            }

            Map<String, Object> responseMap = gson.fromJson(response.body(), Map.class);
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseMap.get("candidates");
            Map<String, Object> firstCandidate = candidates.get(0);
            Map<String, Object> content = (Map<String, Object>) firstCandidate.get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            return ((String) parts.get(0).get("text")).trim();

        } catch (Exception e) {
            context.getLogger().log("Gemini insight generation failed: " + e.getMessage());
            return "현재 분석을 수행할 수 없습니다.";
        }
    }

    private Map<String, Object> calculateStats(List<Map<String, AttributeValue>> clicks) {
        Map<Integer, Integer> clicksByHour = new HashMap<>();
        Map<String, Integer> clicksByDay = new HashMap<>();
        Map<String, Integer> clicksByReferer = new HashMap<>();

        for (Map<String, AttributeValue> click : clicks) {
            String timestamp = click.get("timestamp").s();
            String referer = click.containsKey("referer") ? click.get("referer").s() : "direct";

            try {
                ZonedDateTime dt = ZonedDateTime.parse(timestamp);
                int hour = dt.getHour();
                clicksByHour.merge(hour, 1, Integer::sum);
                String day = dt.format(DateTimeFormatter.ISO_LOCAL_DATE);
                clicksByDay.merge(day, 1, Integer::sum);
                String domain = extractDomain(referer);
                clicksByReferer.merge(domain, 1, Integer::sum);
            } catch (Exception e) {}
        }

        Integer peakHour = clicksByHour.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        String topReferer = clicksByReferer.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        Map<String, Object> stats = new HashMap<>();
        stats.put("clicksByHour", clicksByHour);
        stats.put("clicksByDay", clicksByDay);
        stats.put("clicksByReferer", clicksByReferer);
        stats.put("peakHour", peakHour);
        stats.put("topReferer", topReferer);
        stats.put("period", "7d");

        return stats;
    }

    private String extractDomain(String url) {
        if (url == null || url.equals("direct") || url.isEmpty()) return "direct";
        try {
            URI uri = new URI(url);
            String domain = uri.getHost();
            return domain != null ? domain : "unknown";
        } catch (Exception e) { return "unknown"; }
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message, Map<String, String> headers) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setHeaders(headers);
        Map<String, String> errorBody = new HashMap<>();
        errorBody.put("error", message);
        response.setBody(gson.toJson(errorBody));
        return response;
    }
}