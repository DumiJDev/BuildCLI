package dev.buildcli.cli.commands.project.add;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.buildcli.cli.utilsForTest.TestAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerComposeCommandTest {

    private TestAppender testAppender;
    private final String validDockerFilePath = "./ValidDockerFile";

    @BeforeEach
    void setUp() throws IOException {
        testAppender = new TestAppender();
        testAppender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(DockerComposeCommand.class);
        logger.addAppender(testAppender);

        Files.write(Paths.get(validDockerFilePath), "FROM alpine".getBytes());
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(Paths.get(validDockerFilePath));
        testAppender.stop();
    }

    @Test
    void testDockerComposeCommand_Success() {
        CommandLine cmd = new CommandLine(new DockerComposeCommand());
        int exitCode = cmd.execute("--ports", "8080:8080", "--volumes", "./data:/app/data", "--cpu", "2", "--memory", "512m", "--dockerfile", "./ValidDockerFile");

        assertEquals(0, exitCode);

        List<ILoggingEvent> logs = testAppender.list;
        assertTrue(logs.stream().anyMatch(event -> event.getFormattedMessage().equals("docker-compose.yml created successfully!")));
    }

    @Test
    void testDockerComposeCommand_Failure() {

        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errContent));

        CommandLine cmd = new CommandLine(new DockerComposeCommand());
        int exitCode = cmd.execute("--ports", "8080:8080", "--volumes", "./data:/app/data", "--cpu", "2", "--memory", "512m", "--dockerfile", "./InvalidDockerFile");

        System.setErr(originalErr);

        assertEquals(1, exitCode);

        String errOutput = errContent.toString();
        assertTrue(errOutput.contains("Dockerfile not found: ./InvalidDockerFile"), "Expected Dockerfile not found detail not found in stderr.");
    }

}