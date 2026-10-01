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
    private final SettingsServices services;
    private final Attention attention = new Attention();

    public ChatApp(ChatSession session, Map<String, String> models, Path cwd, boolean mouse) {
        this(session, models, cwd, mouse, ChatScreen.basicServices(session, models));
    }

    public ChatApp(ChatSession session, Map<String, String> models, Path cwd, boolean mouse, SettingsServices services) {
        this.session = session;
        this.mouse = mouse;
        this.services = services;
        this.screen = new ChatScreen(session, models, cwd, this::leave, services);
    }

    @Override
    protected TuiConfig configure() {
        // no fixed tick: the screen is drawn when you type or click, and when the chat changes (see onStart)
        TuiConfig.Builder config = TuiConfig.builder().noTick().mouseCapture(mouse).bracketedPaste(true);
        if (WindowsBackend.isWindows()) {
            WindowsBackend backend = WindowsBackend.open(mouse);
            if (backend != null) {
                config.backend(backend);
            }
        }
        return config.build();
    }

    @Override
    protected void onStart() {
        setWindowTitle("BuildCLI");
        runner().focusManager().setFocus("chat");
        // OSC 52 copy: written on the thread that draws, like the title and the bell
        screen.rawOutput(text -> runner().runOnRenderThread(() -> {
            try {
                runner().tuiRunner().backend().writeRaw(text);
                runner().tuiRunner().backend().flush();
            } catch (java.io.IOException e) {
                // the copy then simply does not happen; the chat says so
            }
        }));
        // Check 12 times a second whether anything changed; draw only then, or while something moves (typing, spinner).
        // Idle, this costs almost nothing, unlike redrawing the whole screen on every tick.
        long[] seen = {-1};
        boolean[] moving = {false};
        long start = System.nanoTime();
        runner().scheduleRepeating(() -> {
            long v = session.version();
            boolean now = screen.animating();
            // one more frame after the movement stops, or the screen keeps showing the last "loading…"
            boolean draw = v != seen[0] || now || moving[0];
            moving[0] = now;
            if (draw) {
                seen[0] = v;
                long ms = (System.nanoTime() - start) / 1_000_000;
                runner().tuiRunner().dispatch(dev.tamboui.tui.event.TickEvent.of(ms, Duration.ofMillis(ms)));
            }
            watchAttention(start);
        }, Duration.ofMillis(80));
    }

    /** The window title and the bell, written on the thread that draws so they cannot cut a frame in two. */
    private void watchAttention(long start) {
        var pending = session.pending();
        Attention.Signal signal = attention.update((System.nanoTime() - start) / 1_000_000, session.pendingCount(),
                pending == null ? null : pending.agent(), session.busy(), services.settings().flag(dev.buildcli.application.Settings.BELL));
        if (signal.title() == null && !signal.bell()) {
            return;
        }
        runner().runOnRenderThread(() -> {
            if (signal.title() != null) {
                setWindowTitle(signal.title());
            }
            if (signal.bell()) {
                var backend = runner().tuiRunner().backend();
                try {
                    backend.writeRaw("\u0007");
                    backend.flush();
                } catch (java.io.IOException e) {
                    // a failed bell is not worth interrupting the chat for
                }
            }
        });
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
