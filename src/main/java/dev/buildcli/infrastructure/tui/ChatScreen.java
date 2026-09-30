package dev.buildcli.infrastructure.tui;

import dev.buildcli.application.ChatSession;
import dev.buildcli.application.ChatSession.Message;
import dev.buildcli.application.ChatSession.State;
import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Attachment;
import dev.buildcli.domain.Task;
import dev.buildcli.infrastructure.TerminalText;
import dev.buildcli.infrastructure.tui.Styled.Span;
import dev.buildcli.ports.EscalationChoice;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.tui.event.PasteEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The chat screen. It looks like WhatsApp Web: a list of chats on the left (the team's group chat and a direct chat
 * with each agent, with previews, times, "typing…" and unread counts), and the open chat on the right with bubbles,
 * ticks and date separators. It works like ChatGPT: streamed replies with markdown and code blocks, suggestions, a
 * {@code /} command menu, an {@code @} mention menu, and a viewer for git diffs and files.
 * Drawn by hand on the terminal buffer; keyboard, mouse (click, wheel, scrollbar drag) and paste (a pasted file path
 * becomes an attachment) end up as calls on the {@link ChatSession}.
 */
final class ChatScreen implements Element {

    private record Row(int x, List<Span> spans) {}

    private record Hit(Rect rect, Runnable action) {}

    /** A slash command: what the menu shows and what it does. {@code arg} is a hint when it takes an argument. */
    record Command(String name, String arg, String description, String shortcut) {}

    /** What the full-screen viewer shows. */
    private record View(String title, List<String> lines, boolean diff, boolean numbered) {}

    static final List<Command> COMMANDS = List.of(
            new Command("diff", "[--staged] [path]", "Show uncommitted changes (git diff)", "Ctrl+G"),
            new Command("status", "", "Show git status", ""),
            new Command("log", "", "Show recent commits", ""),
            new Command("open", "<file>", "Open a file in the viewer", "Ctrl+O"),
            new Command("attach", "<file>", "Attach an image or audio file (or paste its path)", ""),
            new Command("tasks", "", "Show what the team is doing: tasks and handoffs", "Ctrl+T"),
            new Command("stop", "", "Stop the team's current work", "Ctrl+X"),
            new Command("retry", "", "Send the last failed message again", ""),
            new Command("queue", "[clear]", "Show or drop messages waiting their turn", ""),
            new Command("settings", "", "Providers, models, agents, theme and more", "F2"),
            new Command("dm", "@agent", "Open a direct chat with an agent", ""),
            new Command("newgroup", "<name> [@agents]", "Create a group with some agents", ""),
            new Command("add", "@agent", "Add an agent to this group", ""),
            new Command("remove", "@agent", "Remove an agent from this group", ""),
            new Command("admin", "@agent", "Make an agent admin of this group", ""),
            new Command("dismiss", "@agent", "Dismiss an admin of this group", ""),
            new Command("rename", "<name>", "Rename this group", ""),
            new Command("info", "", "Group or contact info", ""),
            new Command("team", "", "Show or hide the chat list", "Ctrl+B"),
            new Command("clear", "", "Clear the conversation from the screen", "Ctrl+L"),
            new Command("help", "", "Keys, commands and tips", ""),
            new Command("quit", "", "Leave BuildCLI", "Ctrl+C"));

    private static final int SIDEBAR_WIDTH = 34;
    private static final int SIDEBAR_MIN_TOTAL = 96;
    private static final int MAX_INPUT_ROWS = 8;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final int MENU_ROWS = 8;
    private static final String SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";

    private final ChatSession session;
    private final Map<String, String> models;
    private final Path cwd;
    private final Runnable quit;
    private final InputEditor input = new InputEditor();
    private final List<Attachment> attachments = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();

    private boolean sidebar = true;
    /** True until the user toggles the chat list: then it follows their choice even on narrow terminals. */
    private boolean autoSidebar = true;
    private int sideWidth;
    private String selected = ChatSession.TEAM;
    private final Map<String, Long> seen = new java.util.HashMap<>();
    private int scrollOff;
    private int lastTotal;
    private int scrollMax;
    private View view;
    private int viewScroll;
    private int viewHeight = 10;
    private int menuIndex;
    private String menuDismissedFor;
    private Rect area = Rect.ZERO;
    private Rect inputTextArea = Rect.ZERO;
    private Rect scrollTrack = Rect.ZERO;
    private boolean draggingScrollbar;
    private int inputFirstRow;
    private int inputWidth = 40;

    private final SettingsServices services;
    private final SettingsView settingsView;
    private boolean settingsOpen;
    private ChatInfoView infoView;
    private boolean infoOpen;

    ChatScreen(ChatSession session, Map<String, String> models, Path cwd, Runnable quit) {
        this(session, models, cwd, quit, basicServices(session, models));
    }

    ChatScreen(ChatSession session, Map<String, String> models, Path cwd, Runnable quit, SettingsServices services) {
        this.session = session;
        this.models = models;
        this.cwd = cwd;
        this.quit = quit;
        this.services = services;
        this.settingsView = new SettingsView(services, () -> settingsOpen = false, file -> {
            settingsOpen = false;
            runCommand("/open " + file);
        });
        this.infoView = new ChatInfoView(session, () -> selected, this::select, () -> infoOpen = false, this::modelLabel);
    }

    /** Settings kept in memory, no providers or agent files: for tests and the demo. */
    static SettingsServices basicServices(ChatSession session, Map<String, String> models) {
        dev.buildcli.application.Settings settings = dev.buildcli.application.Settings.defaults();
        return new SettingsServices() {
            @Override
            public dev.buildcli.application.Settings settings() {
                return settings;
            }

            @Override
            public List<Provider> providers() {
                return List.of();
            }

            @Override
            public void addProvider(String name, String url, String keyEnv) {
                throw new IllegalStateException("not available in the demo");
            }

            @Override
            public void removeProvider(String name) {
                throw new IllegalStateException("not available in the demo");
            }

            @Override
            public java.util.concurrent.CompletableFuture<String> test(String model) {
                return java.util.concurrent.CompletableFuture.completedFuture("not available in the demo");
            }

            @Override
            public java.util.concurrent.CompletableFuture<dev.buildcli.infrastructure.ModelCatalog.Result> models(String provider) {
                return java.util.concurrent.CompletableFuture.completedFuture(new dev.buildcli.infrastructure.ModelCatalog.Result(List.of(), null));
            }

            @Override
            public List<AgentInfo> agents() {
                return session.contacts().stream().map(a -> new AgentInfo(a.name(), a.role(), "built in", "", List.copyOf(a.capabilities()))).toList();
            }

            @Override
            public String createAgent(String name, String role, String instructions, List<String> capabilities, boolean global) {
                throw new IllegalStateException("not available in the demo");
            }

            @Override
            public void deleteAgent(String name) {
                throw new IllegalStateException("not available in the demo");
            }

            @Override
            public String teamModel(String agent) {
                return models.get(agent);
            }
        };
    }

    private String modelLabel(String agent) {
        String own = services.settings().modelFor(agent);
        if (own != null) {
            return own;
        }
        String team = services.teamModel(agent);
        return team != null ? team : services.settings().defaultModel() == null ? models.getOrDefault(agent, "") : services.settings().defaultModel();
    }

    private dev.buildcli.application.Settings settings() {
        return services.settings();
    }

    // ---- Element ----

    @Override
    public void render(Frame frame, Rect rect, RenderContext ctx) {
        area = rect;
        hits.clear();
        scrollTrack = Rect.ZERO;
        Theme.use(settings().get(dev.buildcli.application.Settings.THEME));
        Buffer buf = frame.buffer();
        fill(buf, rect, Theme.on(Theme.TEXT, Theme.BG));
        if (rect.width() < 44 || rect.height() < 12) {
            put(buf, rect.x() + 1, rect.y() + 1, "Terminal too small (need 44x12)", Theme.on(Theme.AMBER, Theme.BG), rect.right());
            frame.clearCursor();
            return;
        }
        int sideW = sidebar && (rect.width() >= SIDEBAR_MIN_TOTAL || !autoSidebar) ? Math.min(SIDEBAR_WIDTH, rect.width() / 2) : 0;
        sideWidth = sideW;
        List<Message> all = session.messages();
        if (sideW > 0) {
            drawChatList(buf, new Rect(rect.x(), rect.y(), sideW, rect.height()), all);
            for (int y = rect.y(); y < rect.bottom(); y++) {
                put(buf, rect.x() + sideW, y, "│", st(Theme.LINE, Theme.BG), rect.right());
            }
        }
        Rect pane = new Rect(rect.x() + sideW + (sideW > 0 ? 1 : 0), rect.y(), rect.width() - sideW - (sideW > 0 ? 1 : 0), rect.height());
        if (settingsOpen) {
            settingsView.render(buf, pane);
            frame.clearCursor();
        } else if (infoOpen) {
            infoView.render(buf, pane);
            frame.clearCursor();
        } else if (view != null) {
            drawViewer(buf, pane);
            frame.clearCursor();
        } else {
            drawPane(frame, buf, pane, all);
        }
        if (session.pending() != null) {
            drawDialog(buf, rect);
            frame.clearCursor();
        }
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.UNKNOWN;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    public String id() {
        return "chat";
    }

    @Override
    public Rect renderedArea() {
        return area;
    }

    // ---- drawing primitives ----

    private static void fill(Buffer buf, Rect r, Style style) {
        if (r.width() > 0 && r.height() > 0) {
            buf.fill(r, new Cell(" ", style));
        }
    }

    /** Draws text clipped at column {@code limit} (exclusive) and returns the columns used. */
    private static int put(Buffer buf, int x, int y, String text, Style style, int limit) {
        if (x >= limit || text.isEmpty()) {
            return 0;
        }
        String clipped = CharWidth.substringByWidth(text, limit - x);
        buf.setString(x, y, clipped, style);
        return CharWidth.of(clipped);
    }

    private void drawSpans(Buffer buf, int x, int y, List<Span> spans, int limit) {
        int cx = x;
        for (Span s : spans) {
            int w = put(buf, cx, y, s.text(), s.style(), limit);
            if (s.action() != null && w > 0) {
                hits.add(new Hit(new Rect(cx, y, w, 1), s.action()));
            }
            cx += w;
        }
    }

    private static String clean(String s) {
        return TerminalText.sanitize(s);
    }

    private static String tokens(long n) {
        return n >= 1000 ? String.format(Locale.ROOT, "%.1fk", n / 1000.0) : Long.toString(n);
    }

    private static Style st(Color fg, Color bg) {
        return Theme.on(fg, bg);
    }

    // ---- chat list (left) ----

    /** The chats in the list: the team first, then one direct chat per agent. */
    private List<String> threads() {
        List<String> out = new ArrayList<>();
        session.groups().forEach(g -> out.add(g.id()));
        out.addAll(session.directChats());
        return out;
    }

    private String title(String thread) {
        var g = session.group(thread);
        return g != null ? clean(g.name()) : clean(thread);
    }

    private List<Message> inThread(List<Message> all, String thread) {
        List<Message> out = new ArrayList<>();
        for (Message m : all) {
            if (m.thread().equals(thread) || m.thread().equals(ChatSession.EVERYWHERE)) {
                out.add(m);
            }
        }
        return out;
    }

    private void select(String thread) {
        selected = thread;
        scrollOff = 0;
        lastTotal = 0;
        menuDismissedFor = null;
    }

    private void drawChatList(Buffer buf, Rect r, List<Message> all) {
        Style base = st(Theme.TEXT, Theme.SIDEBAR);
        Style dim = st(Theme.DIM, Theme.SIDEBAR);
        fill(buf, r, base);
        fill(buf, new Rect(r.x(), r.y(), r.width(), 2), st(Theme.TEXT, Theme.PANEL));
        put(buf, r.x() + 2, r.y(), "BuildCLI", st(Theme.TEXT, Theme.PANEL).bold(), r.right());
        put(buf, r.x() + 2, r.y() + 1, "local AI engineering team", st(Theme.DIM, Theme.PANEL), r.right() - 4);
        put(buf, r.right() - 4, r.y(), " ⚙ ", st(Theme.DIM, Theme.PANEL), r.right());
        hits.add(new Hit(new Rect(r.right() - 4, r.y(), 3, 2), () -> settingsOpen = true));
        put(buf, r.right() - 8, r.y(), " ＋ ", st(Theme.DIM, Theme.PANEL), r.right() - 4);
        hits.add(new Hit(new Rect(r.right() - 8, r.y(), 4, 2), () -> openInfo(ChatInfoView.Mode.NEW_CHAT)));
        int y = r.y() + 3;
        int limit = r.right() - 1;
        List<String> threads = threads();
        for (int i = 0; i < threads.size() && y + 2 < r.bottom() - 1; i++) {
            String t = threads.get(i);
            boolean sel = t.equals(selected);
            Color bg = sel ? Theme.SELECTED : Theme.SIDEBAR;
            Rect row = new Rect(r.x(), y, r.width(), 2);
            fill(buf, row, st(Theme.TEXT, bg));
            hits.add(new Hit(row, () -> select(t)));
            avatar(buf, r.x() + 1, y, t);

            List<Message> msgs = inThread(all, t);
            Message last = null;
            for (int k = msgs.size() - 1; k >= 0; k--) {
                if (!msgs.get(k).thread().equals(ChatSession.EVERYWHERE)) {
                    last = msgs.get(k);
                    break;
                }
            }
            String time = last == null ? "" : when(last);
            put(buf, limit - Wrap.width(time), y, time, st(unread(msgs, t) > 0 ? Theme.GREEN : Theme.DIM, bg), limit);
            put(buf, r.x() + 6, y, title(t), st(Theme.TEXT, bg).bold(), limit - Wrap.width(time) - 1);

            String preview;
            Style ps = st(Theme.DIM, bg);
            ChatSession.Live live = session.live(t);
            boolean isGroup = session.group(t) != null;
            String busyIn = isGroup ? null : session.agentThread(t);
            if (live != null) {
                preview = (isGroup ? clean(live.agent()) + " is " : "") + "typing…";
                ps = st(Theme.GREEN, bg);
            } else if (session.isActive(t)) {
                String who = busyAgentIn(t);
                preview = isGroup && !who.isEmpty() ? clean(who) + " is " + session.agentState(who) + "…"
                        : session.agentState(t) + "…";
                ps = st(Theme.GREEN, bg);
            } else if (busyIn != null) {
                preview = "busy in the " + title(busyIn) + " chat";
                ps = st(Theme.AMBER, bg);
            } else if (last == null) {
                preview = isGroup ? session.group(t).members().size() + " agents and you" : clean(roleOf(t));
            } else {
                preview = previewOf(last, isGroup);
            }
            int badge = unread(msgs, t);
            String b = badge > 0 ? " " + badge + " " : "";
            put(buf, r.x() + 6, y + 1, preview.replace('\n', ' '), ps, limit - Wrap.width(b) - 1);
            if (badge > 0) {
                put(buf, limit - Wrap.width(b), y + 1, b, st(Theme.BG, Theme.GREEN).bold(), limit + 1);
            }
            y += 2;
            put(buf, r.x() + 6, y, "─".repeat(Math.max(0, r.width() - 7)), st(Theme.LINE, Theme.SIDEBAR), limit + 1);
            y++;
        }
        int q = session.queued();
        String foot = (q > 0 ? q + " queued · " : "") + tokens(session.inputTokens() + (long) session.outputTokens()) + " tokens";
        put(buf, r.x() + 2, r.bottom() - 1, foot, q > 0 ? st(Theme.AMBER, Theme.SIDEBAR) : st(Theme.FAINT, Theme.SIDEBAR), limit);
        List<String> idle = session.idleContacts();
        if (!idle.isEmpty()) {
            String warn = "⚠ " + String.join(", ", idle) + (idle.size() == 1 ? " is" : " are") + " in no chat";
            put(buf, r.x() + 2, r.bottom() - 3, warn, st(Theme.AMBER, Theme.SIDEBAR), limit);
            put(buf, r.x() + 2, r.bottom() - 2, "loaded and idle · click to add", st(Theme.FAINT, Theme.SIDEBAR), limit);
            hits.add(new Hit(new Rect(r.x(), r.bottom() - 3, r.width(), 2), () -> openInfo(ChatInfoView.Mode.NEW_CHAT)));
        }
    }

    private void openInfo(ChatInfoView.Mode mode) {
        infoView.open(mode);
        infoOpen = true;
        settingsOpen = false;
    }

    private String roleOf(String agent) {
        Agent a = session.contact(agent);
        return a == null ? "" : a.role();
    }

    private static String previewOf(Message m, boolean group) {
        String text = m.text().isEmpty() && !m.attachments().isEmpty()
                ? (m.attachments().get(0).kind() == Attachment.Kind.IMAGE ? "▣ Photo" : "♪ Audio") : clean(Styled.plain(m.text()));
        return switch (m.kind()) {
            case USER -> (m.state() == State.DONE ? "✓✓ " : m.state() == State.FAILED ? "! " : "✓ ") + text;
            case AGENT -> (group ? clean(m.author()) + ": " : "") + text;
            case ACTIVITY -> clean(m.author()) + " " + text;
            default -> text;
        };
    }

    private int unread(List<Message> msgs, String thread) {
        if (thread.equals(selected)) {
            return 0;
        }
        long seenId = seen.getOrDefault(thread, 0L);
        int n = 0;
        for (Message m : msgs) {
            if (m.kind() == ChatSession.Kind.AGENT && m.id() > seenId && m.thread().equals(thread)) {
                n++;
            }
        }
        return n;
    }

    /** A coloured initial, like a profile picture. */
    private void avatar(Buffer buf, int x, int y, String thread) {
        var g = session.group(thread);
        boolean team = g != null;
        String name = team ? g.name() : thread;
        String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        Color c = team ? Theme.ACCENT : Theme.agentColor(thread);
        put(buf, x, y, " " + initial + " ", st(Theme.ON_ACCENT, c).bold(), x + 3);
        String state = team ? "" : session.agentState(thread);
        if (!state.isEmpty() && !state.equals("idle")) {
            put(buf, x + 3, y, "●", st(state.startsWith("waiting") || state.equals("needs you") ? Theme.AMBER : Theme.GREEN, Theme.SIDEBAR), x + 4);
        }
    }

    private static String when(Message m) {
        java.time.LocalDate day = m.at().atZone(ZoneId.systemDefault()).toLocalDate();
        java.time.LocalDate today = java.time.LocalDate.now();
        if (day.equals(today)) {
            return TIME.format(m.at());
        }
        return day.equals(today.minusDays(1)) ? "Yesterday" : DAY.format(day);
    }

    // ---- the open chat (right) ----

    private void drawPane(Frame frame, Buffer buf, Rect pane, List<Message> all) {
        drawPaneHeader(buf, new Rect(pane.x(), pane.y(), pane.width(), 2));
        inputWidth = Math.max(10, pane.width() - 12);
        List<Wrap.Segment> segs = Wrap.layout(input.text(), inputWidth);
        int visibleRows = Math.min(segs.size(), MAX_INPUT_ROWS);
        int barH = visibleRows + 2;
        int barY = pane.bottom() - barH;
        int chipsY = attachments.isEmpty() ? barY : barY - 1;
        Rect content = new Rect(pane.x(), pane.y() + 2, pane.width(), Math.max(1, chipsY - pane.y() - 2));
        List<Message> msgs = inThread(all, selected);
        if (!msgs.isEmpty()) {
            seen.put(selected, msgs.get(msgs.size() - 1).id());
        }
        drawConversation(buf, content, msgs);
        if (!attachments.isEmpty()) {
            drawChips(buf, new Rect(pane.x(), chipsY, pane.width(), 1));
        }
        Rect bar = new Rect(pane.x(), barY, pane.width(), barH);
        drawInputBar(frame, buf, bar, segs, visibleRows);
        if (session.pending() == null) {
            drawMenu(buf, new Rect(pane.x() + 4, barY, Math.min(pane.width() - 8, 90), barH));
        }
    }

    private void drawPaneHeader(Buffer buf, Rect r) {
        Style base = st(Theme.TEXT, Theme.PANEL);
        fill(buf, r, base);
        int x = r.x() + 1;
        if (sideWidth == 0) {
            put(buf, x, r.y(), "‹", st(Theme.DIM, Theme.PANEL), r.right());
            hits.add(new Hit(new Rect(x, r.y(), 2, 2), () -> sidebar = true));
            x += 2;
        }
        var group = session.group(selected);
        boolean team = group != null;
        String name = team ? group.name() : selected;
        Color c = team ? Theme.ACCENT : Theme.agentColor(selected);
        put(buf, x, r.y(), " " + (name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT)) + " ", st(Theme.BG, c).bold(), r.right());
        int nx = x + 4;
        put(buf, nx, r.y(), title(selected), base.bold(), r.right() - 30);
        String sub;
        Style subStyle = st(Theme.DIM, Theme.PANEL);
        ChatSession.Live live = session.live(selected);
        String elsewhere = team ? null : session.agentThread(selected);
        if (session.isActive(selected)) {
            String who = live != null ? live.agent() : busyAgentIn(selected);
            String state = live != null ? "typing…" : who.isEmpty() ? "working…" : session.agentState(who) + "…";
            sub = (team && !who.isEmpty() ? clean(who) + " is " : "") + state;
            subStyle = st(Theme.GREEN, Theme.PANEL);
        } else if (elsewhere != null) {
            sub = "busy in the " + title(elsewhere) + " chat · will read your messages after";
            subStyle = st(Theme.AMBER, Theme.PANEL);
        } else if (team) {
            StringBuilder sb = new StringBuilder();
            for (String m : group.members()) {
                sb.append(sb.isEmpty() ? "" : ", ").append(clean(m));
            }
            sb.append(sb.isEmpty() ? "you" : ", you");
            sub = sb.toString() + "   · click for group info";
        } else {
            sub = "online · " + clean(roleOf(selected)) + " · " + clean(modelLabel(selected));
        }
        put(buf, nx, r.y() + 1, sub, subStyle, r.right() - 30);
        hits.add(new Hit(new Rect(x, r.y(), Math.max(1, r.right() - 32 - x), 2), () -> openInfo(ChatInfoView.Mode.INFO)));
        int bx = r.right() - 1;
        String[][] buttons = {{" Help ", "help"}, {" Tasks ", "tasks"}, {" Changes ", "diff"}};
        for (String[] b : buttons) {
            bx -= Wrap.width(b[0]) + 1;
            put(buf, bx, r.y(), b[0], st(Theme.TEXT, Theme.FIELD), r.right());
            String cmd = b[1];
            hits.add(new Hit(new Rect(bx, r.y(), Wrap.width(b[0]), 1), () -> runCommand("/" + cmd)));
        }
        if (session.isActive(selected)) {
            String stop = " ■ Stop ";
            int sx = r.right() - 1 - Wrap.width(stop);
            put(buf, sx, r.y() + 1, stop, st(Theme.TEXT, Theme.DANGER), r.right());
            hits.add(new Hit(new Rect(sx, r.y() + 1, Wrap.width(stop), 1), () -> session.stop(selected)));
        }
    }

    /** The agent currently working in {@code thread}, preferring one that is not just waiting for a teammate. */
    private String busyAgentIn(String thread) {
        String who = "";
        for (Agent a : session.contacts()) {
            if (thread.equals(session.agentThread(a.name()))) {
                if (who.isEmpty() || session.agentState(who).startsWith("waiting for ") && !session.agentState(a.name()).equals("idle")) {
                    who = a.name();
                }
            }
        }
        return who;
    }

    private void drawConversation(Buffer buf, Rect r, List<Message> msgs) {
        int width = r.width() - 1;
        List<Row> rows = chatRows(width, msgs);
        int total = rows.size();
        int viewH = r.height();
        scrollMax = Math.max(0, total - viewH);
        if (scrollOff > 0 && total > lastTotal) {
            scrollOff += total - lastTotal; // keep reading where the user stopped
        }
        lastTotal = total;
        scrollOff = Math.max(0, Math.min(scrollOff, scrollMax));
        int first = Math.max(0, total - viewH - scrollOff);
        boolean empty = msgs.isEmpty() && !session.isActive(selected);
        int yOff = total < viewH ? (empty ? Math.max(0, (viewH - total) / 3) : viewH - total) : 0;
        for (int i = 0; i < viewH && first + i < total; i++) {
            Row row = rows.get(first + i);
            drawSpans(buf, r.x() + row.x(), r.y() + yOff + i, row.spans(), r.x() + width);
        }
        if (scrollMax > 0) {
            int trackX = r.right() - 1;
            scrollTrack = new Rect(trackX, r.y(), 1, viewH);
            int thumb = Math.max(1, viewH * viewH / total);
            int pos = (int) Math.round((double) (scrollMax - scrollOff) / scrollMax * (viewH - thumb));
            for (int i = pos; i < pos + thumb && i < viewH; i++) {
                put(buf, trackX, r.y() + i, "▐", st(Theme.FAINT, Theme.BG), trackX + 1);
            }
        }
        if (scrollOff > 0) {
            String pill = " ⌄ ";
            int px = r.right() - 6;
            int py = r.bottom() - 2;
            put(buf, px, py, pill, st(Theme.DIM, Theme.PANEL).bold(), r.right());
            hits.add(new Hit(new Rect(px, py, 3, 1), () -> scrollOff = 0));
        }
    }

    // ---- conversation rows ----

    private List<Row> chatRows(int width, List<Message> msgs) {
        List<Row> rows = new ArrayList<>();
        if (msgs.isEmpty() && !session.isActive(selected)) {
            welcome(rows, width);
            return rows;
        }
        boolean group = session.group(selected) != null;
        long failed = session.lastFailedMessage();
        java.time.LocalDate day = null;
        Message prev = null;
        rows.add(new Row(0, List.of()));
        for (Message m : msgs) {
            java.time.LocalDate d = m.at().atZone(ZoneId.systemDefault()).toLocalDate();
            if (!d.equals(day)) {
                day = d;
                String label = d.equals(java.time.LocalDate.now()) ? "TODAY" : d.equals(java.time.LocalDate.now().minusDays(1)) ? "YESTERDAY"
                        : DAY.format(d).toUpperCase(Locale.ROOT);
                centred(rows, width, " " + label + " ", st(Theme.DIM, Theme.PILL));
                rows.add(new Row(0, List.of()));
                prev = null;
            }
            boolean sameAuthor = prev != null && prev.kind() == m.kind() && prev.author().equals(m.author())
                    && (m.kind() == ChatSession.Kind.USER || m.kind() == ChatSession.Kind.AGENT);
            if (m.kind() == ChatSession.Kind.ACTIVITY && !settings().flag(dev.buildcli.application.Settings.SHOW_ACTIVITY)) {
                continue;
            }
            boolean bothQuiet = prev != null && isQuiet(prev) && isQuiet(m);
            if (prev != null && !sameAuthor && !bothQuiet && !settings().flag(dev.buildcli.application.Settings.COMPACT)) {
                rows.add(new Row(0, List.of()));
            }
            switch (m.kind()) {
                case USER -> userBubble(rows, width, m, m.id() == failed);
                case AGENT -> agentBubble(rows, width, group && !sameAuthor ? clean(m.author()) : null, m.text(), TIME.format(m.at()), false);
                case ACTIVITY -> activity(rows, width, m);
                case SYSTEM -> centred(rows, width, " " + clean(m.text()).replace('\n', ' ') + " ", st(Theme.DIM, Theme.PILL));
                case ERROR -> problem(rows, width, m.text(), failed);
                default -> { }
            }
            prev = m;
        }
        if (session.isActive(selected)) {
            rows.add(new Row(0, List.of()));
            ChatSession.Live live = session.live(selected);
            if (live != null) {
                agentBubble(rows, width, group ? clean(live.agent()) : null, live.text() + " ▍", "", true);
            } else if (session.pending() == null) {
                String who = busyAgentIn(selected);
                typingDots(rows, group ? who : "", who.isEmpty() ? "" : session.agentState(who));
            }
        }
        rows.add(new Row(0, List.of()));
        return rows;
    }

    private static boolean isQuiet(Message m) {
        return m.kind() == ChatSession.Kind.ACTIVITY || m.kind() == ChatSession.Kind.SYSTEM;
    }

    private static void centred(List<Row> rows, int width, String text, Style style) {
        for (String line : Wrap.lines(text, Math.max(10, width - 10))) {
            String l = line.startsWith(" ") ? line : " " + line;
            l = l.endsWith(" ") ? l : l + " ";
            rows.add(new Row(Math.max(0, (width - Wrap.width(l)) / 2), List.of(new Span(l, style))));
        }
    }

    private void typingDots(List<Row> rows, String who, String state) {
        int phase = (int) (System.currentTimeMillis() / 300 % 3);
        StringBuilder dots = new StringBuilder(" ");
        for (int i = 0; i < 3; i++) {
            dots.append(i == phase ? "●" : "•").append(' ');
        }
        List<Span> spans = new ArrayList<>();
        spans.add(new Span(" ", st(Theme.TEXT, Theme.THEM)));
        if (!who.isEmpty()) {
            spans.add(new Span(clean(who) + " ", st(Theme.agentColor(who), Theme.THEM).bold()));
        }
        spans.add(new Span(dots.toString(), st(Theme.DIM, Theme.THEM)));
        if (state.startsWith("waiting") || state.equals("reading") || state.equals("working")) {
            spans.add(new Span(state + " ", st(Theme.DIM, Theme.THEM).italic()));
        }
        rows.add(new Row(2, spans));
    }

    private void welcome(List<Row> rows, int width) {
        var g = session.group(selected);
        boolean team = g != null;
        String who = team ? clean(g.name()) : clean(selected);
        centred(rows, width, "Start a conversation with " + who, st(Theme.TEXT, Theme.BG).bold());
        rows.add(new Row(0, List.of()));
        String sub = team ? (g.admins().isEmpty() ? "No admin yet" : clean(String.join(", ", g.admins())) + " (admin) answers messages that "
                + "mention nobody") + ". Type @ to talk to someone, or mention two people to ask both."
                : clean(roleOf(selected)) + ". Messages here go straight to " + clean(selected) + ".";
        centred(rows, width, sub, st(Theme.DIM, Theme.BG));
        rows.add(new Row(0, List.of()));
        rows.add(new Row(0, List.of()));
        String[][] ideas = team
                ? new String[][] {{"Review my uncommitted changes", "Review my uncommitted changes and point out risks."},
                    {"Explain this project", "Explain how this project is organised and where to start."},
                    {"Add missing tests", "Find important code without tests and add unit tests for it."},
                    {"Show my changes", "/diff"}}
                : new String[][] {{"What can you do?", "What can you do for me in this project, with your tools and permissions?"},
                    {"Look at my changes", "Look at my uncommitted changes and tell me what you think."}};
        int boxW = Math.min(width - 4, 56);
        int x = Math.max(0, (width - boxW) / 2);
        for (String[] idea : ideas) {
            Runnable act = idea[1].startsWith("/") ? () -> runCommand(idea[1]) : () -> input.set(idea[1]);
            String label = "  " + idea[0];
            rows.add(new Row(x, List.of(new Span(label + " ".repeat(Math.max(1, boxW - Wrap.width(label) - 2)) + "› ", st(Theme.TEXT, Theme.PANEL), act))));
            rows.add(new Row(0, List.of()));
        }
    }

    private int maxBody(int width) {
        return Math.max(16, Math.min(width - 10, Math.max(40, (int) (width * 0.72))));
    }

    private void userBubble(List<Row> rows, int width, Message m, boolean retryable) {
        Style base = st(Theme.TEXT, Theme.ME);
        Style code = st(Theme.CODE_TEXT, Theme.CODE);
        int max = maxBody(width);
        List<List<Span>> body = new ArrayList<>();
        for (Attachment a : m.attachments()) {
            attachmentLines(body, a, base, max);
        }
        if (!m.text().isEmpty()) {
            body.addAll(Styled.lines(clean(m.text()), max, base, base.bold(), code));
        }
        List<Span> footer = new ArrayList<>();
        Style meta = st(Theme.ON_ME_DIM, Theme.ME);
        footer.add(new Span(TIME.format(m.at()) + " ", meta));
        switch (m.state()) {
            case QUEUED -> footer.add(new Span("✓", meta));
            case RUNNING -> footer.add(new Span("✓✓", st(Theme.BLUE, Theme.ME)));
            case DONE -> footer.add(new Span("✓✓", st(Theme.BLUE, Theme.ME)));
            case FAILED -> {
                footer.add(new Span("! not sent ", st(Theme.RED, Theme.ME).bold()));
                if (retryable) {
                    footer.add(new Span(" Retry ", st(Theme.BG, Theme.AMBER).bold(), () -> session.retry(m.id())));
                }
            }
            default -> { }
        }
        bubble(rows, width, true, null, null, body, footer, base, code);
    }

    private void agentBubble(List<Row> rows, int width, String author, String text, String time, boolean live) {
        Style base = st(Theme.TEXT, Theme.THEM);
        Style code = st(Theme.CODE_TEXT, Theme.CODE);
        List<List<Span>> body = Styled.lines(clean(text), maxBody(width), base, base.bold(), code);
        List<Span> footer = time.isEmpty() ? List.of() : List.of(new Span(time, st(Theme.DIM, Theme.THEM)));
        bubble(rows, width, false, author, author == null ? null : Theme.agentColor(author), body, footer, base, code);
    }

    private void problem(List<Row> rows, int width, String text, long failed) {
        Style base = st(Theme.TEXT, Theme.ERROR_BG);
        List<List<Span>> body = Styled.lines(clean(text), maxBody(width), base, base.bold(), base);
        List<Span> footer = new ArrayList<>();
        if (failed >= 0) {
            footer.add(new Span(" Retry ", st(Theme.BG, Theme.AMBER).bold(), () -> session.retry(failed)));
        }
        bubble(rows, width, false, "Something went wrong", Theme.RED, body, footer, base, base);
    }

    private void activity(List<Row> rows, int width, Message m) {
        Color c = switch (m.state()) {
            case DONE -> Theme.ACCENT;
            case FAILED -> Theme.RED;
            default -> Theme.AMBER;
        };
        String glyph = switch (m.state()) {
            case DONE -> "✓";
            case FAILED -> "✗";
            default -> "◌";
        };
        String text = clean(m.author()) + " " + clean(m.text()).replace('\n', ' ');
        String shown = CharWidth.truncateWithEllipsis(text, Math.max(10, Math.min(width - 14, 76)), CharWidth.TruncatePosition.END);
        int w = Wrap.width(shown) + 4;
        rows.add(new Row(Math.max(0, (width - w) / 2), List.of(new Span(" " + glyph + " ", st(c, Theme.PILL)),
                new Span(shown + " ", st(m.state() == State.FAILED ? Theme.RED : Theme.DIM, Theme.PILL)))));
    }

    /** A bubble: optional author line, body, a footer aligned right (time, ticks), with one column of padding. */
    private void bubble(List<Row> rows, int width, boolean mine, String author, Color authorColor, List<List<Span>> body,
            List<Span> footer, Style base, Style code) {
        int inner = 0;
        for (List<Span> line : body) {
            inner = Math.max(inner, Styled.width(line));
        }
        if (author != null) {
            inner = Math.max(inner, Wrap.width(author));
        }
        // the time sits on the last line when it fits, as in WhatsApp; otherwise on its own line
        int footW = Styled.width(footer);
        List<Span> lastLine = body.isEmpty() ? List.of() : body.get(body.size() - 1);
        boolean inline = !footer.isEmpty() && Styled.width(lastLine) + 2 + footW <= Math.max(inner, maxBody(width)) && !isCode(lastLine, code);
        inner = Math.max(inner, inline ? Styled.width(lastLine) + 2 + footW : footW);
        inner = Math.max(inner, 6);
        int x = mine ? Math.max(0, width - inner - 4) : 2;
        if (author != null) {
            rows.add(line(x, List.of(new Span(author, st(authorColor, base.bg().orElse(Theme.THEM)).bold())), inner, base));
        }
        for (int i = 0; i < body.size(); i++) {
            List<Span> l = body.get(i);
            if (inline && i == body.size() - 1) {
                List<Span> withTime = new ArrayList<>(l);
                withTime.add(new Span(" ".repeat(inner - Styled.width(l) - footW), base));
                withTime.addAll(footer);
                rows.add(line(x, withTime, inner, base));
            } else {
                rows.add(line(x, l, inner, isCode(l, code) ? code : base));
            }
        }
        if (!inline && !footer.isEmpty()) {
            List<Span> f = new ArrayList<>();
            f.add(new Span(" ".repeat(Math.max(0, inner - footW)), base));
            f.addAll(footer);
            rows.add(line(x, f, inner, base));
        }
    }

    private static boolean isCode(List<Span> line, Style code) {
        if (line.isEmpty()) {
            return false;
        }
        for (Span s : line) {
            if (!s.style().equals(code)) {
                return false;
            }
        }
        return true;
    }

    /** One bubble row; {@code fillStyle} pads the content (code lines keep their darker background to the edge). */
    private static Row line(int x, List<Span> content, int inner, Style fillStyle) {
        List<Span> spans = new ArrayList<>();
        Style edge = Style.create().fg(fillStyle.fg().orElse(Theme.TEXT)).bg(fillStyle == null ? Theme.THEM : fillStyle.bg().orElse(Theme.THEM));
        spans.add(new Span(" ", edge));
        spans.addAll(content);
        spans.add(new Span(" ".repeat(Math.max(0, inner - Styled.width(content))) + " ", edge));
        return new Row(x, spans);
    }

    private void attachmentLines(List<List<Span>> body, Attachment a, Style base, int max) {
        String size = a.size() >= 1024 * 1024 ? a.size() / 1024 / 1024 + " MB" : Math.max(1, a.size() / 1024) + " KB";
        if (a.kind() == Attachment.Kind.IMAGE) {
            Previews.Image img = Previews.image(a);
            if (img != null) {
                int cols = Math.min(img.width(), Math.min(max, 40));
                int pixRows = Math.max(1, Math.round((float) img.height() * cols / img.width()));
                for (int py = 0; py < pixRows; py += 2) {
                    List<Span> line = new ArrayList<>();
                    for (int cx = 0; cx < cols; cx++) {
                        int sx = Math.min(img.width() - 1, cx * img.width() / cols);
                        int top = img.pixels()[Math.min(img.height() - 1, py * img.height() / pixRows) * img.width() + sx];
                        int bottom = img.pixels()[Math.min(img.height() - 1, (py + 1) * img.height() / pixRows) * img.width() + sx];
                        line.add(new Span("▀", Style.create().fg(Color.rgb(top >> 16 & 255, top >> 8 & 255, top & 255))
                                .bg(Color.rgb(bottom >> 16 & 255, bottom >> 8 & 255, bottom & 255))));
                    }
                    body.add(line);
                }
            }
            body.add(List.of(new Span("▣ " + clean(a.name()) + " · " + size, st(Theme.ON_ME_DIM, Theme.ME))));
        } else {
            Previews.Audio audio = Previews.audio(a);
            String d = audio == null ? "" : Previews.duration(audio.seconds());
            List<Span> l = new ArrayList<>();
            l.add(new Span("▶ ", st(Theme.TEXT, Theme.ME).bold()));
            l.add(new Span(audio != null && !audio.waveform().isEmpty() ? audio.waveform() : "━━━━━━━━━━━━━━━━━━", st(Theme.BLUE, Theme.ME)));
            l.add(new Span("  " + (d.isEmpty() ? size : d), st(Theme.ON_ME_DIM, Theme.ME)));
            body.add(l);
            body.add(List.of(new Span("♪ " + clean(a.name()), st(Theme.ON_ME_DIM, Theme.ME))));
        }
    }

    // ---- chips and the input bar ----

    private void drawChips(Buffer buf, Rect r) {
        fill(buf, r, st(Theme.TEXT, Theme.PANEL));
        int x = r.x() + 2;
        for (int i = 0; i < attachments.size(); i++) {
            Attachment a = attachments.get(i);
            String label = (a.kind() == Attachment.Kind.IMAGE ? " ▣ " : " ♪ ") + clean(a.name()) + " ";
            int w = put(buf, x, r.y(), label, st(Theme.TEXT, Theme.FIELD), r.right());
            int idx = i;
            int cw = put(buf, x + w, r.y(), "✕ ", st(Theme.DIM, Theme.FIELD), r.right());
            hits.add(new Hit(new Rect(x + w, r.y(), cw, 1), () -> attachments.remove(idx)));
            x += w + cw + 1;
        }
    }

    private void drawInputBar(Frame frame, Buffer buf, Rect r, List<Wrap.Segment> segs, int visibleRows) {
        fill(buf, r, st(Theme.TEXT, Theme.PANEL));
        Style field = st(Theme.TEXT, Theme.FIELD);
        int fx = r.x() + 5;
        int fw = inputWidth + 2;
        fill(buf, new Rect(fx, r.y() + 1, fw, visibleRows), field);
        put(buf, r.x() + 2, r.y() + visibleRows, "+", st(Theme.DIM, Theme.PANEL).bold(), r.right());
        hits.add(new Hit(new Rect(r.x() + 1, r.y() + 1, 3, visibleRows), () -> input.set("/attach ")));
        String src = input.text();
        int[] cur = input.cursorPosition(inputWidth);
        inputFirstRow = Math.max(0, Math.min(cur[0] - visibleRows + 1, segs.size() - visibleRows));
        int tx = fx + 1;
        for (int i = 0; i < visibleRows; i++) {
            int y = r.y() + 1 + i;
            Wrap.Segment s = segs.get(inputFirstRow + i);
            if (src.isEmpty()) {
                put(buf, tx, y, session.busy() ? "Type a message (it waits its turn)" : "Type a message", st(Theme.DIM, Theme.FIELD), tx + inputWidth);
            } else {
                String line = src.substring(s.start(), s.end()).stripTrailing();
                Style ls = line.startsWith("/") && i == 0 && inputFirstRow == 0 ? st(Theme.BLUE, Theme.FIELD) : field;
                put(buf, tx, y, line, ls, tx + inputWidth);
            }
        }
        boolean canSend = !src.isBlank() || !attachments.isEmpty();
        int sx = fx + fw + 1;
        put(buf, sx, r.y() + visibleRows, " ➤ ", canSend ? st(Theme.BG, Theme.ACCENT).bold() : st(Theme.DIM, Theme.PANEL), r.right());
        hits.add(new Hit(new Rect(sx, r.y() + visibleRows, 3, 1), this::submit));
        inputTextArea = new Rect(tx, r.y() + 1, inputWidth, visibleRows);
        if (session.pending() == null) {
            frame.setCursorPosition(tx + Math.min(cur[1], inputWidth), r.y() + 1 + (cur[0] - inputFirstRow));
        }
    }

    // ---- the / and @ menus ----

    private record MenuItem(String label, String detail, String right, Runnable accept) {}

    /** The command menu while typing "/word", or the mention menu while typing "@name"; empty when neither. */
    private List<MenuItem> menu() {
        String text = input.text();
        String key;
        List<MenuItem> items = new ArrayList<>();
        if (text.startsWith("/") && !text.contains(" ") && !text.contains("\n") && input.cursor() == text.length()) {
            key = text;
            String typed = text.substring(1).toLowerCase(Locale.ROOT);
            for (Command c : COMMANDS) {
                if (c.name().startsWith(typed)) {
                    items.add(new MenuItem("/" + c.name() + (c.arg().isEmpty() ? "" : " " + c.arg()), c.description(), c.shortcut(),
                            () -> acceptCommand(c)));
                }
            }
        } else {
            String prefix = input.mentionPrefix();
            if (prefix == null) {
                return List.of();
            }
            key = "@" + prefix;
            var g = session.group(selected);
            List<Agent> ordered = new ArrayList<>();
            for (Agent a : session.contacts()) {
                if (g == null || g.has(a.name())) {
                    ordered.add(a);
                }
            }
            for (Agent a : session.contacts()) {
                if (g != null && !g.has(a.name())) {
                    ordered.add(a);
                }
            }
            for (Agent a : ordered) {
                if (a.name().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                    String tag = g == null ? "" : !g.has(a.name()) ? "not in this group" : g.isAdmin(a.name()) ? "admin" : "";
                    items.add(new MenuItem("@" + a.name(), a.role(), tag, () -> completeMention(a.name())));
                }
            }
        }
        return key.equals(menuDismissedFor) ? List.of() : items;
    }

    private void acceptCommand(Command c) {
        if (c.arg().isEmpty() || c.arg().startsWith("[")) {
            input.clear();
            runCommand("/" + c.name());
        } else {
            input.set("/" + c.name() + " ");
        }
    }

    private void drawMenu(Buffer buf, Rect box) {
        List<MenuItem> items = menu();
        if (items.isEmpty()) {
            return;
        }
        menuIndex = Math.max(0, Math.min(menuIndex, items.size() - 1));
        int h = Math.min(items.size(), MENU_ROWS);
        int first = Math.max(0, Math.min(menuIndex - h + 1, items.size() - h));
        int w = box.width();
        int y0 = box.y() - h - 1;
        Style bg = st(Theme.TEXT, Theme.DIALOG);
        fill(buf, new Rect(box.x(), y0, w, h + 1), bg);
        put(buf, box.x() + 2, y0, items.get(0).label().startsWith("/") ? "Commands" : "Mention a teammate", st(Theme.DIM, Theme.DIALOG), box.right());
        int labelW = 0;
        for (MenuItem it : items) {
            labelW = Math.max(labelW, Wrap.width(it.label()));
        }
        for (int i = 0; i < h; i++) {
            MenuItem it = items.get(first + i);
            boolean sel = first + i == menuIndex;
            Color rowBg = sel ? Theme.SELECTED : Theme.DIALOG;
            Rect row = new Rect(box.x(), y0 + 1 + i, w, 1);
            fill(buf, row, st(Theme.TEXT, rowBg));
            put(buf, row.x() + 2, row.y(), it.label(), st(Theme.TEXT, rowBg).bold(), row.right() - 1);
            put(buf, row.x() + 4 + labelW, row.y(), clean(it.detail()), st(Theme.DIM, rowBg), row.right() - 10);
            put(buf, row.right() - 2 - Wrap.width(it.right()), row.y(), it.right(), st(Theme.DIM, rowBg), row.right() - 1);
            int idx = first + i;
            hits.add(new Hit(row, () -> {
                menuIndex = idx;
                it.accept().run();
            }));
        }
    }

    private void insertMention(String name) {
        String t = input.text();
        if (!t.isEmpty() && !Character.isWhitespace(t.charAt(Math.max(0, input.cursor() - 1)))) {
            input.insert(" ");
        }
        input.insert("@" + name + " ");
    }

    private void completeMention(String name) {
        input.completeMention(name);
        menuDismissedFor = null;
    }

    // ---- the viewer (diff, file, tasks, help) ----

    private void open(View v) {
        view = v;
        viewScroll = 0;
    }

    private void drawViewer(Buffer buf, Rect r) {
        Style base = st(Theme.TEXT, Theme.BG);
        Style bar = st(Theme.TEXT, Theme.SIDEBAR);
        fill(buf, new Rect(r.x(), r.y(), r.width(), 1), bar);
        put(buf, r.x() + 2, r.y(), clean(view.title()), bar.bold(), r.right() - 12);
        String close = " ✕ Esc ";
        int cx = r.right() - Wrap.width(close) - 1;
        put(buf, cx, r.y(), close, st(Theme.DIM, Theme.SIDEBAR), r.right());
        hits.add(new Hit(new Rect(cx, r.y(), Wrap.width(close), 1), () -> view = null));
        viewHeight = Math.max(1, r.height() - 2);
        List<String> lines = view.lines();
        int max = Math.max(0, lines.size() - viewHeight);
        viewScroll = Math.max(0, Math.min(viewScroll, max));
        int gutter = view.numbered() ? Integer.toString(lines.size()).length() + 2 : 0;
        for (int i = 0; i < viewHeight && viewScroll + i < lines.size(); i++) {
            int n = viewScroll + i;
            String l = clean(lines.get(n)).replace("\t", "    ");
            int y = r.y() + 1 + i;
            int x = r.x() + 2;
            if (view.numbered()) {
                String num = String.format("%" + (gutter - 1) + "d ", n + 1);
                put(buf, x, y, num, st(Theme.FAINT, Theme.BG), r.right());
                x += gutter;
            }
            Style s = base;
            if (view.diff()) {
                if (l.startsWith("diff --git")) {
                    fill(buf, new Rect(r.x(), y, r.width(), 1), st(Theme.TEXT, Theme.ME));
                    s = st(Theme.TEXT, Theme.ME).bold();
                } else if (l.startsWith("+++") || l.startsWith("---") || l.startsWith("index ")) {
                    s = st(Theme.DIM, Theme.BG);
                } else if (l.startsWith("@@")) {
                    s = st(Theme.BLUE, Theme.BG);
                } else if (l.startsWith("+")) {
                    fill(buf, new Rect(r.x(), y, r.width(), 1), st(Theme.TEXT, Theme.ADD_BG));
                    s = st(Theme.ADD_FG, Theme.ADD_BG);
                } else if (l.startsWith("-")) {
                    fill(buf, new Rect(r.x(), y, r.width(), 1), st(Theme.TEXT, Theme.DEL_BG));
                    s = st(Theme.DEL_FG, Theme.DEL_BG);
                }
            }
            put(buf, x, y, l, s, r.right() - 1);
        }
        String foot = lines.isEmpty() ? "empty" : (viewScroll + 1) + "–" + Math.min(lines.size(), viewScroll + viewHeight) + " of " + lines.size()
                + "   ↑↓ PgUp PgDn scroll" + (view.diff() ? " · [ ] previous/next file" : "") + " · Esc close";
        fill(buf, new Rect(r.x(), r.bottom() - 1, r.width(), 1), bar);
        put(buf, r.x() + 2, r.bottom() - 1, foot, st(Theme.DIM, Theme.SIDEBAR), r.right());
    }

    private EventResult viewerKey(KeyEvent key) {
        KeyCode code = key.code();
        char ch = code == KeyCode.CHAR ? key.character() : 0;
        switch (code) {
            case ESCAPE -> view = null;
            case UP -> viewScroll--;
            case DOWN -> viewScroll++;
            case PAGE_UP -> viewScroll -= viewHeight - 1;
            case PAGE_DOWN -> viewScroll += viewHeight - 1;
            case HOME -> viewScroll = 0;
            case END -> viewScroll = Integer.MAX_VALUE / 2;
            case CHAR -> {
                switch (ch) {
                    case 'q' -> view = null;
                    case ' ', 'j' -> viewScroll += ch == ' ' ? viewHeight - 1 : 1;
                    case 'k' -> viewScroll--;
                    case ']' -> jumpFile(1);
                    case '[' -> jumpFile(-1);
                    default -> { }
                }
            }
            default -> { }
        }
        return EventResult.HANDLED;
    }

    private void jumpFile(int dir) {
        List<String> lines = view.lines();
        for (int i = viewScroll + dir; i >= 0 && i < lines.size(); i += dir) {
            if (lines.get(i).startsWith("diff --git")) {
                viewScroll = i;
                return;
            }
        }
    }

    // ---- dialogs ----

    private void drawDialog(Buffer buf, Rect r) {
        ChatSession.Pending p = session.pending();
        int w = Math.min(r.width() - 4, 100);
        Style base = st(Theme.TEXT, Theme.DIALOG);
        List<List<Span>> body = new ArrayList<>();
        String title;
        List<String[]> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (p instanceof ChatSession.Pending.Approval a) {
            title = clean(a.request().agent()) + " asks for approval" + where(p);
            body.add(List.of(new Span(clean(a.request().summary()), base.bold())));
            body.add(List.of());
            int limit = Math.max(3, r.height() - 12);
            List<String> lines = clean(a.request().detail()).lines().toList();
            for (int i = 0; i < Math.min(limit, lines.size()); i++) {
                String l = lines.get(i);
                Color c = l.startsWith("+++") || l.startsWith("---") ? Theme.DIM : l.startsWith("@@") ? Theme.BLUE
                        : l.startsWith("+") ? Theme.ADD_FG : l.startsWith("-") ? Theme.DEL_FG : Theme.TEXT;
                body.add(List.of(new Span(CharWidth.substringByWidth(l.replace("\t", "    "), w - 4), st(c, Theme.DIALOG))));
            }
            if (lines.size() > limit) {
                body.add(List.of(new Span("… " + (lines.size() - limit) + " more lines", st(Theme.DIM, Theme.DIALOG))));
            }
            buttons.add(new String[] {" Approve  Y ", "primary"});
            actions.add(() -> a.answer().complete(true));
            buttons.add(new String[] {" Deny  N ", "plain"});
            actions.add(() -> a.answer().complete(false));
        } else {
            var e = (ChatSession.Pending.Escalation) p;
            title = clean(e.agent()) + " is stuck (task #" + e.taskId() + ")" + where(p);
            body.addAll(Styled.lines(clean(e.objective()), w - 4, base, base.bold(), base));
            body.add(List.of());
            body.addAll(Styled.lines("It failed after the automatic retries: " + clean(e.reason()), w - 4, st(Theme.RED, Theme.DIALOG),
                    st(Theme.RED, Theme.DIALOG).bold(), base));
            buttons.add(new String[] {" Retry  R ", "primary"});
            actions.add(() -> e.answer().complete(EscalationChoice.RETRY));
            buttons.add(new String[] {" Skip task  S ", "plain"});
            actions.add(() -> e.answer().complete(EscalationChoice.SKIP));
            buttons.add(new String[] {" Stop everything  A ", "danger"});
            actions.add(() -> e.answer().complete(EscalationChoice.ABORT));
        }
        int h = body.size() + 5;
        int x = r.x() + (r.width() - w) / 2;
        int y = r.y() + Math.max(1, (r.height() - h) / 2);
        fill(buf, new Rect(x, y, w, h), base);
        Style border = st(Theme.FAINT, Theme.DIALOG);
        put(buf, x, y, "╭" + "─".repeat(w - 2) + "╮", border, x + w);
        for (int i = 1; i < h - 1; i++) {
            put(buf, x, y + i, "│", border, x + w);
            put(buf, x + w - 1, y + i, "│", border, x + w);
        }
        put(buf, x, y + h - 1, "╰" + "─".repeat(w - 2) + "╯", border, x + w);
        put(buf, x + 2, y, " " + title + " ", base.bold(), x + w - 2);
        for (int i = 0; i < body.size(); i++) {
            drawSpans(buf, x + 2, y + 1 + i, body.get(i), x + w - 2);
        }
        int bx = x + 2;
        for (int i = 0; i < buttons.size(); i++) {
            Style bs = switch (buttons.get(i)[1]) {
                case "primary" -> st(Theme.BG, Theme.TEXT).bold();
                case "danger" -> st(Theme.TEXT, Theme.DANGER).bold();
                default -> st(Theme.TEXT, Theme.FIELD);
            };
            int bw = put(buf, bx, y + h - 2, buttons.get(i)[0], bs, x + w - 1);
            hits.add(new Hit(new Rect(bx, y + h - 2, bw, 1), actions.get(i)));
            bx += bw + 2;
        }
    }

    private String where(ChatSession.Pending p) {
        String chat = p.thread().equals(ChatSession.EVERYWHERE) ? title(ChatSession.TEAM) : title(p.thread());
        int n = session.pendingCount();
        return " · " + chat + " chat" + (n > 1 ? " · 1 of " + n : "");
    }

    // ---- keyboard ----

    @Override
    public EventResult handleKeyEvent(KeyEvent key, boolean focused) {
        boolean ctrl = key.hasCtrl();
        boolean alt = key.hasAlt();
        KeyCode code = key.code();
        char ch = code == KeyCode.CHAR ? Character.toLowerCase(key.character()) : 0;

        if (settingsOpen) {
            if (ctrl && ch == 'c') {
                settingsOpen = false;
            } else {
                settingsView.key(key);
            }
            return EventResult.HANDLED;
        }
        if (infoOpen) {
            if (ctrl && ch == 'c') {
                infoOpen = false;
            } else {
                infoView.key(key);
            }
            return EventResult.HANDLED;
        }
        if (code == KeyCode.F2) {
            settingsOpen = true;
            return EventResult.HANDLED;
        }
        if (ctrl && ch == 'c') {
            if (view != null) {
                view = null;
            } else if (!input.isEmpty()) {
                input.clear();
            } else {
                quit.run();
            }
            return EventResult.HANDLED;
        }
        if (ctrl && ch == 'x') {
            session.stop(selected);
            return EventResult.HANDLED;
        }
        ChatSession.Pending p = session.pending();
        if (p != null && code == KeyCode.CHAR && (input.isEmpty() || ctrl)) {
            if (p instanceof ChatSession.Pending.Approval a && (ch == 'y' || ch == 'n')) {
                a.answer().complete(ch == 'y');
                return EventResult.HANDLED;
            }
            if (p instanceof ChatSession.Pending.Escalation e && "rsa".indexOf(ch) >= 0) {
                e.answer().complete(ch == 'r' ? EscalationChoice.RETRY : ch == 's' ? EscalationChoice.SKIP : EscalationChoice.ABORT);
                return EventResult.HANDLED;
            }
        }
        if (ctrl && code == KeyCode.CHAR) {
            switch (ch) {
                case 'g' -> {
                    runCommand("/diff");
                    return EventResult.HANDLED;
                }
                case 'o' -> {
                    view = null;
                    input.set("/open ");
                    return EventResult.HANDLED;
                }
                case 't' -> {
                    runCommand("/tasks");
                    return EventResult.HANDLED;
                }
                case 'b' -> {
                    toggleSidebar();
                    return EventResult.HANDLED;
                }
                case 'l' -> {
                    session.clearMessages();
                    return EventResult.HANDLED;
                }
                default -> { }
            }
        }
        if (view != null) {
            return viewerKey(key);
        }
        if (switchChat(key)) {
            return EventResult.HANDLED;
        }
        List<MenuItem> items = menu();
        if (!items.isEmpty()) {
            switch (code) {
                case UP -> {
                    menuIndex = (menuIndex - 1 + items.size()) % items.size();
                    return EventResult.HANDLED;
                }
                case DOWN -> {
                    menuIndex = (menuIndex + 1) % items.size();
                    return EventResult.HANDLED;
                }
                case TAB -> {
                    items.get(Math.min(menuIndex, items.size() - 1)).accept().run();
                    return EventResult.HANDLED;
                }
                case ENTER -> {
                    String typed = input.mentionPrefix();
                    boolean complete = typed != null && items.size() == 1 && items.get(0).label().equalsIgnoreCase("@" + typed);
                    if (!(alt || key.hasShift()) && !complete) {
                        items.get(Math.min(menuIndex, items.size() - 1)).accept().run();
                        return EventResult.HANDLED;
                    }
                }
                case ESCAPE -> {
                    menuDismissedFor = input.text().startsWith("/") ? input.text() : "@" + input.mentionPrefix();
                    return EventResult.HANDLED;
                }
                default -> { }
            }
        }
        switch (code) {
            case ENTER -> {
                boolean modified = alt || ctrl || key.hasShift();
                boolean enterSends = settings().flag(dev.buildcli.application.Settings.ENTER_SENDS);
                if (endsWithBackslash() && enterSends && !modified) {
                    input.backspace();
                    input.insert("\n");
                } else if (enterSends != modified) {
                    submit();
                } else {
                    input.insert("\n");
                }
            }
            case BACKSPACE -> {
                if (alt || ctrl) {
                    input.deleteWordBefore();
                } else {
                    input.backspace();
                }
            }
            case DELETE -> input.delete();
            case LEFT -> {
                if (ctrl || alt) {
                    input.wordLeft();
                } else {
                    input.left();
                }
            }
            case RIGHT -> {
                if (ctrl || alt) {
                    input.wordRight();
                } else {
                    input.right();
                }
            }
            case HOME -> input.home();
            case END -> input.end();
            case UP -> {
                if (!input.up(inputWidth) && !input.historyPrevious()) {
                    scroll(1);
                }
            }
            case DOWN -> {
                if (!input.down(inputWidth) && !input.historyNext()) {
                    scroll(-1);
                }
            }
            case PAGE_UP -> scroll(Math.max(3, area.height() / 2));
            case PAGE_DOWN -> scroll(-Math.max(3, area.height() / 2));
            case ESCAPE -> scrollOff = 0;
            case TAB -> { }
            case CHAR -> {
                if (ctrl) {
                    switch (ch) {
                        case 'a' -> input.home();
                        case 'e' -> input.end();
                        case 'u' -> input.clear();
                        case 'w' -> input.deleteWordBefore();
                        case 'j' -> input.insert("\n");
                        case 'd' -> {
                            if (input.isEmpty()) {
                                quit.run();
                            } else {
                                input.delete();
                            }
                        }
                        default -> {
                            return EventResult.UNHANDLED;
                        }
                    }
                } else if (key.character() == '\n') {
                    input.insert("\n");
                } else if (!alt && key.character() >= ' ') {
                    input.insert(key.string());
                }
            }
            default -> {
                return EventResult.UNHANDLED;
            }
        }
        if (code == KeyCode.CHAR || code == KeyCode.BACKSPACE) {
            menuIndex = 0;
            menuDismissedFor = null;
        }
        return EventResult.HANDLED;
    }

    private String firstMention(String text) {
        List<String> m = session.mentioned(text);
        if (!m.isEmpty()) {
            return m.get(0);
        }
        String bare = text.strip();
        return session.contact(bare) != null ? bare : null;
    }

    private void toggleSidebar() {
        sidebar = sideWidth == 0;
        autoSidebar = false;
    }

    /** Alt+Up/Down (or Ctrl+PgUp/PgDn) move through the chat list, Alt+1..9 jump to a chat. */
    private boolean switchChat(KeyEvent key) {
        List<String> threads = threads();
        int i = threads.indexOf(selected);
        boolean alt = key.hasAlt();
        if ((alt && key.code() == KeyCode.UP) || (key.hasCtrl() && key.code() == KeyCode.PAGE_UP)) {
            select(threads.get((i - 1 + threads.size()) % threads.size()));
            return true;
        }
        if ((alt && key.code() == KeyCode.DOWN) || (key.hasCtrl() && key.code() == KeyCode.PAGE_DOWN)) {
            select(threads.get((i + 1) % threads.size()));
            return true;
        }
        if (alt && key.code() == KeyCode.CHAR && key.character() >= '1' && key.character() <= '9') {
            int n = key.character() - '1';
            if (n < threads.size()) {
                select(threads.get(n));
            }
            return true;
        }
        return false;
    }

    private boolean endsWithBackslash() {
        String t = input.text();
        return input.cursor() == t.length() && t.endsWith("\\") && !t.endsWith("\\\\");
    }

    private void scroll(int lines) {
        scrollOff = Math.max(0, Math.min(scrollMax, scrollOff + lines));
    }

    // ---- sending and commands ----

    void submit() {
        String text = input.text().strip();
        if (text.isEmpty() && attachments.isEmpty()) {
            return;
        }
        if (attachments.isEmpty() && runCommand(text)) {
            input.remember(text);
            input.clear();
            return;
        }
        session.submit(text, new ArrayList<>(attachments), selected);
        input.remember(text);
        input.clear();
        attachments.clear();
        scrollOff = 0;
    }

    /** @return true if the text was a slash command (and was handled); "/home/me/file" is not one */
    boolean runCommand(String text) {
        if (!text.startsWith("/")) {
            return false;
        }
        String[] parts = text.substring(1).split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].strip() : "";
        if (COMMANDS.stream().noneMatch(c -> c.name().equals(name))) {
            return false;
        }
        try {
            switch (name) {
                case "diff" -> {
                    List<String> lines = LocalViews.diff(cwd, arg);
                    if (lines.isEmpty()) {
                        session.system("No " + (arg.contains("--staged") ? "staged " : "") + "changes.");
                    } else {
                        open(new View("Changes" + (arg.isEmpty() ? "" : "  " + arg), lines, true, false));
                    }
                }
                case "status" -> open(new View("git status", LocalViews.status(cwd), false, false));
                case "log" -> open(new View("Recent commits", LocalViews.log(cwd), false, false));
                case "open" -> {
                    if (arg.isEmpty()) {
                        input.set("/open ");
                        return true;
                    }
                    Path file = resolve(arg);
                    open(new View(cwd.relativize(file.toAbsolutePath().normalize()).toString(), LocalViews.file(file), false, true));
                }
                case "attach" -> {
                    if (arg.isEmpty()) {
                        input.set("/attach ");
                        return true;
                    }
                    attachments.add(Attachment.of(resolve(arg)));
                }
                case "tasks" -> open(new View("Tasks", taskLines(), false, false));
                case "stop" -> session.stop(selected);
                case "retry" -> {
                    long id = session.lastFailedMessage();
                    if (id < 0 || !session.retry(id)) {
                        session.system("Nothing to retry.");
                    }
                }
                case "queue" -> {
                    if (arg.equals("clear")) {
                        session.clearQueue();
                    } else {
                        session.system(session.queued() == 0 ? "No messages are waiting." : session.queued() + " message(s) waiting their turn.");
                    }
                }
                case "team" -> toggleSidebar();
                case "settings" -> settingsOpen = true;
                case "info" -> openInfo(ChatInfoView.Mode.INFO);
                case "dm" -> {
                    String who = firstMention(arg);
                    if (who == null) {
                        openInfo(ChatInfoView.Mode.NEW_CHAT);
                    } else {
                        session.openDirect(who);
                        select(who);
                    }
                }
                case "newgroup" -> {
                    List<String> members = session.mentioned(arg);
                    String groupName = arg.replaceAll("@[A-Za-z][A-Za-z0-9_-]*", "").strip();
                    if (groupName.isEmpty()) {
                        openInfo(ChatInfoView.Mode.NEW_CHAT);
                    } else {
                        select(session.createGroup(groupName, members));
                    }
                }
                case "add", "remove", "admin", "dismiss" -> {
                    if (session.group(selected) == null) {
                        session.error("Open a group first: this is a direct chat.");
                        return true;
                    }
                    List<String> who = session.mentioned(arg);
                    if (who.isEmpty()) {
                        openInfo(name.equals("add") ? ChatInfoView.Mode.ADD_MEMBER : ChatInfoView.Mode.INFO);
                        return true;
                    }
                    for (String w : who) {
                        switch (name) {
                            case "add" -> session.addMember(selected, w);
                            case "remove" -> session.removeMember(selected, w);
                            case "admin" -> session.setAdmin(selected, w, true);
                            default -> session.setAdmin(selected, w, false);
                        }
                    }
                }
                case "rename" -> {
                    if (session.group(selected) != null && !arg.isBlank()) {
                        session.renameGroup(selected, arg);
                    }
                }
                case "clear" -> session.clearMessages();
                case "help" -> open(new View("Help", help(), false, false));
                case "quit" -> quit.run();
                default -> { }
            }
        } catch (IOException | IllegalArgumentException e) {
            session.error(e.getMessage());
        }
        return true;
    }

    private List<String> taskLines() {
        List<String> out = new ArrayList<>();
        List<Task> tasks = session.tasks(selected);
        if (tasks.isEmpty()) {
            out.add("No tasks yet. Each message becomes a task; a handoff becomes a child task.");
        }
        for (Task t : tasks) {
            int depth = 0;
            Integer parent = t.parentId;
            while (parent != null && depth < 10) {
                int id = parent;
                Task up = tasks.stream().filter(x -> x.id == id).findFirst().orElse(null);
                parent = up == null ? null : up.parentId;
                depth++;
            }
            out.add("  ".repeat(depth) + "#" + t.id + "  " + t.status + "  " + t.from + " → " + t.to + "   " + t.tokens + " tokens"
                    + (t.attempts > 1 ? ", attempt " + t.attempts : ""));
            out.add("  ".repeat(depth) + "    " + t.objective.replace('\n', ' '));
        }
        return out;
    }

    private static List<String> help() {
        List<String> out = new ArrayList<>();
        out.add("Talk to your team like in a chat. Press Enter to send; you can keep typing while they work,");
        out.add("messages wait their turn. @name sends a message straight to one teammate.");
        out.add("");
        out.add("Commands (type / to see the menu)");
        for (Command c : COMMANDS) {
            out.add(String.format("  /%-24s %-52s %s", c.name() + (c.arg().isEmpty() ? "" : " " + c.arg()), c.description(), c.shortcut()));
        }
        out.add("");
        out.add("Keys");
        out.add("  Enter send · Shift+Enter, Alt+Enter, Ctrl+J or a trailing \\ new line · ↑ previous message");
        out.add("  PgUp/PgDn or the mouse wheel scroll · Esc jump to the latest · Ctrl+W delete word · Ctrl+U clear");
        out.add("");
        out.add("Mouse");
        out.add("  Click teammates to mention them, menu items, buttons and the ✕ on attachments.");
        out.add("  Drag the scrollbar. To select text for copying, hold Shift (Option on macOS) while dragging.");
        out.add("");
        out.add("Attachments");
        out.add("  Drop or paste the path of an image (png, jpg, gif, webp) or audio file (wav, mp3, m4a, ogg, flac),");
        out.add("  or use /attach <file>. The model must support images or audio for it to be understood.");
        return out;
    }

    private Path resolve(String pathText) {
        String s = pathText.strip();
        if (s.length() > 1 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.startsWith("file://")) {
            try {
                return Path.of(new URI(s));
            } catch (Exception e) {
                s = s.substring(7);
            }
        }
        if (s.startsWith("~")) {
            s = System.getProperty("user.home") + s.substring(1);
        }
        if (!s.matches("^[A-Za-z]:\\\\.*")) {
            s = s.replace("\\ ", " ");
        }
        return cwd.resolve(s);
    }

    // ---- paste and mouse ----

    @Override
    public EventResult handlePasteEvent(PasteEvent paste) {
        if (infoOpen) {
            infoView.paste(paste.text());
            return EventResult.HANDLED;
        }
        if (settingsOpen) {
            settingsView.paste(paste.text());
            return EventResult.HANDLED;
        }
        List<Attachment> files = filesIn(paste.text());
        if (!files.isEmpty()) {
            attachments.addAll(files);
        } else {
            input.insert(paste.text());
        }
        return EventResult.HANDLED;
    }

    /** Terminals paste the path of a dropped file; if every pasted line is an image or audio file, attach them. */
    private List<Attachment> filesIn(String pasted) {
        List<Attachment> out = new ArrayList<>();
        for (String line : pasted.strip().split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                Path p = resolve(line);
                if (!Files.isRegularFile(p)) {
                    return List.of();
                }
                out.add(Attachment.of(p));
            } catch (IllegalArgumentException e) {
                return List.of();
            }
        }
        return out;
    }

    @Override
    public EventResult handleMouseEvent(MouseEvent m) {
        MouseEventKind kind = m.kind();
        boolean inPane = !(sideWidth > 0 && m.x() < area.x() + sideWidth);
        if (settingsOpen && inPane) {
            settingsView.mouse(m);
            return EventResult.HANDLED;
        }
        if (infoOpen && inPane) {
            infoView.mouse(m);
            return EventResult.HANDLED;
        }
        if (kind == MouseEventKind.SCROLL_UP || kind == MouseEventKind.SCROLL_DOWN) {
            int d = kind == MouseEventKind.SCROLL_UP ? 3 : -3;
            if (view != null) {
                viewScroll -= d;
            } else {
                scroll(d);
            }
            return EventResult.HANDLED;
        }
        if (kind == MouseEventKind.RELEASE) {
            draggingScrollbar = false;
            return EventResult.HANDLED;
        }
        boolean onTrack = view == null && scrollTrack.width() > 0 && scrollTrack.contains(m.x(), m.y());
        if ((kind == MouseEventKind.PRESS && m.isLeftButton() && onTrack) || (kind == MouseEventKind.DRAG && draggingScrollbar)) {
            draggingScrollbar = true;
            int rel = Math.max(0, Math.min(scrollTrack.height() - 1, m.y() - scrollTrack.y()));
            scrollOff = scrollMax - (int) Math.round((double) rel / Math.max(1, scrollTrack.height() - 1) * scrollMax);
            scrollOff = Math.max(0, Math.min(scrollMax, scrollOff));
            return EventResult.HANDLED;
        }
        if (kind == MouseEventKind.PRESS && m.isLeftButton()) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i).rect().contains(m.x(), m.y())) {
                    hits.get(i).action().run();
                    return EventResult.HANDLED;
                }
            }
            if (view == null && session.pending() == null && inputTextArea.contains(m.x(), m.y())) {
                input.moveTo(inputWidth, inputFirstRow + m.y() - inputTextArea.y(), m.x() - inputTextArea.x());
            }
        }
        return EventResult.HANDLED;
    }

    // ---- for tests ----

    InputEditor inputForTest() {
        return input;
    }

    List<Attachment> attachmentsForTest() {
        return attachments;
    }

    String selectedForTest() {
        return selected;
    }

    boolean settingsOpenForTest() {
        return settingsOpen;
    }

    boolean viewerOpenForTest() {
        return view != null;
    }
}
