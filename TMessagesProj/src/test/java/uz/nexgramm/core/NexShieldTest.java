package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NexShieldTest {

    @Test
    public void plainHttpsSiteIsFine() {
        assertEquals(NexShield.LINK_OK, NexShield.linkRisk("https://example.com/page?x=1"));
        assertEquals(NexShield.LINK_OK, NexShield.linkRisk("tg://resolve?domain=durov"));
        assertEquals(NexShield.LINK_OK, NexShield.linkRisk("mailto:a@b.c"));
        assertEquals(NexShield.LINK_OK, NexShield.linkRisk(null));
    }

    @Test
    public void riskyLinksAreNamed() {
        assertEquals(NexShield.LINK_HTTP, NexShield.linkRisk("http://example.com"));
        assertEquals(NexShield.LINK_IP, NexShield.linkRisk("https://192.168.1.10/login"));
        assertEquals(NexShield.LINK_IP, NexShield.linkRisk("http://[2001:db8::1]/"));
        assertEquals(NexShield.LINK_APK, NexShield.linkRisk("https://files.example.com/Bank.APK"));
        assertEquals(NexShield.LINK_LOOKALIKE, NexShield.linkRisk("https://xn--pypal-4ve.com/"));
        assertEquals(NexShield.LINK_LOOKALIKE, NexShield.linkRisk("https://login.xn--80ak6aa92e.com/"));
    }

    @Test
    public void ipNeedsFourNumbers() {
        assertEquals(false, NexShield.isIp("1.2.3"));
        assertEquals(false, NexShield.isIp("a.b.c.d"));
        assertEquals(true, NexShield.isIp("10.0.0.1"));
    }
}
