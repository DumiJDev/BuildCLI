package dev.buildcli.cli.commands;

import dev.buildcli.core.domain.BuildCLICommand;
import picocli.CommandLine;

@CommandLine.Command(name="hook",
description = "Manage hooks",
subcommands = {HookAddCommand.class, HookRemoveCommand.class})
public class HookCommand implements dev.buildcli.hooks.HookCommand {

    @Override
    public void run() {

    }
}
