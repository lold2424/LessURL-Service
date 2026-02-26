package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class StatsHandler extends BaseHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final String clicksTable;
    private final String trendInsightsTable;
    private final String geminiApiKey;
    private final HttpClient httpClient;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("H").withZone(ZoneId.of("UTC"));

    public StatsHandler() {
        super();
        this.clicksTable = System.getenv("CLICKS_TABLE");
        this.trendInsightsTable = System.getenv("TREND_INSIGHTS_TABLE");
        this.geminiApiKey = System.getenv("GEMINI_API_KEY");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        String shortId = input.getPathParameters() != null ? input.getPathParameters().get("shortId") : null;

        if (shortId == null || shortId.isEmpty()) {
            return createErrorResponse(400, "Short ID is required");
        }

        try {
            GetItemResponse urlRes = ddb.getItem(GetItemRequest.builder()
                    .tableName(this.urlsTable)
                    .key(Map.of("shortId", AttributeValue.builder().s(shortId).build()))
                    .build());

            if (!urlRes.hasItem()) {
                return createErrorResponse(404, "URL not found");
            }

            Map<String, AttributeValue> urlItem = urlRes.item();
            int totalClicks = urlItem.containsKey("clickCount") ? Integer.parseInt(urlItem.get("clickCount").n()) : 0;

            String sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS).toString();
            QueryResponse clicksRes = ddb.query(QueryRequest.builder()
                    .tableName(this.clicksTable)
                    .keyConditionExpression("shortId = :id AND #ts >= :since")
                    .expressionAttributeNames(Map.of("#ts", "timestamp"))
                    .expressionAttributeValues(Map.of(
                            ":id", AttributeValue.builder().s(shortId).build(),
                            ":since", AttributeValue.builder().s(sevenDaysAgo).build()
                    ))
                    .build());

            List<Map<String, AttributeValue>> clickLogs = clicksRes.items();

            Map<String, Long> clicksByDay = clickLogs.stream()
                    .collect(Collectors.groupingBy(
                            item -> DATE_FORMATTER.format(Instant.parse(item.get("timestamp").s())),
                            Collectors.counting()
                    ));

            Map<String, Long> clicksByHour = clickLogs.stream()
                    .collect(Collectors.groupingBy(
                            item -> HOUR_FORMATTER.format(Instant.parse(item.get("timestamp").s())),
                            Collectors.counting()
                    ));

            Map<String, Long> clicksByReferer = clickLogs.stream()
                    .collect(Collectors.groupingBy(
                            item -> item.containsKey("referer") ? item.get("referer").s() : "direct",
                            Collectors.counting()
                    ));

            QueryResponse trendRes = ddb.query(QueryRequest.builder()
                    .tableName(this.trendInsightsTable)
                    .keyConditionExpression("shortId = :id")
                    .expressionAttributeValues(Map.of(":id", AttributeValue.builder().s(shortId).build()))
                    .build());

            Map<String, Double> countryStats = new HashMap<>();
            Map<String, Double> deviceStats = new HashMap<>();

            for (Map<String, AttributeValue> item : trendRes.items()) {
                String category = item.get("category").s();
                item.forEach((k, v) -> {
                    if (!k.equals("shortId") && !k.equals("category") && !k.equals("lastUpdated")) {
                        if ("COUNTRY".equals(category)) countryStats.put(k, Double.parseDouble(v.n()));
                        else if ("DEVICE".equals(category)) deviceStats.put(k, Double.parseDouble(v.n()));
                    }
                });
            }

            String aiInsight = generateAiInsight(totalClicks, clicksByReferer, countryStats, context);

            Map<String, Object> statsDetails = new HashMap<>();
            statsDetails.put("originalUrl", urlItem.get("originalUrl").s());
            statsDetails.put("title", urlItem.containsKey("title") ? urlItem.get("title").s() : "Untitled");
            statsDetails.put("clicksByDay", clicksByDay);
            statsDetails.put("clicksByHour", clicksByHour);
            statsDetails.put("clicksByReferer", clicksByReferer);
            statsDetails.put("countryStats", countryStats);
            statsDetails.put("deviceStats", deviceStats);
            statsDetails.put("aiInsight", aiInsight);
            statsDetails.put("period", "7d");
            
            String peakHour = clicksByHour.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("0");
            statsDetails.put("peakHour", Integer.parseInt(peakHour));

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("clicks", totalClicks);
            responseData.put("stats", statsDetails);

            recordMetric("STATS_VIEW", Map.of("shortId", shortId));

            return createResponse(200, responseData);

        } catch (Exception e) {
            context.getLogger().log("[Error] StatsHandler: " + e.getMessage());
            return createErrorResponse(500, "Internal Server Error");
        }
    }

    private String generateAiInsight(int total, Map<String, Long> referers, Map<String, Double> countries, Context context) {
        if (this.geminiApiKey == null || total < 1) return "데이터가 부족하여 AI 분석을 생성할 수 없습니다.";
        try {
            String prompt = String.format(
                "당신은 웹 로그 분석 전문가입니다. 다음 데이터를 보고 짧고 흥미로운 인사이트를 한국어로 한 문장으로 제공해줘.\n" +
                "총 클릭 수: %d, 유입 경로: %s, 국가: %s. 불필요한 설명 없이 인사이트만 응답해.",
                total, referers.toString(), countries.toString());

            String body = String.format("{\"contents\":[{\"parts\":[{\"text\":\"%s\"}]}]}", prompt);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + this.geminiApiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> map = gson.fromJson(response.body(), Map.class);
                List<Object> cand = (List<Object>) map.get("candidates");
                Map<String, Object> cont = (Map<String, Object>) ((Map<String, Object>) cand.get(0)).get("content");
                return ((String) ((Map<String, Object>) ((List<Object>) cont.get("parts")).get(0)).get("text")).trim();
            }
        } catch (Exception e) {
            context.getLogger().log("Gemini Error: " + e.getMessage());
        }
        return "현재 통계 데이터를 분석 중입니다.";
    }
}
