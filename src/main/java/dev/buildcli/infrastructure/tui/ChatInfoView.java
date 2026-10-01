package dev.buildcli.infrastructure.tui;

import static dev.buildcli.infrastructure.tui.Draw.fill;
import static dev.buildcli.infrastructure.tui.Draw.putSafe;
import static dev.buildcli.infrastructure.tui.Draw.st;
import dev.buildcli.application.ChatSession;
import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Chat;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.text.CharWidth;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Group info and new chats, as in WhatsApp: who is in a group and who is admin (make or dismiss admin, remove, add
 * members, rename, delete), a contact's details for a direct chat, and starting a chat or a group. Rows carry their
 * actions as buttons; a letter key or a click runs them.
 */
final class ChatInfoView {

    enum Mode { INFO, NEW_CHAT, ADD_MEMBER, NEW_GROUP_MEMBERS }

    private record Action(String label, char key, Runnable run) {}

    private record Row(String text, String detail, Color color, List<Action> actions) {}

    private record Hit(Rect rect, Runnable action) {}

    private final ChatSession session;
    private final Supplier<String> selected;
    private final Consumer<String> select;
    private final Runnable close;
    private final Function<String, String> modelLabel;
    private final List<Hit> hits = new ArrayList<>();
    private Mode mode = Mode.INFO;
    private int index;
    private int first;
    private InputEditor prompt;
    private String promptTitle;
    private Consumer<String> promptDone;
    private String newGroupName;
    private final Set<String> picked = new LinkedHashSet<>();
    private String status = "";
    private Color statusColor = Theme.DIM;

    ChatInfoView(ChatSession session, Supplier<String> selected, Consumer<String> select, Runnable close, Function<String, String> modelLabel) {
        this.session = session;
        this.selected = selected;
        this.select = select;
        this.close = close;
        this.modelLabel = modelLabel;
    }

    void open(Mode m) {
        mode = m;
        index = 0;
        first = 0;
        prompt = null;
        picked.clear();
        status = "";
    }

    // ---- rows ----

    private String title() {
        String id = selected.get();
        Chat g = session.group(id);
        return switch (mode) {
            case NEW_CHAT -> "New chat";
            case ADD_MEMBER -> "Add to " + (g == null ? "group" : g.name());
            case NEW_GROUP_MEMBERS -> "New group: " + newGroupName + " · choose members";
            default -> g != null ? "Group info · " + g.name() : "Contact info · " + id;
        };
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        String id = selected.get();
        Chat g = session.group(id);
        switch (mode) {
            case NEW_CHAT -> {
                out.add(new Row("+ New group", "choose a name and the members", Theme.ACCENT,
                        List.of(new Action("Create", 'g', () -> ask("Name of the new group", "", name -> {
                            newGroupName = name.isBlank() ? "New group" : name.strip();
                            open(Mode.NEW_GROUP_MEMBERS);
                        })))));
                for (Agent a : session.contacts()) {
                    out.add(new Row(a.name(), a.role() + " · " + presence(a.name()), Theme.agentColor(a.name()), List.of(new Action("Message", 'm', () -> {
                        session.openDirect(a.name());
                        select.accept(a.name());
                        close.run();
                    }))));
                }
            }
            case ADD_MEMBER -> {
                for (Agent a : session.contacts()) {
                    if (g != null && !g.has(a.name())) {
                        out.add(new Row(a.name(), a.role(), Theme.agentColor(a.name()), List.of(new Action("Add", 'a', () -> {
                            session.addMember(g.id(), a.name());
                            open(Mode.INFO);
                            ok(a.name() + " joined " + g.name());
                        }))));
                    }
                }
                if (out.isEmpty()) {
                    out.add(new Row("Everyone is already in this group", "", Theme.DIM, List.of()));
                }
            }
            case NEW_GROUP_MEMBERS -> {
                for (Agent a : session.contacts()) {
                    boolean on = picked.contains(a.name());
                    out.add(new Row((on ? "☑ " : "☐ ") + a.name(), a.role(), Theme.agentColor(a.name()), List.of(new Action(on ? "Remove" : "Add", ' ', () -> {
                        if (!picked.remove(a.name())) {
                            picked.add(a.name());
                        }
                    }))));
                }
                out.add(new Row("Create the group", picked.isEmpty() ? "pick at least one member" : picked.size() + " member(s); the first is admin",
                        picked.isEmpty() ? Theme.DIM : Theme.ACCENT, picked.isEmpty() ? List.of() : List.of(new Action("Create", 'c', () -> {
                            String newId = session.createGroup(newGroupName, List.copyOf(picked));
                            select.accept(newId);
                            close.run();
                        }))));
            }
            default -> {
                if (g != null) {
                    for (String m : g.members()) {
                        List<Action> actions = new ArrayList<>();
                        actions.add(g.isAdmin(m) ? new Action("Dismiss admin", 'd', () -> run(() -> session.setAdmin(g.id(), m, false), m + " is no longer admin"))
                                : new Action("Make admin", 'a', () -> run(() -> session.setAdmin(g.id(), m, true), m + " is now admin")));
                        actions.add(new Action("Remove", 'r', () -> run(() -> session.removeMember(g.id(), m), m + " was removed")));
                        actions.add(new Action("Message", 'm', () -> {
                            session.openDirect(m);
                            select.accept(m);
                            close.run();
                        }));
                        out.add(new Row(m + (g.isAdmin(m) ? "  · admin" : ""), roleOf(m) + " · " + presence(m), Theme.agentColor(m), actions));
                    }
                    out.add(new Row("+ Add member", "", Theme.ACCENT, List.of(new Action("Add", 'a', () -> open(Mode.ADD_MEMBER)))));
                    out.add(new Row("Context", g.context().isEmpty() ? "optional: background the agents read before answering" : g.context().replace('\n', ' '),
                            g.context().isEmpty() ? Theme.DIM : Theme.TEXT, List.of(new Action("Edit", 'c',
                                    () -> ask("Context for " + g.name(), g.context(), text -> run(() -> session.setGroupContext(g.id(), text, g.files()),
                                            "Context saved"))),
                                    new Action("Add file", 'f', () -> ask("File to add to the context", "path to a text file", path -> run(() -> {
                                        java.nio.file.Path file = java.nio.file.Path.of(path.strip()).toAbsolutePath().normalize();
                                        if (!java.nio.file.Files.isRegularFile(file)) {
                                            throw new IllegalArgumentException("there is no file " + path.strip());
                                        }
                                        List<String> now = new ArrayList<>(g.files());
                                        if (!now.contains(file.toString())) {
                                            now.add(file.toString());
                                        }
                                        session.setGroupContext(g.id(), g.context(), now);
                                    }, "File added"))))));
                    for (String f : g.files()) {
                        java.nio.file.Path fp = java.nio.file.Path.of(f);
                        out.add(new Row("  " + fp.getFileName(), fp.getParent() == null ? "" : fp.getParent().toString(), Theme.DIM, List.of(new Action("Remove", 'v',
                                () -> run(() -> session.setGroupContext(g.id(), g.context(), g.files().stream().filter(x -> !x.equals(f)).toList()),
                                        "File removed")))));
                    }
                    out.add(new Row("Rename group", g.name(), Theme.TEXT, List.of(new Action("Rename", 'n',
                            () -> ask("New name", g.name(), name -> run(() -> session.renameGroup(g.id(), name), "Renamed"))))));
                    if (!g.id().equals(ChatSession.MAIN)) {
                        out.add(new Row("Delete group", "the messages stay in memory until you quit", Theme.RED, List.of(new Action("Delete", 'x', () -> {
                            session.deleteGroup(g.id());
                            select.accept(ChatSession.MAIN);
                            close.run();
                        }))));
                    }
                } else {
                    Agent a = session.contact(id);
                    if (a != null) {
                        out.add(new Row("Role", a.role(), Theme.TEXT, List.of()));
                        out.add(new Row("Status", presence(id), Theme.TEXT, List.of()));
                        out.add(new Row("Model", modelLabel.apply(id), Theme.TEXT, List.of()));
                        out.add(new Row("May", String.join(", ", a.capabilities().stream().sorted().toList()), Theme.DIM, List.of()));
                        List<String> in = session.groups().stream().filter(x -> x.has(id)).map(Chat::name).toList();
                        out.add(new Row("Groups", in.isEmpty() ? "none" : String.join(", ", in), Theme.DIM, List.of()));
                        for (Chat other : session.groups()) {
                            if (!other.has(id)) {
                                out.add(new Row("Add to " + other.name(), "", Theme.ACCENT, List.of(new Action("Add", 'a',
                                        () -> run(() -> session.addMember(other.id(), id), id + " joined " + other.name())))));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private String roleOf(String agent) {
        Agent a = session.contact(agent);
        return a == null ? "" : a.role();
    }

    private String presence(String agent) {
        String where = session.agentThread(agent);
        String state = session.agentState(agent);
        if (where == null) {
            return "online";
        }
        Chat g = session.group(where);
        return state + " in " + (g != null ? g.name() : "a direct chat");
    }

    private void run(Runnable r, String done) {
        try {
            r.run();
            ok(done);
        } catch (RuntimeException e) {
            status = e.getMessage();
            statusColor = Theme.RED;
        }
    }

    private void ok(String text) {
        status = text;
        statusColor = Theme.ACCENT;
    }

    private void ask(String title, String initial, Consumer<String> done) {
        prompt = new InputEditor();
        prompt.set(initial);
        promptTitle = title;
        promptDone = done;
    }

    // ---- drawing ----




    void render(Buffer buf, Rect r) {
        hits.clear();
        fill(buf, r, st(Theme.TEXT, Theme.BG));
        fill(buf, new Rect(r.x(), r.y(), r.width(), 2), st(Theme.TEXT, Theme.PANEL));
        putSafe(buf, r.x() + 2, r.y(), title(), st(Theme.TEXT, Theme.PANEL).bold(), r.right() - 10);
        putSafe(buf, r.x() + 2, r.y() + 1, mode == Mode.INFO ? "↑↓ choose · letters or click run the buttons · Esc back" : "↑↓ choose · Enter · Esc back",
                st(Theme.DIM, Theme.PANEL), r.right());
        String closeLabel = " ✕ Esc ";
        int cx = r.right() - CharWidth.of(closeLabel) - 1;
        putSafe(buf, cx, r.y(), closeLabel, st(Theme.DIM, Theme.PANEL), r.right());
        hits.add(new Hit(new Rect(cx, r.y(), CharWidth.of(closeLabel), 1), this::back));

        List<Row> rows = rows();
        index = Math.max(0, Math.min(index, rows.size() - 1));
        int top = r.y() + 3;
        int visible = Math.max(1, (r.height() - 5) / 2);
        if (index < first) {
            first = index;
        } else if (index >= first + visible) {
            first = index - visible + 1;
        }
        int w = Math.min(r.width() - 4, 110);
        int x0 = r.x() + 2;
        for (int i = first; i < rows.size() && i < first + visible; i++) {
            Row row = rows.get(i);
            int y = top + (i - first) * 2;
            boolean sel = i == index;
            Color bg = sel ? Theme.SELECTED : Theme.BG;
            Rect line = new Rect(x0, y, w, 1);
            fill(buf, line, st(Theme.TEXT, bg));
            int tw = putSafe(buf, x0 + 1, y, row.text(), st(row.color(), bg).bold(), x0 + w / 2);
            putSafe(buf, x0 + 3 + tw, y, row.detail(), st(Theme.DIM, bg), x0 + w - 2);
            int idx = i;
            hits.add(new Hit(line, () -> index = idx));
            int bx = x0 + w - 1;
            for (int a = row.actions().size() - 1; a >= 0; a--) {
                Action act = row.actions().get(a);
                String label = " " + act.label() + (act.key() == ' ' ? "" : " " + Character.toUpperCase(act.key())) + " ";
                bx -= CharWidth.of(label) + 1;
                Color btn = act.label().startsWith("Delete") || act.label().equals("Remove") ? Theme.DANGER : act.label().equals("Message") ? Theme.FIELD : Theme.ACCENT;
                putSafe(buf, bx, y, label, st(btn == Theme.ACCENT ? Theme.ON_ACCENT : Theme.TEXT, btn), x0 + w);
                hits.add(new Hit(new Rect(bx, y, CharWidth.of(label), 1), act.run()));
            }
        }
        fill(buf, new Rect(r.x(), r.bottom() - 1, r.width(), 1), st(Theme.DIM, Theme.PANEL));
        putSafe(buf, r.x() + 2, r.bottom() - 1, status, st(statusColor, Theme.PANEL), r.right());
        if (prompt != null) {
            hits.clear();
            int bw = Math.min(r.width() - 4, 64);
            Rect b = new Rect(r.x() + (r.width() - bw) / 2, r.y() + r.height() / 3, bw, 5);
            fill(buf, b, st(Theme.TEXT, Theme.DIALOG));
            putSafe(buf, b.x() + 2, b.y() + 1, promptTitle, st(Theme.TEXT, Theme.DIALOG).bold(), b.right() - 2);
            Rect field = new Rect(b.x() + 2, b.y() + 2, b.width() - 4, 1);
            fill(buf, field, st(Theme.TEXT, Theme.FIELD));
            putSafe(buf, field.x() + 1, field.y(), prompt.text() + "▏", st(Theme.TEXT, Theme.FIELD), field.right());
            putSafe(buf, b.x() + 2, b.y() + 3, "Enter ok · Esc cancel", st(Theme.DIM, Theme.DIALOG), b.right() - 2);
        }
    }

    // ---- input ----

    private void back() {
        if (mode == Mode.ADD_MEMBER) {
            open(Mode.INFO);
        } else {
            close.run();
        }
    }

    void key(KeyEvent key) {
        KeyCode code = key.code();
        if (prompt != null) {
            switch (code) {
                case ESCAPE -> prompt = null;
                case ENTER -> {
                    String v = prompt.text();
                    Consumer<String> d = promptDone;
                    prompt = null;
                    d.accept(v);
                }
                case BACKSPACE -> prompt.backspace();
                case LEFT -> prompt.left();
                case RIGHT -> prompt.right();
                case CHAR -> {
                    if (!key.hasCtrl() && !key.hasAlt() && key.character() >= ' ') {
                        prompt.insert(key.string());
                    }
                }
                default -> { }
            }
            return;
        }
        List<Row> rows = rows();
        Row row = rows.isEmpty() ? null : rows.get(Math.max(0, Math.min(index, rows.size() - 1)));
        switch (code) {
            case ESCAPE -> back();
            case UP -> index = Math.max(0, index - 1);
            case DOWN -> index = Math.min(rows.size() - 1, index + 1);
            case ENTER -> {
                if (row != null && !row.actions().isEmpty()) {
                    row.actions().get(0).run().run();
                }
            }
            case CHAR -> {
                char ch = Character.toLowerCase(key.character());
                if (row != null) {
                    for (Action a : row.actions()) {
                        if (a.key() == ch) {
                            a.run().run();
                            return;
                        }
                    }
                }
                if (ch == 'q') {
                    back();
                }
            }
            default -> { }
        }
    }

    void mouse(MouseEvent m) {
        if (m.kind() == MouseEventKind.SCROLL_UP) {
            index = Math.max(0, index - 1);
        } else if (m.kind() == MouseEventKind.SCROLL_DOWN) {
            index++;
        } else if (m.kind() == MouseEventKind.PRESS && m.isLeftButton()) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i).rect().contains(m.x(), m.y())) {
                    hits.get(i).action().run();
                    return;
                }
            }
        }
    }

    void paste(String text) {
        if (prompt != null) {
            prompt.insert(text.replace('\n', ' '));
        }
    }

}
