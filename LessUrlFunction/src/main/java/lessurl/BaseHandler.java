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
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public abstract class BaseHandler<I, O> implements RequestHandler<I, O> {

    protected final DynamoDbClient ddb;
    protected final LambdaClient lambda;
    protected final SqsClient sqs;
    protected final Gson gson;
        protected final String urlsTable;
        protected final String serviceMonitorTable;
        protected final String corsOrigin;
    
        public BaseHandler() {
            this.gson = new GsonBuilder().setPrettyPrinting().create();
            this.urlsTable = System.getenv("URLS_TABLE");
            this.serviceMonitorTable = System.getenv("SERVICE_MONITOR_TABLE");
            this.corsOrigin = System.getenv("CORS_ALLOWED_ORIGIN");
    
            System.out.println("Initializing BaseHandler. CORS Origin: " + this.corsOrigin);
    
            String dynamoDbEndpoint = System.getenv("DYNAMODB_ENDPOINT");
            String awsRegion = System.getenv("AWS_REGION");
    
            var ddbBuilder = DynamoDbClient.builder()
                    .httpClient(UrlConnectionHttpClient.create());
            var lambdaBuilder = LambdaClient.builder()
                    .httpClient(UrlConnectionHttpClient.create());
            var sqsBuilder = SqsClient.builder()
                    .httpClient(UrlConnectionHttpClient.create());
    
            if (dynamoDbEndpoint != null && !dynamoDbEndpoint.isEmpty()) {
                URI endpoint = URI.create(dynamoDbEndpoint);
                ddbBuilder.endpointOverride(endpoint).region(Region.of(awsRegion));
                lambdaBuilder.endpointOverride(endpoint).region(Region.of(awsRegion));
                sqsBuilder.endpointOverride(endpoint).region(Region.of(awsRegion));
            }
            
            this.ddb = ddbBuilder.build();
            this.lambda = lambdaBuilder.build();
            this.sqs = sqsBuilder.build();
        }
    
        // 테스트를 위한 생성자
        protected BaseHandler(DynamoDbClient ddb, LambdaClient lambda, SqsClient sqs, Gson gson, String urlsTable, String corsOrigin) {
            this.ddb = ddb;
            this.lambda = lambda;
            this.sqs = sqs;
            this.gson = gson;
            this.urlsTable = urlsTable;
            this.serviceMonitorTable = System.getenv("SERVICE_MONITOR_TABLE");
            this.corsOrigin = corsOrigin;
        }
    
        protected void recordMetric(String type, Map<String, Object> data) {
            if (this.serviceMonitorTable == null) return;
            try {
                Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item = new HashMap<>();
                item.put("metricType", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(type).build());
                item.put("timestamp", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(java.time.Instant.now().toString()).build());
                item.put("data", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(gson.toJson(data)).build());
    
                ddb.putItem(software.amazon.awssdk.services.dynamodb.model.PutItemRequest.builder()
                        .tableName(this.serviceMonitorTable)
                        .item(item)
                        .build());
            } catch (Exception e) {
                System.err.println("[Monitor Error] Failed to record metric: " + e.getMessage());
            }
        }
    
        protected APIGatewayProxyResponseEvent createResponse(int statusCode, Object body) {        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (corsOrigin != null) {
            headers.put("Access-Control-Allow-Origin", corsOrigin);
        }
        response.setHeaders(headers);
        
        if (body instanceof String) {
            response.setBody((String) body);
        } else {
            response.setBody(gson.toJson(body));
        }
        return response;
    }

    protected APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
        return createResponse(statusCode, Map.of("error", message));
    }
}
