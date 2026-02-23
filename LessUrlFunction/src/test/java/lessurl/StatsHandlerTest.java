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
        statsHandler = new StatsHandler(mockDdb, gson, "UrlsTable", "ClicksTable", "TrendTable", "AnalyticTable", "dummy-api-key", mockHttpClient);
    }

    @Test
    @DisplayName("통계 조회 시 트렌드 데이터를 읽고 AI 분석 결과를 반환한다")
    void testHandleRequest_WithTrendsAndAI() {
        // given
        String shortId = "stats123";
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPathParameters(Collections.singletonMap("shortId", shortId));

        Map<String, AttributeValue> urlItem = new HashMap<>();
        urlItem.put("shortId", AttributeValue.builder().s(shortId).build());
        urlItem.put("originalUrl", AttributeValue.builder().s("https://target.com").build());
        urlItem.put("clickCount", AttributeValue.builder().n("10").build());
        urlItem.put("aiInsight", AttributeValue.builder().s("인기 있는 링크입니다!").build());
        urlItem.put("lastAnalyzed", AttributeValue.builder().s(Instant.now().toString()).build());

        when(mockDdb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(urlItem).build());

        Map<String, AttributeValue> trendItem = new HashMap<>();
        trendItem.put("category", AttributeValue.builder().s("COUNTRY").build());
        Map<String, AttributeValue> statsData = new HashMap<>();
        statsData.put("KR", AttributeValue.builder().n("5").build());
        trendItem.put("statsData", AttributeValue.builder().m(statsData).build());

        when(mockDdb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of(trendItem)).build());

        // when
        APIGatewayProxyResponseEvent response = statsHandler.handleRequest(request, mockContext);

        // then
        assertEquals(200, response.getStatusCode());
        Map responseBody = gson.fromJson(response.getBody(), Map.class);
        Map stats = (Map) responseBody.get("stats");

        assertTrue(stats.containsKey("countryStats"));
        assertEquals(5.0, ((Map)stats.get("countryStats")).get("KR"));
    }
}