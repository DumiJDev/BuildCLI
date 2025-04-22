package dev.buildcli.core.utils;

import picocli.AutoComplete;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class AutoCompleteManager {

    public void setupAutocomplete() {
        String commandName = "buildcli";
        String fullyQualifiedClassName = "dev.buildcli.cli.BuildCLI";

        try {
            List<String> availableShells = detectAvailableShells();

            if (availableShells.isEmpty()) {
                System.out.println("No supported shell detected. Skipping autocomplete setup.");
                return;
            }

            System.out.println("Detected shells: " + availableShells);

            for (String shell : availableShells) {
                Path completionPath = Path.of(System.getProperty("user.home"), "." + commandName + "-completion." + shell);
                generateAutoComplete(fullyQualifiedClassName, commandName, completionPath);
                configureAutoCompleteDynamically(shell, completionPath);
            }

            System.out.println("Autocomplete setup completed successfully!");

        } catch (Exception e) {
            System.err.println("Failed to set up autocomplete: " + e.getMessage());
        }
    }

    private List<String> detectAvailableShells() {
        List<String> availableShells = new ArrayList<>();

        if (isShellInstalled("bash")) availableShells.add("bash");
        if (isShellInstalled("zsh")) availableShells.add("zsh");
        if (isShellInstalled("fish")) availableShells.add("fish");

        return availableShells;
    }

    private boolean isShellInstalled(String shell) {
        try{
            Process process = new ProcessBuilder(OS.isWindows() ? "where" : "which", OS.isWindows() ? shell + ".exe" : shell).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.readLine();

            return output != null && !output.isBlank();
        } catch (Exception e){
            return false;
        }
    }

    private void generateAutoComplete(String fullyQualifiedClassName, String commandName, Path completionPath) {
        try {
            if (Files.exists(completionPath)) {
                System.out.println("Existing autocomplete script detected for " + commandName + ". Overwriting...");
                AutoComplete.main(new String[]{fullyQualifiedClassName, "-n=" + commandName, "-o=" + completionPath.toString(), "--force"});
            } else {
                AutoComplete.main(new String[]{fullyQualifiedClassName, "-n=" + commandName, "-o=" + completionPath.toString()});
            }
        } catch (Exception e) {
            System.err.println("Error generating autocomplete script for " + commandName + ": " + e.getMessage());
        }
    }

    private void configureAutoCompleteDynamically(String shell, Path completionPath) {
        try {
            List<String> configFiles = detectConfigFiles(shell);

            if (configFiles.isEmpty()) {
                System.out.printf("No configuration files detected for %s.%n", shell);
                return;
            }

            String sourceCommand = "source " + completionPath + "\n";

            for (String configFile : configFiles) {
                Path configFilePath = Path.of(configFile.replace("~", System.getProperty("user.home")));

                if (!Files.exists(configFilePath)) {
                    System.out.printf("Configuration file not found: %s. Skipping.%n", configFile);
                    continue;
                }

                String configContent = Files.readString(configFilePath);
                if (configContent.contains(sourceCommand)) {
                    System.out.printf("Autocomplete already configured in %s.%n", configFile);
                    continue;
                }

                Files.writeString(configFilePath, sourceCommand, StandardOpenOption.APPEND);
                System.out.printf("Autocomplete configured in %s. Restart your terminal or run 'source %s'.%n", configFile, configFile);
            }
        } catch (IOException e) {
            System.err.printf("Failed to configure autocomplete for %s: %s%n", shell, e.getMessage());
        }
    }

    private List<String> detectConfigFiles(String shell) {
        List<String> possibleFiles = new ArrayList<>();
        String home = System.getProperty("user.home");

        switch (shell) {
            case "bash":
                possibleFiles.add(home + "/.bashrc");
                possibleFiles.add(home + "/.bash_profile");
                possibleFiles.add(home + "/.profile");
                break;
            case "zsh":
                possibleFiles.add(home + "/.zshrc");
                possibleFiles.add(home + "/.zprofile");
                break;
            case "fish":
                possibleFiles.add(home + "/.config/fish/config.fish");
                break;
            default:
                System.out.println("Unrecognized shell: " + shell);
                return Collections.emptyList();
        }

        return possibleFiles.stream()
                .filter(path -> Files.exists(Path.of(path)))
                .collect(Collectors.toList());
    }
}
