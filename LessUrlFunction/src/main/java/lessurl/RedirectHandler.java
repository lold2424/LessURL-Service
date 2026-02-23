package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.net.URI;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class RedirectHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient ddb;
    private final String urlsTable;
    private final String clicksTable;
    private final String trendInsightsTable;

    public RedirectHandler() {
        this.urlsTable = System.getenv("URLS_TABLE");
        this.clicksTable = System.getenv("CLICKS_TABLE");
        this.trendInsightsTable = System.getenv("TREND_INSIGHTS_TABLE");

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
    }

    protected RedirectHandler(DynamoDbClient ddb, String urlsTable, String clicksTable, String trendInsightsTable) {
        this.ddb = ddb;
        this.urlsTable = urlsTable;
        this.clicksTable = clicksTable;
        this.trendInsightsTable = trendInsightsTable;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        String shortId = input.getPathParameters().get("shortId");

        if (shortId == null || shortId.isEmpty()) {
            return createErrorResponse(400, "Short ID is required");
        }

        try {
            GetItemResponse getResponse = ddb.getItem(GetItemRequest.builder()
                    .tableName(this.urlsTable)
                    .key(Map.of("shortId", AttributeValue.builder().s(shortId).build()))
                    .build());

            if (!getResponse.hasItem()) {
                return createErrorResponse(404, "URL not found");
            }

            String originalUrl = getResponse.item().get("originalUrl").s();

            try {
                updateClickStats(shortId, input, context.getLogger());
            } catch (Exception e) {
                context.getLogger().log("[Error] Failed to update stats: " + e.getMessage());
            }

            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(301);
            Map<String, String> headers = new HashMap<>();
            headers.put("Location", originalUrl);
            headers.put("Cache-Control", "no-cache, no-store, must-revalidate");
            response.setHeaders(headers);

            return response;

        } catch (Exception e) {
            context.getLogger().log("[Critical Error] " + e.getMessage());
            return createErrorResponse(500, "Internal Server Error");
        }
    }
    
    private void updateClickStats(String shortId, APIGatewayProxyRequestEvent input, LambdaLogger logger) {
        try {
            Map<String, AttributeValue> key = Map.of("shortId", AttributeValue.builder().s(shortId).build());

            ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(this.urlsTable)
                    .key(key)
                    .updateExpression("ADD clickCount :inc")
                    .expressionAttributeValues(Map.of(
                            ":inc", AttributeValue.builder().n("1").build()
                    ))
                    .build());
            logger.log("[Success] Incremented clickCount for " + shortId);

            String ip = "unknown";
            if (input.getRequestContext() != null && input.getRequestContext().getIdentity() != null) {
                ip = input.getRequestContext().getIdentity().getSourceIp();
            }

            Map<String, String> headers = input.getHeaders() != null ? input.getHeaders() : new HashMap<>();
            String userAgent = headers.getOrDefault("User-Agent", "unknown");
            String referer = headers.getOrDefault("Referer", "direct");
            String country = headers.getOrDefault("CloudFront-Viewer-Country", "unknown");

            String deviceType = "PC";
            String lowerUA = userAgent.toLowerCase();
            if (lowerUA.contains("mobile") || lowerUA.contains("android") || lowerUA.contains("iphone")) {
                deviceType = "Mobile";
            } else if (lowerUA.contains("tablet") || lowerUA.contains("ipad")) {
                deviceType = "Tablet";
            }

            Map<String, AttributeValue> logItem = new HashMap<>();
            logItem.put("shortId", AttributeValue.builder().s(shortId).build());
            logItem.put("timestamp", AttributeValue.builder().s(Instant.now().toString()).build());
            logItem.put("ip", AttributeValue.builder().s(hashIp(ip)).build());
            logItem.put("userAgent", AttributeValue.builder().s(userAgent).build());
            logItem.put("referer", AttributeValue.builder().s(referer).build());
            logItem.put("country", AttributeValue.builder().s(country).build());
            logItem.put("deviceType", AttributeValue.builder().s(deviceType).build());

            ddb.putItem(PutItemRequest.builder()
                    .tableName(this.clicksTable)
                    .item(logItem)
                    .build());
            logger.log("[Success] Logged click details for " + shortId);

            updateTrendInsights(shortId, country, deviceType, logger);
            
        } catch (Exception e) {
            logger.log("[Error] updateClickStats failed: " + e.getMessage());
            e.printStackTrace();
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

            logger.log("[Success] Updated trendInsights for " + shortId);
        } catch (Exception e) {
            logger.log("[Error] updateTrendInsights failed: " + e.getMessage());
        }
    }

    private String hashIp(String ip) {
        if (ip == null || ip.equals("unknown")) return "unknown";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(ip.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(encodedhash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody("{\"error\": \"" + message + "\"}");
        return response;
    }
}
