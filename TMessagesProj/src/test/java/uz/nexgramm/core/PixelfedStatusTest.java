package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

// JVM test of the Mastodon timeline parsing used by the native Lenta
public class PixelfedStatusTest {

    private static final String TIMELINE = "["
        + "{\"id\":\"30\",\"content\":\"<p>Salom</p>\",\"favourites_count\":3,\"replies_count\":1,\"favourited\":true,"
        + "\"account\":{\"id\":\"11\",\"username\":\"bob\",\"acct\":\"bob\",\"display_name\":\"\",\"avatar\":\"https://feed.test/a.jpg\"},"
        + "\"media_attachments\":["
        + "{\"type\":\"image\",\"url\":\"https://cdn.test/1.jpg\",\"preview_url\":\"https://cdn.test/1s.jpg\",\"meta\":{\"original\":{\"width\":1080,\"height\":1350}}},"
        + "{\"type\":\"video\",\"url\":\"https://cdn.test/2.mp4\",\"preview_url\":\"https://cdn.test/2.jpg\",\"meta\":null},"
        + "{\"type\":\"audio\",\"url\":\"https://cdn.test/3.mp3\"},"
        + "{\"type\":\"image\",\"url\":\"http://insecure.test/4.jpg\"}]},"
        + "{\"id\":\"29\",\"content\":\"\",\"reblog\":{\"id\":\"7\",\"content\":\"<p>Asl</p>\",\"favourites_count\":5,"
        + "\"account\":{\"username\":\"ann\",\"acct\":\"ann\",\"display_name\":\"Ann\",\"avatar\":\"http://x/a.png\"},\"media_attachments\":[]}},"
        + "{\"content\":\"no id\"}]";

    @Test
    public void readsRepostInfo() throws Exception {
        final ArrayList<PixelfedStatus> list = PixelfedStatus.listFromJson("["
            + "{\"id\":\"40\",\"reblogs_count\":9,\"reblogged\":true,\"account\":{\"acct\":\"bob\"},\"media_attachments\":[]},"
            + "{\"id\":\"41\",\"account\":{\"acct\":\"cat\",\"display_name\":\"Cat\"},"
            + "\"reblog\":{\"id\":\"8\",\"reblogs_count\":2,\"account\":{\"acct\":\"ann\"},\"media_attachments\":[]}}]");
        assertEquals(9, list.get(0).reblogsCount);
        assertTrue(list.get(0).reblogged);
        assertNull(list.get(0).rebloggedBy);
        assertEquals("Cat", list.get(1).rebloggedBy);
        assertEquals(2, list.get(1).reblogsCount);
        assertFalse(list.get(1).reblogged);
    }

    @Test
    public void parsesTimeline() throws Exception {
        final ArrayList<PixelfedStatus> list = PixelfedStatus.listFromJson(TIMELINE);
        assertEquals(2, list.size());

        final PixelfedStatus first = list.get(0);
        assertEquals("30", first.id);
        assertEquals("30", first.statusId);
        assertEquals("bob", first.authorName); // empty display name → username
        assertEquals("11", first.authorId);
        assertEquals("https://feed.test/a.jpg", first.avatarUrl);
        assertEquals(3, first.favouritesCount);
        assertEquals(1, first.repliesCount);
        assertTrue(first.favourited);
        assertEquals(2, first.media.size()); // audio and http media dropped
        assertFalse(first.media.get(0).video);
        assertEquals(1080, first.media.get(0).width);
        assertEquals(1350, first.media.get(0).height);
        assertTrue(first.media.get(1).video);
        assertEquals("https://cdn.test/2.mp4", first.media.get(1).url);
        assertEquals("https://cdn.test/2.jpg", first.media.get(1).previewUrl);

        // A boost shows the original post; paging keeps the timeline id, likes go to the original
        final PixelfedStatus boost = list.get(1);
        assertEquals("29", boost.id);
        assertEquals("7", boost.statusId);
        assertEquals("Ann", boost.authorName);
        assertEquals("<p>Asl</p>", boost.contentHtml);
        assertNull(boost.avatarUrl); // not https
        assertFalse(boost.favourited);
    }

    @Test
    public void tokensRoundTripAndHideValues() throws Exception {
        final PixelfedTokens tokens = PixelfedTokens.fromResponse(
            "{\"access_token\":\"acc\",\"refresh_token\":\"ref\",\"expires_in\":2592000}", 1000L, null, "bob");
        assertEquals(1000L + 2592000L * 1000L, tokens.expiresAt);
        final PixelfedTokens copy = PixelfedTokens.fromJson(tokens.toJson());
        assertEquals("acc", copy.accessToken);
        assertEquals("ref", copy.refreshToken);
        assertEquals("bob", copy.username);
        assertEquals("PixelfedTokens{hidden}", copy.toString());

        // No refresh token / expires_in in a refresh response: keep the old one, 30-day expiry
        final PixelfedTokens refreshed = PixelfedTokens.fromResponse("{\"access_token\":\"acc2\"}", 5L, "ref", "bob");
        assertEquals("ref", refreshed.refreshToken);
        assertEquals(5L + PixelfedClient.MAX_TOKEN_AGE_MS, refreshed.expiresAt);
    }

    @Test
    public void parsesAccountAndSingleStatus() throws Exception {
        final PixelfedAccount account = PixelfedAccount.fromJson("{\"id\":\"5\",\"username\":\"ann\",\"acct\":\"ann\",\"display_name\":\" \","
            + "\"avatar\":\"http://x/a.png\",\"note\":\"<p>Salom</p>\",\"statuses_count\":3,\"followers_count\":7,\"following_count\":2}");
        assertEquals("5", account.id);
        assertEquals("ann", account.displayName);
        assertNull(account.avatarUrl);
        assertEquals(3, account.statusesCount);
        assertEquals(7, account.followersCount);
        assertEquals(2, account.followingCount);

        final PixelfedStatus status = PixelfedStatus.oneFromJson("{\"id\":\"9\",\"content\":\"ok\",\"account\":{\"id\":\"5\",\"username\":\"ann\"}}");
        assertEquals("9", status.statusId);
        assertEquals("5", status.authorId);
    }

    @Test
    public void parsesTimeLinkSavedAndPlace() throws Exception {
        final PixelfedStatus s = PixelfedStatus.oneFromJson("{\"id\":\"77\",\"created_at\":\"2026-10-12T10:00:00.000000Z\","
            + "\"url\":\"https://feed.test/p/ann/77\",\"bookmarked\":true,\"place\":{\"name\":\"Samarqand\",\"country\":\"UZ\"},"
            + "\"account\":{\"id\":\"5\",\"username\":\"ann\"},\"media_attachments\":[{\"type\":\"video\",\"url\":\"https://cdn.test/v.mp4\"}]}");
        assertEquals(1791799200000L, s.createdAt);
        assertEquals("https://feed.test/p/ann/77", s.url);
        assertEquals("https://feed.test/p/ann/77", s.shareUrl());
        assertTrue(s.bookmarked);
        assertEquals("Samarqand", s.placeName);
        assertTrue(s.hasVideo());

        final PixelfedStatus noLink = PixelfedStatus.oneFromJson("{\"id\":\"8\",\"url\":\"http://x\",\"account\":{\"id\":\"5\",\"username\":\"ann\"}}");
        assertNull(noLink.url);
        assertEquals(PixelfedClient.BASE + "/p/ann/8", noLink.shareUrl());
        assertNull(noLink.placeName);
        assertFalse(noLink.hasVideo());
    }

    @Test
    public void parsesIsoTimes() {
        assertEquals(1791799200000L, PixelfedStatus.parseTime("2026-10-12T10:00:00Z"));
        assertEquals(1791799200123L, PixelfedStatus.parseTime("2026-10-12T10:00:00.123456Z"));
        assertEquals(1791799200000L, PixelfedStatus.parseTime("2026-10-12T15:00:00+05:00"));
        assertEquals(1791799200000L, PixelfedStatus.parseTime("2026-10-12T05:00:00-0500"));
        assertEquals(0L, PixelfedStatus.parseTime("yesterday"));
        assertEquals(0L, PixelfedStatus.parseTime(null));
    }
}
