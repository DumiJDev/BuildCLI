package dev.buildcli.cli;

import dev.buildcli.core.domain.configs.BuildCLIConfig;
import dev.buildcli.core.log.config.LoggingConfig;
import dev.buildcli.core.utils.BuildCLIService;
import picocli.CommandLine;

public class CommandLineRunner {
  public static void main(String[] args) {
    LoggingConfig.configure();
    BuildCLIService.welcome();

    BuildCLIConfig.initialize();
    var commandLine = new CommandLine(new BuildCLI());

    int exitCode = commandLine.execute(args);
    BuildCLIService.checkUpdatesBuildCLIAndUpdate();

    System.exit(exitCode);
  }
}
