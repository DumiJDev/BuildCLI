package dev.buildcli.cli.commands.plugin;

import dev.buildcli.cli.utils.CommandUtils;
import dev.buildcli.core.domain.BuildCLICommand;
import dev.buildcli.core.domain.configs.BuildCLIConfig;
import dev.buildcli.core.utils.config.ConfigContextLoader;
import dev.buildcli.plugin.PluginType;
import dev.buildcli.plugin.builders.PluginBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.Arrays;

import static dev.buildcli.core.utils.BeautifyShell.blueFg;
import static dev.buildcli.core.utils.input.InteractiveInputUtils.*;

@Command(name = "init", aliases = {"i"}, description = "", mixinStandardHelpOptions = true)
public class InitCommand implements BuildCLICommand {
  private static final Logger log = LoggerFactory.getLogger(InitCommand.class);

  @Option(names = {"--name", "-n"}, description = "Plugin name", arity = "0..1")
  private String name;
  @Option(names = {"--type", "-t"}, description = "Plugin type", converter = PluginType.Converter.class)
  private PluginType type;
  @Option(names = {"--out", "-o"}, description = "Plugin output directory")
  private String outputDirectory;

  private final BuildCLIConfig globalConfig = ConfigContextLoader.getAllConfigs();

  @Override
  public void run() {
    var pluginName = name == null ? question("Plugin name", true) : name;
    var pluginType = type == null ? options("Plugin type", Arrays.stream(PluginType.values()).toList()) : type;
    File pluginDirectory;
    do {
      var plDir = outputDirectory == null ? question("Plugin directory", true) : outputDirectory;

      pluginDirectory = new File(plDir, pluginName);

      if (pluginDirectory.isFile()) {
        log.warn("Plugin directory is not a valid directory: {}", pluginDirectory.getAbsolutePath());
      } else {
        break;
      }

    } while (true);

    var builder = PluginBuilderFactory.create(pluginType);

    var file = builder.build(pluginDirectory, pluginName);

    if (confirm("Do you want to install plugin %s?".formatted(blueFg(pluginName)))) {
      CommandUtils.call("plugin", "add", "-f", file.getAbsolutePath());
    }
  }
}
