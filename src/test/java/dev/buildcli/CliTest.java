package dev.buildcli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.buildcli.cli.BuildCli;
import dev.buildcli.cli.CliContext;
import dev.buildcli.domain.ModelRef;
import dev.buildcli.infrastructure.ScriptedGateway;
import dev.buildcli.ports.LlmGateway;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real commands through picocli with an injected context: no terminal, no model, no real home directory. */
class CliTest {
    @TempDir Path root;
    Path project;
    Path home;
    ByteArrayOutputStream outBytes;
    ByteArrayOutputStream errBytes;
    final List<ModelRef> modelsRequested = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        project = Files.createDirectories(root.resolve("project"));
        home = Files.createDirectories(root.resolve("home"));
    }

    record Result(int code, String out, String err) {}

    Result cli(Supplier<LlmGateway> gateway, String stdin, Map<String, String> env, String... args) {
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        var ctx = new CliContext(project, home, env, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader(stdin)), false,
                (ref, settings) -> {
                    modelsRequested.add(ref);
                    return gateway.get();
                }, (session, models, services) -> {
                    throw new AssertionError("the TUI must not open in these tests");
                });
        int code = BuildCli.run(args, ctx);
        return new Result(code, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
    }

    Result cli(String... args) {
        return cli(() -> {
            throw new AssertionError("no model expected");
        }, "", Map.of(), args);
    }

    /** ana hands off to bruno, who writes src/Hello.java and finishes. */
    static ScriptedGateway happyScript() {
        return new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "add Hello", "brief", "keep it small"))
                .call("bruno", "write_file", Map.of("path", "src/Hello.java", "content", "class Hello {}\n"))
                .say("bruno", "Added src/Hello.java.")
                .say("ana", "Hello was added.");
    }

    // ---- init, agents, teams ----

    @Test
    void initCreatesASampleTeamThatLoadsAndIsAlreadyTrusted() throws Exception {
        Result r = cli("init");
        assertEquals(0, r.code, r.err);
        for (String f : List.of(".buildcli/agents/ana.md", ".buildcli/agents/bruno.md", ".buildcli/agents/carla.md",
                ".buildcli/teams/backend.yaml", "AGENTS.md")) {
            assertTrue(Files.exists(project.resolve(f)), f);
        }
        assertTrue(r.out.contains("Next steps"));
        assertTrue(Files.readString(project.resolve(".buildcli/teams/backend.yaml")).contains("model: qwen2.5:7b"));
        assertEquals(0, cli("team", "list").code, "the generated files pass validation");
        assertTrue(Files.exists(home.resolve(".buildcli/trust.json")), "init approves what it just generated");
    }

    @Test
    void initNeverOverwritesExistingFiles() throws Exception {
        Files.writeString(project.resolve("AGENTS.md"), "my notes");
        Result r = cli("init", "--model", "openai:gpt-x");
        assertEquals(0, r.code);
        assertEquals("my notes", Files.readString(project.resolve("AGENTS.md")));
        assertTrue(r.out.contains("skipped  AGENTS.md"));
        assertTrue(Files.readString(project.resolve(".buildcli/teams/backend.yaml")).contains("provider: openai"));
        assertEquals("skipped", cli("init").out.lines().filter(l -> l.contains("ana.md")).findFirst().orElseThrow().substring(0, 7));
    }

    @Test
    void initRejectsAModelWithoutAProvider() {
        Result r = cli("init", "--model", "justamodel");
        assertEquals(2, r.code);
        assertTrue(r.err.contains("model must be provider:model"), r.err);
        assertFalse(Files.exists(project.resolve(".buildcli")), "nothing is written when the arguments are invalid");
    }

    @Test
    void agentListShowAndCreate() throws Exception {
        cli("init");
        Result list = cli("agent", "list");
        assertTrue(list.out.contains("ana") && list.out.contains("architect") && list.out.contains("project"), list.out);
        Result show = cli("agent", "show", "bruno");
        assertTrue(show.out.contains("may write:    [src/**]"), show.out);
        assertTrue(show.out.contains("[mvn, -q, verify]"), show.out);
        assertEquals(2, cli("agent", "show", "nobody").code);

        Result create = cli("agent", "create", "dora", "--role", "tester", "--capabilities", "filesystem.read,command.execute");
        assertEquals(0, create.code, create.err);
        assertTrue(create.out.contains("ask for your approval"));
        assertTrue(cli("agent", "list").out.contains("dora"));
        assertEquals(2, cli("agent", "create", "dora").code, "no overwrite");
        assertEquals(2, cli("agent", "create", "Bad Name").code);
        assertEquals(2, cli("agent", "create", "erin", "--capabilities", "telepathy").code);
    }

    @Test
    void teamCreateValidatesAgentsAndLead() throws Exception {
        cli("init");
        assertEquals(0, cli("team", "create", "review", "--agents", "carla,ana", "--lead", "ana", "--model", "ollama:qwen2.5:7b").code);
        Result show = cli("team", "show", "review");
        assertTrue(show.out.contains("lead: ana") && show.out.contains("ollama/qwen2.5:7b"), show.out);
        assertEquals(2, cli("team", "create", "x", "--agents", "ana,ghost").code);
        assertEquals(2, cli("team", "create", "y", "--agents", "ana", "--lead", "carla").code);
        assertEquals(2, cli("team", "create", "review", "--agents", "ana").code, "no overwrite");
    }

    @Test
    void anInvalidConfigurationIsReportedWithEveryProblemAndExitCode2() throws Exception {
        Files.createDirectories(project.resolve(".buildcli/agents"));
        Files.writeString(project.resolve(".buildcli/agents/a.yaml"), "name: a\nrole: r\n");
        Files.writeString(project.resolve(".buildcli/agents/b.yaml"), "schema: 1\nname: b\nrole: r\ncapabilites: []\n");
        Result r = cli("agent", "list");
        assertEquals(2, r.code);
        assertTrue(r.err.contains("2 problem(s)") && r.err.contains("missing 'schema'") && r.err.contains("capabilites"), r.err);
    }

    // ---- run ----

    @Test
    void runHeadlessExecutesTheTeamPersistsTheRunAndReportsTheResult() throws Exception {
        cli("init");
        Result r = cli(CliTest::happyScript, "", Map.of(), "run", "--team", "backend", "--headless", "--approve", "writes", "add a Hello class");
        assertEquals(0, r.code, r.err + r.out);
        assertTrue(r.out.contains("Hello was added."), r.out);
        assertTrue(r.out.contains("HandoffCreated"), r.out);
        assertTrue(r.out.contains("[approved by --approve writes]"), r.out);
        assertEquals("class Hello {}\n", Files.readString(project.resolve("src/Hello.java")));
        assertEquals("ollama", modelsRequested.get(0).provider());
        assertEquals("qwen2.5:7b", modelsRequested.get(0).model());

        assertTrue(cli("runs").out.contains("DONE") && cli("runs").out.contains("add a Hello class"));
        Result tasks = cli("task", "list");
        assertTrue(tasks.out.contains("#1") && tasks.out.contains("ana -> bruno") && tasks.out.contains("DONE"), tasks.out + tasks.err);
        assertTrue(cli("task", "show", "2").out.contains("Added src/Hello.java."));
        Result usage = cli("usage");
        assertTrue(usage.out.contains("ana") && usage.out.contains("bruno") && usage.out.contains("total"), usage.out);
        assertTrue(cli("usage", "--json").out.contains("\"totals\":{\"calls\":4,\"inputTokens\":400,\"outputTokens\":80}"));
    }

    @Test
    void approvalsAreDeniedByDefaultWithoutATerminal() throws Exception {
        cli("init");
        Result r = cli(CliTest::happyScript, "", Map.of(), "run", "--headless", "add a Hello class");
        assertFalse(Files.exists(project.resolve("src/Hello.java")), "nothing is approved unless asked for");
        assertTrue(r.out.contains("[denied by --approve none]"), r.out);
    }

    @Test
    void approvalsCanBeAnsweredOnTheConsole() throws Exception {
        cli("init");
        Result r = cli(CliTest::happyScript, "y\n", Map.of(), "run", "--headless", "--approve", "ask", "add a Hello class");
        assertEquals(0, r.code, r.out + r.err);
        assertTrue(Files.exists(project.resolve("src/Hello.java")));
        assertTrue(r.out.contains("Approve? [y/N]") && r.out.contains("+class Hello {}"), r.out);
    }

    @Test
    void theOnlyTeamIsUsedAndADirectAgentRunWorks() throws Exception {
        cli("init");
        Result direct = cli(() -> new ScriptedGateway().say("carla", "Looks good."), "", Map.of(), "run", "--headless", "--agent", "carla", "review it");
        assertEquals(0, direct.code, direct.err);
        assertTrue(direct.out.contains("Looks good."));
    }

    @Test
    void runRefusesClearlyWhenThereIsNothingToRun() throws Exception {
        assertTrue(cli("run", "--headless", "x").err.contains("no teams are defined"));
        cli("init");
        assertTrue(cli("run", "--team", "nope", "--headless", "x").err.contains("no team named 'nope'"));
        assertTrue(cli("run", "--agent", "nope", "--headless", "x").err.contains("no agent named 'nope'"));
        assertTrue(cli("run", "--team", "backend", "--agent", "ana", "--headless", "x").err.contains("either --team or --agent"));
        assertTrue(cli("run", "--headless").err.contains("a request is required"));
        assertEquals(2, cli("run", "--model", "justamodel", "--headless", "x").code);
    }

    @Test
    void aTeamWithoutAModelIsRefusedUpFrontUnlessOneIsGiven() throws Exception {
        cli("init");
        Files.writeString(project.resolve(".buildcli/teams/backend.yaml"), "schema: 1\nname: backend\nlead: ana\nagents: [ana, bruno]\n");
        // the edited team file changes the digest, so the project must be trusted again; approve it
        Result refused = cli("run", "--headless", "x");
        assertEquals(2, refused.code);
        assertTrue(refused.err.contains("no model configured for agent 'ana'"), refused.err);
        Result given = cli(CliTest::happyScript, "", Map.of(), "run", "--headless", "--approve", "all", "--model", "ollama:m", "add Hello");
        assertEquals(0, given.code, given.err + given.out);
        assertEquals("m", modelsRequested.get(0).model());
    }

    @Test
    void changedProjectDefinitionsMustBeTrustedAgainBeforeAnythingRuns() throws Exception {
        cli("init");
        Files.writeString(project.resolve(".buildcli/agents/bruno.md"),
                Files.readString(project.resolve(".buildcli/agents/bruno.md")).replace("src/**", "**"));
        Result r = cli(CliTest::happyScript, "", Map.of(), "run", "--headless", "--approve", "writes", "add Hello");
        assertEquals(1, r.code);
        assertTrue(r.err.contains("were not trusted, so nothing was run"), r.err);
        assertFalse(Files.exists(project.resolve("src/Hello.java")));
        assertTrue(r.out.contains("Trust the agent definitions"), r.out);
        Result ok = cli(CliTest::happyScript, "", Map.of(), "run", "--headless", "--approve", "all", "add Hello");
        assertEquals(0, ok.code, ok.out + ok.err);
    }

    @Test
    void aFailingTaskEscalatesAndWithoutATerminalTheRunAborts() throws Exception {
        cli("init");
        Result r = cli(() -> {
            var g = new ScriptedGateway();
            for (int i = 0; i < 4; i++) {
                g.then("ana", new RuntimeException("provider down"));
            }
            return g;
        }, "", Map.of(), "run", "--headless", "--approve", "all", "do it");
        assertEquals(1, r.code);
        assertTrue(r.out.contains("[task #1 needs you]") && r.out.contains("TaskRetried"), r.out);
        assertTrue(r.out.contains("aborted"), r.out);
        assertTrue(cli("runs").out.contains("ABORTED"));
    }

    @Test
    void stateCommandsSayWhatToDoWhenThereAreNoRuns() {
        assertTrue(cli("runs").out.contains("No runs yet"));
        assertTrue(cli("task", "list").out.contains("No runs yet"));
        assertTrue(cli("usage").out.contains("No runs yet"));
        assertEquals("{\"runs\":[]}", cli("usage", "--json").out.strip());
    }

    @Test
    void anUnknownRunOrTaskIsAnError() throws Exception {
        cli("init");
        cli(CliTest::happyScript, "", Map.of(), "run", "--headless", "--approve", "writes", "add Hello");
        assertEquals(2, cli("task", "list", "--run", "nope").code);
        assertEquals(2, cli("task", "show", "99").code);
    }

    // ---- doctor, config, help ----

    @Test
    void doctorReportsAnUnreachableOllamaAsAWarningNotAFailure() throws Exception {
        cli("init");
        Result r = cli(() -> null, "", Map.of("OLLAMA_HOST", "127.0.0.1:1"), "doctor");
        assertEquals(0, r.code, r.out);
        assertTrue(r.out.contains("warn  Ollama is not reachable at http://127.0.0.1:1"), r.out);
        assertTrue(r.out.contains("ok    2 ") || r.out.contains("agent(s) and 1 team(s) loaded"), r.out);
    }

    @Test
    void doctorFindsOllamaAndChecksThatTheTeamsModelIsPulled() throws Exception {
        cli("init");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", ex -> {
            byte[] body = "{\"models\":[{\"name\":\"qwen2.5:3b\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String host = "127.0.0.1:" + server.getAddress().getPort();
            Result r = cli(() -> null, "", Map.of("OLLAMA_HOST", host), "doctor");
            assertTrue(r.out.contains("has 1 model(s): qwen2.5:3b"), r.out);
            assertTrue(r.out.contains("model 'qwen2.5:7b' is not pulled: run 'ollama pull qwen2.5:7b'"), r.out);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doctorFailsOnAnInvalidConfiguration() throws Exception {
        Files.createDirectories(project.resolve(".buildcli/agents"));
        Files.writeString(project.resolve(".buildcli/agents/a.yaml"), "name: a\n");
        Result r = cli(() -> null, "", Map.of("OLLAMA_HOST", "127.0.0.1:1"), "doctor");
        assertEquals(1, r.code);
        assertTrue(r.out.contains("FAIL  the configuration has"), r.out);
    }

    @Test
    void configShowsLocationsAndNeverTheApiKey() {
        Result r = cli(() -> null, "", Map.of("OPENAI_API_KEY", "sk-very-secret-value-123456", "BUILDCLI_HOME", home.resolve("custom").toString()), "config");
        assertTrue(r.out.contains("global directory  : " + home.resolve("custom")), r.out);
        assertTrue(r.out.contains("OPENAI_API_KEY    : set (not shown)"));
        assertFalse(r.out.contains("very-secret"));
    }

    @Test
    void helpListsTheCommandsAndHidesTheDevelopmentOnes() {
        Result r = cli("--help");
        for (String c : List.of("init", "agent", "team", "run", "runs", "task", "usage", "doctor", "config")) {
            assertTrue(r.out.contains("  " + c), c + "\n" + r.out);
        }
        assertFalse(r.out.contains("bench"));
        assertEquals(0, cli().code, "no arguments without a terminal prints the help");
        assertTrue(cli().out.contains("Usage: buildcli"));
        assertEquals(2, cli("nonsense").code);
    }

    @Test
    void untrustedTextCannotDriveTheTerminalInHeadlessOutput() throws Exception {
        cli("init");
        Files.writeString(project.resolve("notes.txt"), "\u001b[2Jcleared \u001b]0;pwned\u0007 \u202Edisguised");
        Result r = cli(() -> new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "carla", "objective", "read \u001b[31mnotes"))
                .call("carla", "read_file", Map.of("path", "notes.txt"))
                .say("carla", "read it \u001b[1mbold")
                .say("ana", "final \u001b]0;title\u0007 report"), "", Map.of(), "run", "--headless", "--approve", "all", "look at the notes");
        assertEquals(0, r.code, r.out + r.err);
        assertFalse(r.out.contains("\u001b"), "an ESC reached the terminal");
        assertFalse(r.out.contains("\u0007"), "a BEL reached the terminal");
        assertFalse(r.out.contains("\u202E"), "a bidi override reached the terminal");
        assertTrue(r.out.contains("final \u241B]0;title\u2407 report"), r.out);
    }
}
