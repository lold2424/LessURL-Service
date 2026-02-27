package lessurl;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedirectHandlerTest {

    private RedirectHandler redirectHandler;

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

    @BeforeEach
    void setUp() {
        redirectHandler = new RedirectHandler(mockDdb, mockLambda, mockSqs, "mock-urls-table", "mock-analytics-queue-url");

        lenient().when(mockContext.getLogger()).thenReturn(mockLogger);
    }

    @Test
    @DisplayName("유효한 shortId로 요청 시 SQS로 분석 데이터를 전송하고 301 리다이렉트한다")
    void testHandleRequest_Success_WithSqsAnalytics() {
        // given
        String testShortId = "abc1234";
        String originalUrl = "https://www.example.com";

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPathParameters(Map.of("shortId", testShortId));
        request.setHeaders(Map.of(
            "User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)",
            "CloudFront-Viewer-Country", "KR"
        ));
        request.setRequestContext(new APIGatewayProxyRequestEvent.ProxyRequestContext());
        request.getRequestContext().setIdentity(new APIGatewayProxyRequestEvent.RequestIdentity());

        GetItemResponse getItemResponse = GetItemResponse.builder() 
                .item(Map.of("originalUrl", AttributeValue.builder().s(originalUrl).build()))
                .build();

        lenient().when(mockDdb.getItem(any(GetItemRequest.class))).thenReturn(getItemResponse);
        lenient().when(mockSqs.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder().build());

        // when
        APIGatewayProxyResponseEvent response = redirectHandler.handleRequest(request, mockContext);

        // then
        assertEquals(301, response.getStatusCode());
        assertEquals(originalUrl, response.getHeaders().get("Location"));

        verify(mockSqs, atLeastOnce()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    @DisplayName("존재하지 않는 shortId로 요청 시 404 에러를 반환한다")
    void testHandleRequest_NotFound() {
        // given
        String testShortId = "nonexistent";

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPathParameters(Map.of("shortId", testShortId));

        GetItemResponse getItemResponse = GetItemResponse.builder().build();
        when(mockDdb.getItem(any(GetItemRequest.class))).thenReturn(getItemResponse);

        when(mockDdb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder().items(java.util.Collections.emptyList()).build());

        // when
        APIGatewayProxyResponseEvent response = redirectHandler.handleRequest(request, mockContext);

        // then
        assertEquals(404, response.getStatusCode());
        assertTrue(response.getBody().contains("URL not found"));
    }

    @Test
    @DisplayName("shortId가 없는 요청 시 메인 페이지로 301 리다이렉트한다")
    void testHandleRequest_InvalidInput() {
        // given
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPathParameters(Map.of()); 
        
        // when
        APIGatewayProxyResponseEvent response = redirectHandler.handleRequest(request, mockContext);

        // then
        assertEquals(301, response.getStatusCode());
        assertEquals("https://www.lessurl.site", response.getHeaders().get("Location"));
    }
}
