package net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static java.net.HttpURLConnection.*;

/**
 * Created by Edsuns@qq.com on 2020/12/23.
 */
public class HttpRequest {
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 16;
    private static final int DEFAULT_TIMEOUT = 5000;
    private static final int REDIRECTS_MAX = 10;
    private static final Charset DEFAULT_ENCODING = StandardCharsets.UTF_8;
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36";

    private static final int HTTP_TEMP_REDIRECT = 307; // http/1.1 temporary redirect, not in Java's set.

    private static final String LOCATION = "Location";
    private static final String USER_AGENT = "User-Agent";
    private static final String ACCEPT_CHARSET = "Accept-Charset";

    private URL url;
    private Proxy proxy;
    private HttpURLConnection connection;
    private List<String> redirects;
    private int status;
    private Map<String, List<String>> responseHeaders;
    private Charset encoding;
    private byte[] bodyBytes;

    public HttpRequest(URL url) {
        this.url = url;
    }

    public HttpRequest(URL url, Proxy proxy) {
        this.url = url;
        this.proxy = proxy;
    }

    public HttpRequest(String url) throws MalformedURLException {
        this.url = new URL(url);
    }

    public HttpRequest(String url, Proxy proxy) throws MalformedURLException {
        this.url = new URL(url);
        this.proxy = proxy;
    }

    public HttpRequest exec() throws IOException {
        return exec(DEFAULT_TIMEOUT);
    }

    public HttpRequest exec(int timeout) throws IOException {
        List<String> rds = new ArrayList<>();
        connection = openConnectionWithRedirects(url, proxy, timeout, rds, new String[][]{
                {USER_AGENT, DEFAULT_USER_AGENT},
                {ACCEPT_CHARSET, DEFAULT_ENCODING.name()}
        });
        url = connection.getURL();
        redirects = Collections.unmodifiableList(rds);
        // get status
        status = connection.getResponseCode();
        // response headers
        responseHeaders = connection.getHeaderFields();
        // get encoding
        encoding = getEncoding(connection);
        // get response body
        if (status < HTTP_OK || status >= HTTP_BAD_REQUEST) {
            connection.disconnect();
            throw new ConnectException("Bad response status");
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        crossStreams(connection.getInputStream(), bo);
        bodyBytes = bo.toByteArray();
        // disconnect
        connection.disconnect();
        return this;
    }

    public URL getURL() {
        return url;
    }

    public Proxy getProxy() {
        return proxy;
    }

    public HttpURLConnection getConnection() {
        return connection;
    }

    public List<String> getRedirects() {
        return redirects;
    }

    public int getStatus() {
        return status;
    }

    public Map<String, List<String>> getResponseHeaders() {
        return responseHeaders;
    }

    public Charset getEncoding() {
        return encoding;
    }

    public byte[] getBodyBytes() {
        return bodyBytes;
    }

    public String getBody() {
        return new String(bodyBytes, 0, bodyBytes.length, encoding);
    }

    private static Charset getEncoding(HttpURLConnection connection) {
        String contentEncoding = connection.getContentEncoding();
        if (contentEncoding != null) {
            return fallbackEncoding(contentEncoding);
        }

        String contentType = connection.getContentType();
        for (String value : contentType.split(";")) {
            value = value.trim();
            if (value.toLowerCase(Locale.US).startsWith("charset=")) {
                return fallbackEncoding(value.substring(8));
            }
        }

        return DEFAULT_ENCODING;
    }

    private static Charset fallbackEncoding(String encoding) {
        if (StandardCharsets.UTF_8.name().equalsIgnoreCase(encoding))
            return StandardCharsets.UTF_8;
        if (StandardCharsets.ISO_8859_1.name().equalsIgnoreCase(encoding))
            return StandardCharsets.ISO_8859_1;
        if (StandardCharsets.US_ASCII.name().equalsIgnoreCase(encoding))
            return StandardCharsets.US_ASCII;
        return DEFAULT_ENCODING;
    }

    public static void crossStreams(InputStream inputStream, OutputStream outputStream) throws IOException {
        final byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, count);
        }
    }

    public static HttpURLConnection openConnectionWithRedirects(
            URL url, Proxy proxy, int timeout,
            List<String> tempRedirects, String[][] requestHeaders) throws IOException {
        final boolean hasRequestHeaders = requestHeaders != null && requestHeaders.length > 0;
        int redirects = 0;
        do {
            HttpURLConnection conn = (HttpURLConnection) (proxy == null ? url.openConnection() : url.openConnection(proxy));
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            // disable default FollowRedirects, use our method instead
            // for some unknown reason, default FollowRedirects doesn't work if set User-Agent request header
            conn.setInstanceFollowRedirects(false);
            // record redirects
            tempRedirects.add(url.toString());

            if (hasRequestHeaders) {
                for (String[] header : requestHeaders) {
                    conn.setRequestProperty(header[0], header[1]);
                }
            }

            int status = conn.getResponseCode();
            if (status >= HTTP_MULT_CHOICE && status <= HTTP_TEMP_REDIRECT
                    && status != 306 && status != HTTP_NOT_MODIFIED) {
                URL base = conn.getURL();
                String loc = conn.getHeaderField(LOCATION);
                URL target = null;
                if (loc != null) {
                    target = new URL(base, loc);
                }
                conn.disconnect();
                if (target == null) {
                    throw new SecurityException("Illegal URL redirect");
                }
                url = target;
                redirects++;
                continue;
            }
            return conn;
        } while (redirects <= REDIRECTS_MAX);

        throw new ProtocolException("Server redirected too many times (" + redirects + ")");
    }
}
