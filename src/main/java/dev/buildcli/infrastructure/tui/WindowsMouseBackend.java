package dev.buildcli.infrastructure.tui;

import dev.tamboui.backend.jline3.JLineBackend;
import java.io.IOException;
import java.util.ArrayDeque;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Makes the mouse work in a native Windows console. The terminal library only hands mouse events to a program that asked
 * for them through {@code trackMouse}, which the UI library never does, so clicks and the wheel did nothing on Windows.
 * It then reports them in the old X10 form ({@code ESC [ M} and three bytes), while the UI library reads the SGR form
 * ({@code ESC [ < button ; column ; row M}). This turns one into the other before the UI library sees the input.
 *
 * <p>The console reports no motion without a button, so a mouse that only moves produces nothing. A button held while the
 * mouse moves is reported as a drag.
 */
final class WindowsMouseBackend extends JLineBackend {
    private static final int ESC = 27;
    private static final int LOOKAHEAD_MS = 25;
    private final Terminal terminal;
    private final ArrayDeque<Integer> ready = new ArrayDeque<>();
    private int held = -1;

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
        return next(timeout);
    }

    @Override
    public int peek(int timeout) throws IOException {
        if (!ready.isEmpty()) {
            return ready.peekFirst();
        }
        int c = next(timeout);
        if (c >= 0) {
            ready.addFirst(c);
        }
        return c;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            terminal.close();
        }
    }

    private int pull(int timeout) throws IOException {
        return ready.isEmpty() ? super.read(timeout) : ready.poll();
    }

    private int next(int timeout) throws IOException {
        while (true) {
            int c = pull(timeout);
            if (c != ESC) {
                return c;
            }
            int bracket = pull(1);
            if (bracket != '[') {
                if (bracket >= 0) {
                    ready.addFirst(bracket);
                }
                return c;
            }
            int m = pull(1);
            if (m != 'M') {
                if (m >= 0) {
                    ready.addFirst(m);
                }
                ready.addFirst((int) '[');
                return c;
            }
            int button = pull(LOOKAHEAD_MS);
            int column = pull(LOOKAHEAD_MS);
            int row = pull(LOOKAHEAD_MS);
            if (button < 0 || column < 0 || row < 0) {
                continue; // a cut-off mouse report: drop it rather than type its bytes
            }
            String sgr = toSgr(button - 32, column - 32, row - 32);
            if (sgr == null) {
                continue;
            }
            sgr.chars().skip(1).forEach(ready::addLast);
            return ESC;
        }
    }

    /** One X10 report as the SGR sequence for it, or null when it says nothing new (a release with no button held). */
    private String toSgr(int code, int column, int row) {
        if ((code & 64) != 0) {
            return "\u001b[<" + code + ";" + column + ";" + row + "M"; // wheel: 64 up, 65 down
        }
        int button = code & 3;
        if (button == 3) {
            if (held < 0) {
                return null;
            }
            held = -1;
            return "\u001b[<0;" + column + ";" + row + "m";
        }
        if (held < 0) {
            held = button;
            return "\u001b[<" + button + ";" + column + ";" + row + "M";
        }
        return "\u001b[<" + (button | 32) + ";" + column + ";" + row + "M"; // the same button, now moving
    }
}
