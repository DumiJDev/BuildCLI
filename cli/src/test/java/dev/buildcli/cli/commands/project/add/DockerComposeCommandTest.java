package dev.buildcli.cli.commands.project.add;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.buildcli.cli.utilsForTest.TestAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DockerComposeCommand Tests")
class DockerComposeCommandTest {

    private TestAppender testAppender;
    private final String validDockerFilePath = "./ValidDockerFile";
    private final String invalidDockerFilePath = "./InvalidDockerFile";
    private DockerComposeCommand command;

    @BeforeEach
    @DisplayName("Set up test environment")
    void setUp() throws IOException {
        command = new DockerComposeCommand();
        testAppender = new TestAppender();
        testAppender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(DockerComposeCommand.class);
        logger.addAppender(testAppender);

        Files.write(Paths.get(validDockerFilePath), "FROM alpine".getBytes());
    }

    @AfterEach
    @DisplayName("Tear down test environment")
    void tearDown() throws IOException {
        Files.deleteIfExists(Paths.get(validDockerFilePath));
        testAppender.stop();
    }

    @Test
    @DisplayName("Test DockerComposeCommand success scenario")
    void testDockerComposeCommandSuccess() {
        CommandLine cmd = new CommandLine(command);
        int exitCode = cmd.execute("--ports", "8080:8080", "--volumes", "./data:/app/data", "--cpu", "2", "--memory", "512m", "--dockerfile", "./ValidDockerFile");

        assertEquals(0, exitCode);

        List<ILoggingEvent> logs = testAppender.list;
        assertTrue(logs.stream().anyMatch(event -> event.getFormattedMessage().equals("docker-compose.yml created successfully!")));
    }

    @Test
    @DisplayName("Test DockerComposeCommand failure scenario (Dockerfile not found)")
    void testDockerComposeCommandFailureDockerfileNotFound() {
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        CommandLine cmd = new CommandLine(command);
        int exitCode = cmd.execute("--ports", "8080:8080", "--volumes", "./data:/app/data", "--cpu", "2", "--memory", "512m", "--dockerfile", invalidDockerFilePath);

        System.setErr(originalErr);

        assertEquals(1, exitCode);

        String errOutput = errContent.toString();
        assertTrue(errOutput.contains("Dockerfile not found: ./InvalidDockerFile"), "Expected Dockerfile not found detail not found in stderr.");
    }

    @Test
    @DisplayName("Test run() failure scenario - catch block throws ExecutionException")
    void testRunFailureException() {

        command.setDockerFilePath(invalidDockerFilePath);

        CommandLine.ExecutionException thrown = assertThrows(CommandLine.ExecutionException.class,
                command::run,
                "Expected run() to throw CommandLine.ExecutionException");

        assertTrue(thrown.getMessage().contains("Failed to setup docker-compose:"),
                "Exception message should contain 'Failed to setup docker-compose:'");
    }

}