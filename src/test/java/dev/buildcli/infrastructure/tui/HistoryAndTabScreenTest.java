package dev.buildcli.infrastructure.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.buildcli.application.ChatSession;
import dev.buildcli.domain.Task;
import dev.buildcli.domain.TaskStatus;
import dev.tamboui.tui.event.KeyCode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Up and Down walk through what you sent; Tab completes the / and @ menus. */
class HistoryAndTabScreenTest {
    @TempDir Path dir;

    ChatScreen screen() {
        var session = new ChatSession(ChatScreenTest.ROSTER, (team, request, ui, cancelled, dispatcher) -> {
            Task t = new Task(1, null, "user", "ana", request.text(), "");
            t.status = TaskStatus.DONE;
            t.result = "ok";
            return t;
        });
        var screen = new ChatScreen(session, Map.of("ana", "m", "bruno", "m"), dir, () -> { });
        ChatScreenTest.render(screen, 130, 40);
        return screen;
    }

    @Test
    void upWalksBackThroughSentMessagesAndDownComesBack() throws Exception {
        var screen = screen();
        for (String m : new String[] {"one", "two", "three"}) {
            ChatScreenTest.type(screen, m);
            ChatScreenTest.key(screen, KeyCode.ENTER);
            ChatScreenTest.idle(screen.sessionForTest());
        }
        ChatScreenTest.type(screen, "draft");
        ChatScreenTest.key(screen, KeyCode.UP);
        assertTrue(ChatScreenTest.render(screen, 130, 40).contains("+   three"));
        ChatScreenTest.key(screen, KeyCode.UP);
        assertTrue(ChatScreenTest.render(screen, 130, 40).contains("+   two"));
        ChatScreenTest.key(screen, KeyCode.UP);
        assertTrue(ChatScreenTest.render(screen, 130, 40).contains("+   one"));
        ChatScreenTest.key(screen, KeyCode.DOWN);
        ChatScreenTest.key(screen, KeyCode.DOWN);
        ChatScreenTest.key(screen, KeyCode.DOWN);
        assertTrue(ChatScreenTest.render(screen, 130, 40).contains("+   draft"), "back to what you were typing");
    }

    @Test
    void tabOnACommandOnlyFillsItAndEnterRunsIt() {
        var screen = screen();
        ChatScreenTest.type(screen, "/hel");
        assertTrue(ChatScreenTest.render(screen, 130, 40).contains("Tab fills"));
        ChatScreenTest.key(screen, KeyCode.TAB);
        assertEquals("/help ", screen.inputForTest().text(), "nothing ran yet");
        assertFalse(screen.viewerOpenForTest());
        ChatScreenTest.key(screen, KeyCode.ENTER);
        assertTrue(screen.viewerOpenForTest(), "Enter ran it");
    }

    @Test
    void eachChatHasItsOwnHistory() throws Exception {
        var screen = screen();
        var session = screen.sessionForTest();
        ChatScreenTest.type(screen, "in the group");
        ChatScreenTest.key(screen, KeyCode.ENTER);
        ChatScreenTest.idle(session);
        String first = screen.selectedForTest();
        screen.handleKeyEvent(dev.tamboui.tui.event.KeyEvent.ofKey(KeyCode.DOWN, dev.tamboui.tui.event.KeyModifiers.ALT), true);
        assertFalse(first.equals(screen.selectedForTest()), "moved to another chat");
        ChatScreenTest.key(screen, KeyCode.UP);
        assertEquals("", screen.inputForTest().text(), "nothing was written in this chat");
        screen.handleKeyEvent(dev.tamboui.tui.event.KeyEvent.ofKey(KeyCode.UP, dev.tamboui.tui.event.KeyModifiers.ALT), true);
        ChatScreenTest.key(screen, KeyCode.UP);
        assertEquals("in the group", screen.inputForTest().text());
    }

    @Test
    void tabCompletesAMention() {
        var screen = screen();
        ChatScreenTest.type(screen, "@br");
        ChatScreenTest.key(screen, KeyCode.TAB);
        assertEquals("@bruno ", screen.inputForTest().text());
    }

    @Test
    void theChatWithTheNewestMessageIsFirstInTheList() throws Exception {
        var screen = screen();
        var session = screen.sessionForTest();
        session.submit("first", List.of(), "ana");
        ChatScreenTest.idle(session);
        Thread.sleep(5);
        session.submit("second", List.of(), "bruno");
        ChatScreenTest.idle(session);
        assertEquals(List.of("bruno", "ana"), screen.threadsForTest().subList(0, 2));
        Thread.sleep(5);
        session.submit("third", List.of(), "ana");
        ChatScreenTest.idle(session);
        assertEquals(List.of("ana", "bruno"), screen.threadsForTest().subList(0, 2));
    }

    /** An agent that asks the user, in a session whose agent waits for the answer. */
    ChatScreen askingScreen(java.util.concurrent.atomic.AtomicReference<String> got, List<String> options) {
        var session = new ChatSession(ChatScreenTest.ROSTER, (team, request, ui, cancelled, dispatcher) -> {
            got.set(dispatcher.ask("ana", "Which database?", options));
            Task t = new Task(1, null, "user", "ana", request.text(), "");
            t.status = TaskStatus.DONE;
            t.result = "ok";
            return t;
        });
        var screen = new ChatScreen(session, Map.of("ana", "m", "bruno", "m"), dir, () -> { });
        ChatScreenTest.render(screen, 130, 40);
        ChatScreenTest.type(screen, "@ana pick one");
        ChatScreenTest.key(screen, KeyCode.ENTER);
        long end = System.nanoTime() + 5_000_000_000L;
        while (session.pending() == null && System.nanoTime() < end) {
            Thread.onSpinWait();
        }
        return screen;
    }

    @Test
    void anAgentCanAskAQuestionWithOptionsAndTheAnswerGoesBackToIt() throws Exception {
        var got = new java.util.concurrent.atomic.AtomicReference<String>();
        var screen = askingScreen(got, List.of("SQLite", "H2"));
        String out = ChatScreenTest.render(screen, 130, 40);
        assertTrue(out.contains("ana asks you") && out.contains("Which database?"), out);
        assertTrue(out.contains("1. SQLite") && out.contains("2. H2") && out.contains("3. Something else"), out);
        ChatScreenTest.key(screen, KeyCode.DOWN);
        ChatScreenTest.key(screen, KeyCode.ENTER);
        ChatScreenTest.idle(screen.sessionForTest());
        assertEquals("The user answered: H2", got.get());
    }

    @Test
    void youCanTypeYourOwnAnswerInsteadOfPickingAnOption() throws Exception {
        var got = new java.util.concurrent.atomic.AtomicReference<String>();
        var screen = askingScreen(got, List.of("SQLite", "H2"));
        ChatScreenTest.type(screen, "3");
        assertTrue(ChatScreenTest.render(screen, 130, 40).contains("Type your answer below"));
        ChatScreenTest.type(screen, "Postgres");
        ChatScreenTest.key(screen, KeyCode.ENTER);
        ChatScreenTest.idle(screen.sessionForTest());
        assertEquals("The user answered: Postgres", got.get());
    }

    @Test
    void anOpenQuestionIsAnsweredInTheInputBox() throws Exception {
        var got = new java.util.concurrent.atomic.AtomicReference<String>();
        var screen = askingScreen(got, List.of());
        ChatScreenTest.type(screen, "yes please");
        ChatScreenTest.key(screen, KeyCode.ENTER);
        ChatScreenTest.idle(screen.sessionForTest());
        assertEquals("The user answered: yes please", got.get());
    }

    @Test
    void escSkipsTheQuestionAndTheAgentIsToldToDecideItself() throws Exception {
        var got = new java.util.concurrent.atomic.AtomicReference<String>();
        var screen = askingScreen(got, List.of("SQLite", "H2"));
        ChatScreenTest.key(screen, KeyCode.ESCAPE);
        ChatScreenTest.idle(screen.sessionForTest());
        assertTrue(got.get().contains("chose not to answer"), got.get());
    }

    @Test
    void whatYouTypedAndDidNotSendWaitsInItsChat() {
        var screen = screen();
        String first = screen.selectedForTest();
        ChatScreenTest.type(screen, "half a thought");
        screen.handleKeyEvent(dev.tamboui.tui.event.KeyEvent.ofKey(KeyCode.DOWN, dev.tamboui.tui.event.KeyModifiers.ALT), true);
        assertFalse(first.equals(screen.selectedForTest()));
        assertEquals("", screen.inputForTest().text(), "the other chat starts empty");
        ChatScreenTest.type(screen, "something else");
        screen.handleKeyEvent(dev.tamboui.tui.event.KeyEvent.ofKey(KeyCode.UP, dev.tamboui.tui.event.KeyModifiers.ALT), true);
        assertEquals("half a thought", screen.inputForTest().text());
        screen.handleKeyEvent(dev.tamboui.tui.event.KeyEvent.ofKey(KeyCode.DOWN, dev.tamboui.tui.event.KeyModifiers.ALT), true);
        assertEquals("something else", screen.inputForTest().text());
    }
}
