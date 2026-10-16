package uz.nexgramm.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// JVM test of the "video link" detection used before sending a message
public class NexLinksTest {

    @Test
    public void videoLinks() {
        assertTrue(NexLinks.isVideoLink("https://www.instagram.com/reel/DegaNFMoWP2/?igsh=YWN2Zm"));
        assertTrue(NexLinks.isVideoLink("  https://instagram.com/p/C1abc/  "));
        assertTrue(NexLinks.isVideoLink("https://vm.tiktok.com/ZMabc123/"));
        assertTrue(NexLinks.isVideoLink("https://www.tiktok.com/@user/video/7412345678901234567"));
        assertTrue(NexLinks.isVideoLink("https://youtube.com/shorts/abcDEF123"));
        assertTrue(NexLinks.isVideoLink("https://youtu.be/dQw4w9WgXcQ"));
        assertTrue(NexLinks.isVideoLink("https://pin.it/3xyz"));
        assertTrue(NexLinks.isVideoLink("https://x.com/someone/status/1234567890"));
    }

    @Test
    public void notVideoLinks() {
        assertFalse(NexLinks.isVideoLink("salom https://www.instagram.com/reel/Deg/"));
        assertFalse(NexLinks.isVideoLink("https://www.instagram.com/bobirshox/"));
        assertFalse(NexLinks.isVideoLink("https://t.me/NexGrammBot"));
        assertFalse(NexLinks.isVideoLink("https://example.com/video.mp4"));
        assertFalse(NexLinks.isVideoLink(""));
        assertFalse(NexLinks.isVideoLink(null));
    }

    @Test
    public void musicButtons() {
        assertTrue(NexLinks.isMusicButton("📥 Скачать песню"));
        assertTrue(NexLinks.isMusicButton("♪ Musiqa"));
        assertTrue(NexLinks.isMusicButton("Download audio"));
        assertFalse(NexLinks.isMusicButton("Добавить в группу ⤴"));
    }
}
