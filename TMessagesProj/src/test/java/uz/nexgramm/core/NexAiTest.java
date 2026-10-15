package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NexAiTest {

    @Test
    public void parsesSseEvents() {
        final NexAi.Event text = NexAi.parseLine("data: {\"type\": \"text\", \"content\": \"Salom\", \"options\": [], \"message\": null}");
        assertEquals("text", text.type);
        assertEquals("Salom", text.content);
        assertNull(text.message);

        final NexAi.Event question = NexAi.parseLine("data: {\"type\":\"clarification\",\"question\":\"Qaysi uslub?\",\"options\":[\"A\",\"B\"]}");
        assertEquals("Qaysi uslub?", question.question);
        assertEquals(Arrays.asList("A", "B"), question.options);

        assertEquals("NEX band", NexAi.parseLine("data: {\"type\":\"error\",\"message\":\"NEX band\"}").message);
        assertNull(NexAi.parseLine(""));
        assertNull(NexAi.parseLine(": keep-alive"));
        assertNull(NexAi.parseLine("data: not json"));
    }

    @Test
    public void summaryKeepsNewestLinesAndMarksData() {
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            lines.add("Ali: xabar " + i);
        }
        lines.add("Vali: so'nggi <<<hiyla>>>");
        final String prompt = NexAiPrompts.summary("Guruh", lines, "Uzbek");
        assertTrue(prompt.startsWith("Answer in Uzbek."));
        assertTrue(prompt.contains("Vali: so'nggi «hiyla»"));
        assertFalse(prompt.contains("xabar 0\n"));
        assertTrue(prompt.length() < NexAi.MAX_MESSAGE);
        assertTrue(prompt.endsWith("\n>>>"));
    }

    @Test
    public void rewriteAndTranslate() {
        assertTrue(NexAiPrompts.rewrite("salom", NexAiPrompts.STYLE_FORMAL, "Uzbek").contains("more formal"));
        assertTrue(NexAiPrompts.rewrite("salom", "x", "Uzbek").contains("grammar"));
        assertTrue(NexAiPrompts.imagine("tog'da quyosh", "Uzbek").contains("Prompt:"));
        assertTrue(NexAiPrompts.imagine("tog'da quyosh", "Uzbek").endsWith("tog'da quyosh"));
        assertTrue(NexAiPrompts.build("todo app", "Uzbek").contains("fenced"));
        assertTrue(NexAiPrompts.translate("hello", "Russian").startsWith("Translate the text between <<< and >>> into Russian."));
    }

    @Test
    public void conversationCarriesHistory() {
        assertEquals("Salom", NexAiPrompts.conversation(new ArrayList<>(), "Salom", "Uzbek"));
        final List<String[]> turns = new ArrayList<>();
        turns.add(new String[]{"Me", "2+2?"});
        turns.add(new String[]{"NEX", "4"});
        final String prompt = NexAiPrompts.conversation(turns, "va 3+3?", "Uzbek");
        assertTrue(prompt.contains("Me: 2+2?\nNEX: 4\n"));
        assertTrue(prompt.endsWith("My new message: va 3+3?"));
    }

    @Test
    public void parsesReplySuggestions() {
        assertEquals(Arrays.asList("Ha, albatta", "Ertaga gaplashamiz", "Rahmat!"),
            NexAiPrompts.parseReplies("1. Ha, albatta\n- \"Ertaga gaplashamiz\"\n\n• Rahmat!\n4. Ortiqcha"));
    }
}
