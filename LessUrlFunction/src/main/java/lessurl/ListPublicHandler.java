package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ListPublicHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient ddb;
    private final Gson gson;
    private final String tableName;

    public ListPublicHandler() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.tableName = System.getenv("URLS_TABLE");

        String dynamoDbEndpoint = System.getenv("DYNAMODB_ENDPOINT");
        var clientBuilder = DynamoDbClient.builder()
                .httpClient(UrlConnectionHttpClient.create());

        if (dynamoDbEndpoint != null && !dynamoDbEndpoint.isEmpty()) {
            clientBuilder
                    .endpointOverride(URI.create(dynamoDbEndpoint))
                    .region(Region.of(System.getenv("AWS_REGION")));
        }
        this.ddb = clientBuilder.build();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", System.getenv("CORS_ALLOWED_ORIGIN"));

        try {
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(this.tableName)
                    .indexName("VisibilityIndex")
                    .keyConditionExpression("visibility = :v")
                    .expressionAttributeValues(Map.of(":v", AttributeValue.builder().s("PUBLIC").build()))
                    .scanIndexForward(false) // 최신순 정렬
                    .limit(10) // 최근 10개만 조회
                    .build();

            QueryResponse queryResponse = ddb.query(queryRequest);
            List<Map<String, String>> publicUrls = new ArrayList<>();

            for (Map<String, AttributeValue> item : queryResponse.items()) {
                Map<String, String> urlData = new HashMap<>();
                urlData.put("shortId", item.get("shortId").s());
                urlData.put("originalUrl", item.get("originalUrl").s());
                urlData.put("createdAt", item.get("createdAt").s());
                urlData.put("clickCount", item.get("clickCount").n());
                if (item.containsKey("title")) {
                    urlData.put("title", item.get("title").s());
                }
                publicUrls.add(urlData);
            }

            response.setStatusCode(200);
            response.setHeaders(headers);
            response.setBody(gson.toJson(publicUrls));

        } catch (Exception e) {
            context.getLogger().log("Error fetching public URLs: " + e.getMessage());
            response.setStatusCode(500);
            response.setBody(gson.toJson(Map.of("error", "Failed to fetch public URLs")));
        }

        return response;
    }
}
