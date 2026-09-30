package dev.buildcli.infrastructure.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * Colours of the chat: WhatsApp Web's dark and light themes, and a high-contrast one. Every style sets both
 * foreground and background, so the look does not depend on the terminal's own theme. Switched live from Settings.
 */
final class Theme {
    private Theme() {}

    static Color BG;
    static Color SIDEBAR;
    static Color PANEL;
    static Color FIELD;
    static Color SELECTED;
    static Color ME;
    static Color THEM;
    static Color PILL;
    static Color CODE;
    static Color ERROR_BG;
    static Color DIALOG;
    static Color TEXT;
    static Color DIM;
    static Color FAINT;
    static Color ACCENT;
    static Color GREEN;
    static Color BLUE;
    static Color RED;
    static Color AMBER;
    static Color ON_ME_DIM;
    static Color CODE_TEXT;
    static Color LINE;
    static Color ON_ACCENT;
    static Color ADD_BG;
    static Color ADD_FG;
    static Color DEL_BG;
    static Color DEL_FG;
    static Color DANGER;
    private static String current = "";

    private static Color[] agentColors;

    static {
        use("dark");
    }

    /** Switches the palette: "dark", "light" or "contrast". Unknown names fall back to dark. */
    static synchronized void use(String name) {
        String n = name == null ? "dark" : name;
        if (n.equals(current)) {
            return;
        }
        current = n;
        switch (n) {
            case "light" -> {
                BG = Color.rgb(239, 234, 226);
                SIDEBAR = Color.rgb(255, 255, 255);
                PANEL = Color.rgb(240, 242, 245);
                FIELD = Color.rgb(255, 255, 255);
                SELECTED = Color.rgb(240, 242, 245);
                ME = Color.rgb(217, 253, 211);
                THEM = Color.rgb(255, 255, 255);
                PILL = Color.rgb(255, 255, 255);
                CODE = Color.rgb(240, 240, 240);
                ERROR_BG = Color.rgb(253, 226, 226);
                DIALOG = Color.rgb(255, 255, 255);
                TEXT = Color.rgb(17, 27, 33);
                DIM = Color.rgb(102, 119, 129);
                FAINT = Color.rgb(160, 170, 176);
                ACCENT = Color.rgb(0, 128, 105);
                GREEN = Color.rgb(0, 150, 80);
                BLUE = Color.rgb(0, 132, 214);
                RED = Color.rgb(200, 40, 60);
                AMBER = Color.rgb(180, 110, 0);
                ON_ME_DIM = Color.rgb(90, 120, 100);
                CODE_TEXT = Color.rgb(150, 60, 0);
                LINE = Color.rgb(222, 226, 230);
                ON_ACCENT = Color.rgb(255, 255, 255);
                ADD_BG = Color.rgb(220, 245, 225);
                ADD_FG = Color.rgb(20, 110, 40);
                DEL_BG = Color.rgb(253, 225, 225);
                DEL_FG = Color.rgb(170, 30, 40);
                DANGER = Color.rgb(220, 60, 70);
                agentColors = new Color[] {Color.rgb(0, 110, 190), Color.rgb(170, 100, 0), Color.rgb(150, 50, 190), Color.rgb(30, 140, 60),
                    Color.rgb(200, 80, 40), Color.rgb(190, 40, 110), Color.rgb(0, 140, 150), Color.rgb(100, 130, 20)};
            }
            case "contrast" -> {
                BG = Color.rgb(0, 0, 0);
                SIDEBAR = Color.rgb(0, 0, 0);
                PANEL = Color.rgb(20, 20, 20);
                FIELD = Color.rgb(30, 30, 30);
                SELECTED = Color.rgb(50, 50, 50);
                ME = Color.rgb(0, 70, 55);
                THEM = Color.rgb(30, 30, 30);
                PILL = Color.rgb(25, 25, 25);
                CODE = Color.rgb(15, 15, 15);
                ERROR_BG = Color.rgb(90, 0, 0);
                DIALOG = Color.rgb(20, 20, 20);
                TEXT = Color.rgb(255, 255, 255);
                DIM = Color.rgb(200, 200, 200);
                FAINT = Color.rgb(150, 150, 150);
                ACCENT = Color.rgb(0, 230, 170);
                GREEN = Color.rgb(80, 255, 120);
                BLUE = Color.rgb(120, 200, 255);
                RED = Color.rgb(255, 90, 100);
                AMBER = Color.rgb(255, 215, 0);
                ON_ME_DIM = Color.rgb(200, 230, 220);
                CODE_TEXT = Color.rgb(255, 220, 150);
                LINE = Color.rgb(120, 120, 120);
                ON_ACCENT = Color.rgb(0, 0, 0);
                ADD_BG = Color.rgb(0, 60, 0);
                ADD_FG = Color.rgb(120, 255, 140);
                DEL_BG = Color.rgb(80, 0, 0);
                DEL_FG = Color.rgb(255, 150, 150);
                DANGER = Color.rgb(180, 0, 0);
                agentColors = new Color[] {Color.rgb(120, 200, 255), Color.rgb(255, 215, 0), Color.rgb(230, 150, 255), Color.rgb(120, 255, 140),
                    Color.rgb(255, 160, 110), Color.rgb(255, 130, 180), Color.rgb(100, 240, 250), Color.rgb(200, 240, 140)};
            }
            default -> {
                BG = Color.rgb(11, 20, 26);
                SIDEBAR = Color.rgb(17, 27, 33);
                PANEL = Color.rgb(32, 44, 51);
                FIELD = Color.rgb(42, 57, 66);
                SELECTED = Color.rgb(42, 57, 66);
                ME = Color.rgb(0, 92, 75);
                THEM = Color.rgb(32, 44, 51);
                PILL = Color.rgb(24, 34, 41);
                CODE = Color.rgb(8, 14, 18);
                ERROR_BG = Color.rgb(74, 28, 28);
                DIALOG = Color.rgb(32, 44, 51);
                TEXT = Color.rgb(233, 237, 239);
                DIM = Color.rgb(134, 150, 160);
                FAINT = Color.rgb(84, 101, 111);
                ACCENT = Color.rgb(0, 168, 132);
                GREEN = Color.rgb(37, 211, 102);
                BLUE = Color.rgb(83, 189, 235);
                RED = Color.rgb(241, 92, 109);
                AMBER = Color.rgb(255, 202, 40);
                ON_ME_DIM = Color.rgb(160, 200, 190);
                CODE_TEXT = Color.rgb(255, 214, 165);
                LINE = Color.rgb(34, 45, 52);
                ON_ACCENT = Color.rgb(11, 20, 26);
                ADD_BG = Color.rgb(22, 50, 32);
                ADD_FG = Color.rgb(140, 220, 150);
                DEL_BG = Color.rgb(60, 24, 24);
                DEL_FG = Color.rgb(240, 150, 150);
                DANGER = Color.rgb(150, 40, 40);
                agentColors = new Color[] {Color.rgb(83, 189, 235), Color.rgb(255, 202, 40), Color.rgb(224, 120, 255), Color.rgb(102, 187, 106),
                    Color.rgb(255, 138, 101), Color.rgb(240, 98, 146), Color.rgb(77, 208, 225), Color.rgb(174, 213, 129)};
            }
        }
    }

    static String current() {
        return current;
    }

    static Style on(Color fg, Color bg) {
        return Style.create().fg(fg).bg(bg);
    }

    static Color agentColor(String name) {
        return agentColors[Math.floorMod(name.hashCode(), agentColors.length)];
    }
}
