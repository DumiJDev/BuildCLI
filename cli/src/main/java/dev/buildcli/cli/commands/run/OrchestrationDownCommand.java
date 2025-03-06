package dev.buildcli.cli.commands.run;

import dev.buildcli.core.domain.BuildCLICommand;
import dev.buildcli.core.utils.DockerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "down",
        description = "stop all containers")
public class OrchestrationDownCommand implements BuildCLICommand {

    private static final Logger logger = LoggerFactory.getLogger(OrchestrationDownCommand.class.getName());

    @Option(names = {"--name", "-n"}, description = "Force image reconstruction")
    private String containerName;

    @Override
    public void run() {
        try {
            DockerManager.downContainer(containerName);

            String message = (containerName == null || containerName.isEmpty())
                    ? "All running containers have been successfully stopped."
                    : "Container '%s' has been successfully stopped.".formatted(containerName);

            logger.info(message);
        } catch (Exception e) {
            logger.error("Failed to stop containers", e);
        }
    }
}
