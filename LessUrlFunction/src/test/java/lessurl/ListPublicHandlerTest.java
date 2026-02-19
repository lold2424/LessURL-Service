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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListPublicHandlerTest {

    private ListPublicHandler listPublicHandler;
    private final Gson gson = new Gson();

    @Mock
    private DynamoDbClient mockDdb;

    @Mock
    private Context mockContext;

    @Mock
    private LambdaLogger mockLogger;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(mockContext.getLogger()).thenReturn(mockLogger);
        listPublicHandler = new ListPublicHandler();

        Field ddbField = ListPublicHandler.class.getDeclaredField("ddb");
        ddbField.setAccessible(true);
        ddbField.set(listPublicHandler, mockDdb);
        
        Field tableField = ListPublicHandler.class.getDeclaredField("tableName");
        tableField.setAccessible(true);
        tableField.set(listPublicHandler, "UrlsTable");
    }

    @Test
    @DisplayName("공개 URL 목록을 성공적으로 조회하여 반환한다")
    void testHandleRequest_Success() {
        // given
        Map<String, AttributeValue> item = Map.of(
                "shortId", AttributeValue.builder().s("abc12345").build(),
                "originalUrl", AttributeValue.builder().s("https://example.com").build(),
                "createdAt", AttributeValue.builder().s("2026-02-19T10:00:00Z").build(),
                "clickCount", AttributeValue.builder().n("5").build(),
                "title", AttributeValue.builder().s("Example").build()
        );

        QueryResponse queryResponse = QueryResponse.builder()
                .items(List.of(item))
                .build();

        when(mockDdb.query(any(QueryRequest.class))).thenReturn(queryResponse);

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();

        // when
        APIGatewayProxyResponseEvent response = listPublicHandler.handleRequest(request, mockContext);

        // then
        assertEquals(200, response.getStatusCode());
        List<Map<String, String>> body = gson.fromJson(response.getBody(), List.class);
        assertEquals(1, body.size());
        assertEquals("abc12345", body.get(0).get("shortId"));
        assertEquals("https://example.com", body.get(0).get("originalUrl"));
    }

    @Test
    @DisplayName("DB 에러 발생 시 500 에러를 반환한다")
    void testHandleRequest_Error() {
        // given
        when(mockDdb.query(any(QueryRequest.class))).thenThrow(new RuntimeException("DB Error"));
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();

        // when
        APIGatewayProxyResponseEvent response = listPublicHandler.handleRequest(request, mockContext);

        // then
        assertEquals(500, response.getStatusCode());
        assertTrue(response.getBody().contains("Failed to fetch public URLs"));
    }
}
