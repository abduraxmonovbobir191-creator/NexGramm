package uz.nexgramm.core;

// NexGramm: how many files upload at the same time. Telegram uploads one big file at a time, so a video
// sent to four chats goes four times in a row; here several go side by side, as many as the network takes:
// by network type, then by the measured speed of the last uploads (moving average). Pure Java (tested).
public final class NexParallel {

    public static final int NETWORK_SLOW = 0;   // 2G / EDGE
    public static final int NETWORK_MOBILE = 1;
    public static final int NETWORK_WIFI = 2;   // Wi-Fi / Ethernet, unmetered

    private static final double SLOW_KBPS = 48;
    private static final double FAST_MOBILE_KBPS = 600;
    private static final double FAST_WIFI_KBPS = 2000;

    private static double speedKbps = -1; // -1 = nothing measured yet

    private NexParallel() {
    }

    // How many uploads may run at once (small = photos, voice, documents under 10 MB)
    public static int maxUploads(int network, boolean small) {
        return limit(network, measuredKbps(), small);
    }

    static int limit(int network, double kbps, boolean small) {
        if (network == NETWORK_SLOW || kbps >= 0 && kbps < SLOW_KBPS) {
            return small ? 2 : 1;
        }
        if (network == NETWORK_WIFI) {
            if (kbps >= FAST_WIFI_KBPS) {
                return small ? 8 : 6;
            }
            return small ? 6 : 4;
        }
        if (kbps >= FAST_MOBILE_KBPS) {
            return small ? 4 : 3;
        }
        return small ? 3 : 2;
    }

    // An upload finished: bytes in durationMs (average of the recent ones, the newest weighs 30 %)
    public static synchronized void onUploaded(long bytes, long durationMs) {
        if (bytes < 64 * 1024 || durationMs <= 0) {
            return; // too small to tell anything about the network
        }
        final double kbps = bytes / 1024.0 / (durationMs / 1000.0);
        speedKbps = speedKbps < 0 ? kbps : speedKbps * 0.7 + kbps * 0.3;
    }

    public static synchronized double measuredKbps() {
        return speedKbps;
    }

    static synchronized void reset() {
        speedKbps = -1;
    }
}
