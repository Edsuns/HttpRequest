package io.github.edsuns.net;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static java.net.HttpURLConnection.*;

/**
 * Created by Edsuns@qq.com on 2020/12/23.
 */
public class HttpRequest {
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 16;
    private static final int DEFAULT_TIMEOUT = 10000;// 10 seconds
    private static final int REDIRECTS_MAX = 10;
    private static final Charset DEFAULT_ENCODING = StandardCharsets.UTF_8;
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36";

    private static final int HTTP_TEMP_REDIRECT = 307;// http/1.1 temporary redirect, not in Java's set.

    // request & response header names
    public static final String USER_AGENT = "User-Agent";
    public static final String ACCEPT_CHARSET = "Accept-Charset";
    public static final String ACCEPT_ENCODING = "Accept-Encoding";
    public static final String COOKIE = "Cookie";
    public static final String LOCATION = "Location";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_ENCODING = "Content-Encoding";
    public static final String SET_COOKIE = "Set-Cookie";
    /**
     * <p>请求下载范围：{@value}</p>
     * <p>返回数据范围：Content-Range=bytes 0-100/100</p>
     *
     * @see #HEADER_RANGE
     */
    public static final String HEADER_CONTENT_RANGE = "Content-Range";
    /**
     * <p>接收范围请求：{@value}</p>
     * <p>返回全部数据：Accept-Ranges=bytes</p>
     *
     * @see #HEADER_RANGE
     */
    public static final String HEADER_ACCEPT_RANGES = "Accept-Ranges";
    /**
     * <p>范围请求：{@value}</p>
     * <table border="1">
     * 	<caption>HTTP协议断点续传设置</caption>
     * 	<tr>
     * 		<td>Range: bytes=0-499</td>
     * 		<td>范围：0-499</td>
     * 	</tr>
     * 	<tr>
     * 		<td>Range: bytes=500-999</td>
     * 		<td>范围：500-999</td>
     * 	</tr>
     * 	<tr>
     * 		<td>Range: bytes=-500</td>
     * 		<td>最后500字节</td>
     * 	</tr>
     * 	<tr>
     * 		<td>Range: bytes=500-</td>
     * 		<td>500字节开始到结束</td>
     * 	</tr>
     * 	<tr>
     * 		<td>Range: bytes=0-0,-1</td>
     * 		<td>第一个字节和最后一个字节</td>
     * 	</tr>
     * 	<tr>
     * 		<td>Range: bytes=500-600,601-999</td>
     * 		<td>同时指定多个范围</td>
     * 	</tr>
     * </table>
     */
    public static final String HEADER_RANGE = "Range";
    /**
     * <p>接收范围请求：{@value}</p>
     *
     * @see #HEADER_CONTENT_RANGE
     * @see #HEADER_ACCEPT_RANGES
     */
    public static final String HEADER_VALUE_BYTES = "bytes";

    // Content-Type
    public static final String MULTIPART_FORM_DATA = "multipart/form-data";
    public static final String FORM_URL_ENCODED = "application/x-www-form-urlencoded";

    private static final String[][] DEFAULT_REQUEST_HEADERS = new String[][]{
            {USER_AGENT, DEFAULT_USER_AGENT},
            {ACCEPT_ENCODING, "gzip, deflate"},
    };

    /*
     * Matches text content types (like text/xml, application/javascript, application/xhtml+xml;charset=UTF8, etc)
     * https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types#important_mime_types_for_web_developers
     */
    private static final Pattern textContentTypeRxp = Pattern.compile("(?:application|text)/\\w*(?:xml|script).*");

    /**
     * http request methods
     */
    public enum Method {
        GET(false), POST(true), HEAD(false), OPTIONS(false),
        PUT(true), DELETE(false), PATCH(true), TRACE(false);

        private final boolean hasBody;

        Method(boolean hasBody) {
            this.hasBody = hasBody;
        }

        /**
         * Check if this HTTP method has/needs a request body
         *
         * @return if body needed
         */
        public final boolean hasBody() {
            return hasBody;
        }
    }

    private URL url;
    private final String _url;// origin url
    private final Proxy proxy;
    private String[][] headers;// has setter, no getter
    private String[][] headersFinal;
    private boolean followRedirects = HttpURLConnection.getFollowRedirects();
    private int timeout = DEFAULT_TIMEOUT;
    private HttpURLConnection connection;
    private List<String> redirects;
    private Map<String, String> cookies;
    private int status;
    private Map<String, List<String>> responseHeaders;
    private InputStream inputStream;
    private boolean inputStreamHasBeenObtained;
    private boolean responseLoaded = true;
    private Charset encoding;
    private byte[] bodyBytes;
    private String body;

    public HttpRequest(String url) {
        this(url, null);
    }

    public HttpRequest(String url, Proxy proxy) {
        this._url = Objects.requireNonNull(url, "url must not be null");
        this.proxy = proxy;
    }

    public HttpRequest timeout(int timeout) {
        this.timeout = timeout;
        return this;
    }

    public HttpRequest headers(Data headers) {
        return headers(headers.dataList.toArray(new String[0][0]));
    }

    // only replace headers set by user
    public HttpRequest headers(String[][] headers) {
        this.headers = headers;
        return this;
    }

    // private getter
    private String[][] getRequestHeaders() {
        if (headers == null) {// check if there are headers set by user
            if (headersFinal == null)
                headersFinal = DEFAULT_REQUEST_HEADERS;
        } else {
            headersFinal = concat(DEFAULT_REQUEST_HEADERS, headers);
            headers = null;// drop it, already added to headersFinal
        }
        return headersFinal;
    }

    public HttpRequest followRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
        return this;
    }

    // replace all cookies
    public HttpRequest cookies(Map<String, String> cookies) {
        this.cookies = cookies;
        return this;
    }

    public HttpRequest get() throws IOException {
        return exec(Method.GET);
    }

    public HttpRequest get(Data params) throws IOException {
        return exec(Method.GET, params);
    }

    public HttpRequest exec() throws IOException {
        return get();
    }

    public HttpRequest exec(Data params) throws IOException {
        return get(params);
    }

    public HttpRequest exec(Method method) throws IOException {
        return exec(method, null);
    }

    public HttpRequest exec(Method method, Data data) throws IOException {
        reset();
        if (cookies == null)
            cookies = new HashMap<>();
        connection = openConnectionWithRedirects(url, proxy, method, timeout,
                followRedirects ? REDIRECTS_MAX : 0,
                getRequestHeaders(), data, cookies, redirects
        );
        url = connection.getURL();
        redirects = Collections.unmodifiableList(redirects);
        // get status
        status = connection.getResponseCode();
        // response headers
        responseHeaders = connection.getHeaderFields();
        // looking for input stream
        inputStream = connection.getErrorStream();
        if (inputStream == null)
            inputStream = connection.getInputStream();
        if (hasHeaderWithValue(CONTENT_ENCODING, "gzip"))
            inputStream = new GZIPInputStream(inputStream);
        else if (hasHeaderWithValue(CONTENT_ENCODING, "deflate"))
            inputStream = new InflaterInputStream(inputStream, new Inflater(true));
        return this;
    }

    public HttpRequest loadResponse() throws IOException {
        if (inputStream == null) {
            throw new IllegalStateException("Request not yet executed!");
        }
        if (inputStreamHasBeenObtained) {
            throw new IllegalStateException("The input stream has been obtained by method getInputStream().");
        }
        if (responseLoaded) {
            return this;
        }
        try {
            // read body bytes
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            crossStreams(inputStream, bo);
            bodyBytes = bo.toByteArray();
            // get encoding
            String contentType = connection.getContentType();
            encoding = getEncodingFromContentType(contentType);
            if (encoding == null && isTextContentType(contentType))
                encoding = guessEncoding(bodyBytes);
            // create body string if has text body
            if (encoding != null && bodyBytes.length > 0)
                body = new String(bodyBytes, 0, bodyBytes.length, encoding.name());
        } finally {
            responseLoaded = true;
            // finish the request
            connection.disconnect();
        }
        return this;
    }

    // restore to initial state
    private void reset() throws MalformedURLException {
        url = new URL(_url);// always drops the old url
        redirects = new ArrayList<>();// always create a new redirect list
        connection = null;
        status = -1;
        responseHeaders = null;
        inputStream = null;
        inputStreamHasBeenObtained = false;
        responseLoaded = false;
        encoding = null;
        bodyBytes = null;
        body = null;
    }

    public URL getURL() {
        return url;
    }

    public String getOriginUrl() {
        return _url;
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

    public Map<String, String> getCookies() {
        return cookies;
    }

    public int getStatus() {
        return status;
    }

    public Map<String, List<String>> getResponseHeaders() {
        return responseHeaders;
    }

    public InputStream getInputStream() {
        inputStreamHasBeenObtained = true;
        return inputStream;
    }

    /**
     * Get response headers by name.
     *
     * @param name nullable
     * @return response headers
     */
    public List<String> getHeader(String name) {
        if (responseHeaders == null)
            return null;
        List<String> header = responseHeaders.get(name);
        if (header == null && name != null) {
            // find target headers ignore case
            for (Map.Entry<String, List<String>> item : responseHeaders.entrySet()) {
                if (name.equalsIgnoreCase(item.getKey())) {
                    return item.getValue();
                }
            }
        }
        return header;
    }

    public boolean hasHeaderWithValue(String name, String value) {
        List<String> values = getHeader(name);
        if (values != null)
            for (String candidate : values) {
                if (value.equalsIgnoreCase(candidate))
                    return true;
            }
        return false;
    }

    /**
     * <p>Determine if breakpoint transfer is supported.</p>
     *
     * @return true if breakpoint transfer is supported
     * @see #HEADER_CONTENT_RANGE
     * @see #HEADER_ACCEPT_RANGES
     */
    public boolean isBreakpointAvailable() {
        if (hasHeaderWithValue(HEADER_ACCEPT_RANGES, HEADER_VALUE_BYTES)) {
            return true;
        }
        return getHeader(HEADER_CONTENT_RANGE) != null;
    }

    public Charset getEncoding() throws IOException {
        loadResponse();
        return encoding;
    }

    public byte[] getBodyBytes() throws IOException {
        loadResponse();
        return bodyBytes;
    }

    public String getBody() throws IOException {
        loadResponse();
        if (hasTextBody())
            return body;
        return "";
    }

    public boolean isBodyEmpty() throws IOException {
        loadResponse();
        return bodyBytes == null || bodyBytes.length == 0;
    }

    public boolean hasTextBody() throws IOException {
        loadResponse();
        return body != null;
    }

    public boolean isBadStatus() {
        return status < HTTP_OK || status >= HTTP_BAD_REQUEST;
    }

    public static boolean isTextContentType(String contentType) {
        return contentType != null
                && (contentType.startsWith("text/") || textContentTypeRxp.matcher(contentType).matches());
    }

    public static Charset getEncodingFromContentType(String contentType) {
        if (contentType != null) {
            for (String value : contentType.toLowerCase(Locale.ENGLISH).split(";")) {
                value = value.trim();
                if (value.startsWith("charset="))
                    return Charset.forName(value.substring(8));
            }
        }
        return null;
    }

    /**
     * Combine multi-threading and exclusion to guess the encoding
     *
     * @param bytes bytes
     * @return encoding
     */
    public static Charset guessEncoding(final byte[] bytes) {
        if (bytes == null || bytes.length == 0)
            return DEFAULT_ENCODING;
        final CountDownLatch latch = new CountDownLatch(3);// count = validates.length - 1
        final class Validate extends Thread {
            private final Charset charset;
            private byte matches = 2;// 2 means no result yet

            Validate(Charset charset) {
                this.charset = charset;
            }

            @Override
            public void run() {
                try {
                    charset.newDecoder().decode(ByteBuffer.wrap(bytes));
                    matches = 1;// matches
                } catch (CharacterCodingException e) {
                    matches = 0;// doesn't match
                }
                if (matches == 1)
                    while (latch.getCount() > 0)
                        latch.countDown();
                else
                    latch.countDown();
            }
        }
        final Validate[] validates = {
                // these encodings are chosen considering the use of the exclusion
                new Validate(StandardCharsets.UTF_8),
                new Validate(StandardCharsets.ISO_8859_1),
                new Validate(StandardCharsets.US_ASCII),
                new Validate(Charset.forName("GBK"))
        };
        for (Validate validate : validates) {
            validate.start();
        }
        try {
            latch.await();
            for (Validate validate : validates) {
                final byte matches = validate.matches;
                if (matches == 1) {
                    return validate.charset;
                } else if (matches == 2) {
                    // the one remaining after exclusion
                    return validate.charset;
                }
            }
        } catch (InterruptedException ignored) {
        }
        return DEFAULT_ENCODING;
    }

    public static <T> T[] concat(T[] a, T[] b) {
        if (b == null || b.length <= 0)
            return a;
        T[] both = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        return both;
    }

    public static void crossStreams(InputStream inputStream, OutputStream outputStream) throws IOException {
        final byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, count);
        }
    }

    // create HttpURLConnection but don't trigger any connections
    private static HttpURLConnection createConnection(URL url, Proxy proxy, Method method, int timeout,
                                                      Map<String, String> cookies) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) (proxy == null ? url.openConnection() : url.openConnection(proxy));
        conn.setRequestMethod(method.name());
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        // disable native redirection, use our method instead
        // for some unknown reason, native redirection doesn't work if set User-Agent request header
        conn.setInstanceFollowRedirects(false);
        conn.setDoOutput(method.hasBody());
        // set request cookie
        if (cookies != null && cookies.size() > 0) {
            conn.setRequestProperty(COOKIE, toRequestCookieString(cookies));
        }
        return conn;
    }

    private static final char[] mimeBoundaryChars =
            "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final int boundaryLength = 32;

    /**
     * Creates a random string, suitable for use as a mime boundary
     */
    private static String mimeBoundary() {
        final StringBuilder mime = new StringBuilder();
        final Random rand = new Random();
        for (int i = 0; i < boundaryLength; i++) {
            mime.append(mimeBoundaryChars[rand.nextInt(mimeBoundaryChars.length)]);
        }
        return mime.toString();
    }

    private static URL serialiseRequestUrl(URL in, Data data) throws UnsupportedEncodingException, MalformedURLException {
        StringBuilder url = new StringBuilder();
        boolean first = true;
        // reconstitute the query, ready for appends
        url.append(in.getProtocol())
                .append("://")
                .append(in.getAuthority()) // includes host, port
                .append(in.getPath())
                .append("?");
        if (in.getQuery() != null) {
            url.append(in.getQuery());
            first = false;
        }
        for (String[] keyVal : data.dataList) {
            if (!first)
                url.append('&');
            else
                first = false;
            url.append(URLEncoder.encode(keyVal[0], DEFAULT_ENCODING.name()))
                    .append('=')
                    .append(URLEncoder.encode(keyVal[1], DEFAULT_ENCODING.name()));
        }
        data.dataList.clear(); // moved into url as get params
        return new URL(url.toString());
    }

    private static String setOutputContentType(HttpURLConnection conn) {
        String bound = null;
        String contentType = conn.getRequestProperty(CONTENT_TYPE);
        if (contentType != null) {
            if (contentType.contains(MULTIPART_FORM_DATA) && !contentType.contains("boundary")) {
                bound = mimeBoundary();
                conn.setRequestProperty(CONTENT_TYPE, MULTIPART_FORM_DATA + "; boundary=" + bound);
            }
        } else {
            conn.setRequestProperty(CONTENT_TYPE, FORM_URL_ENCODED + "; charset=" + DEFAULT_ENCODING.name());
        }
        return bound;
    }

    private static String encodeMimeName(String val) {
        if (val == null)
            return null;
        return val.replace("\"", "%22");
    }

    private static void writePost(final Data data, final OutputStream outputStream, final String bound) throws IOException {
        if (data == null || data.dataList.isEmpty())
            return;

        final BufferedWriter w = new BufferedWriter(new OutputStreamWriter(outputStream, DEFAULT_ENCODING));

        if (bound != null) {
            // boundary will be set if we're in multipart mode
            for (String[] keyVal : data.dataList) {
                w.write("--");
                w.write(bound);
                w.write("\r\n");
                w.write("Content-Disposition: form-data; name=\"");
                w.write(encodeMimeName(keyVal[0])); // encodes " to %22
                w.write("\"");
                w.write("\r\n\r\n");
                w.write(keyVal[1]);
                w.write("\r\n");
            }
            w.write("--");
            w.write(bound);
            w.write("--");
        } else {
            // regular form data (application/x-www-form-urlencoded)
            boolean first = true;
            for (String[] keyVal : data.dataList) {
                if (!first)
                    w.append('&');
                else
                    first = false;

                w.write(URLEncoder.encode(keyVal[0], DEFAULT_ENCODING.name()));
                w.write('=');
                w.write(URLEncoder.encode(keyVal[1], DEFAULT_ENCODING.name()));
            }
        }
        w.close();
    }

    /**
     * Parse response cookies into a no-duplicate-names cookie map
     *
     * @param respCookies cookies from response
     * @return cookie map
     */
    public static Map<String, String> getCookiesFrom(List<String> respCookies) {
        if (respCookies == null || respCookies.size() <= 0)
            return null;
        Map<String, String> cookies = new HashMap<>();
        for (String cookie : respCookies) {
            if (cookie == null)
                continue;
            TokenQueue cd = new TokenQueue(cookie);
            String cookieName = cd.chompTo("=").trim();
            // name not blank, cookie not null
            if (cookieName.length() > 0) {
                // contains path, date, domain, validateTLSCertificates et al
                cookies.put(cookieName, cookie);
            }
        }
        return cookies;
    }

    /**
     * Parse cookies into request cookie string
     * <p>Request cookie ignores path, date, domain, validateTLSCertificates et al.</p>
     *
     * @param cookies can be either a request cookie
     * @return request cookie
     */
    private static String toRequestCookieString(Map<String, String> cookies) {
        final StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> cookie : cookies.entrySet()) {
            if (!first)
                builder.append("; ");
            else
                first = false;

            String val = cookie.getValue();
            TokenQueue cd = new TokenQueue(val);
            cd.chompTo("=");
            String cookieVal = cd.consumeTo(";").trim();

            builder.append(cookie.getKey()).append('=').append(cookieVal);
            // TODO: spec says only ascii, no escaping / encoding defined. validate on set? or escape somehow here?
        }
        return builder.toString();
    }

    private static HttpURLConnection openConnectionWithRedirects(
            URL url, Proxy proxy, Method method, int timeout, int redirectsMax, String[][] requestHeaders, Data data,
            Map<String, String> tmpCookies, List<String> tmpRedirects) throws IOException {
        String protocol = url.getProtocol();
        if (!protocol.equals("http") && !protocol.equals("https"))
            throw new MalformedURLException("Only http & https protocols supported");
        final boolean hasRequestHeaders = requestHeaders != null && requestHeaders.length > 0;
        int redirects = 0;
        do {
            final boolean methodHasBody = method.hasBody();
            final boolean hasRequestData = data != null && !data.dataList.isEmpty();

            if (!methodHasBody && hasRequestData)
                url = serialiseRequestUrl(url, data);
            HttpURLConnection conn = createConnection(url, proxy, method, timeout, tmpCookies);
            // record redirects
            tmpRedirects.add(url.toString());

            // set request headers
            if (hasRequestHeaders) {
                for (String[] header : requestHeaders) {
                    conn.setRequestProperty(header[0], header[1]);
                }
            }
            String mimeBoundary = null;
            if (methodHasBody)
                mimeBoundary = setOutputContentType(conn);

            conn.connect();
            if (conn.getDoOutput())
                writePost(data, conn.getOutputStream(), mimeBoundary);

            List<String> respCookies = conn.getHeaderFields().get(SET_COOKIE);
            if (respCookies != null)
                tmpCookies.putAll(getCookiesFrom(respCookies));

            int status = conn.getResponseCode();
            if (redirectsMax > 0 && status >= HTTP_MULT_CHOICE && status <= HTTP_TEMP_REDIRECT
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
                // always redirect with a get. any data param from original request are dropped.
                if (status != HTTP_TEMP_REDIRECT) {
                    if (hasRequestData)
                        data.dataList.clear();
                    method = Method.GET;
                }
                url = target;
                redirects++;
                continue;
            }
            return conn;
        } while (redirects <= redirectsMax);

        throw new ProtocolException("Server redirected too many times (" + redirects + ")");
    }

    public static Data data(String name, Object value) {
        return data(name, String.valueOf(value));
    }

    public static Data data(String name, String value) {
        return new Data().data(name, value);
    }

    public static class Data {
        final List<String[]> dataList = new ArrayList<>();

        Data() {
        }

        public Data data(String name, Object value) {
            return data(name, String.valueOf(value));
        }

        public Data data(String name, String value) {
            String[] data = {name, value};
            dataList.add(data);
            return this;
        }
    }

    public static Async async(HttpRequest request, Method method) {
        return async(() -> request, method);
    }

    public static Async async(Callable<HttpRequest> callable, Method method) {
        return new Async(callable, method, null);
    }

    public static Async async(HttpRequest request, Method method, Data data) {
        return async(() -> request, method, data);
    }

    public static Async async(Callable<HttpRequest> callable, Method method, Data data) {
        return new Async(callable, method, data);
    }

    public static class Async implements Runnable {
        static volatile ExecutorService executor;

        static ExecutorService getExecutor() {
            if (executor == null) {
                synchronized (Async.class) {
                    if (executor == null)
                        executor = Executors.newFixedThreadPool(3);
                }
            }
            return executor;
        }

        final Callable<HttpRequest> callable;
        final Method method;
        final Data data;
        SuccessObserver successObserver;
        ErrorObserver errorObserver;

        Async(Callable<HttpRequest> callable, Method method, Data data) {
            this.callable = callable;
            this.method = method;
            this.data = data;
        }

        public void observe(SuccessObserver successObserver) {
            observe(successObserver, null);
        }

        public void observe(SuccessObserver successObserver, ErrorObserver errorObserver) {
            this.successObserver = successObserver;
            this.errorObserver = errorObserver;
            if (callable != null)
                getExecutor().execute(this);
        }

        @Override
        public void run() {
            try {
                HttpRequest request = callable.call();
                if (request != null)
                    request.exec(method, data);
                if (successObserver != null)
                    successObserver.onSuccess(request);
            } catch (Exception e) {
                if (errorObserver != null)
                    errorObserver.onError(e);
            }
        }
    }

    public interface SuccessObserver {
        void onSuccess(HttpRequest request) throws IOException;
    }

    public interface ErrorObserver {
        void onError(Exception exception);
    }

    /**
     * A character queue with parsing helpers.
     */
    static class TokenQueue {
        private final String queue;
        private int pos = 0;

        /**
         * Create a new TokenQueue.
         *
         * @param data string of data to back queue.
         */
        public TokenQueue(String data) {
            queue = data;
        }

        /**
         * Tests if the next characters on the queue match the sequence. Case insensitive.
         *
         * @param seq String to check queue for.
         * @return true if the next characters match.
         */
        public boolean matches(String seq) {
            return queue.regionMatches(true, pos, seq, 0, seq.length());
        }

        /**
         * Tests if the queue matches the sequence (as with match), and if they do, removes the matched string from the
         * queue.
         *
         * @param seq String to search for, and if found, remove from queue.
         * @return true if found and removed, false if not found.
         */
        public boolean matchChomp(String seq) {
            if (matches(seq)) {
                pos += seq.length();
                return true;
            } else {
                return false;
            }
        }

        /**
         * Pulls a string off the queue, up to but exclusive of the match sequence, or to the queue running out.
         *
         * @param seq String to end on (and not include in return, but leave on queue). <b>Case sensitive.</b>
         * @return The matched data consumed from queue.
         */
        public String consumeTo(String seq) {
            int offset = queue.indexOf(seq, pos);
            if (offset != -1) {
                String consumed = queue.substring(pos, offset);
                pos += consumed.length();
                return consumed;
            } else {
                return remainder();
            }
        }

        /**
         * Pulls a string off the queue (like consumeTo), and then pulls off the matched string (but does not return it).
         * <p>
         * If the queue runs out of characters before finding the seq, will return as much as it can (and queue will go
         * isEmpty() == true).
         *
         * @param seq String to match up to, and not include in return, and to pull off queue. <b>Case sensitive.</b>
         * @return Data matched from queue.
         */
        public String chompTo(String seq) {
            String data = consumeTo(seq);
            matchChomp(seq);
            return data;
        }

        /**
         * Consume and return whatever is left on the queue.
         *
         * @return remained of queue.
         */
        public String remainder() {
            final String remainder = queue.substring(pos);
            pos = queue.length();
            return remainder;
        }

        @Override
        public String toString() {
            return queue.substring(pos);
        }
    }
}
