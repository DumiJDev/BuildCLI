package dev.buildcli.cli.commands.plugin;

import dev.buildcli.core.domain.BuildCLICommand;
import dev.buildcli.core.domain.configs.BuildCLIConfig;
import dev.buildcli.core.utils.config.ConfigContextLoader;
import picocli.CommandLine.Command;

@Command(name = "remove", aliases = {"rm"}, description = "", mixinStandardHelpOptions = true)
public class RmCommand implements BuildCLICommand {

  private final BuildCLIConfig globalConfig = ConfigContextLoader.getAllConfigs();

  @Override
  public void run() {

  }
}
