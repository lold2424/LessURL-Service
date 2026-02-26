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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class StatsHandler extends BaseHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final String clicksTable;
    private final String trendInsightsTable;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("H").withZone(ZoneId.of("UTC"));

    public StatsHandler() {
        super();
        this.clicksTable = System.getenv("CLICKS_TABLE");
        this.trendInsightsTable = System.getenv("TREND_INSIGHTS_TABLE");
    }

    protected StatsHandler(DynamoDbClient ddb, LambdaClient lambda, SqsClient sqs, Gson gson, String urlsTable, String clicksTable, String trendInsightsTable) {
        super(ddb, lambda, sqs, gson, urlsTable, "*");
        this.clicksTable = clicksTable;
        this.trendInsightsTable = trendInsightsTable;
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

            Map<String, Map<String, Double>> trendStats = new HashMap<>();
            for (Map<String, AttributeValue> item : trendRes.items()) {
                String category = item.get("category").s();
                Map<String, Double> statsData = new HashMap<>();
                if (item.containsKey("statsData") && item.get("statsData").hasM()) {
                    item.get("statsData").m().forEach((k, v) -> statsData.put(k, Double.parseDouble(v.n())));
                }
                trendStats.put(category.toLowerCase() + "Stats", statsData);
            }

            Map<String, Object> statsDetails = new HashMap<>();
            statsDetails.put("originalUrl", urlItem.get("originalUrl").s());
            statsDetails.put("title", urlItem.containsKey("title") ? urlItem.get("title").s() : "Untitled");
            statsDetails.put("clicksByDay", clicksByDay);
            statsDetails.put("clicksByHour", clicksByHour);
            statsDetails.put("clicksByReferer", clicksByReferer);
            statsDetails.put("countryStats", trendStats.getOrDefault("countryStats", new HashMap<>()));
            statsDetails.put("deviceStats", trendStats.getOrDefault("deviceStats", new HashMap<>()));
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
}
