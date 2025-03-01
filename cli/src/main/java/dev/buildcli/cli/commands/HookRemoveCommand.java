package dev.buildcli.cli.commands;

import dev.buildcli.core.domain.BuildCLICommand;

import dev.buildcli.hooks.HookManager;
import dev.buildcli.hooks.HookPhase;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
        name = "remove",
        description = "Remove um hook existente"
)
public class HookRemoveCommand implements BuildCLICommand {

    @Parameters(index = "0", description = "Fase do hook (before/after)")
    private String phase;

    @Parameters(index = "1", description = "Comando ao qual o hook está associado")
    private String command;

    @Override
    public void run() {
        HookManager hookManager = new HookManager("hooks.json");

        HookPhase hookPhase = HookPhase.valueOf(phase.toUpperCase());
        hookManager.removeHook(command, hookPhase);
        System.out.println("Hook removido com sucesso.");
    }
}
