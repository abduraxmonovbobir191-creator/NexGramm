package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

// JVM test of the on-phone APK check
public class NexApkScanTest {

    private static NexApkScan.Info info(String pkg, String label, String file, String... permissions) {
        final NexApkScan.Info info = new NexApkScan.Info();
        info.readable = true;
        info.packageName = pkg;
        info.label = label;
        info.fileName = file;
        info.permissions = Arrays.asList(permissions);
        return info;
    }

    @Test
    public void smsStealerBehindAPhotoNameIsBlocked() {
        final NexApkScan.Result r = NexApkScan.check(info("com.xyz.app", "Foto", "Rasm.jpg.apk",
            "android.permission.RECEIVE_SMS", "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.INTERNET"));
        assertEquals(NexApkScan.DANGER, r.verdict);
        assertTrue(r.reasons.contains(NexApkScan.SMS_READ));
        assertTrue(r.reasons.contains(NexApkScan.LURE_NAME));
    }

    @Test
    public void fakeTelegramWithScreenControlIsBlocked() {
        final NexApkScan.Info i = info("com.update.service", "Telegram Update", "telegram.apk", "android.permission.SYSTEM_ALERT_WINDOW");
        i.accessibilityService = true;
        assertEquals(NexApkScan.DANGER, NexApkScan.check(i).verdict);
    }

    @Test
    public void ordinaryAppsAndModsAreAllowed() {
        // a modded game: internet, storage, overlay for a mod menu
        assertTrue(NexApkScan.check(info("com.game.mod", "Subway Surf Mod", "subway_mod.apk",
            "android.permission.INTERNET", "android.permission.SYSTEM_ALERT_WINDOW")).verdict != NexApkScan.DANGER);
        // the owner's own build
        assertEquals(NexApkScan.SAFE, NexApkScan.check(info("uz.nexgramm.app", "NexGramm", "nexgramm-debug-arm64-v8a.apk",
            "android.permission.INTERNET", "android.permission.READ_CONTACTS")).verdict);
        // a real SMS app (no hiding sign): a warning, not a block
        assertEquals(NexApkScan.CAUTION, NexApkScan.check(info("com.sms.app", "Messages", "messages.apk",
            "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.READ_CONTACTS")).verdict);
    }

    @Test
    public void namesAndBrands() {
        assertEquals(4, NexApkScan.lureScore("video.mp4.apk"));
        assertEquals(3, NexApkScan.lureScore("Tabriknoma.apk"));
        assertEquals(3, NexApkScan.lureScore("Фото_2024.APK"));
        assertEquals(0, NexApkScan.lureScore("game.apk"));
        assertEquals(0, NexApkScan.lureScore("photo.zip"));
        assertTrue(NexApkScan.impersonates("Payme", "com.free.money"));
        assertFalse(NexApkScan.impersonates("Payme", "uz.dida.payme"));
        assertFalse(NexApkScan.impersonates("Calculator", "com.calc"));
    }

    @Test
    public void unreadableFileIsAWarning() {
        final NexApkScan.Info i = new NexApkScan.Info();
        i.fileName = "app.apk";
        final NexApkScan.Result r = NexApkScan.check(i);
        assertEquals(NexApkScan.SAFE, r.verdict); // 2 points: shown, not blocked
        assertTrue(r.reasons.contains(NexApkScan.UNREADABLE));
    }
}
