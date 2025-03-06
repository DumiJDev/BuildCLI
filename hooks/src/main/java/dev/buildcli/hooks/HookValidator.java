package dev.buildcli.hooks;

import picocli.CommandLine;

import java.util.HashSet;
import java.util.Set;

public class HookValidator {
    private final Set<String> availableCommands;

    public HookValidator(CommandLine commandLine) {
        this.availableCommands = loadAvailableCommands(commandLine);
    }

    private Set<String> loadAvailableCommands(CommandLine commandLine) {
        Set<String> commands = new HashSet<>();
        commandLine.getSubcommands().forEach((name, subcommand) -> {
            CommandLine.Command annotation = subcommand.getCommand().getClass().getAnnotation(CommandLine.Command.class);
            if (annotation != null) {
                commands.add(annotation.name());
            }
        });
        return commands;
    }

    public boolean isValidCommand(String commandName) {
        return availableCommands.contains(commandName);
    }

    public boolean isValidPhase(String phase){
        try {
            HookPhase.valueOf(phase.toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}