package dev.buildcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.buildcli.application.ChatSession;
import dev.buildcli.infrastructure.FileTrustStore;
import dev.buildcli.ports.ChatLog;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** "Add the sample team" from an empty chat: the same agents as `init`, alive in the open chat, in a group, already trusted. */
class SampleTeamTest {
    @TempDir Path root;

    @Test
    void anEmptyChatGetsTheSampleAgentsWithTheirRealPermissionsAndAGroupLedByAna() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Path home = Files.createDirectories(root.resolve("home"));
        var out = new PrintStream(new ByteArrayOutputStream());
        var ctx = new CliContext(project, home, Map.of(), out, out, new BufferedReader(new StringReader("")), false, (r, s) -> null,
                (session, models, services) -> { });
        var config = ctx.loadConfig();
        assertTrue(config.agents().isEmpty(), "it starts with no agents at all");
        var setup = new ChatSetup(ctx, config);
        var services = new ChatServices(ctx, config, setup);
        var session = new ChatSession(config.agents(), setup.groups(), setup.limits(), (t, req, ui, c, d) -> {
            throw new AssertionError("no model is called");
        }, setup.store, () -> 6, ChatLog.NONE);
        services.attach(session);

        List<String> added = services.createSampleAgents();
        assertEquals(List.of("ana", "bruno", "carla"), added);
        for (String f : List.of(".buildcli/agents/ana.md", ".buildcli/agents/bruno.md", ".buildcli/agents/carla.md", "AGENTS.md")) {
            assertTrue(Files.exists(project.resolve(f)), f);
        }
        assertEquals(List.of("ana", "bruno", "carla"), session.contacts().stream().map(a -> a.name()).sorted().toList());
        assertEquals(List.of("src/**"), session.contact("bruno").permissions().writeGlobs(), "bruno is live with what his file says, not a read-only copy");
        assertTrue(session.contact("bruno").can("command.execute"));
        assertTrue(session.contact("ana").permissions().writeGlobs().isEmpty());

        var group = session.group("#backend");
        assertEquals(List.of("ana", "bruno", "carla"), group.members());
        assertEquals(List.of("ana"), group.admins());
        assertEquals(List.of("backend"), new dev.buildcli.infrastructure.FileChatStore(ctx.projectStateDir()).load().stream().map(g -> g.name()).toList(),
                "the group is saved, outside the project");

        var fresh = ctx.loadConfig();
        assertTrue(new FileTrustStore(ctx.trustFile()).isTrusted(ctx.projectKey(), fresh.projectDigest()), "the user asked for them, so no approval prompt follows");
        assertEquals(3, services.agents().size());

        assertThrows(IllegalArgumentException.class, services::createSampleAgents, "a second click says they are already here");
        assertEquals(3, session.contacts().size());
        session.close();
    }

    @Test
    void agentsTheUserAlreadyHasAreKeptAndOnlyTheMissingOnesAreAdded() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Path home = Files.createDirectories(root.resolve("home"));
        var out = new PrintStream(new ByteArrayOutputStream());
        var ctx = new CliContext(project, home, Map.of(), out, out, new BufferedReader(new StringReader("")), false, (r, s) -> null,
                (session, models, services) -> { });
        Files.createDirectories(project.resolve(".buildcli/agents"));
        String mine = "---\nschema: 1\nname: bruno\nrole: my own bruno\ncapabilities: [filesystem.read]\npermissions:\n  filesystem:\n    read: [\"**\"]\n---\nMine.\n";
        Files.writeString(project.resolve(".buildcli/agents/bruno.md"), mine);
        var config = ctx.loadConfig();
        var setup = new ChatSetup(ctx, config);
        var services = new ChatServices(ctx, config, setup);
        var session = new ChatSession(config.agents(), setup.groups(), setup.limits(), (t, req, ui, c, d) -> {
            throw new AssertionError();
        }, setup.store, () -> 6, ChatLog.NONE);
        services.attach(session);

        assertEquals(List.of("ana", "carla"), services.createSampleAgents());
        assertEquals(mine, Files.readString(project.resolve(".buildcli/agents/bruno.md")), "his own file is untouched");
        assertEquals("my own bruno", session.contact("bruno").role());
        session.close();
    }
}
