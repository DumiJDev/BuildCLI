package dev.buildcli.infrastructure.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * Colours of the chat, after WhatsApp Web's dark theme. Every style sets both foreground and background, so the look
 * does not depend on the terminal's own theme.
 */
final class Theme {
    private Theme() {}

    static final Color BG = Color.rgb(11, 20, 26);
    static final Color SIDEBAR = Color.rgb(17, 27, 33);
    static final Color PANEL = Color.rgb(32, 44, 51);
    static final Color FIELD = Color.rgb(42, 57, 66);
    static final Color SELECTED = Color.rgb(42, 57, 66);
    static final Color ME = Color.rgb(0, 92, 75);
    static final Color THEM = Color.rgb(32, 44, 51);
    static final Color PILL = Color.rgb(24, 34, 41);
    static final Color CODE = Color.rgb(8, 14, 18);
    static final Color ERROR_BG = Color.rgb(74, 28, 28);
    static final Color DIALOG = Color.rgb(32, 44, 51);
    static final Color TEXT = Color.rgb(233, 237, 239);
    static final Color DIM = Color.rgb(134, 150, 160);
    static final Color FAINT = Color.rgb(84, 101, 111);
    static final Color ACCENT = Color.rgb(0, 168, 132);
    static final Color GREEN = Color.rgb(37, 211, 102);
    static final Color BLUE = Color.rgb(83, 189, 235);
    static final Color RED = Color.rgb(241, 92, 109);
    static final Color AMBER = Color.rgb(255, 202, 40);
    static final Color ON_ME_DIM = Color.rgb(160, 200, 190);
    static final Color CODE_TEXT = Color.rgb(255, 214, 165);

    private static final Color[] AGENT_COLORS = {
        Color.rgb(83, 189, 235), Color.rgb(255, 202, 40), Color.rgb(224, 120, 255), Color.rgb(102, 187, 106),
        Color.rgb(255, 138, 101), Color.rgb(240, 98, 146), Color.rgb(77, 208, 225), Color.rgb(174, 213, 129)
    };

    static Style on(Color fg, Color bg) {
        return Style.create().fg(fg).bg(bg);
    }

    static Color agentColor(String name) {
        return AGENT_COLORS[Math.floorMod(name.hashCode(), AGENT_COLORS.length)];
    }
}
