package dev.buildcli.cli.commands;

import dev.buildcli.cli.commands.ai.CodeCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "ai", description = "Command to use ai features", mixinStandardHelpOptions = true,
    subcommands = {CodeCommand.class}
)
public class AiCommand {

}
