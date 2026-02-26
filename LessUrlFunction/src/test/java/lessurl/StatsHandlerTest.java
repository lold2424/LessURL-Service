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
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsHandlerTest {

    private StatsHandler statsHandler;
    private final Gson gson = new Gson();

    @Mock
    private DynamoDbClient mockDdb;
    @Mock
    private LambdaClient mockLambda;
    @Mock
    private SqsClient mockSqs;
    @Mock
    private Context mockContext;
    @Mock
    private LambdaLogger mockLogger;
    @Mock
    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        lenient().when(mockContext.getLogger()).thenReturn(mockLogger);
        statsHandler = new StatsHandler(mockDdb, mockLambda, mockSqs, gson, "UrlsTable", "ClicksTable", "TrendTable");
    }

    @Test
    @DisplayName("통계 조회 시 클릭 데이터와 트렌드 데이터를 정상적으로 집계하여 반환한다")
    void testHandleRequest_Success() {
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

        Map<String, AttributeValue> clickLog = new HashMap<>();
        clickLog.put("timestamp", AttributeValue.builder().s(Instant.now().toString()).build());
        clickLog.put("referer", AttributeValue.builder().s("https://google.com").build());
        
        QueryResponse clicksQueryRes = QueryResponse.builder().items(List.of(clickLog)).build();

        Map<String, AttributeValue> trendItem = new HashMap<>();
        trendItem.put("category", AttributeValue.builder().s("COUNTRY").build());
        Map<String, AttributeValue> statsData = new HashMap<>();
        statsData.put("KR", AttributeValue.builder().n("5").build());
        trendItem.put("statsData", AttributeValue.builder().m(statsData).build());
        
        QueryResponse trendQueryRes = QueryResponse.builder().items(List.of(trendItem)).build();

        when(mockDdb.query(any(QueryRequest.class)))
                .thenReturn(clicksQueryRes)
                .thenReturn(trendQueryRes);

        // when
        APIGatewayProxyResponseEvent response = statsHandler.handleRequest(request, mockContext);

        // then
        assertEquals(200, response.getStatusCode());
        Map responseBody = gson.fromJson(response.getBody(), Map.class);
        Map stats = (Map) responseBody.get("stats");

        assertEquals(10.0, responseBody.get("clicks"));
        assertTrue(stats.containsKey("clicksByDay"));
        assertTrue(stats.containsKey("countryStats"));
        assertEquals(5.0, ((Map)stats.get("countryStats")).get("KR"));
        assertEquals(1.0, ((Map)stats.get("clicksByReferer")).get("https://google.com"));
    }
}
