package dev.buildcli.cli.commands.run;

import ch.qos.logback.classic.Logger;
import dev.buildcli.cli.utilsForTest.TestAppender;
import dev.buildcli.core.exceptions.DockerException;
import dev.buildcli.core.utils.DockerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@DisplayName("OrchestrationUpCommand Tests")
@ExtendWith(MockitoExtension.class)
class OrchestrationUpCommandTest {

    private TestAppender testAppender;

    @Mock
    private DockerManager dockerManagerMock;

    @InjectMocks
    private OrchestrationUpCommand command;

    @BeforeEach
    void setUp() {
        testAppender = new TestAppender();
        testAppender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(OrchestrationUpCommand.class);
        logger.addAppender(testAppender);
    }

    @AfterEach
    void tearDown() {
        testAppender.stop();
        Logger logger = (Logger) LoggerFactory.getLogger(DockerManager.class);
        logger.detachAndStopAllAppenders();
    }

    @Test
    @DisplayName("Test run() success - Start all containers")
    void testRunSuccessStartAllContainers() throws DockerException {

        command.run();
        verify(dockerManagerMock).upContainer(false);
        assertTrue(testAppender.list
                .stream()
                .anyMatch(event -> event.getFormattedMessage().equals("All containers have been successfully started.")));
    }

    @Test
    @DisplayName("Test run() success - Start all containers with rebuild")
    void testRunSuccessStartAllContainersWithRebuild() throws DockerException {

        CommandLine cmd = new CommandLine(command);
        int exitCode = cmd.execute("--build");

        assertEquals(0, exitCode);
        verify(dockerManagerMock).upContainer(true);
        assertTrue(testAppender.list
                .stream()
                .anyMatch(event -> event.getFormattedMessage().equals("All containers have been successfully started.")));
    }

    @Test
    @DisplayName("Test run() failure - Exception thrown with 'Failed to start containers: ' message")
    void testRunFailureExceptionMessage() throws Exception {

        DockerException exception = new DockerException("Test exception");
        doThrow(exception).when(dockerManagerMock).upContainer(anyBoolean());

        CommandLine.ExecutionException thrown = assertThrows(CommandLine.ExecutionException.class,
                command::run,
                "Expected run() to throw CommandLine.ExecutionException");

        assertTrue(thrown.getMessage().contains("Failed to start containers: Test exception"),
                "Exception message should contain 'Failed to start containers: Test exception'");

        assertEquals(exception, thrown.getCause());
    }
}