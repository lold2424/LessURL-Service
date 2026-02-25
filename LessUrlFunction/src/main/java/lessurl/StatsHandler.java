package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import com.google.gson.Gson;

public class StatsHandler extends BaseHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final String clicksTable;
    private final String trendInsightsTable;

    public StatsHandler() {
        super();
        this.clicksTable = System.getenv("CLICKS_TABLE");
        this.trendInsightsTable = System.getenv("TREND_INSIGHTS_TABLE");
    }

    protected StatsHandler(DynamoDbClient ddb, LambdaClient lambda, Gson gson, String urlsTable, String clicksTable, String trendInsightsTable) {
        super(ddb, lambda, gson, urlsTable, "*");
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
                context.getLogger().log("[Error] URL not found in DynamoDB: " + shortId);
                return createErrorResponse(404, "URL not found");
            }

            Map<String, AttributeValue> urlItem = urlRes.item();

            int clickCount = 0;
            if (urlItem.containsKey("clickCount") && urlItem.get("clickCount").n() != null) {
                clickCount = Integer.parseInt(urlItem.get("clickCount").n());
            }

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("clicks", clickCount);
            
            Map<String, Object> statsDetails = new HashMap<>();
            statsDetails.put("originalUrl", urlItem.get("originalUrl").s());
            statsDetails.put("title", urlItem.containsKey("title") ? urlItem.get("title").s() : "Untitled");
            statsDetails.put("clicksByHour", new HashMap<>());
            statsDetails.put("clicksByDay", new HashMap<>());
            statsDetails.put("clicksByReferer", new HashMap<>());
            statsDetails.put("aiInsight", "통계 데이터 수집 및 분석 중입니다.");
            statsDetails.put("peakHour", 0);
            statsDetails.put("period", "7d");
            
            responseData.put("stats", statsDetails);

            Map<String, Object> monitorData = new HashMap<>();
            monitorData.put("shortId", shortId);
            recordMetric("STATS_VIEW", monitorData);

            return createResponse(200, responseData);

        } catch (Exception e) {
            context.getLogger().log("[Critical Error] StatsHandler failed: " + e.getMessage());
            e.printStackTrace();
            return createErrorResponse(500, "Server Error: " + e.getMessage());
        }
    }
}
