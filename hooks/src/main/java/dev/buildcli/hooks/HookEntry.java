package dev.buildcli.hooks;

public record HookEntry(String command, HookPhase phase, String hookCommand) {
}
