package dev.buildcli.infrastructure.tui;

import dev.buildcli.application.Settings;
import dev.buildcli.infrastructure.FileCredentialStore;
import dev.buildcli.infrastructure.ModelCatalog;
import dev.buildcli.infrastructure.ProviderRegistry;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Connecting a model in three steps: choose a provider (each one says what it still needs: a key, a running server, a
 * model), choose one of its models, and send it a test message. Only a model that answered becomes the default, for this
 * project or for all projects. It opens by itself when no agent has a model, and from /connect or a failed message.
 */
final class ConnectView {

    private enum Step { PROVIDER, MODEL, TEST }

    private record Hit(Rect rect, Runnable action) {}

    /** What a provider still needs, in one short line and a few lines of help. */
    private record Status(String text, Color color, boolean ready, List<String> help) {}

    private static final String SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";

    private final SettingsServices services;
    private final Consumer<String> finished;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, CompletableFuture<ModelCatalog.Result>> probes = new HashMap<>();
    private final InputEditor filter = new InputEditor();
    private List<SettingsServices.Provider> providers = List.of();
    private Step step = Step.PROVIDER;
    private String reason;
    private int index;
    private int first;
    private SettingsServices.Provider provider;
    private int modelIndex;
    private int modelFirst;
    private String model;
    private CompletableFuture<String> test;
    private long testStarted;
    /** Typing a key: for which provider, what is typed, the check in flight and what to tell the user about it. */
    private boolean enteringKey;
    private SettingsServices.Provider keyFor;
    private final InputEditor keyInput = new InputEditor();
    private CompletableFuture<ModelCatalog.Result> keyCheck;
    private String keyChecking = "";
    private String keyMessage = "";
    private Color keyColor = Theme.DIM;
    /** The key a second Enter saves without a successful check (the provider could not be reached). */
    private String saveAnyway;
    /** After saving a key: the provider to open the model list of once its models have loaded. */
    private String advanceTo;
    private String notice = "";
    private String forgetArmed;

    /** @param finished receives what to tell the user in the chat, or null when they closed without choosing */
    ConnectView(SettingsServices services, Consumer<String> finished) {
        this.services = services;
        this.finished = finished;
    }

    /** Starts over on the provider list. @param why shown on top, e.g. why the last message failed; may be null */
    void open(String why) {
        reason = why;
        step = Step.PROVIDER;
        index = 0;
        first = 0;
        provider = null;
        model = null;
        test = null;
        enteringKey = false;
        notice = "";
        forgetArmed = null;
        providers = ordered(services.providers());
        check();
    }

    /** Local servers first, then providers whose key is set, then the ones that still need a key. */
    private static List<SettingsServices.Provider> ordered(List<SettingsServices.Provider> all) {
        List<SettingsServices.Provider> out = new ArrayList<>(all);
        out.sort(Comparator.comparingInt(p -> p.local() ? 0 : p.keyEnv() == null || p.keySet() ? 1 : 2));
        return out;
    }

    private void check() {
        probes.clear();
        for (SettingsServices.Provider p : providers) {
            if (p.keyEnv() == null || p.keySet()) {
                probes.put(p.name(), services.models(p.name()));
            }
        }
    }

    private void recheck() {
        services.refreshModels();
        providers = ordered(services.providers());
        check();
    }

    /** True while something on screen changes by itself: providers being checked, the test message on its way. */
    boolean animating() {
        if (keyCheck != null && !keyCheck.isDone()) {
            return true;
        }
        if (step == Step.TEST) {
            return test != null && !test.isDone();
        }
        return probes.values().stream().anyMatch(f -> !f.isDone());
    }

    // ---- what each provider needs ----

    private ModelCatalog.Result result(SettingsServices.Provider p) {
        CompletableFuture<ModelCatalog.Result> f = probes.get(p.name());
        return f == null ? null : f.getNow(null);
    }

    private Status status(SettingsServices.Provider p) {
        if (p.keyEnv() != null && !p.keySet()) {
            return new Status("needs a key", Theme.AMBER, false, keyHelp(p));
        }
        ModelCatalog.Result r = result(p);
        if (r == null) {
            return new Status("checking…", Theme.DIM, false, List.of("Checking " + p.url() + " …"));
        }
        if (r.problem() != null) {
            if (p.local()) {
                return new Status("not running", Theme.DIM, false, localHelp(p));
            }
            boolean rejected = r.problem().startsWith("HTTP 401") || r.problem().startsWith("HTTP 403");
            if (rejected && p.keyEnv() != null) {
                return new Status("key rejected", Theme.RED, false, List.of(r.problem(), "",
                        p.keyFrom().equals("environment") ? "The key comes from the environment variable " + p.keyEnv() + ". Fix it there, or press K to save "
                                + "a different key here (a variable that is set wins, so unset it first)."
                                : "Press K to enter a new key."));
            }
            return new Status(p.keyEnv() == null ? "not reachable" : "key or endpoint failed", Theme.RED, false,
                    List.of(r.problem(), "", "Check the URL " + p.url() + (p.keyEnv() == null ? "" : " and the key (press K to enter a new one)")
                            + ", then press R to check again."));
        }
        if (r.models().isEmpty()) {
            return new Status("no models yet", Theme.AMBER, false, p.name().equals("ollama")
                    ? List.of("Ollama is running but has no models. Download one in a terminal:", "",
                            "    ollama pull qwen2.5-coder:7b", "", "then press R. Models of 7B or more work much better with tools.")
                    : List.of(p.name() + " answered but lists no models. Press Enter to type a model name yourself."));
        }
        long free = r.models().stream().filter(ModelCatalog.Model::free).count();
        String count = r.models().size() + (r.models().size() == 1 ? " model" : " models") + (p.local() || free == 0 ? "" : ", " + free + " free");
        return new Status("ready · " + count, Theme.GREEN, true, List.of((p.description().isBlank() ? p.name() : p.description())
                + ". Press Enter to choose a model."));
    }

    private List<String> keyHelp(SettingsServices.Provider p) {
        List<String> help = new ArrayList<>();
        String page = ProviderRegistry.keyPage(p.name());
        if (!p.description().isBlank()) {
            help.add(p.description() + ".");
            help.add("");
        }
        help.add("1. " + (page != null ? "Create a key at " + page : "Get a key from the provider"));
        help.add("2. Press Enter here and paste it. It is saved only on this computer, readable by your account only.");
        help.add("");
        help.add("Prefer an environment variable? Set " + p.keyEnv() + " before starting BuildCLI; it always wins over a saved key.");
        return help;
    }

    private static List<String> localHelp(SettingsServices.Provider p) {
        return switch (p.name()) {
            case "ollama" -> List.of("Ollama runs models on this machine, free and private.", "",
                    "1. Install it from https://ollama.com", "2. Start it:  ollama serve", "3. Download a model:  ollama pull qwen2.5-coder:7b",
                    "4. Press R to check again.");
            case "lmstudio" -> List.of("LM Studio runs models on this machine, free and private.", "",
                    "1. Install it from https://lmstudio.ai and download a model", "2. Start its server: Developer tab › Start server",
                    "3. Press R to check again.");
            default -> List.of("Nothing answers at " + p.url() + ". Start the server, then press R to check again.");
        };
    }

    // ---- models ----

    private List<ModelCatalog.Model> models() {
        ModelCatalog.Result r = provider == null ? null : result(provider);
        if (r == null) {
            return List.of();
        }
        String f = filter.text().strip().toLowerCase(Locale.ROOT);
        List<ModelCatalog.Model> out = new ArrayList<>();
        for (ModelCatalog.Model m : r.models()) {
            if (f.isEmpty() || m.ref().toLowerCase(Locale.ROOT).contains(f) || m.note().toLowerCase(Locale.ROOT).contains(f)) {
                out.add(m);
            }
        }
        // the router of free models first, then free ones, then models that can use tools
        out.sort(Comparator.comparingInt((ModelCatalog.Model m) -> m.ref().endsWith("openrouter/free") ? 0 : 1)
                .thenComparingInt(m -> m.free() ? 0 : 1)
                .thenComparingInt(m -> m.tools() ? 0 : 1)
                .thenComparing(ModelCatalog.Model::ref));
        return out;
    }

    // ---- steps ----

    private void chooseProvider(int i) {
        if (i < 0 || i >= providers.size()) {
            return;
        }
        index = i;
        SettingsServices.Provider p = providers.get(i);
        if (p.keyEnv() != null && !p.keySet()) {
            startKey(p);
            return;
        }
        ModelCatalog.Result r = result(p);
        if (r == null || r.problem() != null) {
            return; // the help under the list says what is missing
        }
        provider = p;
        filter.clear();
        modelIndex = 0;
        modelFirst = 0;
        step = Step.MODEL;
    }

    // ---- the key ----

    private void startKey(SettingsServices.Provider p) {
        enteringKey = true;
        keyFor = p;
        keyInput.clear();
        keyCheck = null;
        keyMessage = "";
        keyColor = Theme.DIM;
        saveAnyway = null;
        notice = "";
        forgetArmed = null;
    }

    private void submitKey() {
        if (keyCheck != null && !keyCheck.isDone()) {
            return;
        }
        String key = FileCredentialStore.clean(keyInput.text());
        if (!FileCredentialStore.valid(key)) {
            keyMessage = key.isEmpty() ? "Paste the key first." : "That does not look like a key: it has spaces or line breaks.";
            keyColor = Theme.RED;
            return;
        }
        if (key.equals(saveAnyway)) {
            saveKey(key);
            return;
        }
        saveAnyway = null;
        keyChecking = key;
        keyMessage = "Checking the key with " + keyFor.name() + "…";
        keyColor = Theme.DIM;
        keyCheck = services.checkKey(keyFor.name(), key);
    }

    /** Called every frame: acts on a finished key check. */
    private void pollKey() {
        if (keyCheck != null && keyCheck.isDone() && enteringKey) {
            ModelCatalog.Result r = keyCheck.getNow(null);
            keyCheck = null;
            String problem = r == null ? "no answer" : r.problem();
            if (problem == null) {
                saveKey(keyChecking);
            } else if (problem.startsWith("HTTP 401") || problem.startsWith("HTTP 403")) {
                keyMessage = keyFor.name() + " did not accept that key (" + problem + "). Check it and paste it again.";
                keyColor = Theme.RED;
                keyInput.clear();
            } else {
                keyMessage = "Could not check it: " + problem + ". Press Enter again to save it anyway, or paste a different one.";
                keyColor = Theme.AMBER;
                saveAnyway = keyChecking;
            }
        }
        if (advanceTo != null) {
            for (int i = 0; i < providers.size(); i++) {
                SettingsServices.Provider p = providers.get(i);
                if (p.name().equals(advanceTo) && probes.get(p.name()) != null && probes.get(p.name()).isDone()) {
                    advanceTo = null;
                    index = i;
                    ModelCatalog.Result r = result(p);
                    if (r != null && r.problem() == null) {
                        chooseProvider(i);
                    }
                    return;
                }
            }
        }
    }

    private void saveKey(String key) {
        try {
            services.saveKey(keyFor.name(), key);
        } catch (Exception e) {
            keyMessage = "Could not save it: " + e.getMessage();
            keyColor = Theme.RED;
            return;
        }
        String name = keyFor.name();
        enteringKey = false;
        keyInput.clear();
        saveAnyway = null;
        notice = "Key saved. Looking for " + name + "'s models…";
        advanceTo = name;
        recheck();
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).name().equals(name)) {
                index = i; // the list is sorted by readiness, so the provider moved
            }
        }
    }

    private void forgetKey(SettingsServices.Provider p) {
        try {
            notice = services.forgetKey(p.name()) ? "Forgot the saved key for " + p.name() + "." : "There was no saved key to forget.";
        } catch (Exception e) {
            notice = "Could not forget it: " + e.getMessage();
        }
        forgetArmed = null;
        recheck();
    }

    private void chooseModel() {
        List<ModelCatalog.Model> items = models();
        String typed = filter.text().strip();
        String choice;
        if (items.isEmpty()) {
            if (typed.isEmpty()) {
                return;
            }
            choice = typed.contains(":") ? typed : provider.name() + ":" + typed;
        } else {
            choice = items.get(Math.max(0, Math.min(modelIndex, items.size() - 1))).ref();
        }
        startTest(choice);
    }

    private void startTest(String ref) {
        model = ref;
        step = Step.TEST;
        testStarted = System.nanoTime();
        test = services.test(ref);
    }

    private boolean testPassed() {
        String r = test == null ? null : test.getNow(null);
        return r != null && r.startsWith("ok");
    }

    private void use(Scope scope) {
        if (!testPassed()) {
            return;
        }
        services.settings().set(scope, Settings.DEFAULT_MODEL, model);
        List<String> own = new ArrayList<>();
        for (SettingsServices.AgentInfo a : services.agents()) {
            if (services.settings().modelFor(a.name()) != null) {
                own.add(a.name());
            }
        }
        String msg = "Default model: " + model + (scope == Scope.PROJECT ? " (this project)." : " (all projects).");
        if (!own.isEmpty()) {
            msg += " " + String.join(", ", own) + (own.size() == 1 ? " keeps its" : " keep their") + " own model (Settings › Models).";
        }
        if (services.agents().isEmpty()) {
            msg += " Now create an agent: F2 › Agents.";
        }
        finished.accept(msg);
    }

    private void back() {
        switch (step) {
            case TEST -> step = Step.MODEL;
            case MODEL -> step = Step.PROVIDER;
            default -> finished.accept(null);
        }
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

    private int button(Buffer buf, int x, int y, String label, Style style, int limit, Runnable action) {
        int w = put(buf, x, y, label, style, limit);
        hits.add(new Hit(new Rect(x, y, w, 1), action));
        return w;
    }

    void render(Buffer buf, Rect r) {
        hits.clear();
        pollKey();
        fill(buf, r, st(Theme.TEXT, Theme.BG));
        fill(buf, new Rect(r.x(), r.y(), r.width(), 2), st(Theme.TEXT, Theme.PANEL));
        put(buf, r.x() + 2, r.y(), "Connect a model", st(Theme.TEXT, Theme.PANEL).bold(), r.right());
        int x = r.x() + 2;
        String[] names = {"1 Provider", "2 Model", "3 Test"};
        for (int i = 0; i < names.length; i++) {
            boolean on = i == step.ordinal();
            boolean doneStep = i < step.ordinal();
            x += put(buf, x, r.y() + 1, names[i], on ? st(Theme.ACCENT, Theme.PANEL).bold() : st(doneStep ? Theme.TEXT : Theme.FAINT, Theme.PANEL), r.right());
            if (i < names.length - 1) {
                x += put(buf, x, r.y() + 1, "  ›  ", st(Theme.FAINT, Theme.PANEL), r.right());
            }
        }
        String closeLabel = " ✕ Esc ";
        int cx = r.right() - CharWidth.of(closeLabel) - 1;
        button(buf, cx, r.y(), closeLabel, st(Theme.DIM, Theme.PANEL), r.right(), () -> finished.accept(null));

        Rect body = new Rect(r.x() + 2, r.y() + 3, r.width() - 4, r.height() - 5);
        if (reason != null && !reason.isBlank() && step == Step.PROVIDER) {
            for (String line : Wrap.lines(reason, body.width())) {
                put(buf, body.x(), body.y(), line, st(Theme.AMBER, Theme.BG), body.right());
                body = new Rect(body.x(), body.y() + 1, body.width(), body.height() - 1);
            }
            body = new Rect(body.x(), body.y() + 1, body.width(), body.height() - 1);
        }
        String keys = enteringKey ? "Enter save · Ctrl+U clear · Esc back" : switch (step) {
            case PROVIDER -> "↑↓ choose · Enter next · K key · D forget key · R check again · Esc close";
            case MODEL -> "type to search · ↑↓ choose · Enter test it · Esc back";
            case TEST -> testPassed() ? "P this project · G all projects · B another model · Esc back"
                    : test != null && test.isDone() ? "R try again · B another model · Esc back" : "Esc back";
        };
        if (enteringKey) {
            drawKey(buf, body);
        } else {
            switch (step) {
                case PROVIDER -> drawProviders(buf, body);
                case MODEL -> drawModels(buf, body);
                default -> drawTest(buf, body);
            }
        }
        Rect foot = new Rect(r.x(), r.bottom() - 1, r.width(), 1);
        fill(buf, foot, st(Theme.DIM, Theme.PANEL));
        put(buf, foot.x() + 2, foot.y(), keys, st(Theme.DIM, Theme.PANEL), foot.right());
    }

    /** What is typed, hidden: dots, except the last four characters so a wrong paste can be told from a right one. */
    static String masked(String key) {
        if (key.length() <= 8) {
            return "•".repeat(key.length());
        }
        return "•".repeat(key.length() - 4) + key.substring(key.length() - 4);
    }

    private void drawKey(Buffer buf, Rect b) {
        put(buf, b.x(), b.y(), "Your " + keyFor.name() + " key", st(Theme.TEXT, Theme.BG).bold(), b.right());
        String page = ProviderRegistry.keyPage(keyFor.name());
        int y = b.y() + 1;
        put(buf, b.x(), y, page != null ? "Create one at " + page : "Get one from " + keyFor.url(), st(Theme.DIM, Theme.BG), b.right());
        Rect field = new Rect(b.x(), b.y() + 3, b.width(), 1);
        fill(buf, field, st(Theme.TEXT, Theme.FIELD));
        String shown = masked(keyInput.text());
        int avail = field.width() - 3;
        String clipped = CharWidth.of(shown) > avail ? CharWidth.substringByWidthFromEnd(shown, avail) : shown;
        put(buf, field.x() + 1, field.y(), shown.isEmpty() ? "Paste the key here▏" : clipped + "▏",
                st(shown.isEmpty() ? Theme.DIM : Theme.TEXT, Theme.FIELD), field.right());
        int row = b.y() + 5;
        if (!keyMessage.isEmpty()) {
            for (String line : Wrap.lines(keyMessage, b.width())) {
                put(buf, b.x(), row++, line, st(keyColor, Theme.BG), b.right());
            }
            row++;
        }
        String where = services.keyFile().isEmpty() ? "on this computer" : "in " + services.keyFile();
        for (String line : Wrap.lines("It is saved only on this computer, " + where + ", readable by your account only. It is never put in a project, "
                + "and it is sent nowhere except to " + keyFor.name() + " (" + keyFor.url() + ").", b.width())) {
            put(buf, b.x(), row++, line, st(Theme.FAINT, Theme.BG), b.right());
        }
        row++;
        put(buf, b.x(), row, "You can also set " + keyFor.keyEnv() + " in your environment instead; that always wins over a saved key.",
                st(Theme.FAINT, Theme.BG), b.right());
    }

    private void drawProviders(Buffer buf, Rect b) {
        put(buf, b.x(), b.y(), "Where should your agents' model come from?", st(Theme.TEXT, Theme.BG).bold(), b.right());
        int top = b.y() + 2;
        int rows = Math.max(3, Math.min(providers.size(), b.height() / 2 - 1));
        index = Math.max(0, Math.min(index, providers.size() - 1));
        if (index < first) {
            first = index;
        } else if (index >= first + rows) {
            first = index - rows + 1;
        }
        for (int i = first; i < providers.size() && i < first + rows; i++) {
            SettingsServices.Provider p = providers.get(i);
            Status s = status(p);
            boolean sel = i == index;
            Color bg = sel ? Theme.SELECTED : Theme.BG;
            Rect row = new Rect(b.x(), top + i - first, b.width(), 1);
            fill(buf, row, st(Theme.TEXT, bg));
            String mark = s.ready() ? "● " : "○ ";
            int w = put(buf, row.x() + 1, row.y(), mark, st(s.ready() ? Theme.GREEN : Theme.FAINT, bg), row.right());
            w += put(buf, row.x() + 1 + w, row.y(), p.name(), sel ? st(Theme.TEXT, bg).bold() : st(Theme.TEXT, bg), row.right());
            put(buf, row.x() + 16, row.y(), p.local() ? "on this machine" : "cloud", st(Theme.FAINT, bg), row.right());
            String text = s.text().equals("checking…") ? SPINNER.charAt((int) (System.currentTimeMillis() / 80 % SPINNER.length())) + " checking" : s.text();
            int tw = CharWidth.of(text);
            put(buf, Math.max(row.x() + 34, row.right() - tw - 1), row.y(), text, st(s.color(), bg), row.right());
            int idx = i;
            hits.add(new Hit(row, () -> {
                if (index == idx) {
                    chooseProvider(idx);
                } else {
                    index = idx;
                }
            }));
        }
        if (providers.isEmpty()) {
            return;
        }
        int y = top + rows + 1;
        put(buf, b.x(), y, "─".repeat(b.width()), st(Theme.LINE, Theme.BG), b.right());
        y += 2;
        SettingsServices.Provider p = providers.get(index);
        Status s = status(p);
        put(buf, b.x(), y - 1, p.name(), st(Theme.TEXT, Theme.BG).bold(), b.right());
        if (!notice.isEmpty()) {
            put(buf, b.x(), y++, notice, st(Theme.GREEN, Theme.BG), b.right());
        }
        if (p.keyEnv() != null && p.keySet() && !p.keyFrom().isBlank()) {
            String source = p.keyFrom().equals("saved") ? "Key: saved by you (K replace · D forget)"
                    : "Key: from the environment variable " + p.keyEnv() + " (K saves a different one)";
            put(buf, b.x(), y++, source, st(Theme.DIM, Theme.BG), b.right());
        }
        for (String line : s.help()) {
            for (String part : line.isEmpty() ? List.of("") : Wrap.lines(line, b.width())) {
                if (y >= b.bottom()) {
                    return;
                }
                boolean command = part.startsWith("    ");
                put(buf, b.x(), y++, part, st(command ? Theme.ACCENT : Theme.DIM, Theme.BG), b.right());
            }
        }
        if (p.keyEnv() != null && !p.keySet() && y + 1 < b.bottom()) {
            button(buf, b.x(), y + 1, " Add your key  Enter ", st(Theme.ON_ACCENT, Theme.ACCENT).bold(), b.right(), () -> chooseProvider(index));
        } else if (s.ready() && y + 1 < b.bottom()) {
            button(buf, b.x(), y + 1, " Choose a model  Enter ", st(Theme.ON_ACCENT, Theme.ACCENT).bold(), b.right(), () -> chooseProvider(index));
        } else if (!s.ready() && !s.text().startsWith("needs") && result(p) != null && y + 1 < b.bottom()) {
            button(buf, b.x(), y + 1, " Check again  R ", st(Theme.TEXT, Theme.FIELD), b.right(), this::recheck);
        }
    }

    private void drawModels(Buffer buf, Rect b) {
        put(buf, b.x(), b.y(), "Which " + provider.name() + " model?", st(Theme.TEXT, Theme.BG).bold(), b.right());
        Rect field = new Rect(b.x(), b.y() + 2, b.width(), 1);
        fill(buf, field, st(Theme.TEXT, Theme.FIELD));
        String f = filter.text();
        put(buf, field.x() + 1, field.y(), f.isEmpty() ? "Search, or type a model name and press Enter▏" : f + "▏",
                st(f.isEmpty() ? Theme.DIM : Theme.TEXT, Theme.FIELD), field.right());
        List<ModelCatalog.Model> items = models();
        int rows = Math.max(1, b.height() - 6);
        modelIndex = Math.max(0, Math.min(modelIndex, items.size() - 1));
        if (modelIndex < modelFirst) {
            modelFirst = modelIndex;
        } else if (modelIndex >= modelFirst + rows) {
            modelFirst = modelIndex - rows + 1;
        }
        for (int i = 0; i < rows && modelFirst + i < items.size(); i++) {
            ModelCatalog.Model m = items.get(modelFirst + i);
            boolean sel = modelFirst + i == modelIndex;
            Color bg = sel ? Theme.SELECTED : Theme.BG;
            Rect row = new Rect(b.x(), b.y() + 4 + i, b.width(), 1);
            fill(buf, row, st(Theme.TEXT, bg));
            String name = m.ref().substring(m.ref().indexOf(':') + 1);
            int w = put(buf, row.x() + 1, row.y(), name, st(m.tools() ? Theme.TEXT : Theme.DIM, bg), row.right() - 2);
            put(buf, row.x() + 3 + w, row.y(), m.note() + (m.tools() ? "" : " · no tools"), st(m.free() && !provider.local() ? Theme.ACCENT : Theme.DIM, bg),
                    row.right() - 1);
            int idx = modelFirst + i;
            hits.add(new Hit(row, () -> {
                modelIndex = idx;
                chooseModel();
            }));
        }
        String info = items.isEmpty() ? (f.isBlank() ? "No models listed." : "No match: Enter tests " + provider.name() + ":" + f.strip())
                : items.size() + " models · models marked \"no tools\" can chat but cannot read or edit files";
        put(buf, b.x(), b.bottom() - 1, info, st(Theme.FAINT, Theme.BG), b.right());
    }

    private void drawTest(Buffer buf, Rect b) {
        put(buf, b.x(), b.y(), "Testing " + model, st(Theme.TEXT, Theme.BG).bold(), b.right());
        String r = test == null ? null : test.getNow(null);
        int y = b.y() + 2;
        if (r == null) {
            long secs = (System.nanoTime() - testStarted) / 1_000_000_000L;
            put(buf, b.x(), y, SPINNER.charAt((int) (System.currentTimeMillis() / 80 % SPINNER.length())) + " Sending a one-word message… " + secs + " s",
                    st(Theme.DIM, Theme.BG), b.right());
            if (secs >= 10) {
                put(buf, b.x(), y + 2, "Free and local models can take a while the first time (loading the model).", st(Theme.FAINT, Theme.BG), b.right());
            }
            return;
        }
        if (r.startsWith("ok")) {
            put(buf, b.x(), y, "✓ " + answered(r), st(Theme.GREEN, Theme.BG), b.right());
            put(buf, b.x(), y + 2, "Use it for agents that have no model of their own:", st(Theme.TEXT, Theme.BG), b.right());
            int x = b.x();
            x += button(buf, x, y + 4, " This project  P ", st(Theme.ON_ACCENT, Theme.ACCENT).bold(), b.right(), () -> use(Scope.PROJECT)) + 2;
            x += button(buf, x, y + 4, " All projects  G ", st(Theme.TEXT, Theme.FIELD), b.right(), () -> use(Scope.GLOBAL)) + 2;
            button(buf, x, y + 4, " Another model  B ", st(Theme.DIM, Theme.FIELD), b.right(), () -> step = Step.MODEL);
            return;
        }
        for (String line : Wrap.lines("✗ " + r, b.width())) {
            put(buf, b.x(), y++, line, st(Theme.RED, Theme.BG), b.right());
        }
        if (r.contains("401") || r.contains("403") || r.toLowerCase(Locale.ROOT).contains("api key") || r.toLowerCase(Locale.ROOT).contains("unauthorized")) {
            put(buf, b.x(), y++, "This looks like a key problem: press Esc until the provider list, select it and press K for a new key.",
                    st(Theme.AMBER, Theme.BG), b.right());
        }
        int x = b.x();
        x += button(buf, x, y + 1, " Try again  R ", st(Theme.TEXT, Theme.FIELD), b.right(), () -> startTest(model)) + 2;
        button(buf, x, y + 1, " Another model  B ", st(Theme.TEXT, Theme.FIELD), b.right(), () -> step = Step.MODEL);
    }

    /** "ok 17713 ms: pong" as "It answered "pong" in 17.7 s". */
    static String answered(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("ok (\\d+) ms: (.*)", java.util.regex.Pattern.DOTALL).matcher(line);
        if (!m.matches()) {
            return "It answered.";
        }
        long ms = Long.parseLong(m.group(1));
        String text = m.group(2).strip().replaceAll("\\s+", " ");
        text = text.length() > 40 ? text.substring(0, 40) + "…" : text;
        return "It answered \"" + text + "\" in " + (ms < 1000 ? ms + " ms" : String.format(Locale.ROOT, "%.1f s", ms / 1000.0));
    }

    // ---- input ----

    void key(KeyEvent key) {
        KeyCode code = key.code();
        char ch = code == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt() ? Character.toLowerCase(key.character()) : 0;
        if (enteringKey) {
            keyKey(key);
            return;
        }
        if (code != KeyCode.CHAR || ch != 'd') {
            forgetArmed = null;
        }
        if (code == KeyCode.ESCAPE) {
            back();
            return;
        }
        switch (step) {
            case PROVIDER -> {
                switch (code) {
                    case UP -> index = Math.max(0, index - 1);
                    case DOWN -> index = Math.min(providers.size() - 1, index + 1);
                    case ENTER -> chooseProvider(index);
                    default -> {
                        if (ch == 'r') {
                            recheck();
                        } else if (ch == 'q') {
                            finished.accept(null);
                        } else if (ch == 'k' && !providers.isEmpty() && providers.get(index).keyEnv() != null) {
                            startKey(providers.get(index));
                        } else if (ch == 'd' && !providers.isEmpty()) {
                            askToForget(providers.get(index));
                        }
                    }
                }
            }
            case MODEL -> {
                switch (code) {
                    case UP -> modelIndex = Math.max(0, modelIndex - 1);
                    case DOWN -> modelIndex++;
                    case PAGE_UP -> modelIndex = Math.max(0, modelIndex - 10);
                    case PAGE_DOWN -> modelIndex += 10;
                    case ENTER -> chooseModel();
                    case BACKSPACE -> {
                        filter.backspace();
                        modelIndex = 0;
                    }
                    case CHAR -> {
                        if (key.hasCtrl() && Character.toLowerCase(key.character()) == 'u') {
                            filter.clear();
                        } else if (!key.hasCtrl() && !key.hasAlt() && key.character() >= ' ') {
                            filter.insert(key.string());
                        }
                        modelIndex = 0;
                    }
                    default -> { }
                }
            }
            default -> {
                boolean done = test != null && test.isDone();
                if (ch == 'p' || (code == KeyCode.ENTER && testPassed())) {
                    use(Scope.PROJECT);
                } else if (ch == 'g') {
                    use(Scope.GLOBAL);
                } else if (ch == 'b') {
                    step = Step.MODEL;
                } else if ((ch == 'r' || code == KeyCode.ENTER) && done && !testPassed()) {
                    startTest(model);
                }
            }
        }
    }

    private void askToForget(SettingsServices.Provider p) {
        if (!p.keyFrom().equals("saved")) {
            notice = p.keyEnv() == null ? p.name() + " needs no key." : p.keyFrom().equals("environment")
                    ? "That key comes from the environment variable " + p.keyEnv() + "; BuildCLI cannot remove it." : "No saved key for " + p.name() + ".";
        } else if (p.name().equals(forgetArmed)) {
            forgetKey(p);
        } else {
            forgetArmed = p.name();
            notice = "Press D again to forget the saved key for " + p.name() + ".";
        }
    }

    private void keyKey(KeyEvent key) {
        KeyCode code = key.code();
        switch (code) {
            case ESCAPE -> {
                enteringKey = false;
                keyCheck = null;
            }
            case ENTER -> submitKey();
            case BACKSPACE -> {
                keyInput.backspace();
                saveAnyway = null;
            }
            case LEFT -> keyInput.left();
            case RIGHT -> keyInput.right();
            case HOME -> keyInput.home();
            case END -> keyInput.end();
            case CHAR -> {
                if (key.hasCtrl() && Character.toLowerCase(key.character()) == 'u') {
                    keyInput.clear();
                } else if (!key.hasCtrl() && !key.hasAlt() && key.character() > ' ') {
                    keyInput.insert(key.string());
                }
                saveAnyway = null;
            }
            default -> { }
        }
    }

    void paste(String text) {
        if (enteringKey) {
            keyInput.insert(text.replaceAll("\\s+", "")); // a key never has spaces: drop the line break that came with the paste
            saveAnyway = null;
            return;
        }
        if (step == Step.MODEL) {
            filter.insert(text.strip());
            modelIndex = 0;
        }
    }

    void mouse(MouseEvent m) {
        if (m.kind() == MouseEventKind.SCROLL_UP || m.kind() == MouseEventKind.SCROLL_DOWN) {
            int d = m.kind() == MouseEventKind.SCROLL_UP ? -1 : 1;
            if (step == Step.MODEL) {
                modelIndex = Math.max(0, modelIndex + d * 3);
            } else if (step == Step.PROVIDER) {
                index = Math.max(0, Math.min(providers.size() - 1, index + d));
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
