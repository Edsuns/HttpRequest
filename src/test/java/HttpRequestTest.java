import net.HttpRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Created by Edsuns@qq.com on 2020/12/24.
 */
public class HttpRequestTest {
    private static final String TEST_HTTP_URL = "http://bing.com";

    @Test
    public void testRedirects() throws IOException {
        HttpRequest request = new HttpRequest(TEST_HTTP_URL).exec();
        int status = request.getStatus();
        String url = request.getURL().toString();
        int redirectsCount = request.getRedirects().size();
        int bodyLength = request.getBody().length();

        assertEquals(status, HTTP_OK);
        assertNotEquals(url, TEST_HTTP_URL);
        assertTrue(redirectsCount > 0);
        assertTrue(bodyLength > 0);
    }
}
