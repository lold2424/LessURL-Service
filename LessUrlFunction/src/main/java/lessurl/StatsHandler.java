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
        this(
                DynamoDbClient.builder()
                        .httpClient(UrlConnectionHttpClient.create())
                        .build(),
                new GsonBuilder().setPrettyPrinting().create(),
                System.getenv("URLS_TABLE"),
                System.getenv("CLICKS_TABLE")
        );
    }

    public StatsHandler(DynamoDbClient ddb, Gson gson, String urlsTableName, String clicksTableName) {
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

            // 1. URL 기본 정보 조회 (UrlsTable)
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

            // 2. 최근 7일간의 클릭 로그 조회 (ClicksTable)
            String sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS).toString();

            Map<String, AttributeValue> expressionValues = new HashMap<>();
            expressionValues.put(":id", AttributeValue.builder().s(shortId).build());
            expressionValues.put(":ts", AttributeValue.builder().s(sevenDaysAgo).build());

            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(this.clicksTableName)
                    .keyConditionExpression("shortId = :id AND #ts >= :ts")
                    .expressionAttributeNames(Collections.singletonMap("#ts", "timestamp")) // 예약어 회피
                    .expressionAttributeValues(expressionValues)
                    .build();

            QueryResponse queryResponse = ddb.query(queryRequest);

            // 3. 통계 계산 (파이썬 로직 이식)
            Map<String, Object> calculatedStats = calculateStats(queryResponse.items());

            // 추가 정보 삽입
            calculatedStats.put("originalUrl", urlItem.get("originalUrl").s());
            if (urlItem.containsKey("title")) {
                calculatedStats.put("title", urlItem.get("title").s());
            }

            // 4. API 명세서 형식 준수 { clicks, stats }
            Map<String, Object> responseBody = new HashMap<>();
            // clicks: 총 클릭 수 (DB의 clickCount)
            responseBody.put("clicks", Integer.parseInt(urlItem.get("clickCount").n()));
            // stats: 계산된 상세 통계
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

    // 파이썬의 calculate_stats 함수를 자바로 변환
    private Map<String, Object> calculateStats(List<Map<String, AttributeValue>> clicks) {
        Map<Integer, Integer> clicksByHour = new HashMap<>();
        Map<String, Integer> clicksByDay = new HashMap<>();
        Map<String, Integer> clicksByReferer = new HashMap<>();

        for (Map<String, AttributeValue> click : clicks) {
            String timestamp = click.get("timestamp").s();
            // referer가 없으면 direct로 처리
            String referer = click.containsKey("referer") ? click.get("referer").s() : "direct";

            try {
                // ISO 8601 파싱 (UTC 기준)
                ZonedDateTime dt = ZonedDateTime.parse(timestamp); // 예: 2026-02-04T12:00:00Z

                // 시간별 집계
                int hour = dt.getHour();
                clicksByHour.merge(hour, 1, Integer::sum);

                // 일별 집계
                String day = dt.format(DateTimeFormatter.ISO_LOCAL_DATE); // YYYY-MM-DD
                clicksByDay.merge(day, 1, Integer::sum);

                // 레퍼러 도메인 집계
                String domain = extractDomain(referer);
                clicksByReferer.merge(domain, 1, Integer::sum);

            } catch (Exception e) {
                // 날짜 파싱 실패 등은 무시 (파이썬의 pass와 동일)
            }
        }

        // Peak Hour 계산
        Integer peakHour = clicksByHour.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // Top Referer 계산
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

    // 파이썬의 extract_domain 함수 변환
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