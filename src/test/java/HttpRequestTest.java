import net.HttpRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.util.Map;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Created by Edsuns@qq.com on 2020/12/24.
 */
public class HttpRequestTest {
    private static final String URL_HTTP = "http://bing.com";
    private static final String URL_SUGGESTION_API = "https://suggestion.baidu.com/su";
    private static final String URL_NEED_PROXY = "https://www.google.com";

    @Test
    public void testFollowRedirects() throws IOException {
        final HttpRequest request = new HttpRequest(URL_HTTP).exec();
        final int status = request.getStatus();
        final String url = request.getURL().toString();
        final int redirectsSize = request.getRedirects().size();
        final int bodyLength = request.getBody().length();

        assertEquals(status, HTTP_OK);
        assertNotEquals(url, URL_HTTP);
        assertTrue(redirectsSize > 1);
        assertTrue(bodyLength > 0);
    }

    @Test
    public void testDisableFollowRedirects() throws IOException {
        final HttpRequest request = new HttpRequest(URL_HTTP).followRedirects(false).exec();
        final int status = request.getStatus();
        final String url = request.getURL().toString();
        final int redirectsSize = request.getRedirects().size();

        assertNotEquals(status, HTTP_OK);
        assertEquals(url, URL_HTTP);
        assertEquals(redirectsSize, 1);
    }

    @Test
    public void testGetAndPost() throws IOException {
        final String key = "wd";
        final String query = "something";
        final HttpRequest requestGet = new HttpRequest(URL_SUGGESTION_API)
                .exec(HttpRequest.Method.GET, HttpRequest.data(key, query));
        final HttpRequest requestPost = new HttpRequest(URL_SUGGESTION_API)
                .exec(HttpRequest.Method.POST, HttpRequest.data(key, query));

        assertEquals(requestGet.getStatus(), HTTP_OK);
        assertEquals(requestPost.getStatus(), HTTP_OK);
        assertEquals(requestGet.getRedirects().size(), 1);
        assertEquals(requestPost.getRedirects().size(), 1);
        assertTrue(requestGet.hasTextBody());
        assertTrue(requestPost.hasTextBody());
        assertEquals(requestGet.getBody(), requestPost.getBody());
    }

    @Test
    public void testCookies() throws IOException {
        final HttpRequest request = new HttpRequest(URL_HTTP).exec();
        int status = request.getStatus();
        Map<String, String> cookies = request.getCookies();

        assertEquals(status, HTTP_OK);
        assertTrue(cookies.size() > 0);

        // request again
        request.cookies(cookies).exec();
        status = request.getStatus();
        cookies = request.getCookies();

        assertEquals(status, HTTP_OK);
        assertTrue(cookies.size() > 0);
    }

    @Test
    public void testProxy() throws IOException {
        final String proxyHost = "127.0.0.1";
        final int proxyPort = 10809;
        final Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        final int timeout = 300;
        final HttpRequest request = new HttpRequest(URL_NEED_PROXY, proxy)
                .timeout(timeout).exec(HttpRequest.Method.HEAD);
        final int status = request.getStatus();

        HttpRequest requestNoProxy = null;
        try {
            requestNoProxy = new HttpRequest(URL_NEED_PROXY)
                    .timeout(timeout).exec(HttpRequest.Method.HEAD);
        } catch (SocketTimeoutException ignored) {
        }

        assertEquals(status, HTTP_OK);
        assertTrue(request.isBodyEmpty());
        assertNull(requestNoProxy);
    }
}
