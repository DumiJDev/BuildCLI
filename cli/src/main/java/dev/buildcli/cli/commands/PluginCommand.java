package dev.buildcli.cli.commands;

import dev.buildcli.cli.commands.plugin.AddCommand;
import dev.buildcli.cli.commands.plugin.InitCommand;
import picocli.CommandLine.Command;

@Command(name = "plugin", aliases = {"plg"}, description = "", mixinStandardHelpOptions = true,
    subcommands = {AddCommand.class, InitCommand.class, RmCommand.class}
)
public class PluginCommand {
}
