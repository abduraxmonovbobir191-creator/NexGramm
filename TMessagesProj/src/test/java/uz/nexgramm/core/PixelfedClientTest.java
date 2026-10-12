package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

// JVM test of the short error code shown on the fallback screen (DEBUG oauth-reason)
public class PixelfedClientTest {

    private static String detail(String body, String wwwAuthenticate) {
        return PixelfedClient.errorDetail(new PixelfedClient.Response(401, body, wwwAuthenticate));
    }

    @Test
    public void errorDetailIsShortAndSafe() {
        assertEquals("invalid_token", detail("{}", "Bearer realm=\"x\", error=\"invalid_token\", error_description=\"secret\""));
        assertEquals("access_denied", detail("{\"error\":\"access_denied\",\"hint\":\"token abc\"}", null));
        assertEquals("unauthenticated.", detail("{\"message\":\"Unauthenticated.\"}", null));
        assertEquals("a_b_c", detail("{\"error\":\" A b/c \"}", null));
        assertNull(detail("<html>nope</html>", null));
        final StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longName.append('x');
        }
        assertEquals(40, detail("{\"error\":\"" + longName + "\"}", null).length());
    }
}
