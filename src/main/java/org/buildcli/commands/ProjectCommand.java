package org.buildcli.commands;

import org.buildcli.commands.code.DocumentCommand;
import org.buildcli.commands.project.*;
import picocli.CommandLine.Command;

@Command(name = "project", aliases = {"p"}, description = "",
    subcommands = {
        AddCommand.class, RmCommand.class, BuildCommand.class, SetCommand.class,
        TestCommand.class, RunCommand.class, InitCommand.class, CleanupCommand.class,
        UpdateCommand.class, CodeCommand.class
    },
    mixinStandardHelpOptions = true
)
public class ProjectCommand {

}
