package dev.buildcli.hooks;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.buildcli.core.domain.BuildCLICommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

public class HookManager {

    private static final List<HookEntry> hooks = new ArrayList<>();
    private final Gson gson = new Gson();
    private final String hooksFilePath;
    private final Set<String> availableCommands;

    public HookManager(String hooksFilePath) {
        this.hooksFilePath = hooksFilePath;
        this.availableCommands = loadAvailableCommands();
        loadHooksFromJson();
    }

    private void loadHooksFromJson() {
        System.out.println("Loading hooks from JSON file: " + hooksFilePath);
        File file = new File(hooksFilePath);
        if (!file.exists()) {
            try {
                if (file.createNewFile()) {
                    try (FileWriter writer = new FileWriter(file)) {
                        writer.write("[]");
                    }
                }
            } catch (IOException e) {
                System.err.println("Error creating hooks file: " + e.getMessage());
            }
        }

        Type type = new TypeToken<List<HookEntry>>() {}.getType();
        try (FileReader reader = new FileReader(file)) {
            List<HookEntry> loadedHooks = gson.fromJson(reader, type);
            if (loadedHooks != null) {
                hooks.clear();
                hooks.addAll(loadedHooks);
            }
            System.out.println("Loaded hooks: " + hooks);
        } catch (IOException e) {
            System.err.println("Error loading hooks: " + e.getMessage());
        }
    }

    private void saveHooksToJson() {
        System.out.println("Saving hooks to JSON file: " + hooksFilePath);
        File file = new File(hooksFilePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(hooks, writer);
            System.out.println("Hooks saved successfully.");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void registerHook(HookEntry hookEntry) {
        System.out.println("Registering hook: " + hookEntry);
        if (!isValidCommand(hookEntry.command())) {
            System.out.println("Invalid command: " + hookEntry.command());
            throw new IllegalArgumentException("Unknown command: " + hookEntry.command());
        }
        hooks.add(hookEntry);
        saveHooksToJson();
    }

    public void removeHook(String commandName, HookPhase phase) {
        System.out.println("Removing hook for command: " + commandName + ", phase: " + phase);
        hooks.removeIf(h -> h.command().equals(commandName) && h.phase() == phase);
        saveHooksToJson();
    }

    private boolean isValidCommand(String commandName) {
        System.out.println("Validating command: " + commandName);
        boolean isValid = availableCommands.contains(commandName);
        System.out.println("Is valid command: " + isValid);
        return isValid;
    }

    private Set<String> loadAvailableCommands() {
        System.out.println("Loading available commands...");
        Set<String> commands = new HashSet<>();
        ServiceLoader<BuildCLICommand> loader = ServiceLoader.load(BuildCLICommand.class);

        for (BuildCLICommand command : loader) {
            commands.add(Arrays.stream(command.getClass().getAnnotations())
                    .filter(a -> a instanceof Command)
                    .map(a -> ((Command) a).name())
                    .findFirst()
                    .orElseThrow());
            System.out.println("Valid command: " + command.getClass().getSimpleName());
        }

        System.out.println("Available commands: " + commands);
        return commands;
    }

    public List<String> getHooksForCommand(String commandName, HookPhase phase) {
        System.out.println("Getting hooks for command: " + commandName + ", phase: " + phase);
        List<String> hooksForCommand = hooks.stream()
                .filter(h -> h.command().equals(commandName) && h.phase() == phase)
                .map(HookEntry::hookCommand)
                .toList();
        System.out.println("Hooks for command: " + hooksForCommand);
        return hooksForCommand;
    }

    public static void executeHook(String[] args, CommandLine commandLine) {
        String command = String.join(" ",args);
        System.out.println("Comando: "+command);
       for (HookEntry hook : hooks){
           if (hook.command().equals(command)){
               System.out.println("Hook founded: " +hook.hookCommand());
               int execute = commandLine.execute(hook.hookCommand().replace("[]", ""));

               System.exit(execute);
           }
       }
    }
}