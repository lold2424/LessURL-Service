package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
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

    protected StatsHandler(DynamoDbClient ddb, LambdaClient lambda, SqsClient sqs, Gson gson, String urlsTable, String clicksTable, String trendInsightsTable) {
        super(ddb, lambda, sqs, gson, urlsTable, "*");
        this.clicksTable = clicksTable;
        this.trendInsightsTable = trendInsightsTable;
        this.geminiApiKey = null;
        this.httpClient = null;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        String inputId = input.getPathParameters() != null ? input.getPathParameters().get("shortId") : null;

        if (inputId == null || inputId.isEmpty()) {
            return createErrorResponse(400, "ID is required");
        }

        try {
            GetItemResponse urlRes = ddb.getItem(GetItemRequest.builder()
                    .tableName(this.urlsTable)
                    .key(Map.of("shortId", AttributeValue.builder().s(inputId).build()))
                    .build());

            Map<String, AttributeValue> urlItem;
            String shortId;

            if (urlRes.hasItem()) {
                urlItem = urlRes.item();
                shortId = inputId;
            } else {
                QueryResponse aliasRes = ddb.query(QueryRequest.builder()
                        .tableName(this.urlsTable)
                        .indexName("CustomAliasIndex")
                        .keyConditionExpression("customAlias = :a")
                        .expressionAttributeValues(Map.of(":a", AttributeValue.builder().s(inputId).build()))
                        .limit(1)
                        .build());
                
                if (aliasRes.hasItems() && !aliasRes.items().isEmpty()) {
                    urlItem = aliasRes.items().get(0);
                    shortId = urlItem.get("shortId").s();
                } else {
                    return createErrorResponse(404, "URL not found");
                }
            }

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

            String peakHour = clicksByHour.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("0");

            Map<String, Object> allStats = new HashMap<>();
            allStats.put("totalClicks", totalClicks);
            allStats.put("dailyTrends", clicksByDay);
            allStats.put("hourlyDistribution", clicksByHour);
            allStats.put("peakHour", peakHour);
            allStats.put("referers", clicksByReferer);
            allStats.put("countries", countryStats);
            allStats.put("devices", deviceStats);

            String aiInsight = generateAiInsight(allStats, context);

            Map<String, Object> statsDetails = new HashMap<>();
            statsDetails.put("shortId", shortId);
            statsDetails.put("originalUrl", urlItem.get("originalUrl").s());
            statsDetails.put("title", urlItem.containsKey("title") ? urlItem.get("title").s() : "Untitled");
            statsDetails.put("clicksByDay", clicksByDay);
            statsDetails.put("clicksByHour", clicksByHour);
            statsDetails.put("clicksByReferer", clicksByReferer);
            statsDetails.put("countryStats", countryStats);
            statsDetails.put("deviceStats", deviceStats);
            statsDetails.put("aiInsight", aiInsight);
            statsDetails.put("period", "7d");
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

    private String generateAiInsight(Map<String, Object> stats, Context context) {
        if (this.geminiApiKey == null || (int)stats.get("totalClicks") < 1) 
            return "충분한 방문 데이터가 수집된 후 정밀 AI 분석이 제공됩니다.";
            
        try {
            String prompt = String.format(
                "당신은 데이터 분석 전문가입니다. 아래 제공된 URL 클릭 통계 데이터를 종합적으로 분석하여, " +
                "사용자 행동 패턴과 유의미한 비즈니스 인사이트를 한국어로 아주 명확하고 전문적으로 요약해줘. " +
                "불필요한 인사말은 생략하고 3문장 이내로 핵심만 작성해.\n\n" +
                "데이터: %s", gson.toJson(stats));

            Map<String, Object> part = Map.of("text", prompt);
            Map<String, Object> content = Map.of("parts", List.of(part));
            Map<String, Object> requestMap = Map.of(
                "contents", List.of(content),
                "generationConfig", Map.of(
                    "temperature", 0.7,
                    "maxOutputTokens", 500
                )
            );
            String body = gson.toJson(requestMap);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash-lite:generateContent?key=" + this.geminiApiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> map = gson.fromJson(response.body(), Map.class);
                List<Object> candidates = (List<Object>) map.get("candidates");
                if (candidates != null && !candidates.isEmpty()) {
                    Map<String, Object> candidate = (Map<String, Object>) candidates.get(0);
                    Map<String, Object> resContent = (Map<String, Object>) candidate.get("content");
                    List<Object> parts = (List<Object>) resContent.get("parts");
                    if (parts != null && !parts.isEmpty()) {
                        String result = (String) ((Map<String, Object>) parts.get(0)).get("text");
                        return result.trim().replaceAll("\\*", "");
                    }
                }
            }
            context.getLogger().log("Gemini API Error: " + response.statusCode() + " - " + response.body());
        } catch (Exception e) {
            context.getLogger().log("Gemini Exception: " + e.getMessage());
        }
        return "데이터 분석 중 오류가 발생했습니다. 잠시 후 다시 확인해주세요.";
    }
}
