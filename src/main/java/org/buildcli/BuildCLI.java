package org.buildcli;

import org.buildcli.commands.AboutCommand;
import org.buildcli.commands.AutocompleteCommand;
import org.buildcli.commands.ConfigCommand;
import org.buildcli.commands.ProjectCommand;
import org.buildcli.commands.VersionCommand;
import org.buildcli.commands.project.AddCommand;
import org.buildcli.commands.project.BuildCommand;
import org.buildcli.commands.project.CleanupCommand;
import org.buildcli.commands.project.CodeCommand;
import org.buildcli.commands.project.DocumentCodeCommand;
import org.buildcli.commands.project.InitCommand;
import org.buildcli.commands.project.ReleaseCommand;
import org.buildcli.commands.project.RmCommand;
import org.buildcli.commands.project.RunCommand;
import org.buildcli.commands.project.SetCommand;
import org.buildcli.commands.project.TestCommand;
import org.buildcli.commands.project.UpdateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "buildcli", mixinStandardHelpOptions = true,
    version = "BuildCLI 0.0.14",
    description = "BuildCLI - A CLI for Java Project Management",
    subcommands = {
        AddCommand.class, BuildCommand.class, CleanupCommand.class, CodeCommand.class, DocumentCodeCommand.class,
        InitCommand.class, ReleaseCommand.class, RmCommand.class, RunCommand.class, SetCommand.class, TestCommand.class,
        UpdateCommand.class, AboutCommand.class, AutocompleteCommand.class, ConfigCommand.class, ProjectCommand.class,
        VersionCommand.class, CommandLine.HelpCommand.class,
    }
)
public class BuildCLI {

}
