package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsHandlerTest {

    private StatsHandler statsHandler;
    private final Gson gson = new Gson();

    @Mock
    private DynamoDbClient mockDdb;
    @Mock
    private Context mockContext;
    @Mock
    private LambdaLogger mockLogger;
    @Mock
    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        lenient().when(mockContext.getLogger()).thenReturn(mockLogger);
        statsHandler = new StatsHandler(mockDdb, gson, "UrlsTable", "ClicksTable", "dummy-api-key", mockHttpClient);
    }

    @Test
    @DisplayName("통계 계산 로직이 정상적으로 동작한다")
    void testHandleRequest_CalculatesStats() {
        // given
        String shortId = "stats123";
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPathParameters(Collections.singletonMap("shortId", shortId));

        Map<String, AttributeValue> urlItem = new HashMap<>();
        urlItem.put("shortId", AttributeValue.builder().s(shortId).build());
        urlItem.put("originalUrl", AttributeValue.builder().s("https://target.com").build());
        urlItem.put("clickCount", AttributeValue.builder().n("10").build());

        when(mockDdb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(urlItem).build());

        List<Map<String, AttributeValue>> items = new ArrayList<>();

        Map<String, AttributeValue> log1 = new HashMap<>();
        log1.put("timestamp", AttributeValue.builder().s("2026-02-05T10:00:00Z").build());
        log1.put("referer", AttributeValue.builder().s("https://www.google.com/search").build());
        items.add(log1);

        Map<String, AttributeValue> log2 = new HashMap<>();
        log2.put("timestamp", AttributeValue.builder().s("2026-02-05T10:30:00Z").build());
        log2.put("referer", AttributeValue.builder().s("https://search.daum.net").build());
        items.add(log2);

        when(mockDdb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(items).build());

        // when
        APIGatewayProxyResponseEvent response = statsHandler.handleRequest(request, mockContext);

        // then
        assertEquals(200, response.getStatusCode());

        Map responseBody = gson.fromJson(response.getBody(), Map.class);

        assertTrue(responseBody.containsKey("clicks"));
        assertTrue(responseBody.containsKey("stats"));

        assertEquals(10.0, responseBody.get("clicks"));

        Map stats = (Map) responseBody.get("stats");
        assertEquals("https://target.com", stats.get("originalUrl"));
        assertEquals(10.0, ((Map)stats.get("clicksByHour")).get("10"));
        assertEquals("2026-02-05", ((Map)stats.get("clicksByDay")).keySet().iterator().next());
    }
}