package dev.buildcli.cli.commands;


import dev.buildcli.core.domain.BuildCLICommand;
import dev.buildcli.core.utils.BuildCLIService;
import picocli.CommandLine.Command;

@Command(name = "about", aliases = {"ab"}, description = "Displays information about BuildCLI, including its purpose and usage.", mixinStandardHelpOptions = true)
public class AboutCommand implements BuildCLICommand {

  @Override
  public void run() {
    BuildCLIService.about();
  }
}
