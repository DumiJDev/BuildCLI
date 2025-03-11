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
import static org.mockito.Mockito.*;

@DisplayName("OrchestrationDownCommand Tests")
@ExtendWith(MockitoExtension.class)
class OrchestrationDownCommandTest {

    private TestAppender testAppender;

    @Mock
    private DockerManager dockerManagerMock;

    @InjectMocks
    private OrchestrationDownCommand command;

    @BeforeEach
    void setUp() {
        testAppender = new TestAppender();
        testAppender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(OrchestrationDownCommand.class);
        logger.addAppender(testAppender);
    }

    @AfterEach
    void tearDown() {
        testAppender.stop();
        Logger logger = (Logger) LoggerFactory.getLogger(DockerManager.class);
        logger.detachAndStopAllAppenders();
    }

    @Test
    @DisplayName("Test run() success - Stop all containers")
    void testRunSuccessStopAllContainers() throws DockerException {

        command.run();
        verify(dockerManagerMock).downContainer(null);
        assertTrue(testAppender.list
                .stream()
                .anyMatch(
                        event -> event.getFormattedMessage().equals("All running containers have been successfully stopped.")));
    }

    @Test
    @DisplayName("Test run() success - Stop a single container")
    void testRunSuccessStopSingleContainer() throws DockerException {

        CommandLine cmd = new CommandLine(command);
        int exitCode = cmd.execute("-n", "container1");

        assertEquals(0, exitCode);
        verify(dockerManagerMock).downContainer("container1");
        assertTrue(testAppender.list
                .stream()
                .anyMatch(
                        event -> event.getFormattedMessage().equals("Container 'container1' has been successfully stopped.")));
    }

    @Test
    @DisplayName("Test run() failure - Exception thrown with 'Failed to stop containers: ' message")
    void testRunFailureExceptionMessage() throws Exception {

        command.setContainerName("container1");

        DockerException exception = new DockerException("Test exception");
        doThrow(exception).when(dockerManagerMock).downContainer(anyString());

        CommandLine.ExecutionException thrown = assertThrows(CommandLine.ExecutionException.class,
                command::run,
                "Expected run() to throw CommandLine.ExecutionException");

        assertTrue(thrown.getMessage().contains("Failed to stop containers: Test exception"),
                "Exception message should contain 'Failed to stop containers: Test exception'");

        assertEquals(exception, thrown.getCause());
    }
}