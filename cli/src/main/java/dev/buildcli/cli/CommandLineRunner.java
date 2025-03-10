package dev.buildcli.cli;

import dev.buildcli.core.utils.input.ShellInteractiveUtils;
import dev.buildcli.core.domain.configs.BuildCLIConfig;
import dev.buildcli.plugin.CommandFactory;
import dev.buildcli.core.log.config.LoggingConfig;
import dev.buildcli.core.utils.BuildCLIService;
import dev.buildcli.plugin.BuildCLICommandPlugin;
import dev.buildcli.plugin.PluginManager;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.List;

public class CommandLineRunner {
  private static CommandLine commandLine;

  public static void main(String[] args) {
    LoggingConfig.configure();

    if (BuildCLIService.shouldShowAsciiArt(args)) {
      BuildCLIService.welcome();
    }

    var commandPlugins = PluginManager.getCommands();

    BuildCLIConfig.initialize();
    commandLine = new CommandLine(new BuildCLI());

    register(commandPlugins);

    int exitCode = commandLine.execute(args);
    BuildCLIService.checkUpdatesBuildCLIAndUpdate();

    System.exit(exitCode);
  }

  private static void register(List<BuildCLICommandPlugin> plugins) {
    for (BuildCLICommandPlugin commandPlugin : plugins) {
      commandLine.addSubcommand(CommandFactory.createCommandLine(commandPlugin));
    }
  }
}
