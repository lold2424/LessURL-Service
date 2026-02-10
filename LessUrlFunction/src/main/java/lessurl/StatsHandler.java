package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class StatsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient ddb;
    private final Gson gson;
    private final String urlsTableName;
    private final String clicksTableName;

    public StatsHandler() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.urlsTableName = System.getenv("URLS_TABLE");
        this.clicksTableName = System.getenv("CLICKS_TABLE");

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

    protected StatsHandler(DynamoDbClient ddb, Gson gson, String urlsTableName, String clicksTableName) {
        this.ddb = ddb;
        this.gson = gson;
        this.urlsTableName = urlsTableName;
        this.clicksTableName = clicksTableName;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");

        try {
            String shortId = input.getPathParameters().get("shortId");
            if (shortId == null) {
                return createErrorResponse(400, "Short ID is required", headers);
            }

            Map<String, AttributeValue> urlKey = new HashMap<>();
            urlKey.put("shortId", AttributeValue.builder().s(shortId).build());

            GetItemRequest getRequest = GetItemRequest.builder()
                    .tableName(this.urlsTableName)
                    .key(urlKey)
                    .build();

            Map<String, AttributeValue> urlItem = ddb.getItem(getRequest).item();

            if (urlItem == null || urlItem.isEmpty()) {
                return createErrorResponse(404, "URL not found", headers);
            }

            String sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS).toString();

            Map<String, AttributeValue> expressionValues = new HashMap<>();
            expressionValues.put(":id", AttributeValue.builder().s(shortId).build());
            expressionValues.put(":ts", AttributeValue.builder().s(sevenDaysAgo).build());

            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(this.clicksTableName)
                    .keyConditionExpression("shortId = :id AND #ts >= :ts")
                    .expressionAttributeNames(Collections.singletonMap("#ts", "timestamp"))
                    .expressionAttributeValues(expressionValues)
                    .build();

            QueryResponse queryResponse = ddb.query(queryRequest);

            Map<String, Object> calculatedStats = calculateStats(queryResponse.items());

            calculatedStats.put("originalUrl", urlItem.get("originalUrl").s());
            if (urlItem.containsKey("title")) {
                calculatedStats.put("title", urlItem.get("title").s());
            }

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("clicks", Integer.parseInt(urlItem.get("clickCount").n()));
            responseBody.put("stats", calculatedStats);

            response.setStatusCode(200);
            response.setHeaders(headers);
            response.setBody(gson.toJson(responseBody));

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            e.printStackTrace();
            return createErrorResponse(500, "Internal Server Error", headers);
        }

        return response;
    }

    private Map<String, Object> calculateStats(List<Map<String, AttributeValue>> clicks) {
        Map<Integer, Integer> clicksByHour = new HashMap<>();
        Map<String, Integer> clicksByDay = new HashMap<>();
        Map<String, Integer> clicksByReferer = new HashMap<>();

        for (Map<String, AttributeValue> click : clicks) {
            String timestamp = click.get("timestamp").s();
            String referer = click.containsKey("referer") ? click.get("referer").s() : "direct";

            try {
                ZonedDateTime dt = ZonedDateTime.parse(timestamp);

                int hour = dt.getHour();
                clicksByHour.merge(hour, 1, Integer::sum);

                String day = dt.format(DateTimeFormatter.ISO_LOCAL_DATE);
                clicksByDay.merge(day, 1, Integer::sum);

                String domain = extractDomain(referer);
                clicksByReferer.merge(domain, 1, Integer::sum);

            } catch (Exception e) {
            }
        }

        Integer peakHour = clicksByHour.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        String topReferer = clicksByReferer.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        Map<String, Object> stats = new HashMap<>();
        stats.put("clicksByHour", clicksByHour);
        stats.put("clicksByDay", clicksByDay);
        stats.put("clicksByReferer", clicksByReferer);
        stats.put("peakHour", peakHour);
        stats.put("topReferer", topReferer);
        stats.put("period", "7d");

        return stats;
    }

    private String extractDomain(String url) {
        if (url == null || url.equals("direct") || url.isEmpty()) {
            return "direct";
        }
        try {
            URI uri = new URI(url);
            String domain = uri.getHost();
            return domain != null ? domain : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message, Map<String, String> headers) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setHeaders(headers);
        Map<String, String> errorBody = new HashMap<>();
        errorBody.put("error", message);
        response.setBody(gson.toJson(errorBody));
        return response;
    }
}