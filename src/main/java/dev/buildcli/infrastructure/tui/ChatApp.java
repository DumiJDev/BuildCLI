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
        return TuiConfig.builder().tickRate(Duration.ofMillis(80)).mouseCapture(mouse).bracketedPaste(true).build();
    }

    @Override
    protected void onStart() {
        setWindowTitle("BuildCLI");
        runner().focusManager().setFocus("chat");
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
