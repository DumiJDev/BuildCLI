package dev.buildcli.infrastructure.tui;

import dev.buildcli.application.ChatSession;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.TuiConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Runs the chat screen until the user quits. Mouse capture can be turned off with BUILDCLI_MOUSE=0 (e.g. to select text freely). */
public final class ChatApp extends ToolkitApp {
    private final ChatSession session;
    private final ChatScreen screen;
    private final boolean mouse;

    public ChatApp(ChatSession session, Map<String, String> models, Path cwd, boolean mouse) {
        this(session, models, cwd, mouse, ChatScreen.basicServices(session, models));
    }

    public ChatApp(ChatSession session, Map<String, String> models, Path cwd, boolean mouse, SettingsServices services) {
        this.session = session;
        this.mouse = mouse;
        this.screen = new ChatScreen(session, models, cwd, this::leave, services);
    }

    @Override
    protected TuiConfig configure() {
        // no fixed tick: the screen is drawn when you type or click, and when the chat changes (see onStart)
        return TuiConfig.builder().noTick().mouseCapture(mouse).bracketedPaste(true).build();
    }

    @Override
    protected void onStart() {
        setWindowTitle("BuildCLI");
        runner().focusManager().setFocus("chat");
        // Check 12 times a second whether anything changed; draw only then, or while something moves (typing, spinner).
        // Idle, this costs almost nothing, unlike redrawing the whole screen on every tick.
        long[] seen = {-1};
        long start = System.nanoTime();
        runner().scheduleRepeating(() -> {
            long v = session.version();
            if (v != seen[0] || screen.animating()) {
                seen[0] = v;
                long ms = (System.nanoTime() - start) / 1_000_000;
                runner().tuiRunner().dispatch(dev.tamboui.tui.event.TickEvent.of(ms, Duration.ofMillis(ms)));
            }
        }, Duration.ofMillis(80));
    }

    @Override
    protected Element render() {
        return screen;
    }

    private void leave() {
        session.close();
        quit();
    }
}
