package dev.buildcli.cli;

import dev.buildcli.core.domain.configs.BuildCLIConfig;
import dev.buildcli.core.log.config.LoggingConfig;
import dev.buildcli.core.utils.BuildCLIService;
import dev.buildcli.plugin.BuildCLICommandPlugin;
import dev.buildcli.plugin.utils.PluginManager;
import picocli.CommandLine;

import java.util.List;

public class CommandLineRunner {

  public static void main(String[] args) {
    LoggingConfig.configure();

    if (BuildCLIService.shouldShowAsciiArt(args)) {
      BuildCLIService.welcome();
    }


    BuildCLIConfig.initialize();
    var commandLine = new CommandLine(new BuildCLI());

    PluginManager.registerPlugins(commandLine);

    int exitCode = commandLine.execute(args);
    BuildCLIService.checkUpdatesBuildCLIAndUpdate();

    System.exit(exitCode);
  }

  private static void register(CommandLine commandLine, List<BuildCLICommandPlugin> plugins) {

  }
}
