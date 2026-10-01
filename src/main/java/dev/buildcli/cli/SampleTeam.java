package dev.buildcli.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The three sample agents (an architect who leads, a developer who writes and builds, a reviewer) and an AGENTS.md, written
 * by {@code buildcli init} and by the "add the sample agents" button of an empty chat. Existing files are never overwritten.
 */
final class SampleTeam {
    static final List<String> NAMES = List.of("ana", "bruno", "carla");
    /** The group the samples are put in; ana, the first, is its admin. */
    static final String GROUP = "backend";

    private SampleTeam() {}

    /** @return how many files were created; each one, and each one skipped, is reported to {@code log} */
    static int writeFiles(Path project, Consumer<String> log) throws IOException {
        Path base = project.resolve(".buildcli");
        int created = 0;
        created += write(project, base.resolve("agents/ana.md"), ANA, log);
        created += write(project, base.resolve("agents/bruno.md"), BRUNO, log);
        created += write(project, base.resolve("agents/carla.md"), CARLA, log);
        created += write(project, project.resolve("AGENTS.md"), AGENTS_MD, log);
        return created;
    }

    private static int write(Path project, Path file, String content, Consumer<String> log) throws IOException {
        Path shown = project.relativize(file);
        if (Files.exists(file)) {
            log.accept("skipped  " + shown + " (already exists)");
            return 0;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        log.accept("created  " + shown);
        return 1;
    }

    static final String ANA = """
            ---
            schema: 1
            name: ana
            role: architect
            description: Owns architecture, boundaries and technical trade-offs. Leads the team.
            capabilities: [filesystem.read, search, git.read, agent.handoff]
            permissions:
              filesystem:
                read: ["**"]
            ---
            You are the software architect and the admin of the backend group.
            You do not implement production code yourself: read the code, decide how the work should be done, and delegate
            the implementation to a teammate with a handoff. Give a short brief (decisions, constraints, relevant paths), not
            a transcript. When teammates report back, check that the result answers the request, then give the user a short
            final report. Challenge unnecessary complexity.
            """;

    static final String BRUNO = """
            ---
            schema: 1
            name: bruno
            role: developer
            description: Implements tasks and runs the build.
            capabilities: [filesystem.read, filesystem.write, search, git.read, command.execute]
            permissions:
              filesystem:
                read: ["**"]
                write: ["src/**"]       # every write is still shown to you as a diff and needs your approval
              command:
                allow:                   # argv arrays; anything else asks you first
                  - ["mvn", "-q", "test"]
                  - ["mvn", "-q", "verify"]
                timeout: 10m
            ---
            You implement the tasks you are given with small, focused changes that follow the project's conventions in
            AGENTS.md. Run the tests after you change code. When you are done, report what you changed and the test result.
            """;

    static final String CARLA = """
            ---
            schema: 1
            name: carla
            role: reviewer
            description: Reviews changes for correctness, tests and style.
            capabilities: [filesystem.read, search, git.read]
            permissions:
              filesystem:
                read: ["**"]
            ---
            You review changes. Use git to see what changed, read the affected code and its tests, and report concrete
            problems (bugs, missing tests, unclear naming) with file and line. Say plainly when the change looks good.
            """;

    static final String AGENTS_MD = """
            # Project context

            <!-- BuildCLI gives this file to its agents as background information (it can never change what they are
                 allowed to do). Keep it short and factual. -->

            ## Build and test
            - Build: `mvn -q verify`
            - Run the tests: `mvn -q test`

            ## Architecture
            Describe the main modules and how they fit together.

            ## Conventions
            List the code style, naming and testing rules the team follows.
            """;
}
