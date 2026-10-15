package uz.nexgramm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// NexGramm: an APK check on the phone, from what the APK itself declares (its manifest), before it can be
// installed. Pure Java (unit tested); the Android side (NexApkGuard) reads the manifest and acts.
// It looks for what the SMS / bank stealers spread in chats do: read SMS (one-time codes), take over the
// screen (accessibility, device admin, overlays), pass for a known app, or hide behind a photo / video name.
// Only a strong sign together with a hiding sign blocks the file; ordinary apps, own builds and modded games
// stay allowed. It is a heuristic, not an antivirus.
public final class NexApkScan {

    public static final int SAFE = 0;
    public static final int CAUTION = 1;
    public static final int DANGER = 2;

    // reason codes, in the order they are shown
    public static final String SMS_READ = "sms_read";
    public static final String SMS_SEND = "sms_send";
    public static final String ACCESSIBILITY = "accessibility";
    public static final String DEVICE_ADMIN = "device_admin";
    public static final String NOTIFICATIONS = "notifications";
    public static final String OVERLAY = "overlay";
    public static final String INSTALLS_APPS = "installs_apps";
    public static final String IMPERSONATION = "impersonation";
    public static final String LURE_NAME = "lure_name";
    public static final String CONTACTS = "contacts";
    public static final String CALLS = "calls";
    public static final String UNREADABLE = "unreadable";

    private static final int DANGER_SCORE = 8;
    private static final int CAUTION_SCORE = 3;

    // a picture / video / document name ending in .apk: "rasm.jpg.apk", "Foto.apk", "Tabriknoma.apk"
    private static final Pattern DOUBLE_EXTENSION = Pattern.compile(".*\\.(jpe?g|png|gif|webp|heic|mp4|mov|avi|3gp|mp3|pdf|docx?|xlsx?|txt)\\s*\\.apk$");
    private static final Pattern LURE_WORDS = Pattern.compile(".*(foto|photo|rasm|surat|video|img|image|screenshot|skrin|tabrik|otkritka|"
        + "фото|видео|скрин|открытка|поздрав|картин).*");

    // brand word in the app's name → the package prefixes the real app uses
    private static final String[][] BRANDS = {
        {"telegram", "org.telegram.", "org.thunderdog.challegram", "uz.nexgramm."},
        {"nexgramm", "uz.nexgramm."},
        {"whatsapp", "com.whatsapp"},
        {"instagram", "com.instagram."},
        {"payme", "uz.dida.payme"},
        {"click", "air.com.ssdsoftwaresolutions.clickuz", "uz.click."},
        {"google", "com.google.", "com.android."},
    };

    // What the Android side read from the APK; nothing here comes from the network
    public static final class Info {
        public boolean readable;
        public String packageName = "";
        public String label = "";
        public String fileName = ""; // the name shown in the chat
        public List<String> permissions = new ArrayList<>();
        public boolean accessibilityService;
        public boolean notificationListener;
        public boolean deviceAdmin;
        public String versionName;
    }

    public static final class Result {
        public final int verdict;
        public final int score;
        public final List<String> reasons;

        Result(int verdict, int score, List<String> reasons) {
            this.verdict = verdict;
            this.score = score;
            this.reasons = reasons;
        }
    }

    private NexApkScan() {
    }

    public static Result check(Info info) {
        final ArrayList<String> reasons = new ArrayList<>();
        int score = 0;
        final List<String> p = info.permissions;
        final boolean smsRead = has(p, "android.permission.READ_SMS") || has(p, "android.permission.RECEIVE_SMS");
        if (smsRead) {
            score += 4;
            reasons.add(SMS_READ);
        }
        if (has(p, "android.permission.SEND_SMS")) {
            score += 3;
            reasons.add(SMS_SEND);
        }
        if (info.accessibilityService) {
            score += 4;
            reasons.add(ACCESSIBILITY);
        }
        if (info.deviceAdmin) {
            score += 4;
            reasons.add(DEVICE_ADMIN);
        }
        if (info.notificationListener) {
            score += 2;
            reasons.add(NOTIFICATIONS);
        }
        final boolean overlay = has(p, "android.permission.SYSTEM_ALERT_WINDOW");
        if (overlay) {
            score += 2;
            reasons.add(OVERLAY);
        }
        if (has(p, "android.permission.REQUEST_INSTALL_PACKAGES")) {
            score += 2;
            reasons.add(INSTALLS_APPS);
        }
        final boolean impersonation = impersonates(info.label, info.packageName);
        if (impersonation) {
            score += 3;
            reasons.add(IMPERSONATION);
        }
        final int lure = lureScore(info.fileName);
        if (lure > 0) {
            score += lure;
            reasons.add(LURE_NAME);
        }
        if (has(p, "android.permission.READ_CONTACTS")) {
            score += 1;
            reasons.add(CONTACTS);
        }
        if (has(p, "android.permission.READ_CALL_LOG") || has(p, "android.permission.CALL_PHONE") || has(p, "android.permission.PROCESS_OUTGOING_CALLS")) {
            score += 1;
            reasons.add(CALLS);
        }
        if (!info.readable) {
            score += 2;
            reasons.add(UNREADABLE);
        }
        // blocked only for a strong sign (codes, screen control) together with a hiding one
        final boolean strong = smsRead || info.accessibilityService || info.deviceAdmin;
        final boolean hiding = lure > 0 || impersonation || info.deviceAdmin || info.accessibilityService && (smsRead || overlay);
        final int verdict = score >= DANGER_SCORE && strong && hiding ? DANGER : score >= CAUTION_SCORE ? CAUTION : SAFE;
        return new Result(verdict, score, reasons);
    }

    // 4 for a double extension, 3 for a photo / video word in an .apk name, else 0
    static int lureScore(String fileName) {
        if (fileName == null) {
            return 0;
        }
        final String name = fileName.trim().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".apk")) {
            return 0;
        }
        if (DOUBLE_EXTENSION.matcher(name).matches()) {
            return 4;
        }
        return LURE_WORDS.matcher(name.substring(0, name.length() - 4)).matches() ? 3 : 0;
    }

    // the name says a known app, the package is not that app's
    static boolean impersonates(String label, String packageName) {
        if (label == null || packageName == null) {
            return false;
        }
        final String l = label.toLowerCase(Locale.ROOT);
        for (String[] brand : BRANDS) {
            if (!l.contains(brand[0])) {
                continue;
            }
            for (int i = 1; i < brand.length; i++) {
                if (packageName.startsWith(brand[i])) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean has(List<String> permissions, String name) {
        return permissions != null && permissions.contains(name);
    }
}
