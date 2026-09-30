package dev.buildcli.cli;

import dev.buildcli.domain.ModelRef;
import dev.buildcli.application.ChatSession;
import dev.buildcli.infrastructure.FileConfigRepository;
import dev.buildcli.infrastructure.ProviderSettings;
import dev.buildcli.infrastructure.StateLocations;
import dev.buildcli.ports.ConfigException;
import dev.buildcli.ports.ConfigRepository;
import dev.buildcli.ports.LlmGateway;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Everything the commands need from the outside world, injected so they can be tested without a terminal, a model or
 * the user's real home directory.
 */
public final class CliContext {

    /** Builds the gateway for one provider/model pair. */
    public interface GatewayFactory {
        LlmGateway create(ModelRef ref, ProviderSettings settings);
    }

    /** Opens the chat UI on a session and blocks until the user quits. */
    public interface TuiLauncher {
        void launch(ChatSession session, Map<String, String> models) throws Exception;
    }

    public final Path cwd;
    public final Path home;
    public final Map<String, String> env;
    public final PrintStream out;
    public final PrintStream err;
    public final BufferedReader in;
    public final boolean terminal;
    public final GatewayFactory gateways;
    public final TuiLauncher tui;

    public CliContext(Path cwd, Path home, Map<String, String> env, PrintStream out, PrintStream err, BufferedReader in,
                      boolean terminal, GatewayFactory gateways, TuiLauncher tui) {
        this.cwd = cwd.toAbsolutePath().normalize();
        this.home = home;
        this.env = env;
        // everything printed may contain untrusted text (model replies, tool output): never let it drive the terminal
        this.out = dev.buildcli.infrastructure.SafePrintStream.wrap(out);
        this.err = dev.buildcli.infrastructure.SafePrintStream.wrap(err);
        this.in = in;
        this.terminal = terminal;
        this.gateways = gateways;
        this.tui = tui;
    }

    /** The real process: real streams, real environment, real terminal detection, real providers. */
    public static CliContext system() {
        return new CliContext(Path.of("").toAbsolutePath(), Path.of(System.getProperty("user.home")), System.getenv(),
                System.out, System.err, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                isInteractiveTerminal(), (ref, settings) -> settings.gatewayFor(ref),
                (session, models) -> new dev.buildcli.infrastructure.tui.ChatApp(session, models, Path.of("").toAbsolutePath(),
                        !"0".equals(System.getenv("BUILDCLI_MOUSE"))).run());
    }

    /** Providers (built in plus the user's providers.yaml) and the environment their keys are read from. */
    public ProviderSettings providerSettings() {
        return ProviderSettings.fromEnvironment(env, globalDir());
    }

    public Path globalDir() {
        return StateLocations.globalDir(env, home);
    }

    public Path stateDb() {
        return StateLocations.stateDb(globalDir(), cwd);
    }

    /** Identifies this project in the trust store. */
    public String projectKey() {
        try {
            return cwd.toRealPath().toString();
        } catch (java.io.IOException e) {
            return cwd.toString();
        }
    }

    public Path trustFile() {
        return globalDir().resolve("trust.json");
    }

    /** Loads the configuration, printing every problem and returning null if it is invalid. */
    public ConfigRepository loadConfig() {
        try {
            return new FileConfigRepository(cwd, globalDir());
        } catch (ConfigException e) {
            err.println("The configuration has " + e.problems().size() + " problem(s):");
            e.problems().forEach(p -> err.println("  - " + p));
            return null;
        }
    }

    public boolean hasState() {
        return Files.isRegularFile(stateDb());
    }

    /**
     * Whether stdin and stdout are a real terminal. On JDK 22+ {@code System.console()} is non-null even when redirected,
     * so {@code Console.isTerminal()} is consulted by reflection (the project compiles for 21).
     */
    static boolean isInteractiveTerminal() {
        var console = System.console();
        if (console == null || "dumb".equals(System.getenv("TERM"))) {
            return false;
        }
        try {
            Method m = console.getClass().getMethod("isTerminal");
            m.setAccessible(true);
            return (Boolean) m.invoke(console);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true; // JDK 21: a non-null console means a terminal
        }
    }
}
