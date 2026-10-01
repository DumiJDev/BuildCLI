package dev.buildcli.infrastructure.tui;

import dev.buildcli.application.Settings;
import dev.buildcli.infrastructure.ModelCatalog;
import dev.buildcli.infrastructure.TerminalText;
import dev.buildcli.ports.SettingsStore.Scope;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The settings screen: General, Appearance, Models, Providers and Agents, each saved for this project or for all
 * projects. Everything is reachable by keyboard and mouse. The work (saving, testing a provider, listing models,
 * writing agent files) is done by {@link Services}, so this class only draws and reacts.
 */
final class SettingsView {

    private enum Section { GENERAL("General"), APPEARANCE("Appearance"), MODELS("Models"), PROVIDERS("Providers"), AGENTS("Agents");

        final String label;

        Section(String label) {
            this.label = label;
        }
    }

    private record Item(String label, String value, Color valueColor, String help, Runnable activate, Runnable left, Runnable right,
                        Runnable delete) {}

    private record Hit(Rect rect, Runnable action) {}

    /** A one-line question with an input, answered with Enter. */
    private record Prompt(String title, String hint, InputEditor editor, Consumer<String> onSubmit) {}

    private record Confirm(String text, Runnable yes) {}

    private static final class Picker {
        final String title;
        final Consumer<String> onPick;
        final InputEditor filter = new InputEditor();
        final List<CompletableFuture<ModelCatalog.Result>> sources = new ArrayList<>();
        int index;
        int first;

        Picker(String title, Consumer<String> onPick) {
            this.title = title;
            this.onPick = onPick;
        }
    }

    private final SettingsServices services;
    private final Runnable close;
    private final Consumer<String> open;
    private final Runnable connect;
    private final List<Hit> hits = new ArrayList<>();
    private Section section = Section.GENERAL;
    private Scope scope = Scope.PROJECT;
    private int index;
    private int firstVisible;
    private Prompt prompt;
    private Confirm confirm;
    private Picker picker;
    private String status = "";
    private Color statusColor = Theme.DIM;

    SettingsView(SettingsServices services, Runnable close, Consumer<String> open, Runnable connect) {
        this.services = services;
        this.close = close;
        this.open = open;
        this.connect = connect;
    }

    // ---- items of each section ----

    private List<Item> items() {
        Settings s = services.settings();
        List<Item> out = new ArrayList<>();
        switch (section) {
            case GENERAL, APPEARANCE -> {
                for (Settings.Definition d : Settings.section(section.label)) {
                    out.add(settingItem(s, d));
                }
                if (section == Section.GENERAL) {
                    out.add(new Item("Where settings are saved", scope == Scope.PROJECT ? "this project" : "all projects", Theme.DIM,
                            "Project settings win over the ones for all projects. Press S or click the switch above to change.",
                            this::toggleScope, this::toggleScope, this::toggleScope, null));
                }
            }
            case MODELS -> {
                out.add(settingItem(s, Settings.DEFINITIONS.stream().filter(d -> d.key().equals(Settings.DEFAULT_MODEL)).findFirst().orElseThrow()));
                for (SettingsServices.AgentInfo a : services.agents()) {
                    String key = Settings.AGENT_MODEL + a.name();
                    String own = s.stored(scope, key);
                    String effective = s.modelFor(a.name());
                    String team = services.teamModel(a.name());
                    String value = effective != null ? effective : team != null ? team + "  (team)" : s.defaultModel() != null
                            ? s.defaultModel() + "  (default)" : "not set";
                    out.add(new Item(a.name(), value, effective != null ? Theme.TEXT : Theme.DIM,
                            (own != null ? "Set " + scopeLabel() + ". " : "") + "Enter to choose a model · Del to go back to the team's",
                            () -> pickModel("Model for " + a.name(), m -> save(key, m)), null, null, () -> save(key, null)));
                }
            }
            case PROVIDERS -> {
                for (SettingsServices.Provider p : services.providers()) {
                    boolean needsKey = p.keyEnv() != null && !p.keySet();
                    String key = p.keyEnv() == null ? "no key needed" : !p.keySet() ? "no key yet"
                            : p.keyFrom().equals("saved") ? "key saved" : "key from " + p.keyEnv();
                    out.add(new Item(p.name(), key, p.keyEnv() == null || p.keySet() ? Theme.GREEN : Theme.AMBER,
                            p.url() + (needsKey ? "   ·  Enter to add your key" : "   ·  Enter to test") + (p.removable() ? " · Del to remove" : ""),
                            needsKey ? connect : () -> testProvider(p.name()), null, null, p.removable() ? () -> confirm("Remove provider " + p.name() + "?", () -> {
                                try {
                                    services.removeProvider(p.name());
                                    ok("Removed " + p.name());
                                } catch (Exception e) {
                                    fail(e.getMessage());
                                }
                            }) : null));
                }
                out.add(new Item("+ Add a provider", "", Theme.ACCENT, "Any OpenAI-compatible endpoint: vLLM, a company gateway, another cloud",
                        this::addProvider, null, null, null));
            }
            case AGENTS -> {
                for (SettingsServices.AgentInfo a : services.agents()) {
                    out.add(new Item(a.name(), a.role() + " · " + a.origin(), Theme.agentColor(a.name()),
                            String.join(", ", a.capabilities()) + "   ·  Enter to view · Del to delete",
                            () -> open.accept(a.file()), null, null,
                            () -> confirm("Delete agent " + a.name() + "? This deletes " + a.file(), () -> {
                                try {
                                    services.deleteAgent(a.name());
                                    ok("Deleted " + a.name() + "; it left every group.");
                                } catch (Exception e) {
                                    fail(e.getMessage());
                                }
                            })));
                }
                out.add(new Item("+ New agent", "", Theme.ACCENT, "Name, role, what it may do; saved " + scopeLabel(), this::newAgent, null, null, null));
                out.add(new Item("+ Sample team", "", Theme.ACCENT, "ana (architect), bruno (developer), carla (reviewer) in a group, for this project",
                        this::addSamples, null, null, null));
            }
            default -> { }
        }
        return out;
    }

    private Item settingItem(Settings s, Settings.Definition d) {
        String stored = s.stored(scope, d.key());
        String effective = s.get(d.key());
        String origin = stored != null ? scopeLabel() : s.stored(other(), d.key()) != null ? (other() == Scope.PROJECT ? "this project" : "all projects") : "default";
        String help = d.help() + "   ·  " + origin + (stored != null ? " · Del to reset" : "");
        Runnable reset = () -> save(d.key(), null);
        return switch (d.type()) {
            case BOOLEAN -> {
                boolean on = Boolean.parseBoolean(effective);
                Runnable toggle = () -> save(d.key(), Boolean.toString(!on));
                yield new Item(d.label(), on ? "● On" : "○ Off", on ? Theme.ACCENT : Theme.DIM, help, toggle, toggle, toggle, reset);
            }
            case CHOICE -> {
                int i = Math.max(0, d.choices().indexOf(effective));
                Runnable next = () -> save(d.key(), d.choices().get((i + 1) % d.choices().size()));
                Runnable prev = () -> save(d.key(), d.choices().get((i - 1 + d.choices().size()) % d.choices().size()));
                yield new Item(d.label(), "‹ " + effective + " ›", Theme.TEXT, help, next, prev, next, reset);
            }
            case MODEL -> new Item(d.label(), effective == null || effective.isBlank() ? "not set" : effective,
                    effective == null || effective.isBlank() ? Theme.DIM : Theme.TEXT, help,
                    () -> pickModel(d.label(), m -> save(d.key(), m)), null, null, reset);
            default -> new Item(d.label(), effective == null ? "" : effective, Theme.TEXT, help,
                    () -> ask(d.label(), d.help(), effective == null ? "" : effective, v -> {
                        if (d.type() == Settings.Type.NUMBER && !v.isBlank() && !v.strip().matches("\\d{1,4}")) {
                            fail("A whole number, please");
                            return;
                        }
                        save(d.key(), v);
                    }), null, null, reset);
        };
    }

    private Scope other() {
        return scope == Scope.PROJECT ? Scope.GLOBAL : Scope.PROJECT;
    }

    private String scopeLabel() {
        return scope == Scope.PROJECT ? "for this project" : "for all projects";
    }

    private void toggleScope() {
        scope = other();
        ok("Changes are now saved " + scopeLabel());
    }

    private void save(String key, String value) {
        try {
            services.settings().set(scope, key, value);
            ok(value == null ? "Reset " + scopeLabel() : "Saved " + scopeLabel());
        } catch (RuntimeException e) {
            fail(e.getMessage());
        }
    }

    private void ok(String text) {
        status = text;
        statusColor = Theme.ACCENT;
    }

    private void fail(String text) {
        status = text == null ? "Failed" : text;
        statusColor = Theme.RED;
    }

    // ---- flows ----

    private void ask(String title, String hint, String initial, Consumer<String> onSubmit) {
        InputEditor e = new InputEditor();
        e.set(initial);
        prompt = new Prompt(title, hint, e, onSubmit);
    }

    private void confirm(String text, Runnable yes) {
        confirm = new Confirm(text, yes);
    }

    private void addProvider() {
        ask("New provider: name", "lowercase, used as name:model", "", name -> ask("URL of " + name, "e.g. http://gpu-box:8000/v1", "https://",
                url -> ask("Environment variable with the key", "leave empty if it needs none; the key itself is never stored", "",
                        env -> {
                            try {
                                services.addProvider(name.strip(), url.strip(), env.isBlank() ? null : env.strip());
                                ok("Added " + name + (env.isBlank() ? "" : ". Set " + env + " before starting BuildCLI."));
                            } catch (Exception e) {
                                fail(e.getMessage());
                            }
                        })));
    }

    private void newAgent() {
        ask("New agent: name", "lowercase letters, digits, - and _", "", name -> ask("Role of " + name, "e.g. reviewer, tester, writer", "developer",
                role -> ask("What " + name + " may do", "comma-separated: " + String.join(", ", dev.buildcli.domain.Capability.KNOWN.stream().sorted().toList()),
                        "filesystem.read, search", caps -> ask("How " + name + " should work", "one sentence; edit the file later for more", "",
                                how -> {
                                    try {
                                        String file = services.createAgent(name.strip(), role.strip(), how.strip(),
                                                Arrays.stream(caps.split(",")).map(String::strip).filter(c -> !c.isEmpty()).toList(), scope == Scope.GLOBAL);
                                        ok("Created " + file + ". You can chat with " + name.strip() + " now.");
                                    } catch (Exception e) {
                                        fail(e.getMessage());
                                    }
                                }))));
    }

    private void addSamples() {
        try {
            ok("Added " + String.join(", ", services.createSampleAgents()) + " and a group 'backend'.");
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    private void testProvider(String provider) {
        String suggestion = provider.equals("openrouter") ? "openrouter:openrouter/free" : provider + ":";
        ask("Test " + provider, "provider:model to send a one-word request to", suggestion, model -> {
            status = "Testing " + model + "…";
            statusColor = Theme.DIM;
            services.test(model.strip()).thenAccept(line -> {
                if (line.startsWith("ok")) {
                    ok(line);
                } else {
                    fail(line);
                }
            });
        });
    }

    private void pickModel(String title, Consumer<String> onPick) {
        Picker p = new Picker(title, onPick);
        for (SettingsServices.Provider pr : services.providers()) {
            if (pr.keyEnv() == null || pr.keySet()) {
                p.sources.add(services.models(pr.name()));
            }
        }
        picker = p;
    }

    private List<ModelCatalog.Model> pickerItems() {
        List<ModelCatalog.Model> all = new ArrayList<>();
        String f = picker.filter.text().strip().toLowerCase(Locale.ROOT);
        for (var src : picker.sources) {
            ModelCatalog.Result r = src.getNow(null);
            if (r != null) {
                for (ModelCatalog.Model m : r.models()) {
                    if (f.isEmpty() || m.ref().toLowerCase(Locale.ROOT).contains(f) || m.note().toLowerCase(Locale.ROOT).contains(f)) {
                        all.add(m);
                    }
                }
            }
        }
        return all;
    }

    // ---- drawing ----

    private static void fill(Buffer buf, Rect r, Style style) {
        if (r.width() > 0 && r.height() > 0) {
            buf.fill(r, new Cell(" ", style));
        }
    }

    private static int put(Buffer buf, int x, int y, String text, Style style, int limit) {
        if (x >= limit || text.isEmpty()) {
            return 0;
        }
        String clipped = CharWidth.substringByWidth(TerminalText.sanitize(text), limit - x);
        buf.setString(x, y, clipped, style);
        return CharWidth.of(clipped);
    }

    private static Style st(Color fg, Color bg) {
        return Theme.on(fg, bg);
    }

    void render(Buffer buf, Rect r) {
        hits.clear();
        fill(buf, r, st(Theme.TEXT, Theme.BG));
        Rect bar = new Rect(r.x(), r.y(), r.width(), 2);
        fill(buf, bar, st(Theme.TEXT, Theme.PANEL));
        put(buf, r.x() + 2, r.y(), "Settings", st(Theme.TEXT, Theme.PANEL).bold(), r.right());
        put(buf, r.x() + 2, r.y() + 1, "Saved " + scopeLabel(), st(Theme.DIM, Theme.PANEL), r.right());
        int x = r.x() + 14;
        for (Scope sc : new Scope[] {Scope.PROJECT, Scope.GLOBAL}) {
            String label = sc == Scope.PROJECT ? " This project " : " All projects ";
            boolean on = sc == scope;
            int w = put(buf, x, r.y(), label, on ? st(Theme.ON_ACCENT, Theme.ACCENT).bold() : st(Theme.TEXT, Theme.FIELD), r.right());
            hits.add(new Hit(new Rect(x, r.y(), w, 1), () -> {
                if (scope != sc) {
                    toggleScope();
                }
            }));
            x += w + 1;
        }
        String closeLabel = " ✕ Esc ";
        int cx = r.right() - CharWidth.of(closeLabel) - 1;
        put(buf, cx, r.y(), closeLabel, st(Theme.DIM, Theme.PANEL), r.right());
        hits.add(new Hit(new Rect(cx, r.y(), CharWidth.of(closeLabel), 1), close));

        int navW = 18;
        Rect nav = new Rect(r.x(), r.y() + 2, navW, r.height() - 3);
        fill(buf, nav, st(Theme.TEXT, Theme.SIDEBAR));
        int ny = nav.y() + 1;
        for (Section s : Section.values()) {
            boolean on = s == section;
            Rect row = new Rect(nav.x(), ny, navW, 1);
            fill(buf, row, st(Theme.TEXT, on ? Theme.SELECTED : Theme.SIDEBAR));
            put(buf, row.x() + 2, ny, (on ? "▌ " : "  ") + s.label, on ? st(Theme.TEXT, Theme.SELECTED).bold() : st(Theme.DIM, Theme.SIDEBAR), row.right());
            hits.add(new Hit(row, () -> select(s)));
            ny += 2;
        }

        Rect content = new Rect(r.x() + navW + 2, r.y() + 3, r.width() - navW - 4, r.height() - 5);
        List<Item> items = items();
        index = Math.max(0, Math.min(index, items.size() - 1));
        int per = 3;
        int visible = Math.max(1, content.height() / per);
        if (index < firstVisible) {
            firstVisible = index;
        } else if (index >= firstVisible + visible) {
            firstVisible = index - visible + 1;
        }
        for (int i = firstVisible; i < items.size() && i < firstVisible + visible; i++) {
            Item it = items.get(i);
            int y = content.y() + (i - firstVisible) * per;
            boolean sel = i == index;
            Color bg = sel ? Theme.SELECTED : Theme.BG;
            Rect row = new Rect(content.x(), y, content.width(), 2);
            fill(buf, row, st(Theme.TEXT, bg));
            put(buf, row.x() + 1, y, it.label(), st(Theme.TEXT, bg).bold(), row.right() - 2);
            int vw = CharWidth.of(TerminalText.sanitize(it.value()));
            put(buf, Math.max(row.x() + 24, row.right() - vw - 2), y, it.value(), st(it.valueColor(), bg), row.right() - 1);
            put(buf, row.x() + 1, y + 1, it.help(), st(Theme.DIM, bg), row.right() - 1);
            int idx = i;
            hits.add(new Hit(row, () -> {
                index = idx;
                it.activate().run();
            }));
        }
        if (items.size() > visible) {
            put(buf, content.right() - 12, content.bottom(), (firstVisible + 1) + "–" + Math.min(items.size(), firstVisible + visible) + " of " + items.size(),
                    st(Theme.FAINT, Theme.BG), content.right());
        }
        Rect foot = new Rect(r.x(), r.bottom() - 1, r.width(), 1);
        fill(buf, foot, st(Theme.DIM, Theme.PANEL));
        String keys = "↑↓ choose · Enter change · ←→ switch · Del reset · Tab section · S scope · Esc close";
        put(buf, foot.x() + navW + 2, foot.y(), status.isEmpty() ? keys : status, st(status.isEmpty() ? Theme.DIM : statusColor, Theme.PANEL), foot.right());

        if (picker != null || prompt != null || confirm != null) {
            hits.clear(); // a dialog is modal: clicks outside it do nothing
        }
        if (picker != null) {
            drawPicker(buf, r);
        } else if (prompt != null) {
            drawPrompt(buf, r);
        } else if (confirm != null) {
            drawConfirm(buf, r);
        }
    }

    private Rect box(Rect r, int w, int h) {
        int bw = Math.min(r.width() - 4, w);
        int bh = Math.min(r.height() - 2, h);
        return new Rect(r.x() + (r.width() - bw) / 2, r.y() + Math.max(1, (r.height() - bh) / 2), bw, bh);
    }

    private void frame(Buffer buf, Rect b, String title) {
        fill(buf, b, st(Theme.TEXT, Theme.DIALOG));
        Style border = st(Theme.FAINT, Theme.DIALOG);
        put(buf, b.x(), b.y(), "╭" + "─".repeat(b.width() - 2) + "╮", border, b.right());
        for (int y = b.y() + 1; y < b.bottom() - 1; y++) {
            put(buf, b.x(), y, "│", border, b.right());
            put(buf, b.right() - 1, y, "│", border, b.right());
        }
        put(buf, b.x(), b.bottom() - 1, "╰" + "─".repeat(b.width() - 2) + "╯", border, b.right());
        put(buf, b.x() + 2, b.y(), " " + title + " ", st(Theme.TEXT, Theme.DIALOG).bold(), b.right() - 2);
    }

    private void drawPrompt(Buffer buf, Rect r) {
        Rect b = box(r, 70, 7);
        frame(buf, b, prompt.title());
        put(buf, b.x() + 2, b.y() + 1, prompt.hint(), st(Theme.DIM, Theme.DIALOG), b.right() - 2);
        Rect field = new Rect(b.x() + 2, b.y() + 3, b.width() - 4, 1);
        fill(buf, field, st(Theme.TEXT, Theme.FIELD));
        String t = prompt.editor().text();
        int avail = field.width() - 2;
        String shown = CharWidth.of(t) > avail ? CharWidth.substringByWidthFromEnd(t, avail) : t;
        put(buf, field.x() + 1, field.y(), shown + "▏", st(Theme.TEXT, Theme.FIELD), field.right());
        put(buf, b.x() + 2, b.bottom() - 2, "Enter save · Esc cancel", st(Theme.DIM, Theme.DIALOG), b.right() - 2);
    }

    private void drawConfirm(Buffer buf, Rect r) {
        List<String> lines = Wrap.lines(confirm.text(), 60);
        Rect b = box(r, 66, lines.size() + 5);
        frame(buf, b, "Are you sure?");
        for (int i = 0; i < lines.size(); i++) {
            put(buf, b.x() + 2, b.y() + 1 + i, lines.get(i), st(Theme.TEXT, Theme.DIALOG), b.right() - 2);
        }
        int y = b.bottom() - 2;
        int w1 = put(buf, b.x() + 2, y, " Yes  Y ", st(Theme.TEXT, Theme.DANGER).bold(), b.right());
        hits.add(new Hit(new Rect(b.x() + 2, y, w1, 1), this::confirmYes));
        int w2 = put(buf, b.x() + 4 + w1, y, " No  N ", st(Theme.TEXT, Theme.FIELD), b.right());
        hits.add(new Hit(new Rect(b.x() + 4 + w1, y, w2, 1), () -> confirm = null));
    }

    private void confirmYes() {
        Runnable yes = confirm.yes();
        confirm = null;
        yes.run();
    }

    private void drawPicker(Buffer buf, Rect r) {
        Rect b = box(r, 90, r.height() - 4);
        frame(buf, b, picker.title);
        Rect field = new Rect(b.x() + 2, b.y() + 1, b.width() - 4, 1);
        fill(buf, field, st(Theme.TEXT, Theme.FIELD));
        String f = picker.filter.text();
        put(buf, field.x() + 1, field.y(), f.isEmpty() ? "Search models, or type provider:model and press Enter▏" : f + "▏",
                st(f.isEmpty() ? Theme.DIM : Theme.TEXT, Theme.FIELD), field.right());
        List<ModelCatalog.Model> items = pickerItems();
        int rows = b.height() - 5;
        picker.index = Math.max(0, Math.min(picker.index, items.size() - 1));
        if (picker.index < picker.first) {
            picker.first = picker.index;
        } else if (picker.index >= picker.first + rows) {
            picker.first = picker.index - rows + 1;
        }
        int loading = 0;
        List<String> problems = new ArrayList<>();
        for (var src : picker.sources) {
            ModelCatalog.Result res = src.getNow(null);
            if (res == null) {
                loading++;
            } else if (res.problem() != null) {
                problems.add(res.problem());
            }
        }
        for (int i = 0; i < rows && picker.first + i < items.size(); i++) {
            ModelCatalog.Model m = items.get(picker.first + i);
            boolean sel = picker.first + i == picker.index;
            Color bg = sel ? Theme.SELECTED : Theme.DIALOG;
            Rect row = new Rect(b.x() + 1, b.y() + 3 + i, b.width() - 2, 1);
            fill(buf, row, st(Theme.TEXT, bg));
            int w = put(buf, row.x() + 1, row.y(), m.ref(), st(m.tools() ? Theme.TEXT : Theme.DIM, bg), row.right() - 2);
            put(buf, row.x() + 3 + w, row.y(), m.note(), st(m.free() ? Theme.ACCENT : Theme.DIM, bg), row.right() - 1);
            int idx = picker.first + i;
            hits.add(new Hit(row, () -> {
                picker.index = idx;
                pickSelected();
            }));
        }
        String info = loading > 0 ? "Loading models from " + loading + " provider(s)…"
                : items.size() + " models" + (problems.isEmpty() ? "" : " · " + String.join(" · ", problems));
        put(buf, b.x() + 2, b.bottom() - 2, info + "   ·  ↑↓ Enter pick · Esc cancel", st(Theme.DIM, Theme.DIALOG), b.right() - 2);
    }

    private void pickSelected() {
        List<ModelCatalog.Model> items = pickerItems();
        String typed = picker.filter.text().strip();
        String choice = typed.contains(":") && (items.isEmpty() || items.stream().noneMatch(m -> m.ref().equals(typed)) && picker.index == 0
                && !items.get(0).ref().toLowerCase(Locale.ROOT).contains(typed.toLowerCase(Locale.ROOT)))
                ? typed : items.isEmpty() ? null : items.get(picker.index).ref();
        if (choice == null) {
            fail("Type provider:model, e.g. openrouter:openrouter/free");
            return;
        }
        Consumer<String> onPick = picker.onPick;
        picker = null;
        onPick.accept(choice);
    }

    // ---- input ----

    /** Opens the Agents section on the "new agent" questions (from the empty chat screen). */
    void startNewAgent() {
        select(Section.AGENTS);
        newAgent();
    }

    /** Opens the Models section: the default model and the model of each agent. */
    void showModels() {
        select(Section.MODELS);
    }

    private void select(Section s) {
        section = s;
        index = 0;
        firstVisible = 0;
        status = "";
    }

    void key(KeyEvent key) {
        KeyCode code = key.code();
        char ch = code == KeyCode.CHAR ? Character.toLowerCase(key.character()) : 0;
        if (picker != null) {
            switch (code) {
                case ESCAPE -> picker = null;
                case UP -> picker.index = Math.max(0, picker.index - 1);
                case DOWN -> picker.index++;
                case PAGE_DOWN -> picker.index += 10;
                case PAGE_UP -> picker.index = Math.max(0, picker.index - 10);
                case ENTER -> pickSelected();
                default -> edit(picker.filter, key);
            }
            if (code == KeyCode.CHAR || code == KeyCode.BACKSPACE) {
                picker.index = 0;
                picker.first = 0;
            }
            return;
        }
        if (prompt != null) {
            switch (code) {
                case ESCAPE -> prompt = null;
                case ENTER -> {
                    Prompt p = prompt;
                    prompt = null;
                    p.onSubmit().accept(p.editor().text());
                }
                default -> edit(prompt.editor(), key);
            }
            return;
        }
        if (confirm != null) {
            if (ch == 'y' || code == KeyCode.ENTER) {
                confirmYes();
            } else if (ch == 'n' || code == KeyCode.ESCAPE) {
                confirm = null;
            }
            return;
        }
        List<Item> items = items();
        Item it = items.isEmpty() ? null : items.get(Math.max(0, Math.min(index, items.size() - 1)));
        switch (code) {
            case ESCAPE -> close.run();
            case UP -> index = Math.max(0, index - 1);
            case DOWN -> index = Math.min(items.size() - 1, index + 1);
            case TAB -> select(Section.values()[(section.ordinal() + (key.hasShift() ? Section.values().length - 1 : 1)) % Section.values().length]);
            case ENTER -> run(it == null ? null : it.activate());
            case LEFT -> run(it == null ? null : it.left());
            case RIGHT -> run(it == null ? null : it.right());
            case DELETE, BACKSPACE -> run(it == null ? null : it.delete());
            case CHAR -> {
                if (ch == ' ') {
                    run(it == null ? null : it.activate());
                } else if (ch == 's') {
                    toggleScope();
                } else if (ch == 'q') {
                    close.run();
                } else if (ch >= '1' && ch <= '5') {
                    select(Section.values()[ch - '1']);
                }
            }
            default -> { }
        }
    }

    private static void run(Runnable r) {
        if (r != null) {
            r.run();
        }
    }

    private static void edit(InputEditor e, KeyEvent key) {
        switch (key.code()) {
            case BACKSPACE -> e.backspace();
            case DELETE -> e.delete();
            case LEFT -> e.left();
            case RIGHT -> e.right();
            case HOME -> e.home();
            case END -> e.end();
            case CHAR -> {
                if (key.hasCtrl() && Character.toLowerCase(key.character()) == 'u') {
                    e.clear();
                } else if (!key.hasCtrl() && !key.hasAlt() && key.character() >= ' ') {
                    e.insert(key.string());
                }
            }
            default -> { }
        }
    }

    void paste(String text) {
        if (picker != null) {
            picker.filter.insert(text.strip());
        } else if (prompt != null) {
            prompt.editor().insert(text.replace('\n', ' '));
        }
    }

    void mouse(MouseEvent m) {
        if (m.kind() == MouseEventKind.SCROLL_UP || m.kind() == MouseEventKind.SCROLL_DOWN) {
            int d = m.kind() == MouseEventKind.SCROLL_UP ? -1 : 1;
            if (picker != null) {
                picker.index = Math.max(0, picker.index + d * 3);
            } else {
                index = Math.max(0, index + d);
            }
            return;
        }
        if (m.kind() == MouseEventKind.PRESS && m.isLeftButton()) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i).rect().contains(m.x(), m.y())) {
                    hits.get(i).action().run();
                    return;
                }
            }
        }
    }
}
