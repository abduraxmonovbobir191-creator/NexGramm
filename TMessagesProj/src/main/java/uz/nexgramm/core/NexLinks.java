package uz.nexgramm.core;

import java.util.Locale;
import java.util.regex.Pattern;

// NexGramm: which messages are "just a video link" that NexGramm sends as the video itself (through the SaveX
// bot): one Instagram / TikTok / YouTube Shorts / Pinterest / X / Facebook / Likee link and nothing else.
// Pure Java (unit tested).
public final class NexLinks {

    private static final Pattern SUPPORTED = Pattern.compile(
        "^https?://(www\\.|m\\.|vm\\.|vt\\.)?("
            + "instagram\\.com/(reel|reels|p|tv|stories)/\\S+"
            + "|tiktok\\.com/\\S+"
            + "|youtube\\.com/(shorts|watch)\\S*"
            + "|youtu\\.be/\\S+"
            + "|pinterest\\.[a-z.]+/pin/\\S+|pin\\.it/\\S+"
            + "|(x|twitter)\\.com/[^/\\s]+/status/\\S+"
            + "|(facebook\\.com/(reel|watch|share)\\S*|fb\\.watch/\\S+)"
            + "|likee\\.video/\\S+|l\\.likee\\.video/\\S+"
            + ")$");

    private NexLinks() {
    }

    public static boolean isVideoLink(CharSequence text) {
        if (text == null) {
            return false;
        }
        final String s = text.toString().trim();
        if (s.isEmpty() || s.length() > 600 || s.indexOf(' ') >= 0 || s.indexOf('\n') >= 0) {
            return false;
        }
        return SUPPORTED.matcher(s.toLowerCase(Locale.ROOT)).matches();
    }

    // a bot button that gives the music of the video: "Скачать песню", "♪ Musiqa", "Download audio"…
    public static boolean isMusicButton(String text) {
        if (text == null) {
            return false;
        }
        final String t = text.toLowerCase(Locale.ROOT);
        return t.contains("песн") || t.contains("музык") || t.contains("music") || t.contains("musiqa") || t.contains("qo'shiq")
            || t.contains("audio") || t.contains("аудио") || t.contains("mp3") || t.contains("♪") || t.contains("🎵") || t.contains("🎶");
    }
}
