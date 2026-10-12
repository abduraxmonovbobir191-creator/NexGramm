package uz.nexgramm.core;

// NexGramm: plain formatting rules of the Lenta (pure Java, unit tested)
public final class PixelfedFormat {

    public static final int AGO_NOW = 0;
    public static final int AGO_MINUTES = 1;
    public static final int AGO_HOURS = 2;
    public static final int AGO_DAYS = 3;
    public static final int AGO_WEEKS = 4;
    public static final int AGO_DATE = 5;

    private PixelfedFormat() {
    }

    // {unit, value}: under a minute = now, then minutes, hours, days (< 7), weeks (< 5), else a date
    public static int[] ago(long now, long then) {
        final long seconds = Math.max(0, (now - then) / 1000);
        if (then <= 0) {
            return new int[]{AGO_DATE, 0};
        }
        if (seconds < 60) {
            return new int[]{AGO_NOW, 0};
        }
        final long minutes = seconds / 60;
        if (minutes < 60) {
            return new int[]{AGO_MINUTES, (int) minutes};
        }
        final long hours = minutes / 60;
        if (hours < 24) {
            return new int[]{AGO_HOURS, (int) hours};
        }
        final long days = hours / 24;
        if (days < 7) {
            return new int[]{AGO_DAYS, (int) days};
        }
        if (days < 35) {
            return new int[]{AGO_WEEKS, (int) (days / 7)};
        }
        return new int[]{AGO_DATE, 0};
    }

    // As in the design: 950 → "950", 1248 → "1 248", 2100 → "2,1K", 5400 → "5,4K", 5 400 000 → "5,4M"
    // (thin space, comma decimal; full digits below 2 000)
    public static String count(long value) {
        if (value < 0) {
            value = 0;
        }
        if (value < 2_000) {
            final String digits = Long.toString(value);
            if (digits.length() <= 3) {
                return digits;
            }
            return digits.substring(0, digits.length() - 3) + " " + digits.substring(digits.length() - 3);
        }
        if (value < 1_000_000) {
            return shortNumber(value, 1_000) + "K";
        }
        return shortNumber(value, 1_000_000) + "M";
    }

    private static String shortNumber(long value, long unit) {
        final long whole = value / unit;
        final long tenth = (value % unit) * 10 / unit;
        return whole >= 100 || tenth == 0 ? Long.toString(whole) : whole + "," + tenth;
    }

    // "Aziza Karimova" → "AK", "Bobirshox" → "B" (first letters of the first and the last word)
    public static String initials(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "";
        }
        final String[] words = name.trim().split("\\s+");
        final StringBuilder sb = new StringBuilder();
        appendFirstLetter(sb, words[0]);
        if (words.length > 1) {
            appendFirstLetter(sb, words[words.length - 1]);
        }
        return sb.toString();
    }

    private static void appendFirstLetter(StringBuilder sb, String word) {
        for (int i = 0; i < word.length(); ) {
            final int cp = word.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(Character.toUpperCase(cp));
                return;
            }
            i += Character.charCount(cp);
        }
    }
}
