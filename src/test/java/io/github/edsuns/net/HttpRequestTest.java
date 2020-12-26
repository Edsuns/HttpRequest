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
                .exec(HttpRequest.Method.GET,
                        HttpRequest.data(key, query)
                                .data("cb", 1)// just for testing type conversion
                );
        final HttpRequest requestPost = new HttpRequest(URL_SUGGESTION_API)
                .exec(HttpRequest.Method.POST,
                        HttpRequest.data(key, query)
                                .data("cb", 1)// just for testing type conversion
                );

        assertEquals(requestGet.getStatus(), HTTP_OK);
        assertEquals(requestPost.getStatus(), HTTP_OK);
        assertEquals(requestGet.getRedirects().size(), 1);
        assertEquals(requestPost.getRedirects().size(), 1);
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

        assertEquals(status, HTTP_OK);
        assertTrue(cookies.size() > 0);

        // drop one cookie
        cookies.remove(cookies.keySet().iterator().next());
        final int sizeNew = cookies.size();

        assertEquals(sizeNew, sizeOriginal - 1);

        // request again
        request.exec();
        final int sizeFinal = cookies.size();
        status = request.getStatus();

        assertEquals(status, HTTP_OK);
        assertEquals(sizeFinal, sizeOriginal);
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

        assertEquals(status, HTTP_OK);
        assertTrue(request.isBodyEmpty());
        assertNull(requestNoProxy);
    }

    @Test
    public void testBodyIsNotText() throws IOException {
        final HttpRequest request = new HttpRequest(URL_PNG).exec();
        final int status = request.getStatus();

        assertEquals(status, HTTP_OK);
        assertFalse(request.hasTextBody());
        assertTrue(request.getBody().isEmpty());
        assertTrue(request.getBodyBytes().length > 0);
    }

    @Test
    public void testBadStatus() throws IOException {
        final HttpRequest request = new HttpRequest(URL_404).exec();
        final int status = request.getStatus();

        assertEquals(status, HttpURLConnection.HTTP_NOT_FOUND);
        assertFalse(request.getBody().isEmpty());
    }

    @Test
    public void testAsyncRequest() throws InterruptedException {
        final int timeout = 300;
        final CountDownLatch latch = new CountDownLatch(2);

        final long t = 10;// max time to submit async request
        final long t1 = System.currentTimeMillis();
        HttpRequest.async(new HttpRequest(URL_NEED_PROXY, proxy).timeout(timeout), HttpRequest.Method.HEAD)
                .observe(request -> {
                    final int status = request.getStatus();
                    assertEquals(status, HTTP_OK);
                    latch.countDown();
                });

        HttpRequest.async(new HttpRequest(URL_NEED_PROXY).timeout(timeout), HttpRequest.Method.HEAD)
                .observe(request -> {
                    final int status = request.getStatus();
                    assertEquals(status, HTTP_OK);
                }, exception -> {
                    assertNotNull(exception);
                    latch.countDown();
                });
        final long t2 = System.currentTimeMillis();

        assertTrue(t2 - t1 < t);
        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        assertTrue(System.currentTimeMillis() - t2 > t);// true proves that it executes asynchronously
    }
}
