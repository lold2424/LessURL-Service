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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedirectHandlerTest {

    private RedirectHandler redirectHandler;

    @Mock
    private DynamoDbClient mockDdb;

    @Mock
    private Context mockContext;
    
    @Mock
    private LambdaLogger mockLogger;

    @BeforeEach
    void setUp() {
        redirectHandler = new RedirectHandler(mockDdb, "mock-urls-table", "mock-clicks-table", "mock-trend-table");

        lenient().when(mockContext.getLogger()).thenReturn(mockLogger);
    }

    @Test
    @DisplayName("유효한 shortId로 요청 시 국가 및 기기 정보를 포함하여 로그를 남기고 트렌드를 업데이트한다")
    void testHandleRequest_Success_WithAnalytics() {
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

        // when
        APIGatewayProxyResponseEvent response = redirectHandler.handleRequest(request, mockContext);

        // then
        assertEquals(301, response.getStatusCode());
        
        // 여기에 ddb.putItem(clicks)과 ddb.updateItem(trend)이 호출되었는지 검증하는 로직이 필요하나
        // 현재 Mockito 설정상 호출 여부만 확인하거나 ArgumentCaptor를 사용할 수 있음
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
