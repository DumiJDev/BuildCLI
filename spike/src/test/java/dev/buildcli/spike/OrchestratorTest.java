package dev.buildcli.spike;

import static org.junit.jupiter.api.Assertions.*;

import dev.buildcli.spike.application.Events;
import dev.buildcli.spike.application.Orchestrator;
import dev.buildcli.spike.application.RunAborted;
import dev.buildcli.spike.application.ToolRuntime;
import dev.buildcli.spike.domain.*;
import dev.buildcli.spike.infrastructure.HeadlessUi;
import dev.buildcli.spike.infrastructure.JdbcEventStore;
import dev.buildcli.spike.infrastructure.ScriptedGateway;
import dev.buildcli.spike.ports.EscalationChoice;
import dev.buildcli.spike.ports.LlmReply;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrchestratorTest {
    @TempDir Path workspace;
    JdbcEventStore store;

    static final Agent ANA = new Agent("ana", "architect", "You design, you do not implement.",
            Set.of("filesystem.read", "agent.handoff"), Permissions.none());
    static final Agent BRUNO = new Agent("bruno", "developer", "You implement.",
            Set.of("filesystem.read", "filesystem.write", "command.execute"),
            new Permissions(List.of("out/**"), List.of(List.of("java", "-version")), Duration.ofSeconds(30)));

    @BeforeEach
    void setUp() {
        store = new JdbcEventStore(JdbcEventStore.IN_MEMORY);
    }

    Orchestrator orch(ScriptedGateway llm, HeadlessUi ui, Limits limits) {
        Team team = new Team("backend", "ana", List.of(ANA, BRUNO), limits);
        Events events = new Events(store, "run1", ui);
        return new Orchestrator(team, llm, new ToolRuntime(workspace, ui, events), ui, events);
    }

    static HeadlessUi ui(boolean approveAll, EscalationChoice choice) {
        return new HeadlessUi(r -> approveAll, choice, false);
    }

    static long count(HeadlessUi ui, String type) {
        return ui.events.stream().filter(e -> e.type().equals(type)).count();
    }

    @Test
    void handoffRunsTargetAgentAndReturnsItsResult() throws Exception {
        var llm = new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "write out/hello.txt", "brief", "hi"))
                .call("bruno", "write_file", Map.of("path", "out/hello.txt", "content", "hello"))
                .call("bruno", "run_command", Map.of("argv", List.of("java", "-version")))
                .say("bruno", "done: file written and verified")
                .say("ana", "Bruno finished.");
        var ui = ui(true, EscalationChoice.ABORT);
        Task root = orch(llm, ui, Limits.defaults()).run("create the greeting");

        assertEquals(TaskStatus.DONE, root.status);
        assertEquals("hello", Files.readString(workspace.resolve("out/hello.txt")));
        assertEquals(1, count(ui, "HandoffCreated"));
        assertEquals(1, ui.approvals.size(), "only the write needs approval; java -version is on the allow list");
        assertEquals(store.list("run1").size(), ui.events.size(), "every event is persisted");
    }

    @Test
    void commandOutsidePolicyNeedsApprovalAndIsNotRunWhenDenied() {
        var llm = new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "clean up"))
                .call("bruno", "run_command", Map.of("argv", List.of("touch", "pwned")))
                .say("bruno", "could not run it")
                .say("ana", "ok");
        var ui = ui(false, EscalationChoice.ABORT);
        orch(llm, ui, Limits.defaults()).run("go");

        assertEquals(1, ui.approvals.size());
        assertEquals("command", ui.approvals.get(0).kind());
        assertFalse(Files.exists(workspace.resolve("pwned")));
        assertEquals(1, count(ui, "ApprovalDenied"));
    }

    @Test
    void shellStringsAreRejectedNotExecuted() {
        var llm = new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "x"))
                .call("bruno", "run_command", Map.of("argv", "ls; touch pwned"))
                .say("bruno", "ok")
                .say("ana", "ok");
        var ui = ui(true, EscalationChoice.ABORT);
        orch(llm, ui, Limits.defaults()).run("go");
        assertTrue(ui.approvals.isEmpty());
        assertFalse(Files.exists(workspace.resolve("pwned")));
    }

    @Test
    void writesOutsideWriteGlobsAreDeniedWithoutAskingTheUser() {
        var llm = new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "x"))
                .call("bruno", "write_file", Map.of("path", "src/Main.java", "content", "x"))
                .say("bruno", "ok")
                .say("ana", "ok");
        var ui = ui(true, EscalationChoice.ABORT);
        orch(llm, ui, Limits.defaults()).run("go");
        assertTrue(ui.approvals.isEmpty());
        assertFalse(Files.exists(workspace.resolve("src/Main.java")));
    }

    @Test
    void pathEscapingTheWorkspaceIsBlocked() {
        var llm = new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "x"))
                .call("bruno", "write_file", Map.of("path", "../escape.txt", "content", "x"))
                .say("bruno", "ok")
                .say("ana", "ok");
        var ui = ui(true, EscalationChoice.ABORT);
        orch(llm, ui, Limits.defaults()).run("go");
        assertFalse(Files.exists(workspace.getParent().resolve("escape.txt")));
    }

    @Test
    void capabilitiesAreEnforcedEvenIfTheModelCallsAToolItWasNotGiven() {
        var llm = new ScriptedGateway()
                .call("ana", "write_file", Map.of("path", "out/x.txt", "content", "x"))
                .say("ana", "gave up");
        var ui = ui(true, EscalationChoice.ABORT);
        orch(llm, ui, Limits.defaults()).run("go");
        assertFalse(Files.exists(workspace.resolve("out/x.txt")));
        assertTrue(ui.approvals.isEmpty());
    }

    @Test
    void invalidHandoffIsReturnedToTheModelAsAnErrorNotAsAFailure() {
        var llm = new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "zoe", "objective", "x"))
                .say("ana", "no such teammate, doing it myself");
        var ui = ui(true, EscalationChoice.ABORT);
        Task root = orch(llm, ui, Limits.defaults()).run("go");
        assertEquals(TaskStatus.DONE, root.status);
        assertEquals(0, count(ui, "HandoffCreated"));
        assertEquals(0, count(ui, "TaskFailed"));
    }

    @Test
    void handoffDepthIsLimited() {
        Agent looper = new Agent("ana", "architect", "", Set.of("agent.handoff"), Permissions.none());
        Agent looper2 = new Agent("bruno", "developer", "", Set.of("agent.handoff"), Permissions.none());
        var llm = new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "a"))
                .call("bruno", "handoff", Map.of("to", "ana", "objective", "b"))
                .say("ana", "depth 2 done")
                .say("bruno", "depth 1 done")
                .say("ana", "root done");
        var ui = ui(true, EscalationChoice.ABORT);
        Team team = new Team("t", "ana", List.of(looper, looper2), new Limits(3, 12, 1, 30_000, 3));
        Events events = new Events(store, "run1", ui);
        Task root = new Orchestrator(team, llm, new ToolRuntime(workspace, ui, events), ui, events).run("go");
        // maxDepth=1: ana->bruno is depth 1 (ok), bruno->ana would be depth 2 (rejected as an error to the model)
        assertEquals(TaskStatus.DONE, root.status);
        assertEquals(1, count(ui, "HandoffCreated"));
    }

    @Test
    void failingTaskIsRetriedThreeTimesThenEscalatedToTheUser() {
        var llm = new ScriptedGateway();
        for (int i = 0; i < 4; i++) {
            llm.then("ana", new RuntimeException("provider down"));
        }
        var ui = ui(true, EscalationChoice.ABORT);
        var ex = assertThrows(RunAborted.class, () -> orch(llm, ui, Limits.defaults()).run("go"));

        assertEquals(4, count(ui, "TaskFailed"), "initial attempt + 3 retries");
        assertEquals(3, count(ui, "TaskRetried"));
        assertEquals(1, count(ui, "TaskEscalated"));
        assertEquals(1, ui.escalations);
        assertTrue(ex.getMessage().contains("provider down"));
    }

    @Test
    void userCanRetryAnEscalatedTask() {
        var llm = new ScriptedGateway();
        for (int i = 0; i < 4; i++) {
            llm.then("ana", new RuntimeException("flaky"));
        }
        llm.say("ana", "worked this time");
        var ui = ui(true, EscalationChoice.RETRY);
        Task root = orch(llm, ui, Limits.defaults()).run("go");
        assertEquals(TaskStatus.DONE, root.status);
        assertEquals(1, ui.escalations);
    }

    @Test
    void userCanSkipAnEscalatedTask() {
        var llm = new ScriptedGateway();
        for (int i = 0; i < 4; i++) {
            llm.then("ana", new RuntimeException("flaky"));
        }
        var ui = ui(true, EscalationChoice.SKIP);
        Task root = orch(llm, ui, Limits.defaults()).run("go");
        assertEquals(TaskStatus.FAILED, root.status);
    }

    @Test
    void tokenBudgetEscalatesImmediatelyWithoutRetries() {
        var llm = new ScriptedGateway().then("ana", new LlmReply("big", List.of(), 40_000, 10));
        var ui = ui(true, EscalationChoice.ABORT);
        assertThrows(RunAborted.class, () -> orch(llm, ui, Limits.defaults()).run("go"));
        assertEquals(0, count(ui, "TaskRetried"));
        assertEquals(1, count(ui, "LimitReached"));
        assertEquals(1, count(ui, "TaskEscalated"));
    }

    @Test
    void userDenyingAnApprovalIsNotAFailureAndIsNeverRetried() {
        var llm = new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "x"))
                .call("bruno", "write_file", Map.of("path", "out/a.txt", "content", "a"))
                .say("bruno", "the user declined the write")
                .say("ana", "ok");
        var ui = ui(false, EscalationChoice.ABORT);
        Task root = orch(llm, ui, Limits.defaults()).run("go");
        assertEquals(TaskStatus.DONE, root.status);
        assertEquals(0, count(ui, "TaskFailed"));
        assertFalse(Files.exists(workspace.resolve("out/a.txt")));
    }

    @Test
    void maxStepsFailsTheTask() {
        var llm = new ScriptedGateway();
        for (int i = 0; i < 12 * 4; i++) {
            llm.call("ana", "handoff", Map.of("to", "zoe", "objective", "x"));
        }
        var ui = ui(true, EscalationChoice.ABORT);
        assertThrows(RunAborted.class, () -> orch(llm, ui, Limits.defaults()).run("go"));
        assertEquals(4, count(ui, "TaskFailed"));
    }

    @Test
    void emptyReplyIsNudgedOnceInsteadOfFailingTheTask() {
        var llm = new ScriptedGateway()
                .then("ana", new LlmReply("", List.of(), 10, 0))
                .say("ana", "all good");
        var ui = ui(true, EscalationChoice.ABORT);
        Task root = orch(llm, ui, Limits.defaults()).run("go");
        assertEquals(TaskStatus.DONE, root.status);
        assertEquals(1, count(ui, "AgentNudged"));
        assertEquals(0, count(ui, "TaskFailed"));
    }

    @Test
    void repeatedEmptyRepliesStillFailTheAttempt() {
        var llm = new ScriptedGateway();
        for (int i = 0; i < 8; i++) {
            llm.then("ana", new LlmReply("", List.of(), 10, 0));
        }
        var ui = ui(true, EscalationChoice.ABORT);
        assertThrows(RunAborted.class, () -> orch(llm, ui, Limits.defaults()).run("go"));
        assertEquals(4, count(ui, "TaskFailed"));
        assertEquals(4, count(ui, "AgentNudged"));
    }

    @Test
    void handoffsPerAttemptAreCappedSoALeadCannotDelegateForever() {
        var llm = new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "one"))
                .say("bruno", "one done")
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "two"))
                .say("bruno", "two done")
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "three"))
                .say("bruno", "three done")
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "four"))
                .say("ana", "final report");
        var ui = ui(true, EscalationChoice.ABORT);
        Task root = orch(llm, ui, Limits.defaults()).run("go");
        assertEquals(TaskStatus.DONE, root.status);
        assertEquals(3, count(ui, "HandoffCreated"), "the 4th handoff is rejected by the runtime");
    }

    @Test
    void symlinkInsideTheWorkspaceCannotEscapeIt() throws Exception {
        Path outside = Files.createTempDirectory("outside-");
        Files.createDirectories(workspace.resolve("out"));
        try {
            Files.createSymbolicLink(workspace.resolve("out/link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.abort("symlinks not available on this platform/user: " + e);
        }
        var llm = new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "bruno", "objective", "x"))
                .call("bruno", "write_file", Map.of("path", "out/link/pwned.txt", "content", "x"))
                .say("bruno", "ok")
                .say("ana", "ok");
        var ui = ui(true, EscalationChoice.ABORT);
        orch(llm, ui, Limits.defaults()).run("go");
        assertFalse(Files.exists(outside.resolve("pwned.txt")), "write escaped through the symlink");
        assertTrue(ui.approvals.isEmpty(), "must be refused before asking the user");
    }
}
