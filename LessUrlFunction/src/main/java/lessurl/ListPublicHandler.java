package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ListPublicHandler extends BaseHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            QueryResponse res = ddb.query(QueryRequest.builder()
                    .tableName(this.urlsTable)
                    .indexName("VisibilityIndex")
                    .keyConditionExpression("visibility = :v")
                    .expressionAttributeValues(Map.of(":v", AttributeValue.builder().s("PUBLIC").build()))
                    .scanIndexForward(false)
                    .limit(20)
                    .build());

            List<Map<String, String>> items = res.items().stream().map(item -> {
                Map<String, String> m = Map.of(
                    "shortId", item.get("shortId").s(),
                    "title", item.get("title") != null ? item.get("title").s() : "No Title",
                    "clickCount", item.get("clickCount").n(),
                    "createdAt", item.get("createdAt").s()
                );
                return m;
            }).collect(Collectors.toList());

            return createResponse(200, items);

        } catch (Exception e) {
            context.getLogger().log("[Error] " + e.getMessage());
            return createErrorResponse(500, "Server Error");
        }
    }
}
