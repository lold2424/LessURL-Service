package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class AnalyticsHandler extends BaseHandler<SQSEvent, String> {

    private final String clicksTable;
    private final String trendInsightsTable;

    public AnalyticsHandler() {
        super();
        this.clicksTable = System.getenv("CLICKS_TABLE");
        this.trendInsightsTable = System.getenv("TREND_INSIGHTS_TABLE");
    }

    @Override
    public String handleRequest(SQSEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        int successCount = 0;

        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> input = gson.fromJson(msg.getBody(), Map.class);
                processAnalytics(input, logger);
                successCount++;
            } catch (Exception e) {
                logger.log("[Error] Failed to process SQS message " + msg.getMessageId() + ": " + e.getMessage());
            }
        }

        logger.log(String.format("[Batch Success] Processed %d/%d messages", successCount, event.getRecords().size()));
        return "SUCCESS";
    }

    private void processAnalytics(Map<String, String> input, LambdaLogger logger) {
        String shortId = input.get("shortId");
        String ip = input.getOrDefault("ip", "unknown");
        String userAgent = input.getOrDefault("userAgent", "unknown");
        String referer = input.getOrDefault("referer", "direct");
        String country = input.getOrDefault("country", "unknown");
        String deviceType = input.getOrDefault("deviceType", "PC");

        try {
            ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(this.urlsTable)
                    .key(Map.of("shortId", AttributeValue.builder().s(shortId).build()))
                    .updateExpression("ADD clickCount :inc")
                    .expressionAttributeValues(Map.of(":inc", AttributeValue.builder().n("1").build()))
                    .build());

            Map<String, AttributeValue> logItem = new HashMap<>();
            logItem.put("shortId", AttributeValue.builder().s(shortId).build());
            logItem.put("timestamp", AttributeValue.builder().s(Instant.now().toString()).build());
            logItem.put("ip", AttributeValue.builder().s(ip).build());
            logItem.put("userAgent", AttributeValue.builder().s(userAgent).build());
            logItem.put("referer", AttributeValue.builder().s(referer).build());
            logItem.put("country", AttributeValue.builder().s(country).build());
            logItem.put("deviceType", AttributeValue.builder().s(deviceType).build());

            ddb.putItem(PutItemRequest.builder()
                    .tableName(this.clicksTable)
                    .item(logItem)
                    .build());

            updateTrendInsights(shortId, country, deviceType, logger);
        } catch (Exception e) {
            logger.log("[Error] processAnalytics failed for " + shortId + ": " + e.getMessage());
            throw e;
        }
    }

    private void updateTrendInsights(String shortId, String country, String deviceType, LambdaLogger logger) {
        try {
            ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(this.trendInsightsTable)
                    .key(Map.of(
                            "shortId", AttributeValue.builder().s(shortId).build(),
                            "category", AttributeValue.builder().s("COUNTRY").build()
                    ))
                    .updateExpression("ADD statsData.#c :inc SET lastUpdated = :now")
                    .expressionAttributeNames(Map.of("#c", country))
                    .expressionAttributeValues(Map.of(
                            ":inc", AttributeValue.builder().n("1").build(),
                            ":now", AttributeValue.builder().s(Instant.now().toString()).build()
                    ))
                    .build());

            ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(this.trendInsightsTable)
                    .key(Map.of(
                            "shortId", AttributeValue.builder().s(shortId).build(),
                            "category", AttributeValue.builder().s("DEVICE").build()
                    ))
                    .updateExpression("ADD statsData.#d :inc SET lastUpdated = :now")
                    .expressionAttributeNames(Map.of("#d", deviceType))
                    .expressionAttributeValues(Map.of(
                            ":inc", AttributeValue.builder().n("1").build(),
                            ":now", AttributeValue.builder().s(Instant.now().toString()).build()
                    ))
                    .build());
        } catch (Exception e) {
            logger.log("[Error] updateTrendInsights failed: " + e.getMessage());
        }
    }
}
