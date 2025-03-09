package dev.buildcli.cli.commands.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OrchestrationCommand Tests")
class OrchestrationCommandTest {

    @Test
    @DisplayName("Test that the run() method displays the correct usage message.")
    void testRunDisplaysUsage() {

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(outContent);
        PrintStream originalOut = System.out;
        System.setOut(ps);

        CommandLine cmd = new CommandLine(new OrchestrationCommand());
        cmd.execute();

        System.setOut(originalOut);

        String output = outContent.toString().trim();

        assertTrue(output.contains("Usage: orchestration"));
        assertTrue(output.contains("Manage container orchestration using Docker Compose."));
        assertTrue(output.contains("-h, --help"));
        assertTrue(output.contains("-V, --version"));
        assertTrue(output.contains("Commands:"));
        assertTrue(output.contains("up"));
        assertTrue(output.contains("down"));
    }

    @Test
    @DisplayName("Test that the subcommands are correctly registered.")
    void testSubcommandsRegistered() {
        CommandLine cmd = new CommandLine(new OrchestrationCommand());

        assertTrue(cmd.getSubcommands().containsKey("up"));
        assertTrue(cmd.getSubcommands().containsKey("down"));
    }
}