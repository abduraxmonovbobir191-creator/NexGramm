package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

// Integration test of PixelfedClient against a scripted HTTP layer and in-memory secrets:
// token refresh on 401, refresh after 30 days, refresh rejected → disconnected, upload retry after 401
public class PixelfedClientFlowTest {

    private static final long USER = 42;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static final class Call {
        final String method;
        final String path;
        final String body;
        final String bearer;
        final File file;

        Call(String method, String path, String body, String bearer, File file) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.bearer = bearer;
            this.file = file;
        }
    }

    private final ArrayDeque<PixelfedClient.Response> script = new ArrayDeque<>();
    private final ArrayList<Call> calls = new ArrayList<>();
    private final HashMap<String, String> store = new HashMap<>();

    private final PixelfedClient.Transport transport = new PixelfedClient.Transport() {
        @Override
        public PixelfedClient.Response request(String method, String path, String formBody, String bearer) {
            calls.add(new Call(method, path, formBody, bearer, null));
            return next();
        }

        @Override
        public PixelfedClient.Response multipart(String path, ArrayList<String[]> fields, String fileField, File file, String fileName,
                                                 String mime, String bearer, PixelfedClient.UploadProgress progress) {
            calls.add(new Call("POST", path, fileField + ":" + mime, bearer, file));
            if (progress != null) {
                progress.onProgress(10, 10);
            }
            return next();
        }

        private PixelfedClient.Response next() {
            final PixelfedClient.Response r = script.poll();
            if (r == null) {
                throw new AssertionError("unexpected request");
            }
            return r;
        }
    };

    private final PixelfedClient.Secrets secrets = new PixelfedClient.Secrets() {
        @Override
        public String load(String key) {
            return store.get(key);
        }

        @Override
        public void save(String key, String value) {
            store.put(key, value);
        }

        @Override
        public void clear(String key) {
            store.remove(key);
        }
    };

    private static PixelfedClient.Response response(int code, String body) {
        return new PixelfedClient.Response(code, body, null);
    }

    private void giveTokens(String access, String refresh, long obtainedAt) throws Exception {
        store.put("pf_tokens_" + USER, new JSONObject().put("a", access).put("r", refresh).put("o", obtainedAt)
            .put("e", obtainedAt + 30 * DAY).put("u", "bob").toString());
    }

    @Before
    public void setUp() throws Exception {
        PixelfedClient.setForTests(transport, secrets);
        store.put("pf_client", new JSONObject().put("id", "7").put("secret", "client-secret").toString());
    }

    @Test
    public void refreshesOnceOn401AndRetries() throws Exception {
        giveTokens("acc1", "ref1", System.currentTimeMillis());
        script.add(response(401, "{\"error\":\"invalid_token\"}"));
        script.add(response(200, "{\"access_token\":\"acc2\",\"refresh_token\":\"ref2\",\"token_type\":\"Bearer\",\"expires_in\":2592000}"));
        script.add(response(200, "[]"));

        assertTrue(PixelfedClient.timelineBlocking(USER, true, null).isEmpty());

        assertEquals(3, calls.size());
        assertEquals("acc1", calls.get(0).bearer);
        assertTrue(calls.get(0).path.startsWith("/api/v1/timelines/public?local=true"));
        assertEquals("/oauth/token", calls.get(1).path);
        assertTrue(calls.get(1).body.contains("grant_type=refresh_token"));
        assertTrue(calls.get(1).body.contains("refresh_token=ref1"));
        assertTrue(calls.get(1).body.contains("client_id=7"));
        assertEquals("acc2", calls.get(2).bearer);
        final PixelfedTokens saved = PixelfedClient.getTokens(USER);
        assertEquals("acc2", saved.accessToken);
        assertEquals("ref2", saved.refreshToken);
        assertEquals("bob", saved.username);
    }

    @Test
    public void tokenOlderThan30DaysIsRefreshedFirst() throws Exception {
        giveTokens("old", "ref1", System.currentTimeMillis() - 31 * DAY);
        script.add(response(200, "{\"access_token\":\"fresh\",\"refresh_token\":\"ref2\"}"));
        script.add(response(200, "[]"));

        PixelfedClient.timelineBlocking(USER, false, "99");

        assertEquals("/oauth/token", calls.get(0).path);
        assertEquals("fresh", calls.get(1).bearer);
        assertTrue(calls.get(1).path.contains("/api/v1/timelines/home") && calls.get(1).path.contains("max_id=99"));
    }

    @Test
    public void rejectedRefreshDisconnects() throws Exception {
        giveTokens("acc1", "ref1", System.currentTimeMillis());
        script.add(response(401, ""));
        script.add(response(400, "{\"error\":\"invalid_grant\"}"));
        try {
            PixelfedClient.setFavouriteBlocking(USER, "5", true);
            fail("expected NotConnectedException");
        } catch (PixelfedClient.NotConnectedException expected) {
            // OAuth again
        }
        assertNull(PixelfedClient.getTokens(USER));
        assertNull(store.get("pf_tokens_" + USER));
    }

    @Test
    public void uploadRetriesAfter401WithNewToken() throws Exception {
        giveTokens("acc1", "ref1", System.currentTimeMillis());
        final File file = File.createTempFile("upload", ".jpg");
        file.deleteOnExit();
        script.add(response(401, ""));
        script.add(response(200, "{\"access_token\":\"acc2\",\"refresh_token\":\"ref2\"}"));
        script.add(response(200, "{\"id\":\"m1\",\"type\":\"image\"}"));
        final long[] progress = new long[1];

        final String id = PixelfedClient.uploadMediaBlocking(USER, file, "image/jpeg", (sent, total) -> progress[0] = sent);

        assertEquals("m1", id);
        assertEquals(3, calls.size());
        assertEquals("/api/v1/media", calls.get(2).path);
        assertEquals("acc2", calls.get(2).bearer);
        assertEquals("file:image/jpeg", calls.get(2).body);
        assertEquals(file, calls.get(2).file);
        assertEquals(10, progress[0]);
    }

    @Test
    public void uploadErrorCarriesShortReason() throws Exception {
        giveTokens("acc1", "ref1", System.currentTimeMillis());
        script.add(response(422, "{\"error\":\"The file must not be greater than 15000 kilobytes.\"}"));
        try {
            PixelfedClient.uploadMediaBlocking(USER, File.createTempFile("big", ".mp4"), "video/mp4", null);
            fail("expected HttpException");
        } catch (PixelfedClient.HttpException e) {
            assertEquals(422, e.code);
            assertTrue(e.reason().startsWith("media_http_422:the_file_must_not_be_greater"));
        }
    }

    @Test
    public void relationshipsAndPostBodies() throws Exception {
        giveTokens("acc1", "ref1", System.currentTimeMillis());
        script.add(response(200, "[{\"id\":\"1\",\"following\":true},{\"id\":\"2\",\"following\":false,\"requested\":true},{\"id\":\"3\"}]"));
        final HashMap<String, Boolean> follows = PixelfedClient.relationshipsBlocking(USER, Arrays.asList("1", "2", "3"));
        assertEquals(Boolean.TRUE, follows.get("1"));
        assertEquals(Boolean.TRUE, follows.get("2"));
        assertEquals(Boolean.FALSE, follows.get("3"));
        assertTrue(calls.get(0).path.contains("id%5B%5D=1&id%5B%5D=2&id%5B%5D=3"));

        script.add(response(200, "{\"id\":\"9\",\"account\":{\"id\":\"5\",\"username\":\"bob\"}}"));
        final PixelfedStatus posted = PixelfedClient.createStatusBlocking(USER, "salom", new ArrayList<>(Arrays.asList("m1", "m2")), null);
        assertNotNull(posted);
        assertEquals("POST", calls.get(1).method);
        assertEquals("status=salom&visibility=public&media_ids%5B%5D=m1&media_ids%5B%5D=m2", calls.get(1).body);

        script.add(response(200, "{}"));
        PixelfedClient.reportStatusBlocking(USER, "9", "spam");
        assertEquals("/api/v1.1/report", calls.get(2).path);
        assertEquals("report_type=spam&object_id=9&object_type=post", calls.get(2).body);

        script.add(response(200, "{}"));
        PixelfedClient.deleteStatusBlocking(USER, "9");
        assertEquals("DELETE", calls.get(3).method);
        assertEquals("/api/v1/statuses/9", calls.get(3).path);
    }

    @Test
    public void profileUpdateAndFavourites() throws Exception {
        giveTokens("acc1", "ref1", System.currentTimeMillis());
        script.add(response(200, "{\"id\":\"5\",\"username\":\"bob\",\"display_name\":\"Bob\",\"note\":\"hi\",\"website\":\"https://bob.uz\"}"));
        final PixelfedAccount updated = PixelfedClient.updateProfileBlocking(USER, " Bob ", "hi", "bob.uz");
        assertEquals("Bob", updated.displayName);
        assertEquals("POST", calls.get(0).method);
        assertEquals("/api/v1/accounts/update_credentials", calls.get(0).path);
        assertEquals("display_name=Bob&note=hi&website=https%3A%2F%2Fbob.uz", calls.get(0).body);

        script.add(response(200, "[]"));
        assertTrue(PixelfedClient.favouritesBlocking(USER).isEmpty());
        assertEquals("/api/v1/favourites?limit=40", calls.get(1).path);
    }
}
