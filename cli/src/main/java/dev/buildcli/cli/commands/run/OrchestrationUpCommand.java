package dev.buildcli.cli.commands.run;

import dev.buildcli.core.domain.BuildCLICommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import static dev.buildcli.core.utils.DockerManager.upContainer;

@Command(name = "up",
        description = "start all containers")
public class OrchestrationUpCommand implements BuildCLICommand {

    private static final Logger logger = LoggerFactory.getLogger(OrchestrationUpCommand.class.getName());

    @Option(names = {"--build", "-b"}, description = "Force image reconstruction")
    private boolean rebuild;


    @Override
    public void run() {
        try {
            upContainer(rebuild);
        } catch (Exception e) {
            logger.error("Failed to start containers", e);
        }
    }
}
