package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.net.URI;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ShortenHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient ddb;
    private final Gson gson;
    private final String tableName;

    public ShortenHandler() {
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

    protected ShortenHandler(DynamoDbClient ddb, Gson gson, String tableName) {
        this.ddb = ddb;
        this.gson = gson;
        this.tableName = tableName;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json");
                    headers.put("Access-Control-Allow-Origin", "https://dkyc3z72xqs74.cloudfront.net");
                    headers.put("Access-Control-Allow-Methods", "POST,OPTIONS");
                    headers.put("Access-Control-Allow-Headers", "Content-Type");
        try {
            String body = input.getBody();
            if (body == null || body.isEmpty()) {
                return createErrorResponse(400, "Request body is empty", headers);
            }

            Map<String, String> requestData = gson.fromJson(body, Map.class);
            String originalUrl = requestData.get("url");

            String title = requestData.get("title");

            if (originalUrl == null || originalUrl.trim().isEmpty()) {
                return createErrorResponse(400, "URL is required", headers);
            }
            if (!originalUrl.startsWith("http")) {
                originalUrl = "https://" + originalUrl;
            }

            String shortId = UUID.randomUUID().toString().substring(0, 8);

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("shortId", AttributeValue.builder().s(shortId).build());
            item.put("originalUrl", AttributeValue.builder().s(originalUrl).build());
            item.put("createdAt", AttributeValue.builder().s(Instant.now().toString()).build());
            item.put("clickCount", AttributeValue.builder().n("0").build());

            if (title != null && !title.isEmpty()) {
                item.put("title", AttributeValue.builder().s(title).build());
            }

            PutItemRequest putRequest = PutItemRequest.builder()
                    .tableName(this.tableName)
                    .item(item)
                    .build();

            ddb.putItem(putRequest);

            String domain = input.getHeaders().get("Host");
            String stage = input.getRequestContext().getStage();
            String protocol = input.getHeaders().getOrDefault("X-Forwarded-Proto", "https");
            String shortUrl = String.format("%s://%s/%s", protocol, domain, shortId);

            if (!domain.contains("localhost")) {
                shortUrl = String.format("%s://%s/%s/%s", protocol, domain, stage, shortId);
            }

            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("shortId", shortId);
            responseBody.put("shortUrl", shortUrl);

            response.setStatusCode(200);
            response.setHeaders(headers);
            response.setBody(gson.toJson(responseBody));

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return createErrorResponse(500, "Internal Server Error: " + e.getMessage(), headers);
        }

        return response;
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message, Map<String, String> headers) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        headers.put("Access-Control-Allow-Origin", "https://dkyc3z72xqs74.cloudfront.net"); // CORS 헤더 추가
        headers.put("Access-Control-Allow-Methods", "POST,OPTIONS"); // CORS 헤더 추가
        headers.put("Access-Control-Allow-Headers", "Content-Type"); // CORS 헤더 추가
        response.setHeaders(headers);
        Map<String, String> errorBody = new HashMap<>();
        errorBody.put("error", message);
        response.setBody(gson.toJson(errorBody));
        return response;
    }
}