package dev.buildcli.infrastructure.tui;

import dev.tamboui.backend.jline3.JLineBackend;
import java.io.IOException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Makes the mouse work in a native Windows console. The terminal library only hands mouse events to a program that asked
 * for them through {@code trackMouse}, which the UI library never does, so clicks and the wheel did nothing on Windows.
 * It then reports them in the old X10 form, while the UI library reads the SGR form; {@link X10Mouse} converts one into
 * the other before the UI library sees the input.
 */
final class WindowsMouseBackend extends JLineBackend {
    private final Terminal terminal;
    private final X10Mouse mouse = new X10Mouse(super::read);

    WindowsMouseBackend(Terminal terminal) {
        super(terminal);
        this.terminal = terminal;
        terminal.trackMouse(Terminal.MouseTracking.Button);
    }

    /** A backend on the system console with the mouse working, or null if the console cannot be opened. */
    static WindowsMouseBackend open() {
        try {
            return new WindowsMouseBackend(TerminalBuilder.builder().system(true).jansi(true).build());
        } catch (IOException | RuntimeException e) {
            return null; // the default backend then opens (and reports) the console as before
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    @Override
    public int read(int timeout) throws IOException {
        return mouse.read(timeout);
    }

    @Override
    public int peek(int timeout) throws IOException {
        return mouse.peek(timeout);
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            terminal.close();
        }
    }
}
