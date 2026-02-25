package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class MonitorHandler extends BaseHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        Map<String, String> headers = input.getHeaders();
        String authHeader = null;
        if (headers != null) {
            authHeader = headers.get("Authorization");
            if (authHeader == null) authHeader = headers.get("authorization");
        }
        
        String adminToken = System.getenv("NEXT_PUBLIC_ADMIN_TOKEN");
        
        if (adminToken != null && !adminToken.equals(authHeader)) {
            context.getLogger().log("[Access Denied] Provided token: " + authHeader);
            return createErrorResponse(403, "Unauthorized");
        }

        try {
            Map<String, Object> result = new HashMap<>();
            String dayAgo = Instant.now().minus(24, ChronoUnit.HOURS).toString();

            List<Map<String, AttributeValue>> maliciousLogs = queryMetrics("MALICIOUS_URL", dayAgo);
            result.put("maliciousCount", maliciousLogs.size());
            result.put("maliciousList", maliciousLogs.stream()
                    .map(item -> {
                        Map<String, Object> data = gson.fromJson(item.get("data").s(), Map.class);
                        data.put("timestamp", item.get("timestamp").s());
                        return data;
                    })
                    .limit(10).collect(Collectors.toList()));

            List<Map<String, AttributeValue>> perfLogs = queryMetrics("PERFORMANCE", dayAgo);
            List<Map<String, Object>> slowRequests = perfLogs.stream()
                    .map(item -> {
                        Map<String, Object> data = (Map<String, Object>) gson.fromJson(item.get("data").s(), Map.class);
                        data.put("timestamp", item.get("timestamp").s());
                        return data;
                    })
                    .sorted((a, b) -> Double.compare(convertToDouble(b.get("duration")), convertToDouble(a.get("duration"))))
                    .limit(Math.max(1, perfLogs.size() / 5))
                    .collect(Collectors.toList());
            result.put("slowRequests", slowRequests);

            List<Map<String, AttributeValue>> statsViews = queryMetrics("STATS_VIEW", dayAgo);
            result.put("statsViewCount", statsViews.size());

            return createResponse(200, result);
        } catch (Exception e) {
            context.getLogger().log("[Error] MonitorHandler: " + e.getMessage());
            e.printStackTrace();
            return createErrorResponse(500, "Internal Server Error: " + e.getMessage());
        }
    }

    private List<Map<String, AttributeValue>> queryMetrics(String type, String since) {
        if (this.serviceMonitorTable == null) return Collections.emptyList();
        try {
            QueryResponse res = ddb.query(QueryRequest.builder()
                    .tableName(this.serviceMonitorTable)
                    .keyConditionExpression("metricType = :t AND #ts >= :s")
                    .expressionAttributeNames(Map.of("#ts", "timestamp"))
                    .expressionAttributeValues(Map.of(
                            ":t", AttributeValue.builder().s(type).build(),
                            ":s", AttributeValue.builder().s(since).build()
                    ))
                    .build());
            return res.items();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private double convertToDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        if (obj instanceof String) return Double.parseDouble((String) obj);
        return 0.0;
    }
}
