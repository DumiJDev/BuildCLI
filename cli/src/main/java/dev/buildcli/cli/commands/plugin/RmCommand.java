package dev.buildcli.cli.commands.plugin;

import dev.buildcli.core.domain.BuildCLICommand;
import dev.buildcli.core.domain.configs.BuildCLIConfig;
import dev.buildcli.core.utils.config.ConfigContextLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static dev.buildcli.core.constants.ConfigDefaultConstants.PLUGIN_PATHS;
import static dev.buildcli.core.utils.input.InteractiveInputUtils.confirm;
import static dev.buildcli.core.utils.input.InteractiveInputUtils.question;

@Command(name = "remove", aliases = {"rm"}, description = "", mixinStandardHelpOptions = true)
public class RmCommand implements BuildCLICommand {
  private final Logger logger = LoggerFactory.getLogger("RmPluginCommand");

  @Parameters(description = "")
  private List<String> names;

  private final BuildCLIConfig globalConfig = ConfigContextLoader.getAllConfigs();

  @Override
  public void run() {
    var localNames = new ArrayList<String>();

    if (names == null || names.isEmpty()) {
      while (true) {
        var name = question("Please enter the name of the build cli command:");
        localNames.add(name);
        var doContinue = confirm("Do you want add more build cli commands?");

        if (!doContinue) {
          break;
        }
      }
    }

    var paths = globalConfig.getProperty(PLUGIN_PATHS).orElse(System.getProperty("user.home") + "/.buildcli/plugins").split(";");

    for (String path : paths) {
      for (String name : names) {
        var jar = Paths.get(path, name + ".jar");
        logger.info("Removing build cli commands from {}", jar);
        if (Files.isRegularFile(jar)) {
          try {
            Files.deleteIfExists(jar);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }

          logger.info("Removed build cli command from {}", jar);
        } else {
          logger.info("Jar {} does not exist", jar);
        }
      }
    }
  }
}
