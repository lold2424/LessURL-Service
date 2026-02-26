package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public class RedirectHandler extends BaseHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final String analyticsQueueUrl;

    public RedirectHandler() {
        super();
        this.analyticsQueueUrl = System.getenv("ANALYTICS_QUEUE_URL");
    }

    protected RedirectHandler(DynamoDbClient ddb, LambdaClient lambda, software.amazon.awssdk.services.sqs.SqsClient sqs, String urlsTable, String analyticsQueueUrl) {
        super(ddb, lambda, sqs, new Gson(), urlsTable, "*");
        this.analyticsQueueUrl = analyticsQueueUrl;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        String inputId = input.getPathParameters() != null ? input.getPathParameters().get("shortId") : null;

        if (inputId == null || inputId.isEmpty() || inputId.equals("/")) {
            APIGatewayProxyResponseEvent response = createResponse(301, "");
            response.getHeaders().put("Location", "https://www.lessurl.site");
            return response;
        }

        String shortId = inputId.trim();

        try {
            GetItemResponse getResponse = ddb.getItem(GetItemRequest.builder()
                    .tableName(this.urlsTable)
                    .key(Map.of("shortId", AttributeValue.builder().s(shortId).build()))
                    .build());

            Map<String, AttributeValue> item;

            if (getResponse.hasItem()) {
                item = getResponse.item();
            } else {
                software.amazon.awssdk.services.dynamodb.model.QueryResponse queryResponse = ddb.query(software.amazon.awssdk.services.dynamodb.model.QueryRequest.builder()
                        .tableName(this.urlsTable)
                        .indexName("CustomAliasIndex")
                        .keyConditionExpression("customAlias = :alias")
                        .expressionAttributeValues(Map.of(":alias", AttributeValue.builder().s(shortId).build()))
                        .limit(1)
                        .build());
                
                if (queryResponse.hasItems() && !queryResponse.items().isEmpty()) {
                    item = queryResponse.items().get(0);
                    shortId = item.get("shortId").s();
                } else {
                    return createErrorResponse(404, "URL not found");
                }
            }

            String originalUrl = item.get("originalUrl").s();

            try {
                sendToAnalyticsQueue(shortId, input, context.getLogger());
            } catch (Exception e) {
                context.getLogger().log("[Warning] Failed to send to analytics queue: " + e.getMessage());
            }

            APIGatewayProxyResponseEvent response = createResponse(301, "");
            response.getHeaders().put("Location", originalUrl);
            response.getHeaders().put("Cache-Control", "no-cache, no-store, must-revalidate");

            return response;

        } catch (Exception e) {
            context.getLogger().log("[Critical Error] " + e.getMessage());
            return createErrorResponse(500, "Internal Server Error");
        }
    }
    
    private void sendToAnalyticsQueue(String shortId, APIGatewayProxyRequestEvent input, LambdaLogger logger) {
        if (this.analyticsQueueUrl == null) return;

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

        Map<String, String> payload = new HashMap<>();
        payload.put("shortId", shortId);
        payload.put("ip", hashIp(ip));
        payload.put("userAgent", userAgent);
        payload.put("referer", referer);
        payload.put("country", country);
        payload.put("deviceType", deviceType);

        SendMessageRequest sendMsgRequest = SendMessageRequest.builder()
                .queueUrl(this.analyticsQueueUrl)
                .messageBody(gson.toJson(payload))
                .build();

        sqs.sendMessage(sendMsgRequest);
        logger.log("[Success] Sent to AnalyticsQueue for " + shortId);
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
}
