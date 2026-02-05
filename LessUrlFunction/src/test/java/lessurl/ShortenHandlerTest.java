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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortenHandlerTest {

    private ShortenHandler shortenHandler;
    private final Gson gson = new Gson();

    @Mock
    private DynamoDbClient mockDdb;

    @Mock
    private Context mockContext;

    @Mock
    private LambdaLogger mockLogger;

    @Captor
    private ArgumentCaptor<PutItemRequest> putItemRequestCaptor;

    @BeforeEach
    void setUp() {
        lenient().when(mockContext.getLogger()).thenReturn(mockLogger);
        shortenHandler = new ShortenHandler(mockDdb, gson, "TestTable");
    }

    private APIGatewayProxyRequestEvent createApiRequest(String body) {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(body);

        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "test-api.com");
        headers.put("X-Forwarded-Proto", "https");
        request.setHeaders(headers);

        APIGatewayProxyRequestEvent.ProxyRequestContext requestContext = new APIGatewayProxyRequestEvent.ProxyRequestContext();
        requestContext.setStage("prod");
        request.setRequestContext(requestContext);

        return request;
    }

    @Test
    @DisplayName("유효한 URL 요청 시 200 OK와 shortUrl을 반환한다")
    void testHandleRequest_Success() {
        // given
        String originalUrl = "https://www.google.com";
        String requestBody = "{\"url\": \"" + originalUrl + "\"}";
        APIGatewayProxyRequestEvent request = createApiRequest(requestBody);

        // when
        APIGatewayProxyResponseEvent response = shortenHandler.handleRequest(request, mockContext);

        // then
        assertEquals(200, response.getStatusCode());

        Map<String, String> responseBody = gson.fromJson(response.getBody(), Map.class);
        assertEquals(2, responseBody.size());
        assertTrue(responseBody.containsKey("shortId"));
        assertTrue(responseBody.containsKey("shortUrl"));

        verify(mockDdb).putItem(putItemRequestCaptor.capture());
        String savedShortId = putItemRequestCaptor.getValue().item().get("shortId").s();

        String expectedUrlPrefix = "https://test-api.com/prod/";
        String returnedShortUrl = responseBody.get("shortUrl");

        assertTrue(returnedShortUrl.startsWith(expectedUrlPrefix));
        assertTrue(returnedShortUrl.endsWith(savedShortId));
    }

    @Test
    @DisplayName("URL에 프로토콜이 없으면 https:// 를 추가하고 shortUrl을 반환한다")
    void testHandleRequest_AddsHttpsPrefix() {
        // given
        String inputUrl = "google.com";
        String expectedUrl = "https://google.com";
        String requestBody = "{\"url\": \"" + inputUrl + "\"}";
        APIGatewayProxyRequestEvent request = createApiRequest(requestBody);

        // when
        APIGatewayProxyResponseEvent response = shortenHandler.handleRequest(request, mockContext);

        // then
        assertEquals(200, response.getStatusCode());

        Map<String, String> responseBody = gson.fromJson(response.getBody(), Map.class);
        assertEquals(2, responseBody.size());
        assertTrue(responseBody.containsKey("shortUrl"));

        verify(mockDdb).putItem(putItemRequestCaptor.capture());
        PutItemRequest capturedRequest = putItemRequestCaptor.getValue();
        assertEquals(expectedUrl, capturedRequest.item().get("originalUrl").s());

        String savedShortId = capturedRequest.item().get("shortId").s();
        String returnedShortUrl = responseBody.get("shortUrl");
        assertTrue(returnedShortUrl.endsWith(savedShortId));
    }


    @Test
    @DisplayName("Body가 비어있으면 400 에러를 반환한다")
    void testHandleRequest_EmptyBody() {
        // given
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(null);

        // when
        APIGatewayProxyResponseEvent response = shortenHandler.handleRequest(request, mockContext);

        // then
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Request body is empty"));
    }

    @Test
    @DisplayName("DynamoDB 저장 중 에러가 발생하면 500 에러를 반환한다")
    void testHandleRequest_DynamoDbError() {
        // given
        String requestBody = "{\"url\": \"https://error.com\"}";
        APIGatewayProxyRequestEvent request = createApiRequest(requestBody);

        doThrow(new RuntimeException("DynamoDB Error")).when(mockDdb).putItem(any(PutItemRequest.class));

        // when
        APIGatewayProxyResponseEvent response = shortenHandler.handleRequest(request, mockContext);

        // then
        assertEquals(500, response.getStatusCode());
        assertTrue(response.getBody().contains("Internal Server Error"));
    }

    @Test
    @DisplayName("URL과 Title을 요청하면 DB에 저장하고 shortId, shortUrl만 반환한다")
    void testHandleRequest_WithTitle() {
        // given
        String inputUrl = "google.com";
        String inputTitle = "구글 메인";
        String requestBody = String.format("{\"url\": \"%s\", \"title\": \"%s\"}", inputUrl, inputTitle);
        APIGatewayProxyRequestEvent request = createApiRequest(requestBody);

        // when
        APIGatewayProxyResponseEvent response = shortenHandler.handleRequest(request, mockContext);

        // then
        assertEquals(200, response.getStatusCode());

        Map<String, String> responseBody = gson.fromJson(response.getBody(), Map.class);

        assertEquals(2, responseBody.size());
        assertTrue(responseBody.containsKey("shortId"));
        assertTrue(responseBody.containsKey("shortUrl"));

        verify(mockDdb).putItem(putItemRequestCaptor.capture());
        PutItemRequest capturedRequest = putItemRequestCaptor.getValue();
        String savedShortId = capturedRequest.item().get("shortId").s();
        String returnedShortUrl = responseBody.get("shortUrl");

        assertEquals(inputTitle, capturedRequest.item().get("title").s());
        assertEquals("https://" + inputUrl, capturedRequest.item().get("originalUrl").s());
        assertTrue(returnedShortUrl.endsWith(savedShortId));
        assertTrue(returnedShortUrl.startsWith("https://test-api.com/prod/"));
    }
}