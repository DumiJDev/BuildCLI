package dev.buildcli.infrastructure.tui;

import static dev.buildcli.infrastructure.tui.Draw.clean;
import static dev.buildcli.infrastructure.tui.Draw.fill;
import static dev.buildcli.infrastructure.tui.Draw.put;
import static dev.buildcli.infrastructure.tui.Draw.putFit;
import static dev.buildcli.infrastructure.tui.Draw.st;
import dev.buildcli.application.ChatSession;
import dev.buildcli.application.ChatSession.Message;
import dev.buildcli.application.ChatSession.State;
import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Attachment;
import dev.buildcli.infrastructure.tui.Styled.Span;
import dev.buildcli.ports.EscalationChoice;
import dev.tamboui.buffer.Buffer;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The chat screen. It looks like WhatsApp Web: a list of chats on the left (your groups and a direct chat
 * with each agent, with previews, times, "typing…" and unread counts), and the open chat on the right with bubbles,
 * ticks and date separators. It works like ChatGPT: streamed replies with markdown and code blocks, suggestions, a
 * {@code /} command menu, an {@code @} mention menu, and a viewer for git diffs and files.
 * Drawn by hand on the terminal buffer; keyboard, mouse (click, wheel, scrollbar drag) and paste (a pasted file path
 * becomes an attachment) end up as calls on the {@link ChatSession}.
 */
final class ChatScreen implements Element {

    private record Hit(Rect rect, Runnable action) {}

    /** What the full-screen viewer shows. */
    private record View(String title, List<String> lines, boolean diff, boolean numbered, long changes, boolean confirmUndo) {
        View(String title, List<String> lines, boolean diff, boolean numbered) {
            this(title, lines, diff, numbered, -1, false);
        }
    }


    private static final int SIDEBAR_WIDTH = 34;
    private static final int SIDEBAR_MIN_TOTAL = 96;
    private static final int MAX_INPUT_ROWS = 8;
    private static final int MENU_ROWS = 8;
    private static final String SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";

    private final ChatSession session;
    private final Map<String, String> models;
    private final Path cwd;
    private final Runnable quit;
    private final InputEditor input = new InputEditor();
    /** The question an agent is asking you, which option is highlighted, and whether you are typing your own answer instead. */
    private ChatSession.Pending.Question asked;
    private int choice;
    private boolean typingAnswer;
    private final List<Attachment> attachments = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();

    private boolean sidebar = true;
    /** True until the user toggles the chat list: then it follows their choice even on narrow terminals. */
    private boolean autoSidebar = true;
    private int sideWidth;
    private String selected = ChatSession.MAIN;
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
    private final ChatListView chatList;
    private final ChatCommands commands;
    private final ConversationRows conversation;
    private final AgentFather father;
    private final SettingsView settingsView;
    private boolean settingsOpen;
    private ChatInfoView infoView;
    private boolean infoOpen;
    private final ConnectView connectView;
    private boolean connectOpen;
    /** Writes straight to the terminal (OSC 52 copy); set by the app once the terminal is up. */
    private volatile java.util.function.Consumer<String> rawOutput;
    private java.util.function.Function<String, String> copier = text -> Clipboard.copy(text, rawOutput);
    /** A short message at the bottom of the conversation ("Copied 12 lines"); it fades after a few seconds. */
    private volatile String toast = "";
    private volatile long toastUntil;
    /** Find in this chat: the query, which match is current and whether to scroll to it on the next frame. */
    private boolean searching;
    private final InputEditor findInput = new InputEditor();
    private List<Integer> findRows = List.of();
    private int findIndex = -1;
    private boolean findJump;
    private boolean findDirty;

    ChatScreen(ChatSession session, Map<String, String> models, Path cwd, Runnable quit) {
        this(session, models, cwd, quit, basicServices(session, models));
    }

    ChatScreen(ChatSession session, Map<String, String> models, Path cwd, Runnable quit, SettingsServices services) {
        this.session = session;
        // what you sent in earlier runs is still there to walk back through with Up
        for (var m : session.messages()) {
            if (m.kind() == ChatSession.Kind.USER) {
                input.scope(m.thread());
                input.remember(m.text());
            }
        }
        input.scope("");
        this.chatList = new ChatListView(session, new ChatListView.Host() {
            @Override
            public void hit(Rect rect, Runnable action) {
                hits.add(new Hit(rect, action));
            }

            @Override
            public String selected() {
                return selected;
            }

            @Override
            public void select(String thread) {
                ChatScreen.this.select(thread);
            }

            @Override
            public void openSettings() {
                settingsOpen = true;
            }

            @Override
            public void openNewChat() {
                openInfo(ChatInfoView.Mode.NEW_CHAT);
            }

            @Override
            public void showSidebar() {
                sidebar = true;
            }
        });
        this.commands = new ChatCommands(session, services, cwd, new ChatCommands.Host() {
            @Override
            public String selected() {
                return selected;
            }

            @Override
            public void select(String thread) {
                ChatScreen.this.select(thread);
            }

            @Override
            public void openView(ChatCommands.ViewSpec v) {
                open(new View(v.title(), v.lines(), v.diff(), v.numbered()));
            }

            @Override
            public void setInput(String text) {
                input.set(text);
            }

            @Override
            public void attach(Attachment attachment) {
                attachments.add(attachment);
            }

            @Override
            public void openSettings() {
                settingsOpen = true;
            }

            @Override
            public void showModels() {
                settingsOpen = true;
                settingsView.showModels();
            }

            @Override
            public void openConnect() {
                ChatScreen.this.openConnect(null);
            }

            @Override
            public void openInfo(ChatInfoView.Mode mode) {
                ChatScreen.this.openInfo(mode);
            }

            @Override
            public void toggleSidebar() {
                ChatScreen.this.toggleSidebar();
            }

            @Override
            public void openFind(String text) {
                openSearch(text);
            }

            @Override
            public void openChats(String text) {
                chatList.openSearch(text);
            }

            @Override
            public void copyLast(boolean wholeMessage) {
                ChatScreen.this.copyLast(wholeMessage);
            }

            @Override
            public void review(long changesId) {
                reviewChanges(changesId, false);
            }

            @Override
            public void undoLast() {
                ChatScreen.this.undoLast();
            }

            @Override
            public void quit() {
                ChatScreen.this.quit.run();
            }

            @Override
            public void say(String text) {
                ChatScreen.this.say(text);
            }

            @Override
            public String titleOf(String thread) {
                return chatList.title(thread);
            }
        });
        this.conversation = new ConversationRows(session, services, chatList, new ConversationRows.Host() {
            @Override
            public String selected() {
                return selected;
            }

            @Override
            public void review(long changesId, boolean undo) {
                reviewChanges(changesId, undo);
            }

            @Override
            public void copy(String text) {
                copyText(text);
            }

            @Override
            public void addSampleAgents() {
                ChatScreen.this.addSampleAgents();
            }

            @Override
            public void createAgent() {
                settingsOpen = true;
                settingsView.startNewAgent();
            }

            @Override
            public void openFather() {
                select(ChatSession.FATHER);
            }

            @Override
            public void openConnect(String why) {
                ChatScreen.this.openConnect(why);
            }

            @Override
            public void openSettings() {
                settingsOpen = true;
            }

            @Override
            public boolean runCommand(String text) {
                return commands.run(text);
            }

            @Override
            public void setInput(String text) {
                input.set(text);
            }
        });
        this.father = new AgentFather(session, services);
        session.father(father::handle);
        this.models = models;
        this.cwd = cwd;
        this.quit = quit;
        this.services = services;
        this.settingsView = new SettingsView(services, () -> settingsOpen = false, file -> {
            settingsOpen = false;
            runCommand("/open " + file);
        }, () -> openConnect(null));
        this.infoView = new ChatInfoView(session, () -> selected, this::select, () -> infoOpen = false, this::modelLabel);
        this.connectView = new ConnectView(services, message -> {
            connectOpen = false;
            if (message != null) {
                session.system(message);
            }
        });
        ensureSelection();
        if (services.canConnect() && !session.contacts().isEmpty() && noModelAnywhere()) {
            openConnect("None of your agents has a model yet. Connect one to start chatting; it takes a minute.");
        }
    }

    /** Nothing to answer with: no default model, no agent with a model of its own, no --model on the command line. */
    private boolean noModelAnywhere() {
        return settings().defaultModel() == null && session.contacts().stream().allMatch(a -> modelLabel(a.name()).startsWith("no model"))
                && models.values().stream().allMatch(v -> v.startsWith("no model"));
    }

    /** The connect screen: choose a provider and a model, test it, make it the default. @param why shown on top, or null */
    private void openConnect(String why) {
        settingsOpen = false;
        infoOpen = false;
        view = null;
        connectView.open(why);
        connectOpen = true;
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

        };
    }

    /** True while something on screen moves by itself: an agent working (spinner, dots) or a preview loading. */
    boolean animating() {
        return session.busy() || conversation.loadingPreviews() || connectOpen && connectView.animating() || System.currentTimeMillis() < toastUntil;
    }


    /** For the app: how to write bytes straight to the terminal. */
    void rawOutput(java.util.function.Consumer<String> writer) {
        this.rawOutput = writer;
    }

    /** For tests: replaces the system clipboard. The function returns how it copied, or null when it could not. */
    void copier(java.util.function.Function<String, String> copier) {
        this.copier = copier;
    }

    private void say(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 3_000; // animating() asks for the redraws until it expires
    }

    /** Copies on another thread, because a clipboard tool can take a moment, and says how it went. */
    private void copyText(String text) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            String how = copier.apply(text);
            long lines = text.lines().count();
            say(how == null ? "Could not copy: no clipboard tool found (install xclip, xsel or wl-copy)"
                    : "Copied " + lines + (lines == 1 ? " line" : " lines"));
        });
    }

    private String modelLabel(String agent) {
        String own = services.settings().modelFor(agent);
        if (own != null) {
            return own;
        }
        String def = services.settings().defaultModel();
        return def != null ? def : models.getOrDefault(agent, "no model: type /connect");
    }

    private dev.buildcli.application.Settings settings() {
        return services.settings();
    }

    // ---- Element ----

    @Override
    public void render(Frame frame, Rect rect, RenderContext ctx) {
        area = rect;
        hits.clear();
        ensureSelection();
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
            chatList.draw(buf, new Rect(rect.x(), rect.y(), sideW, rect.height()), all);
            for (int y = rect.y(); y < rect.bottom(); y++) {
                put(buf, rect.x() + sideW, y, "│", st(Theme.LINE, Theme.BG), rect.right());
            }
        }
        Rect pane = new Rect(rect.x() + sideW + (sideW > 0 ? 1 : 0), rect.y(), rect.width() - sideW - (sideW > 0 ? 1 : 0), rect.height());
        if (connectOpen) {
            connectView.render(buf, pane);
            frame.clearCursor();
        } else if (settingsOpen) {
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




    // ---- chat list (left) ----

    /** Keeps a chat open: the first group or agent, once there is one (for example right after the first agent is created). */
    private void ensureSelection() {
        if (chatList.exists(selected)) {
            return;
        }
        String first = session.defaultChat();
        if (first != null) {
            if (session.group(first) == null) {
                session.openDirect(first);
            }
            select(first);
        }
    }

    private void select(String thread) {
        selected = thread;
        searching = false;
        chatList.closeSearch();
        if (ChatSession.FATHER.equals(thread)) {
            father.greet();
        }
        scrollOff = 0;
        lastTotal = 0;
        menuDismissedFor = null;
    }

    private void openInfo(ChatInfoView.Mode mode) {
        infoView.open(mode);
        infoOpen = true;
        settingsOpen = false;
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
        int findH = searching ? 1 : 0;
        Rect content = new Rect(pane.x(), pane.y() + 2 + findH, pane.width(), Math.max(1, chipsY - pane.y() - 2 - findH));
        List<Message> msgs = chatList.inThread(all, selected);
        if (!msgs.isEmpty()) {
            chatList.markSeen(selected, msgs.get(msgs.size() - 1).id());
        }
        drawConversation(buf, content, msgs);
        if (searching) {
            drawFindBar(buf, new Rect(pane.x(), pane.y() + 2, pane.width(), 1));
        }
        if (!attachments.isEmpty()) {
            drawChips(buf, new Rect(pane.x(), chipsY, pane.width(), 1));
        }
        Rect bar = new Rect(pane.x(), barY, pane.width(), barH);
        drawInputBar(frame, buf, bar, segs, visibleRows);
        if (searching) {
            frame.clearCursor(); // the cursor is in the search box
        }
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
        boolean isGroup = group != null;
        String name = isGroup ? group.name() : chatList.exists(selected) ? chatList.title(selected) : "Welcome";
        Color c = isGroup ? Theme.ACCENT : Theme.agentColor(selected);
        put(buf, x, r.y(), " " + (name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT)) + " ", st(Theme.BG, c).bold(), r.right());
        int nx = x + 4;
        put(buf, nx, r.y(), chatList.exists(selected) ? chatList.title(selected) : "Welcome", base.bold(), r.right() - 30);
        String sub;
        Style subStyle = st(Theme.DIM, Theme.PANEL);
        ChatSession.Live live = session.live(selected);
        String elsewhere = isGroup ? null : session.agentThread(selected);
        if (session.isActive(selected)) {
            String who = live != null ? live.agent() : chatList.busyAgentIn(selected);
            String state = live != null ? "typing…" : who.isEmpty() ? "working…" : session.agentState(who) + "…";
            sub = (isGroup && !who.isEmpty() ? clean(who) + " is " : "") + state;
            subStyle = st(Theme.GREEN, Theme.PANEL);
        } else if (elsewhere != null) {
            sub = "busy in the " + chatList.title(elsewhere) + " chat · will read your messages after";
            subStyle = st(Theme.AMBER, Theme.PANEL);
        } else if (isGroup) {
            StringBuilder sb = new StringBuilder();
            for (String m : group.members()) {
                sb.append(sb.isEmpty() ? "" : ", ").append(clean(m));
            }
            sb.append(sb.isEmpty() ? "you" : ", you");
            sub = sb.toString() + "   · click for group info";
        } else if (chatList.hasDescription(selected)) {
            sub = chatList.describe(selected);
        } else if (session.contact(selected) == null) {
            sub = "create an agent to start";
        } else {
            sub = "online · " + clean(chatList.roleOf(selected)) + " · " + clean(modelLabel(selected));
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
    private record RowsKey(long version, int width, String chat, String theme, boolean activity, boolean compact, long frame) {}

    private RowsKey rowsKey;
    private List<ConversationRows.Row> rowsCache = List.of();

    private void drawConversation(Buffer buf, Rect r, List<Message> msgs) {
        int width = r.width() - 1;
        // rebuilding every bubble is the costly part of a frame: reuse the rows until the chat changes or something moves
        boolean moving = session.isActive(selected) || conversation.loadingPreviews();
        var key = new RowsKey(session.version(), width, selected, Theme.current(), settings().flag(dev.buildcli.application.Settings.SHOW_ACTIVITY),
                settings().flag(dev.buildcli.application.Settings.COMPACT), moving ? System.currentTimeMillis() / 100 : 0);
        if (!key.equals(rowsKey)) {
            conversation.previewsRendered();
            rowsCache = conversation.build(width, msgs);
            rowsKey = key;
        }
        List<ConversationRows.Row> rows = rowsCache;
        int total = rows.size();
        int viewH = r.height();
        scrollMax = Math.max(0, total - viewH);
        if (scrollOff > 0 && total > lastTotal) {
            scrollOff += total - lastTotal; // keep reading where the user stopped
        }
        lastTotal = total;
        scrollOff = Math.max(0, Math.min(scrollOff, scrollMax));
        String query = searching ? findInput.text().strip().toLowerCase(Locale.ROOT) : "";
        if (searching) {
            findRows = matchingRows(rows, query);
            if (findDirty) {
                findIndex = findRows.size() - 1; // start at the newest match
                findJump = true;
                findDirty = false;
            }
            findIndex = findRows.isEmpty() ? -1 : Math.max(0, Math.min(findIndex, findRows.size() - 1));
            if (findJump && findIndex >= 0) {
                int firstWanted = Math.max(0, Math.min(findRows.get(findIndex) - viewH / 2, Math.max(0, total - viewH)));
                scrollOff = Math.max(0, Math.min(scrollMax, total - viewH - firstWanted));
                findJump = false;
            }
        }
        int first = Math.max(0, total - viewH - scrollOff);
        boolean empty = msgs.isEmpty() && !session.isActive(selected);
        int yOff = total < viewH ? (empty ? Math.max(0, (viewH - total) / 3) : viewH - total) : 0;
        for (int i = 0; i < viewH && first + i < total; i++) {
            ConversationRows.Row row = rows.get(first + i);
            drawSpans(buf, r.x() + row.x(), r.y() + yOff + i, row.spans(), r.x() + width);
            if (!query.isEmpty()) {
                boolean current = findIndex >= 0 && findRows.get(findIndex) == first + i;
                highlight(buf, r.x() + row.x(), r.y() + yOff + i, row.spans(), query, current, r.x() + width);
            }
        }
        if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
            String t = " " + toast + " ";
            int tw = Wrap.width(t);
            put(buf, r.x() + Math.max(0, (width - tw) / 2), r.bottom() - 1, t, st(Theme.TEXT, Theme.PANEL).bold(), r.x() + width);
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

    // ---- find in this chat ----

    private static String plainText(List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        for (Span sp : spans) {
            sb.append(sp.text());
        }
        return sb.toString();
    }

    /** The rows whose text contains the (lower-cased) query, top to bottom. A phrase split over two rows is not found. */
    private static List<Integer> matchingRows(List<ConversationRows.Row> rows, String query) {
        if (query.isEmpty()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (plainText(rows.get(i).spans()).toLowerCase(Locale.ROOT).contains(query)) {
                out.add(i);
            }
        }
        return out;
    }

    /** Draws the matches of {@code query} in one row again, over the text, in the highlight colours. */
    private void highlight(Buffer buf, int x, int y, List<Span> spans, String query, boolean current, int limit) {
        String plain = plainText(spans);
        String lower = plain.toLowerCase(Locale.ROOT);
        if (lower.length() != plain.length()) {
            return; // a letter that changes length when lower-cased would put the highlight in the wrong column
        }
        Style style = current ? st(Theme.BG, Theme.ACCENT).bold() : st(Theme.BG, Theme.AMBER);
        for (int at = lower.indexOf(query); at >= 0; at = lower.indexOf(query, at + query.length())) {
            put(buf, x + Wrap.width(plain.substring(0, at)), y, plain.substring(at, at + query.length()), style, limit);
        }
    }

    private void openSearch(String text) {
        searching = true;
        findInput.set(text);
        findIndex = -1;
        findDirty = true;
    }

    private void drawFindBar(Buffer buf, Rect r) {
        Style bar = st(Theme.TEXT, Theme.PANEL);
        fill(buf, r, bar);
        int x = r.x() + 2;
        x += put(buf, x, r.y(), "Find in this chat: ", st(Theme.DIM, Theme.PANEL), r.right());
        String text = findInput.text();
        x += put(buf, x, r.y(), text.isEmpty() ? "type to search▏" : text + "▏", st(text.isEmpty() ? Theme.DIM : Theme.TEXT, Theme.FIELD), r.right() - 40);
        String count = text.isBlank() ? "" : findRows.isEmpty() ? "no matches" : (findIndex + 1) + " of " + findRows.size();
        String right = count + "   ↑ older  ↓ newer  Esc close ✕ ";
        int rx = Math.max(x + 2, r.right() - Wrap.width(right));
        put(buf, rx, r.y(), right, st(findRows.isEmpty() && !text.isBlank() ? Theme.RED : Theme.DIM, Theme.PANEL), r.right());
        hits.add(new Hit(new Rect(r.right() - 3, r.y(), 3, 1), () -> searching = false));
    }

    private void findStep(int direction) {
        if (findRows.isEmpty()) {
            return;
        }
        findIndex = (Math.max(0, findIndex) + direction + findRows.size()) % findRows.size();
        findJump = true;
    }

    private EventResult searchKey(KeyEvent key) {
        KeyCode code = key.code();
        switch (code) {
            case ESCAPE -> searching = false;
            case ENTER -> findStep(key.hasShift() ? -1 : 1);
            case UP -> findStep(-1);
            case DOWN -> findStep(1);
            case PAGE_UP -> scroll(10);
            case PAGE_DOWN -> scroll(-10);
            case BACKSPACE -> {
                findInput.backspace();
                findDirty = true;
            }
            case LEFT -> findInput.left();
            case RIGHT -> findInput.right();
            case CHAR -> {
                if (key.hasCtrl() && Character.toLowerCase(key.character()) == 'u') {
                    findInput.clear();
                    findDirty = true;
                } else if (!key.hasCtrl() && !key.hasAlt() && key.character() >= ' ') {
                    findInput.insert(key.string());
                    findDirty = true;
                }
            }
            default -> { }
        }
        return EventResult.HANDLED;
    }

    /** /copy: the last code block of the last answer, or with "message" (or when it has no code) the whole answer. */
    private void copyLast(boolean wholeMessage) {
        List<Message> msgs = chatList.inThread(session.messages(), selected);
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m.kind() != ChatSession.Kind.AGENT) {
                continue;
            }
            List<String> blocks = Styled.codeBlocks(m.text());
            copyText(wholeMessage || blocks.isEmpty() ? m.text() : blocks.get(blocks.size() - 1));
            return;
        }
        say("Nothing to copy yet");
    }

    // ---- conversation rows ----

    /** Opens the files of a changes card as a diff. With {@code undo} it asks to confirm putting them back. */
    private void reviewChanges(long id, boolean undo) {
        var files = session.changes(id);
        if (files.isEmpty()) {
            session.system("These changes were not kept, so they cannot be shown.");
            return;
        }
        List<String> lines = new ArrayList<>();
        for (var n : dev.buildcli.application.tools.FileChanges.net(files)) {
            lines.add("diff --git a/" + n.path() + " b/" + n.path());
            lines.addAll(dev.buildcli.application.tools.FileChanges.diff(List.of(new dev.buildcli.domain.FileChange(
                    n.agents().get(0), n.path(), n.existed(), n.before(), n.after()))).lines().toList());
        }
        String who = files.get(0).agent();
        open(new View(undo ? "Undo " + who + "'s changes?" : who + "'s changes", lines, true, false, id, undo));
    }

    private void undoLast() {
        long id = session.lastChanges(selected);
        if (id < 0) {
            session.system("No changes to undo in this chat.");
        } else if (!session.canUndo()) {
            session.system("Undo is not available here.");
        } else {
            reviewChanges(id, true);
        }
    }

    /** The "add the sample agents" button of an empty chat: writes the agents, adds them live and opens their group. */
    private void addSampleAgents() {
        try {
            List<String> added = services.createSampleAgents();
            if (session.group("#maintainers") != null) {
                select("#maintainers");
            }
            say("Added " + String.join(", ", added) + ". Say something in the group to start.");
        } catch (Exception e) {
            say("Could not add them: " + e.getMessage());
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
                String hint = ChatSession.FATHER.equals(selected) ? "Ask AgentFather: /newagent, /agents, /editagent, /help"
                        : ChatSession.isAgentChat(selected) ? "Read only: ask one of them, in their own chat, to write to the other"
                        : selected.equals(ChatSession.NOTES) ? "A note to yourself: no agent reads it"
                        : session.busy() ? "Type a message (it waits its turn)" : "Type a message";
                put(buf, tx, y, hint, st(Theme.DIM, Theme.FIELD), tx + inputWidth);
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
        if (session.pending() == null || session.pending() instanceof ChatSession.Pending.Question && typingOwnAnswer()) {
            frame.setCursorPosition(tx + Math.min(cur[1], inputWidth), r.y() + 1 + (cur[0] - inputFirstRow));
        }
    }

    // ---- the / and @ menus ----

    private record MenuItem(String label, String detail, String right, Runnable accept, Runnable fill) {
        MenuItem(String label, String detail, String right, Runnable accept) {
            this(label, detail, right, accept, accept);
        }
    }

    /** The command menu while typing "/word", or the mention menu while typing "@name"; empty when neither. */
    private List<MenuItem> menu() {
        String text = input.text();
        String key;
        List<MenuItem> items = new ArrayList<>();
        if (text.startsWith("/") && !text.contains(" ") && !text.contains("\n") && input.cursor() == text.length()) {
            key = text;
            String typed = text.substring(1).toLowerCase(Locale.ROOT);
            for (ChatCommands.Command c : commandsHere()) {
                if (c.name().startsWith(typed)) {
                    items.add(new MenuItem("/" + c.name() + (c.arg().isEmpty() ? "" : " " + c.arg()), c.description(), c.shortcut(),
                            () -> acceptCommand(c), () -> input.set("/" + c.name() + " ")));
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

    /** The commands the menu offers in the open chat: AgentFather's own, in its chat, and the ones every chat has. */
    private List<ChatCommands.Command> commandsHere() {
        if (!ChatSession.FATHER.equals(selected)) {
            return ChatCommands.LIST;
        }
        List<ChatCommands.Command> all = new ArrayList<>(AgentFather.COMMANDS);
        all.addAll(ChatCommands.LIST);
        return all;
    }

    private void acceptCommand(ChatCommands.Command c) {
        if (c.arg().isEmpty() || c.arg().startsWith("[")) {
            input.clear();
            if (!runCommand("/" + c.name())) {
                // not one of the commands every chat has: the chat itself answers it (AgentFather's)
                session.submit("/" + c.name(), List.of(), selected);
            }
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
        put(buf, box.x() + 2, y0, items.get(0).label().startsWith("/") ? "Commands" : "Mention an agent", st(Theme.DIM, Theme.DIALOG), box.right());
        boolean commands = items.get(0).label().startsWith("/");
        String hint = (commands ? "↑↓ choose · Tab fills · Enter runs" : "↑↓ choose · Tab or Enter completes") + (items.size() > h ? " · " + items.size() + " matches" : "");
        put(buf, box.right() - 2 - Wrap.width(hint), y0, hint, st(Theme.FAINT, Theme.DIALOG), box.right() - 1);
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
            putFit(buf, row.x() + 4 + labelW, row.y(), clean(it.detail()), st(Theme.DIM, rowBg), row.right() - 10);
            put(buf, row.right() - 2 - Wrap.width(it.right()), row.y(), it.right(), st(Theme.DIM, rowBg), row.right() - 1);
            int idx = first + i;
            hits.add(new Hit(row, () -> {
                menuIndex = idx;
                it.accept().run();
            }));
        }
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
        if (view.changes() >= 0) {
            long id = view.changes();
            Rect b = new Rect(r.x(), r.bottom() - 2, r.width(), 1);
            fill(buf, b, st(Theme.TEXT, Theme.PANEL));
            int x = r.x() + 2;
            if (view.confirmUndo()) {
                x += put(buf, x, b.y(), "Files you or another agent changed since are left alone.  ", st(Theme.DIM, Theme.PANEL), r.right());
                x += put(buf, x, b.y(), " Undo  Y ", st(Theme.TEXT, Theme.DANGER).bold(), r.right());
                hits.add(new Hit(new Rect(x - 9, b.y(), 9, 1), () -> undoChanges(id)));
                x += put(buf, x + 1, b.y(), " Cancel  N ", st(Theme.TEXT, Theme.FIELD), r.right()) + 1;
                hits.add(new Hit(new Rect(x - 11, b.y(), 11, 1), () -> view = null));
            } else if (session.canUndo() && !undone(id)) {
                int w = put(buf, x, b.y(), " Undo these changes  U ", st(Theme.TEXT, Theme.FIELD), r.right());
                hits.add(new Hit(new Rect(x, b.y(), w, 1), () -> reviewChanges(id, true)));
            }
            viewHeight = Math.max(1, viewHeight - 1);
        }
    }

    private boolean undone(long id) {
        return session.messages().stream().anyMatch(m -> m.id() == id && m.state() == State.UNDONE);
    }

    private void undoChanges(long id) {
        view = null;
        session.undo(id);
    }

    private EventResult viewerKey(KeyEvent key) {
        KeyCode code = key.code();
        char ch = code == KeyCode.CHAR ? key.character() : 0;
        if (view.changes() >= 0 && code == KeyCode.CHAR && !key.hasCtrl()) {
            char c = Character.toLowerCase(ch);
            if (view.confirmUndo() && c == 'y') {
                undoChanges(view.changes());
                return EventResult.HANDLED;
            }
            if (view.confirmUndo() && c == 'n') {
                view = null;
                return EventResult.HANDLED;
            }
            if (!view.confirmUndo() && c == 'u' && session.canUndo() && !undone(view.changes())) {
                reviewChanges(view.changes(), true);
                return EventResult.HANDLED;
            }
        }
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
        List<Integer> questionRows = new ArrayList<>();
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
            if (a.request().grantKey() != null) {
                buttons.add(new String[] {" Always here  A ", "plain"});
                actions.add(() -> session.approveAlways(a));
                body.add(List.of());
                for (String line : Wrap.lines("A: " + clean(a.request().grantLabel()) + ". Until you close BuildCLI; /revoke takes it back.", w - 4)) {
                    body.add(List.of(new Span(line, st(Theme.DIM, Theme.DIALOG))));
                }
            }
            buttons.add(new String[] {" Deny  N ", "plain"});
            actions.add(() -> a.answer().complete(false));
        } else if (p instanceof ChatSession.Pending.Question q) {
            title = clean(q.agent()) + " asks you" + where(p);
            body.addAll(Styled.lines(clean(q.question()), w - 4, base, base.bold(), base));
            body.add(List.of());
            if (!q.options().isEmpty() && !typingAnswer) {
                for (int i = 0; i <= q.options().size(); i++) {
                    boolean other = i == q.options().size();
                    String label = other ? "Something else…" : clean(q.options().get(i));
                    Style os = i == choice ? st(Theme.BG, Theme.TEXT).bold() : other ? st(Theme.DIM, Theme.DIALOG) : base;
                    int row = body.size();
                    questionRows.add(row);
                    body.add(List.of(new Span((i == choice ? " ❯ " : "   ") + (i + 1) + ". " + label + " ", os)));
                }
                body.add(List.of());
                body.add(List.of(new Span("↑↓ or a number to choose · Enter confirms · Esc skips", st(Theme.DIM, Theme.DIALOG))));
            } else {
                body.add(List.of(new Span(typingAnswer && !q.options().isEmpty() ? "Type your answer below · Enter sends · Esc goes back"
                        : "Type your answer below · Enter sends · Esc skips", st(Theme.DIM, Theme.DIALOG))));
            }
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
        int h = body.size() + (buttons.isEmpty() ? 3 : 5);
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
        for (int i = 0; i < questionRows.size(); i++) {
            int index = i;
            hits.add(new Hit(new Rect(x + 2, y + 1 + questionRows.get(i), w - 4, 1), () -> chooseAnswer(index)));
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

    private boolean typingOwnAnswer() {
        return session.pending() instanceof ChatSession.Pending.Question q && (typingAnswer || q.options().isEmpty());
    }

    /** The keys while an agent asks you something; null lets the key edit the answer in the input box. */
    private EventResult questionKey(ChatSession.Pending.Question q, KeyEvent key) {
        KeyCode code = key.code();
        if (key.hasCtrl()) {
            return null;
        }
        if (typingAnswer || q.options().isEmpty()) {
            if (code == KeyCode.ENTER && !key.hasShift() && !key.hasAlt()) {
                String text = input.text().strip();
                if (!text.isEmpty()) {
                    input.clear();
                    q.answer().complete(text);
                }
                return EventResult.HANDLED;
            }
            if (code == KeyCode.ESCAPE) {
                if (q.options().isEmpty()) {
                    q.answer().complete("");
                } else {
                    typingAnswer = false;
                }
                return EventResult.HANDLED;
            }
            return null;
        }
        int n = q.options().size() + 1;
        switch (code) {
            case UP -> choice = (choice - 1 + n) % n;
            case DOWN, TAB -> choice = (choice + 1) % n;
            case ENTER -> chooseAnswer(choice);
            case ESCAPE -> q.answer().complete("");
            case CHAR -> {
                int d = key.character() - '1';
                if (d >= 0 && d < n) {
                    chooseAnswer(d);
                }
            }
            default -> { }
        }
        return EventResult.HANDLED;
    }

    private void chooseAnswer(int index) {
        if (!(session.pending() instanceof ChatSession.Pending.Question q)) {
            return;
        }
        if (index >= q.options().size()) {
            typingAnswer = true;
            input.clear();
        } else {
            q.answer().complete(q.options().get(index));
        }
    }

    private String where(ChatSession.Pending p) {
        String chat = p.thread().equals(ChatSession.EVERYWHERE) ? chatList.title(ChatSession.MAIN) : chatList.title(p.thread());
        int n = session.pendingCount();
        return " · " + chat + " chat" + (n > 1 ? " · 1 of " + n : "");
    }

    // ---- keyboard ----

    @Override
    public EventResult handleKeyEvent(KeyEvent key, boolean focused) {
        input.scope(selected);
        boolean ctrl = key.hasCtrl();
        boolean alt = key.hasAlt();
        KeyCode code = key.code();
        char ch = code == KeyCode.CHAR ? Character.toLowerCase(key.character()) : 0;

        if (connectOpen) {
            if (ctrl && ch == 'c') {
                connectOpen = false;
            } else {
                connectView.key(key);
            }
            return EventResult.HANDLED;
        }
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
            if (chatList.isSearching()) {
                chatList.closeSearch();
            } else if (searching) {
                searching = false;
            } else if (view != null) {
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
        if (ctrl && ch == 'k' && view == null) {
            chatList.openSearch("");
            return EventResult.HANDLED;
        }
        if (chatList.isSearching() && view == null && session.pending() == null) {
            return chatList.searchKey(key);
        }
        if (ctrl && ch == 'f' && view == null) {
            openSearch(searching ? findInput.text() : "");
            return EventResult.HANDLED;
        }
        if (searching && view == null && session.pending() == null) {
            return searchKey(key);
        }
        ChatSession.Pending p = session.pending();
        if (p instanceof ChatSession.Pending.Question q) {
            if (q != asked) {
                asked = q;
                choice = 0;
                typingAnswer = false;
            }
            EventResult answered = questionKey(q, key);
            if (answered != null) {
                return answered;
            }
        }
        if (p != null && !(p instanceof ChatSession.Pending.Question) && code == KeyCode.CHAR && (input.isEmpty() || ctrl)) {
            if (p instanceof ChatSession.Pending.Approval a && (ch == 'y' || ch == 'n')) {
                a.answer().complete(ch == 'y');
                return EventResult.HANDLED;
            }
            if (p instanceof ChatSession.Pending.Approval a && ch == 'a' && a.request().grantKey() != null) {
                session.approveAlways(a);
                return EventResult.HANDLED;
            }
            if (p instanceof ChatSession.Pending.Escalation e && "rsa".indexOf(ch) >= 0) {
                e.answer().complete(ch == 'r' ? EscalationChoice.RETRY : ch == 's' ? EscalationChoice.SKIP : EscalationChoice.ABORT);
                return EventResult.HANDLED;
            }
            if (input.isEmpty() && !ctrl && !alt) {
                // a letter that answers nothing must not start a message: it would make Y and N stop working
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
                    scrollOff = 0; // redraw at the latest message; deleting a chat is /clear, which asks first
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
                    items.get(Math.min(menuIndex, items.size() - 1)).fill().run();
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

    private void toggleSidebar() {
        sidebar = sideWidth == 0;
        autoSidebar = false;
    }

    /** Alt+Up/Down (or Ctrl+PgUp/PgDn) move through the chat list, Alt+1..9 jump to a chat. */
    private boolean switchChat(KeyEvent key) {
        List<String> threads = chatList.threads();
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

    /** @return true if the text was a slash command (and was handled); "/home/me/file" is not one */
    boolean runCommand(String text) {
        return commands.run(text);
    }

    void submit() {
        String text = input.text().strip();
        commands.typed(text);
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

    // ---- paste and mouse ----

    @Override
    public EventResult handlePasteEvent(PasteEvent paste) {
        if (connectOpen) {
            connectView.paste(paste.text());
            return EventResult.HANDLED;
        }
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
                Path p = ChatCommands.resolve(cwd, line);
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
        if (connectOpen && inPane) {
            connectView.mouse(m);
            return EventResult.HANDLED;
        }
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
            if (!inPane) {
                chatList.scrollBy(-d / 3);
            } else if (view != null) {
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

    List<String> threadsForTest() {
        return chatList.threads();
    }

    ChatSession sessionForTest() {
        return session;
    }

    boolean connectOpenForTest() {
        return connectOpen;
    }

    boolean listSearchOpenForTest() {
        return chatList.isSearching();
    }

    void openNewAgentForTest() {
        settingsOpen = true;
        settingsView.startNewAgent();
    }

    boolean settingsOpenForTest() {
        return settingsOpen;
    }

    boolean viewerOpenForTest() {
        return view != null;
    }
}
