package uz.nexgramm.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

// JVM test of the Lenta formatting rules (time ago, counters, initials)
public class PixelfedFormatTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void timeAgo() {
        assertArrayEquals(new int[]{PixelfedFormat.AGO_NOW, 0}, PixelfedFormat.ago(NOW, NOW - 30_000));
        assertArrayEquals(new int[]{PixelfedFormat.AGO_MINUTES, 5}, PixelfedFormat.ago(NOW, NOW - 5 * 60_000));
        assertArrayEquals(new int[]{PixelfedFormat.AGO_HOURS, 2}, PixelfedFormat.ago(NOW, NOW - 2 * 3_600_000 - 59_000));
        assertArrayEquals(new int[]{PixelfedFormat.AGO_DAYS, 3}, PixelfedFormat.ago(NOW, NOW - 3 * 86_400_000L));
        assertArrayEquals(new int[]{PixelfedFormat.AGO_WEEKS, 2}, PixelfedFormat.ago(NOW, NOW - 15 * 86_400_000L));
        assertEquals(PixelfedFormat.AGO_DATE, PixelfedFormat.ago(NOW, NOW - 40 * 86_400_000L)[0]);
        assertEquals(PixelfedFormat.AGO_DATE, PixelfedFormat.ago(NOW, 0)[0]);
        assertEquals(PixelfedFormat.AGO_NOW, PixelfedFormat.ago(NOW, NOW + 10_000)[0]); // clock skew
    }

    @Test
    public void counts() {
        assertEquals("0", PixelfedFormat.count(-3));
        assertEquals("42", PixelfedFormat.count(42));
        assertEquals("1 248", PixelfedFormat.count(1248));
        assertEquals("1\u2009999", PixelfedFormat.count(1999));
        assertEquals("2,1K", PixelfedFormat.count(2_100));
        assertEquals("4,8K", PixelfedFormat.count(4_800));
        assertEquals("5,4K", PixelfedFormat.count(5_400));
        assertEquals("12,4K", PixelfedFormat.count(12_480));
        assertEquals("120K", PixelfedFormat.count(120_400));
        assertEquals("5,4M", PixelfedFormat.count(5_400_000));
        assertEquals("2M", PixelfedFormat.count(2_000_000));
    }

    @Test
    public void initials() {
        assertEquals("AK", PixelfedFormat.initials("Aziza Karimova"));
        assertEquals("B", PixelfedFormat.initials("Bobirshox"));
        assertEquals("A", PixelfedFormat.initials("aziza.photo"));
        assertEquals("ДА", PixelfedFormat.initials("дилшод  ахмедов"));
        assertEquals("", PixelfedFormat.initials("  "));
        assertEquals("", PixelfedFormat.initials(null));
    }
}
