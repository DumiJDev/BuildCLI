package dev.buildcli.infrastructure.tui;

import static dev.buildcli.infrastructure.tui.Draw.DAY;
import static dev.buildcli.infrastructure.tui.Draw.TIME;
import static dev.buildcli.infrastructure.tui.Draw.clean;
import static dev.buildcli.infrastructure.tui.Draw.fill;
import static dev.buildcli.infrastructure.tui.Draw.put;
import static dev.buildcli.infrastructure.tui.Draw.st;
import static dev.buildcli.infrastructure.tui.Draw.tokens;

import dev.buildcli.application.ChatSession;
import dev.buildcli.application.ChatSession.Message;
import dev.buildcli.application.ChatSession.State;
import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Attachment;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The list of chats on the left, with its search box: names, previews, times, "typing…" and unread counts; and finding a chat
 * by name, role or something said in it (Ctrl+K). The screen around it tells it what is selected and what to do on a click.
 */
final class ChatListView {

    /** What the list needs from the screen it lives in. */
    interface Host {
        /** Registers a clickable area for this frame. */
        void hit(Rect rect, Runnable action);

        String selected();

        void select(String thread);

        void openSettings();

        void openNewChat();

        void showSidebar();
    }

    private final ChatSession session;
    private final Host host;
    private final InputEditor query = new InputEditor();
    private boolean searching;
    private int index;
    /** The newest message of each chat that was on screen: what is newer counts as unread. */
    private final Map<String, Long> seen = new HashMap<>();

    ChatListView(ChatSession session, Host host) {
        this.session = session;
        this.host = host;
    }

    /** Whether {@code thread} is a chat that can be opened: a group, an agent, your notes, or a chat between two agents. */
    boolean exists(String thread) {
        return session.group(thread) != null || session.contact(thread) != null || isSpecial(thread);
    }

    /** Your notes and the private chats between agents: chats with no agent on the other side to answer you. */
    boolean isSpecial(String thread) {
        return ChatSession.NOTES.equals(thread) || ChatSession.isAgentChat(thread);
    }

    /** The line under the name of a special chat. */
    String describe(String thread) {
        return ChatSession.NOTES.equals(thread) ? "only you can read this · no agent sees it"
                : "private chat between agents · you can read it, not write in it";
    }

    boolean isSearching() {
        return searching;
    }

    void markSeen(String thread, long messageId) {
        seen.put(thread, messageId);
    }

    /** Opens the search box, with some text already typed. */
    void openSearch(String text) {
        openSearch();
        query.set(text);
    }

    /** The chats in the list: groups, your notes, a direct chat per agent, and the private chats between agents. */
    List<String> threads() {
        List<String> out = new ArrayList<>();
        session.groups().forEach(g -> out.add(g.id()));
        out.add(ChatSession.NOTES);
        out.addAll(session.directChats());
        out.addAll(session.agentChats());
        return out;
    }

    String title(String thread) {
        if (thread.equals(ChatSession.NOTES)) {
            return "You (notes)";
        }
        if (ChatSession.isAgentChat(thread)) {
            return String.join(" ↔ ", ChatSession.agentChatMembers(thread));
        }
        var g = session.group(thread);
        return g != null ? clean(g.name()) : clean(thread);
    }

    List<Message> inThread(List<Message> all, String thread) {
        List<Message> out = new ArrayList<>();
        for (Message m : all) {
            if (m.thread().equals(thread) || m.thread().equals(ChatSession.EVERYWHERE)) {
                out.add(m);
            }
        }
        return out;
    }


    void draw(Buffer buf, Rect r, List<Message> all) {
        Style base = st(Theme.TEXT, Theme.SIDEBAR);
        Style dim = st(Theme.DIM, Theme.SIDEBAR);
        fill(buf, r, base);
        fill(buf, new Rect(r.x(), r.y(), r.width(), 2), st(Theme.TEXT, Theme.PANEL));
        put(buf, r.x() + 2, r.y(), "BuildCLI", st(Theme.TEXT, Theme.PANEL).bold(), r.right());
        put(buf, r.x() + 2, r.y() + 1, "local AI engineering team", st(Theme.DIM, Theme.PANEL), r.right() - 4);
        put(buf, r.right() - 4, r.y(), " ⚙ ", st(Theme.DIM, Theme.PANEL), r.right());
        host.hit(new Rect(r.right() - 4, r.y(), 3, 2), () -> host.openSettings());
        put(buf, r.right() - 8, r.y(), " ＋ ", st(Theme.DIM, Theme.PANEL), r.right() - 4);
        host.hit(new Rect(r.right() - 8, r.y(), 4, 2), () -> host.openNewChat());
        drawListSearch(buf, new Rect(r.x() + 1, r.y() + 2, r.width() - 2, 1));
        int y = r.y() + 4;
        int limit = r.right() - 1;
        if (searching && !query.text().isBlank()) {
            drawSearchResults(buf, r, y, limit, all);
            return;
        }
        List<String> threads = threads();
        for (int i = 0; i < threads.size() && y + 2 < r.bottom() - 1; i++) {
            String t = threads.get(i);
            boolean sel = t.equals(host.selected());
            Color bg = sel ? Theme.SELECTED : Theme.SIDEBAR;
            Rect row = new Rect(r.x(), y, r.width(), 2);
            fill(buf, row, st(Theme.TEXT, bg));
            host.hit(row, () -> host.select(t));
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
            host.hit(new Rect(r.x(), r.bottom() - 3, r.width(), 2), () -> host.openNewChat());
        }
    }

    // ---- searching the chat list ----

    /** One thing the chat search found: a chat (or an agent with no chat yet) and, for a match in a message, a piece of it. */
    private record Found(String thread, String snippet, boolean newChat) {}

    private void openSearch() {
        host.showSidebar();
        searching = true;
        index = 0;
    }

    /** Closes the search box and forgets what was typed. */
    void closeSearch() {
        searching = false;
        query.clear();
        index = 0;
    }

    /**
     * Chats whose name or role contains the text, then chats with a message that does, then agents you have no chat with
     * yet (opening one starts it): like the search box of a messaging app. Case does not matter.
     */
    private List<Found> searchChats(String text, List<Message> all) {
        String q = text.strip().toLowerCase(Locale.ROOT);
        List<Found> byName = new ArrayList<>();
        List<Found> byMessage = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String t : threads()) {
            seen.add(t);
            String hay = title(t).toLowerCase(Locale.ROOT) + " " + roleOf(t).toLowerCase(Locale.ROOT);
            if (hay.contains(q)) {
                byName.add(new Found(t, "", false));
                continue;
            }
            List<Message> msgs = inThread(all, t);
            for (int k = msgs.size() - 1; k >= 0; k--) {
                Message m = msgs.get(k);
                if (m.thread().equals(ChatSession.EVERYWHERE) || m.kind() == ChatSession.Kind.ACTIVITY) {
                    continue;
                }
                String plain = clean(Styled.plain(m.text()));
                int at = plain.toLowerCase(Locale.ROOT).indexOf(q);
                if (at >= 0) {
                    int from = Math.max(0, at - 18);
                    byMessage.add(new Found(t, (from > 0 ? "…" : "") + plain.substring(from), false));
                    break;
                }
            }
        }
        List<Found> out = new ArrayList<>(byName);
        out.addAll(byMessage);
        for (Agent a : session.contacts()) {
            if (!seen.contains(a.name()) && (a.name() + " " + a.role()).toLowerCase(Locale.ROOT).contains(q)) {
                out.add(new Found(a.name(), "Start a chat with " + clean(a.name()), true));
            }
        }
        return out;
    }

    private void openFound(Found f) {
        if (f.newChat()) {
            session.openDirect(f.thread());
        }
        host.select(f.thread());
    }

    private void drawListSearch(Buffer buf, Rect r) {
        Style field = st(searching ? Theme.TEXT : Theme.DIM, Theme.FIELD);
        fill(buf, r, field);
        String text = query.text();
        String shown = searching ? "⌕ " + text + "▏" : "⌕ Search chats  Ctrl+K";
        put(buf, r.x() + 1, r.y(), shown, field, r.right() - 1);
        host.hit(r, this::openSearch);
    }

    private void drawSearchResults(Buffer buf, Rect r, int top, int limit, List<Message> all) {
        List<Found> found = searchChats(query.text(), all);
        index = found.isEmpty() ? 0 : Math.max(0, Math.min(index, found.size() - 1));
        if (found.isEmpty()) {
            put(buf, r.x() + 2, top, "No chat or agent matches '" + clean(query.text().strip()) + "'", st(Theme.DIM, Theme.SIDEBAR), limit);
            return;
        }
        int y = top;
        int first = Math.max(0, index - Math.max(0, (r.bottom() - 1 - top) / 3 - 1));
        for (int i = first; i < found.size() && y + 2 < r.bottom() - 1; i++) {
            Found f = found.get(i);
            Color bg = i == index ? Theme.SELECTED : Theme.SIDEBAR;
            Rect row = new Rect(r.x(), y, r.width(), 2);
            fill(buf, row, st(Theme.TEXT, bg));
            host.hit(row, () -> openFound(f));
            avatar(buf, r.x() + 1, y, f.thread());
            put(buf, r.x() + 6, y, title(f.thread()), st(Theme.TEXT, bg).bold(), limit);
            String line = f.snippet().isEmpty() ? clean(roleOf(f.thread())) : f.snippet();
            if (line.isEmpty() && session.group(f.thread()) != null) {
                line = session.group(f.thread()).members().size() + " agents and you";
            }
            put(buf, r.x() + 6, y + 1, line, st(f.newChat() ? Theme.GREEN : Theme.DIM, bg), limit);
            y += 2;
            put(buf, r.x() + 6, y, "─".repeat(Math.max(0, r.width() - 7)), st(Theme.LINE, Theme.SIDEBAR), limit + 1);
            y++;
        }
        put(buf, r.x() + 2, r.bottom() - 2, "↑↓ choose · Enter open · Esc close", st(Theme.FAINT, Theme.SIDEBAR), limit);
    }

    EventResult searchKey(KeyEvent key) {
        KeyCode code = key.code();
        List<Found> found = query.text().isBlank() ? List.of() : searchChats(query.text(), session.messages());
        switch (code) {
            case ESCAPE -> closeSearch();
            case ENTER -> {
                if (!found.isEmpty()) {
                    openFound(found.get(Math.max(0, Math.min(index, found.size() - 1))));
                } else if (query.text().isBlank()) {
                    closeSearch();
                }
            }
            case UP -> index = Math.max(0, index - 1);
            case DOWN -> index = Math.min(Math.max(0, found.size() - 1), index + 1);
            case BACKSPACE -> {
                query.backspace();
                index = 0;
            }
            case LEFT -> query.left();
            case RIGHT -> query.right();
            case CHAR -> {
                if (key.hasCtrl() && Character.toLowerCase(key.character()) == 'u') {
                    query.clear();
                    index = 0;
                } else if (!key.hasCtrl() && !key.hasAlt() && key.character() >= ' ') {
                    query.insert(key.string());
                    index = 0;
                }
            }
            default -> { }
        }
        return EventResult.HANDLED;
    }


    String roleOf(String agent) {
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
        if (thread.equals(host.selected())) {
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
        boolean isGroup = g != null;
        String name = isGroup ? g.name() : thread;
        String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        Color c = isGroup ? Theme.ACCENT : Theme.agentColor(thread);
        put(buf, x, y, " " + initial + " ", st(Theme.ON_ACCENT, c).bold(), x + 3);
        String state = isGroup ? "" : session.agentState(thread);
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


    String busyAgentIn(String thread) {
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
}
