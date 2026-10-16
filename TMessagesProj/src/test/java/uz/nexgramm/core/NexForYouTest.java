package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NexForYouTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 3_600_000L;

    private static NexForYou.Candidate post(String id, String author, String tags, boolean video, int likes, long ageHours) {
        return new NexForYou.Candidate(id, author, NexForYou.hashtags(tags), video, likes, 0, NOW - ageHours * HOUR, false, false);
    }

    @Test
    public void likedAuthorsAndTagsRankHigher() {
        final NexForYou model = new NexForYou();
        model.record("cats", NexForYou.hashtags("#cat"), false, NexForYou.LIKE, NOW);
        model.record("cats", NexForYou.hashtags("#cat"), false, NexForYou.SAVE, NOW);
        final NexForYou.Candidate liked = post("1", "cats", "<p>#Cat</p>", false, 0, 5);
        final NexForYou.Candidate other = post("2", "cars", "#car", false, 0, 5);
        assertTrue(model.score(liked, NOW, 1) > model.score(other, NOW, 1));
    }

    @Test
    public void popularAndFreshBeatOldAndQuiet() {
        final NexForYou model = new NexForYou();
        assertTrue(model.score(post("1", "a", "", false, 50, 2), NOW, 1) > model.score(post("2", "b", "", false, 0, 2), NOW, 1));
        assertTrue(model.score(post("1", "a", "", false, 5, 1), NOW, 1) > model.score(post("2", "b", "", false, 5, 24 * 30), NOW, 1));
    }

    @Test
    public void seenPostsGoDown() {
        final NexForYou model = new NexForYou();
        final NexForYou.Candidate c = post("1", "a", "", false, 3, 3);
        final float before = model.score(c, NOW, 1);
        model.markSeen("1", NOW);
        assertTrue(model.score(c, NOW, 1) < before - 2f);
    }

    @Test
    public void videoTasteLearned() {
        final NexForYou model = new NexForYou();
        for (int i = 0; i < 5; i++) {
            model.record("x", null, true, NexForYou.VIDEO_OPEN, NOW);
        }
        assertTrue(model.score(post("v", "n", "", true, 0, 1), NOW, 1) > model.score(post("p", "m", "", false, 0, 1), NOW, 1));
    }

    @Test
    public void sameAuthorKeptApart() {
        final NexForYou model = new NexForYou();
        model.record("star", null, false, NexForYou.FOLLOW, NOW);
        final List<NexForYou.Candidate> ranked = model.rank(Arrays.asList(
            post("1", "star", "", false, 9, 1), post("2", "star", "", false, 9, 1), post("3", "star", "", false, 9, 1),
            post("4", "b", "", false, 0, 1), post("5", "c", "", false, 0, 1)), NOW, 7);
        assertEquals("star", ranked.get(0).authorId);
        assertNotEquals("star", ranked.get(1).authorId);
        assertEquals(5, ranked.size());
    }

    @Test
    public void scoresFadeAndSurviveJson() {
        final NexForYou model = new NexForYou();
        model.record("a", NexForYou.hashtags("#sun"), true, NexForYou.LIKE, NOW);
        model.markSeen("9", NOW);
        final NexForYou copy = NexForYou.fromJson(model.toJson());
        assertEquals(model.authors.get("a"), copy.authors.get("a"), 0.001f);
        assertEquals(model.tags.get("sun"), copy.tags.get("sun"), 0.001f);
        assertTrue(copy.wasSeen("9"));
        copy.decayTo(NOW + 30L * 24 * HOUR);
        assertTrue(copy.authors.get("a") < model.authors.get("a") * 0.5f);
        assertEquals(0, NexForYou.fromJson("not json").authors.size());
    }

    @Test
    public void hashtagsFromHtml() {
        final ArrayList<String> tags = NexForYou.hashtags("<p>Hi <a href=\"x\">#<span>Toshkent</span></a> #food #food #a</p>");
        assertEquals(Arrays.asList("toshkent", "food"), tags);
    }

    @Test
    public void notInterestedSinksAuthorAndTopic() {
        final NexForYou model = new NexForYou();
        model.record("spam", NexForYou.hashtags("#bet"), false, NexForYou.NOT_INTERESTED, NOW);
        final float before = new NexForYou().score(post("1", "spam", "#bet", false, 10, 2), NOW, 1);
        assertTrue(model.score(post("1", "spam", "#bet", false, 10, 2), NOW, 1) < before - 2f);
        assertTrue(model.score(post("2", "other", "#bet", false, 10, 2), NOW, 1) < before);
    }

    @Test
    public void newUsersLeanOnPopularity() {
        final NexForYou fresh = new NexForYou();
        final NexForYou known = new NexForYou();
        for (int i = 0; i < 10; i++) {
            known.record("a" + i, NexForYou.hashtags("#t" + i), false, NexForYou.LIKE, NOW);
        }
        assertEquals(0f, fresh.knowledge(), 0.001f);
        assertEquals(1f, known.knowledge(), 0.001f);
        final NexForYou.Candidate hit = post("1", "x", "", false, 200, 2);
        final NexForYou.Candidate quiet = post("2", "y", "", false, 0, 2);
        final float freshGap = fresh.score(hit, NOW, 1) - fresh.score(quiet, NOW, 1);
        final float knownGap = known.score(hit, NOW, 1) - known.score(quiet, NOW, 1);
        assertTrue(freshGap > knownGap);
    }

    @Test
    public void oneTopicDoesNotFillTheScreen() {
        final NexForYou model = new NexForYou();
        for (int i = 0; i < 5; i++) {
            model.record("x", NexForYou.hashtags("#cars"), false, NexForYou.LIKE, NOW);
        }
        final ArrayList<NexForYou.Candidate> list = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            list.add(post("c" + i, "a" + i, "#cars", false, 5, 2));
        }
        list.add(post("f", "z", "#food", false, 0, 2));
        final java.util.List<NexForYou.Candidate> ranked = model.rank(list, NOW, 1);
        assertEquals("cars", ranked.get(0).tags.get(0));
        assertEquals("cars", ranked.get(1).tags.get(0));
        assertEquals("f", ranked.get(2).id); // a third #cars in a row waits for the food post
    }
}
