package dev.buildcli.cli;

import dev.buildcli.cli.commands.AboutCommand;
import dev.buildcli.cli.commands.AutocompleteCommand;
import dev.buildcli.cli.commands.ChangelogCommand;
import dev.buildcli.cli.commands.ConfigCommand;
import dev.buildcli.cli.commands.DoctorCommand;
import dev.buildcli.cli.commands.ProjectCommand;
import dev.buildcli.cli.commands.RunCommand;
import dev.buildcli.cli.commands.VersionCommand;
import dev.buildcli.cli.commands.project.AddCommand;
import dev.buildcli.cli.commands.project.BuildCommand;
import dev.buildcli.cli.commands.project.CleanupCommand;
import dev.buildcli.cli.commands.project.CodeCommand;
import dev.buildcli.cli.commands.project.DocumentCodeCommand;
import dev.buildcli.cli.commands.project.InitCommand;
import dev.buildcli.cli.commands.project.ReleaseCommand;
import dev.buildcli.cli.commands.project.RmCommand;
import dev.buildcli.cli.commands.project.SetCommand;
import dev.buildcli.cli.commands.project.TestCommand;
import dev.buildcli.cli.commands.project.UpdateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "buildcli", mixinStandardHelpOptions = true,
    version = "BuildCLI 0.0.14",
    description = "BuildCLI - A CLI for Java Project Management",
    subcommands = {
        AddCommand.class, BuildCommand.class, CleanupCommand.class, CodeCommand.class, DocumentCodeCommand.class,
        InitCommand.class, ReleaseCommand.class, RmCommand.class, SetCommand.class, TestCommand.class, UpdateCommand.class,
        AboutCommand.class, AutocompleteCommand.class, ChangelogCommand.class, ConfigCommand.class,
        DoctorCommand.class, ProjectCommand.class, RunCommand.class, VersionCommand.class, CommandLine.HelpCommand.class,
    }
)
public class BuildCLI {

}
