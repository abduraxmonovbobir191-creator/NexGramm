package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

// JVM test of the silent login: initData parsing and the core exchange against a mocked HTTP layer
public class CoreInitDataTest {

    private static final String INIT_DATA = "query_id=AAHdF6IQAAAAAN0XohDhrOrc&user=%7B%22id%22%3A279058397%2C%22first_name%22%3A%22A%20B%22%7D&auth_date=1700000000&hash=c0ffee";

    private static final String WEBVIEW_URL = "https://ng.nexecos.com/app-auth#tgWebAppData="
        + "query_id%3DAAHdF6IQAAAAAN0XohDhrOrc%26user%3D%257B%2522id%2522%253A279058397%252C%2522first_name%2522%253A%2522A%2520B%2522%257D%26auth_date%3D1700000000%26hash%3Dc0ffee"
        + "&tgWebAppVersion=9.0&tgWebAppPlatform=android";

    private static final class MockTransport implements CoreInitData.Transport {
        final int code;
        final String body;
        String path;
        JSONObject sent;

        MockTransport(int code, String body) {
            this.code = code;
            this.body = body;
        }

        @Override
        public CoreClient.Response post(String path, JSONObject body) {
            this.path = path;
            this.sent = body;
            return new CoreClient.Response(code, this.body);
        }
    }

    @Test
    public void extractsInitDataFromFragment() {
        assertEquals(INIT_DATA, CoreInitData.extract(WEBVIEW_URL));
    }

    @Test
    public void extractsInitDataWhenNotFirstParam() {
        assertEquals("a=1&hash=x", CoreInitData.extract("https://h/app-auth?x=1#tgWebAppVersion=9.0&tgWebAppData=a%3D1%26hash%3Dx"));
    }

    @Test
    public void extractsInitDataFromQuery() {
        assertEquals("a=1&hash=x", CoreInitData.extract("https://h/app-auth?tgWebAppData=a%3D1%26hash%3Dx&tgWebAppVersion=9.0#tgWebAppPlatform=android"));
    }

    @Test
    public void describeShowsOnlyKeys() {
        assertEquals("host=ng.nexecos.com same=yes #[tgWebAppData,tgWebAppVersion,tgWebAppPlatform] ?[]",
            CoreInitData.describe("https://ng.nexecos.com/app-auth", WEBVIEW_URL));
        assertEquals("host=h same=no #[tgWebAppVersion,(6 chars)] ?[x]",
            CoreInitData.describe("https://ng.nexecos.com/app-auth", "https://h/other?x=secret#tgWebAppVersion=9.0&secret"));
        assertEquals("host=ng.nexecos.com same=prefix #[] ?[]",
            CoreInitData.describe("https://ng.nexecos.com/app-auth", "https://ng.nexecos.com/app-auth/"));
        assertEquals("url=null", CoreInitData.describe("https://ng.nexecos.com/app-auth", null));
    }

    @Test
    public void noInitData() {
        assertNull(CoreInitData.extract(null));
        assertNull(CoreInitData.extract("https://ng.nexecos.com/app-auth"));
        assertNull(CoreInitData.extract("https://ng.nexecos.com/app-auth#tgWebAppVersion=9.0"));
        assertNull(CoreInitData.extract("https://ng.nexecos.com/app-auth#tgWebAppData="));
        assertNull(CoreInitData.extract("https://ng.nexecos.com/app-auth#tgWebAppData=%zz"));
    }

    @Test
    public void exchangeSendsInitDataAndReadsTokens() throws Exception {
        final MockTransport transport = new MockTransport(200,
            "{\"access_token\":\"acc\",\"refresh_token\":\"ref\",\"expires_in\":900,\"refresh_expires_in\":2592000,\"token_type\":\"Bearer\"}");
        final CoreTokens tokens = CoreInitData.exchangeBlocking(transport, INIT_DATA, 1000L);

        assertEquals("/v1/auth/app/initdata", transport.path);
        assertEquals(1, transport.sent.length());
        assertEquals(INIT_DATA, transport.sent.getString("init_data"));
        assertEquals("acc", tokens.accessToken);
        assertEquals("ref", tokens.refreshToken);
        assertEquals(1000L + 900_000L, tokens.accessExpiresAt);
        assertEquals(1000L + 2_592_000_000L, tokens.refreshExpiresAt);
        assertEquals("CoreTokens{hidden}", tokens.toString());
    }

    @Test
    public void exchangeHttpErrorKeepsCode() throws Exception {
        for (int code : new int[]{401, 404, 429, 500}) {
            try {
                CoreInitData.exchangeBlocking(new MockTransport(code, "{\"error\":\"x\"}"), INIT_DATA, 0);
                fail("expected HttpException " + code);
            } catch (CoreInitData.HttpException e) {
                assertEquals(code, e.code);
            }
        }
    }

    @Test
    public void exchangeBadBody() throws Exception {
        for (String body : new String[]{"", "{}", "{\"access_token\":\"\",\"refresh_token\":\"r\",\"expires_in\":1,\"refresh_expires_in\":1}"}) {
            try {
                CoreInitData.exchangeBlocking(new MockTransport(200, body), INIT_DATA, 0);
                fail("expected IOException for " + body);
            } catch (CoreInitData.HttpException e) {
                fail("not an http error");
            } catch (IOException e) {
                assertEquals("bad initdata response", e.getMessage());
            }
        }
    }
}
