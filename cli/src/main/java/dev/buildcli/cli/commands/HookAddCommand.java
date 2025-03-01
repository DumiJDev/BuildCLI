package dev.buildcli.cli.commands;

import dev.buildcli.core.domain.BuildCLICommand;
import dev.buildcli.hooks.HookEntry;
import dev.buildcli.hooks.HookManager;
import dev.buildcli.hooks.HookPhase;
import picocli.CommandLine;

@CommandLine.Command(
        name = "add",
        description = "Adiciona um novo hook"
)
public class HookAddCommand implements BuildCLICommand {

    @CommandLine.Parameters(index = "0", description = "Fase do hook (before/after)")
    private String phase;

    @CommandLine.Parameters(index = "1", description = "Comando ao qual o hook será associado")
    private String command;

    @CommandLine.Parameters(index = "2", description = "Comando do hook a ser executado")
    private String hookCommand;

    @Override
    public void run() {
        HookManager hookManager = new HookManager("hooks.json");

        HookPhase hookPhase = HookPhase.valueOf(phase.toUpperCase());
        HookEntry hookEntry = new HookEntry(command, hookPhase, hookCommand);

        hookManager.registerHook(hookEntry);
        System.out.println("Hook registrado com sucesso: " + hookEntry);
    }
}
