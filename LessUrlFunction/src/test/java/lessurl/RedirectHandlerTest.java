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
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;

@ExtendWith(MockitoExtension.class)
class RedirectHandlerTest {

    private RedirectHandler redirectHandler;

    @Mock
    private DynamoDbClient mockDdb;

    @Mock
    private LambdaClient mockLambda;

    @Mock
    private Context mockContext;
    
    @Mock
    private LambdaLogger mockLogger;

    @BeforeEach
    void setUp() {
        redirectHandler = new RedirectHandler(mockDdb, mockLambda, "mock-urls-table", "mock-analytics-function");

        lenient().when(mockContext.getLogger()).thenReturn(mockLogger);
    }

    @Test
    @DisplayName("유효한 shortId로 요청 시 AnalyticsFunction을 비동기로 호출하고 301 리다이렉트한다")
    void testHandleRequest_Success_WithAsyncAnalytics() {
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
        lenient().when(mockLambda.invoke(any(InvokeRequest.class))).thenReturn(InvokeResponse.builder().build());

        // when
        APIGatewayProxyResponseEvent response = redirectHandler.handleRequest(request, mockContext);

        // then
        assertEquals(301, response.getStatusCode());
        assertEquals(originalUrl, response.getHeaders().get("Location"));

        verify(mockLambda, atLeastOnce()).invoke(any(InvokeRequest.class));
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

        // when
        APIGatewayProxyResponseEvent response = redirectHandler.handleRequest(request, mockContext);

        // then
        assertEquals(404, response.getStatusCode());
        assertEquals("{\"error\": \"URL not found\"}", response.getBody());
    }

    @Test
    @DisplayName("shortId가 없는 요청 시 400 에러를 반환한다")
    void testHandleRequest_InvalidInput() {
        // given
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPathParameters(Map.of()); 
        
        // when
        APIGatewayProxyResponseEvent response = redirectHandler.handleRequest(request, mockContext);

        // then
        assertEquals(400, response.getStatusCode());
        assertEquals("{\"error\": \"Short ID is required\"}", response.getBody());
    }
}
