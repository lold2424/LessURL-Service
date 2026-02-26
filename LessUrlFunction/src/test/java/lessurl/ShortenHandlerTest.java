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
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortenHandlerTest {

    private ShortenHandler shortenHandler;
    private final Gson gson = new Gson();
    private final String testApiKey = "test-api-key";
    private final String testSafeBrowsingApiKey = "test-safe-browsing-key";


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

    @Mock
    private HttpResponse<String> mockHttpResponse;


    @Captor
    private ArgumentCaptor<PutItemRequest> putItemRequestCaptor;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        lenient().when(mockContext.getLogger()).thenReturn(mockLogger);

        String safeResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\\"classification\\": \\"SAFE\\"}"
                      }
                    ],
                    "role": "model"
                  }
                }
              ]
            }
            """;
        lenient().when(mockHttpResponse.statusCode()).thenReturn(200);
        lenient().when(mockHttpResponse.body()).thenReturn(safeResponse);
        lenient().when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);

        shortenHandler = new ShortenHandler(mockDdb, mockLambda, mockSqs, gson, "TestTable", testApiKey, testSafeBrowsingApiKey, mockHttpClient);
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
        assertTrue(responseBody.size() >= 2);
        assertTrue(responseBody.containsKey("shortId"));
        assertTrue(responseBody.containsKey("shortUrl"));
        assertEquals(7, ((String)responseBody.get("shortId")).length());

        verify(mockDdb).putItem(putItemRequestCaptor.capture());
        String savedShortId = putItemRequestCaptor.getValue().item().get("shortId").s();

        String expectedUrlPrefix = "https://test-api.com/prod/";
        String returnedShortUrl = responseBody.get("shortUrl");

        assertTrue(returnedShortUrl.startsWith(expectedUrlPrefix));
        assertTrue(returnedShortUrl.endsWith(savedShortId));
    }
    
    @Test
    @DisplayName("AI가 URL을 악성으로 판단하면 400 에러를 반환한다")
    void testHandleRequest_MaliciousUrlDetected() throws IOException, InterruptedException {
        // given
        String maliciousUrl = "http://malicious-site.com";
        String requestBody = "{\"url\": \"" + maliciousUrl + "\"}";
        APIGatewayProxyRequestEvent request = createApiRequest(requestBody);

        String maliciousResponse = """
            { "candidates": [ { "content": { "parts": [ { "text": "{\\"classification\\": \\"MALWARE\\"}" } ] } } ] }
            """;

        when(mockHttpResponse.body()).thenReturn(maliciousResponse);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);

        // when
        APIGatewayProxyResponseEvent response = shortenHandler.handleRequest(request, mockContext);

        // then
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("유해 URL이 감지되었습니다."));
        verify(mockDdb, never()).putItem(any(PutItemRequest.class));
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
        assertTrue(responseBody.size() >= 2);
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
        assertTrue(response.getBody().contains("Body is empty"));
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
        assertTrue(response.getBody().contains("Server Error"));
    }

    @Test
    @DisplayName("URL과 CustomAlias를 요청하면 DB에 저장하고 해당 alias가 포함된 shortUrl을 반환한다")
    void testHandleRequest_WithCustomAlias() {
        // given
        String inputUrl = "google.com";
        String inputAlias = "my-google";
        String requestBody = String.format("{\"url\": \"%s\", \"customAlias\": \"%s\"}", inputUrl, inputAlias);
        APIGatewayProxyRequestEvent request = createApiRequest(requestBody);

        // when
        APIGatewayProxyResponseEvent response = shortenHandler.handleRequest(request, mockContext);

        // then
        assertEquals(200, response.getStatusCode());

        Map<String, String> responseBody = gson.fromJson(response.getBody(), Map.class);

        assertTrue(responseBody.size() >= 2);
        assertTrue(responseBody.containsKey("shortId"));
        assertTrue(responseBody.containsKey("shortUrl"));

        verify(mockDdb).putItem(putItemRequestCaptor.capture());
        PutItemRequest capturedRequest = putItemRequestCaptor.getValue();
        String savedShortId = capturedRequest.item().get("shortId").s();
        String returnedShortUrl = responseBody.get("shortUrl");

        assertEquals(inputAlias, capturedRequest.item().get("customAlias").s());
        assertEquals("https://" + inputUrl, capturedRequest.item().get("originalUrl").s());
        assertTrue(returnedShortUrl.endsWith(inputAlias));
        assertTrue(returnedShortUrl.startsWith("https://test-api.com/prod/"));
    }

    @Test
    @DisplayName("visibility 옵션을 명시하면 DB에 해당 값으로 저장된다")
    void testHandleRequest_WithVisibility() {
        // given
        String inputUrl = "google.com";
        String inputVisibility = "PUBLIC";
        String requestBody = String.format("{\"url\": \"%s\", \"visibility\": \"%s\"}", inputUrl, inputVisibility);
        APIGatewayProxyRequestEvent request = createApiRequest(requestBody);

        // when
        shortenHandler.handleRequest(request, mockContext);

        // then
        verify(mockDdb).putItem(putItemRequestCaptor.capture());
        PutItemRequest capturedRequest = putItemRequestCaptor.getValue();
        assertEquals("PUBLIC", capturedRequest.item().get("visibility").s());
    }

    @Test
    @DisplayName("visibility 옵션이 없으면 기본값 PRIVATE으로 저장된다")
    void testHandleRequest_DefaultVisibilityIsPrivate() {
        // given
        String inputUrl = "google.com";
        String requestBody = String.format("{\"url\": \"%s\"}", inputUrl);
        APIGatewayProxyRequestEvent request = createApiRequest(requestBody);

        // when
        shortenHandler.handleRequest(request, mockContext);

        // then
        verify(mockDdb).putItem(putItemRequestCaptor.capture());
        PutItemRequest capturedRequest = putItemRequestCaptor.getValue();
        assertEquals("PRIVATE", capturedRequest.item().get("visibility").s());
    }
}