package dev.buildcli.cli.commands.run;


import dev.buildcli.core.domain.BuildCLICommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import static java.lang.System.*;

@Command(
        name = "orchestration",
        aliases = "oc",
        description = "Manage container orchestration using Docker Compose.",
        mixinStandardHelpOptions = true,
        subcommands = {
                OrchestrationUpCommand.class,
                OrchestrationDownCommand.class
        }
)
public class OrchestrationCommand implements BuildCLICommand {

    @Override
    public void run() {
        CommandLine.usage(this, out);
    }
}