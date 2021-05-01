package io.github.edsuns.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Created by Edsuns@qq.com on 2020/12/24.
 */
public class HttpRequestTest {
    private static final String URL_HTTP = "http://bing.com";
    private static final String URL_SUGGESTION_API = "https://suggestion.baidu.com/su";
    private static final String URL_NEED_PROXY = "https://www.google.com";
    private static final String URL_PNG = "https://www.apple.com/apple-touch-icon.png";
    private static final String URL_404 = "https://www.gstatic.com";

    final String proxyHost = "127.0.0.1";
    final int proxyPort = 10809;
    final Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));

    @Test
    public void testFollowRedirects() throws IOException {
        final HttpRequest request = new HttpRequest(URL_HTTP).exec();
        final int status = request.getStatus();
        final String url = request.getURL().toString();
        final int redirectsSize = request.getRedirects().size();
        final int bodyLength = request.getBody().length();

        assertEquals(HTTP_OK, status);
        assertNotEquals(URL_HTTP, url);
        assertTrue(redirectsSize > 1);
        assertTrue(bodyLength > 0);
    }

    @Test
    public void testDisableFollowRedirects() throws IOException {
        final HttpRequest request = new HttpRequest(URL_HTTP).followRedirects(false).exec();
        final int status = request.getStatus();
        final String url = request.getURL().toString();
        final int redirectsSize = request.getRedirects().size();

        assertNotEquals(HTTP_OK, status);
        assertEquals(URL_HTTP, url);
        assertEquals(redirectsSize, 1);
    }

    @Test
    public void testGetAndPost() throws IOException {
        final String key = "wd";
        final String query = "something";
        final HttpRequest requestGet = new HttpRequest(URL_SUGGESTION_API)
                .exec(HttpRequest.Method.GET,
                        HttpRequest.data(key, query)
                                .data("cb", 1)// just for testing type conversion
                );
        final HttpRequest requestPost = new HttpRequest(URL_SUGGESTION_API)
                .exec(HttpRequest.Method.POST,
                        HttpRequest.data(key, query)
                                .data("cb", 1)// just for testing type conversion
                );

        assertEquals(HTTP_OK, requestGet.getStatus());
        assertEquals(HTTP_OK, requestPost.getStatus());
        assertEquals(1, requestGet.getRedirects().size());
        assertEquals(1, requestPost.getRedirects().size());
        assertTrue(requestGet.hasTextBody());
        assertTrue(requestPost.hasTextBody());
        assertTrue(requestGet.getBody().contains(query));
        assertTrue(requestPost.getBody().contains(query));
    }

    @Test
    public void testCookies() throws IOException {
        final HttpRequest request = new HttpRequest(URL_HTTP).exec();
        int status = request.getStatus();
        final Map<String, String> cookies = request.getCookies();
        final int sizeOriginal = cookies.size();

        assertEquals(HTTP_OK, status);
        assertTrue(cookies.size() > 0);

        // drop one cookie
        cookies.remove(cookies.keySet().iterator().next());
        final int sizeNew = cookies.size();

        assertEquals(sizeOriginal - 1, sizeNew);

        // request again
        request.exec();
        final int sizeFinal = cookies.size();
        status = request.getStatus();

        assertEquals(HTTP_OK, status);
        assertEquals(sizeOriginal, sizeFinal);
    }

    @Test
    public void testProxy() throws IOException {
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

        assertEquals(HTTP_OK, status);
        assertTrue(request.isBodyEmpty());
        assertNull(requestNoProxy);
    }

    @Test
    public void testBodyIsNotText() throws IOException {
        final HttpRequest request = new HttpRequest(URL_PNG).exec();
        final int status = request.getStatus();

        assertEquals(HTTP_OK, status);
        assertFalse(request.hasTextBody());
        assertTrue(request.getBody().isEmpty());
        assertTrue(request.getBodyBytes().length > 0);
    }

    @Test
    public void testBadStatus() throws IOException {
        final HttpRequest request = new HttpRequest(URL_404).exec();
        final int status = request.getStatus();

        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, status);
        assertFalse(request.getBody().isEmpty());
    }

    @Test
    public void testAsyncRequest() throws InterruptedException {
        final int timeout = 300;
        final CountDownLatch latch = new CountDownLatch(2);

        final long t = 10;// max time to submit async request
        final long begin = System.currentTimeMillis();
        HttpRequest.async(new HttpRequest(URL_NEED_PROXY, proxy).timeout(timeout), HttpRequest.Method.HEAD)
                .observe(request -> {
                    final int status = request.getStatus();
                    assertEquals(HTTP_OK, status);
                    latch.countDown();
                });

        HttpRequest.async(new HttpRequest(URL_NEED_PROXY).timeout(timeout), HttpRequest.Method.HEAD)
                .observe(request -> {
                    final int status = request.getStatus();
                    assertEquals(HTTP_OK, status);
                }, exception -> {
                    assertNotNull(exception);
                    latch.countDown();
                });
        final long end = System.currentTimeMillis();

        assertTrue(end - begin < t);
        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        assertTrue(System.currentTimeMillis() - end > t);// true proves that it executes asynchronously
    }
}
