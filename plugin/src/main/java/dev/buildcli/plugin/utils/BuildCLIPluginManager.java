package dev.buildcli.plugin.utils;

import dev.buildcli.core.domain.jar.Jar;
import dev.buildcli.core.utils.config.ConfigContextLoader;
import dev.buildcli.core.utils.filesystem.FindFilesUtils;
import dev.buildcli.plugin.BuildCLICommandPlugin;
import dev.buildcli.plugin.BuildCLIPlugin;
import dev.buildcli.plugin.BuildCLITemplatePlugin;
import dev.buildcli.plugin.enums.TemplateType;
import dev.buildcli.plugin.factories.CommandFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static dev.buildcli.core.constants.ConfigDefaultConstants.PLUGIN_PATHS;
import static dev.buildcli.core.utils.BeautifyShell.blueFg;
import static dev.buildcli.core.utils.input.InteractiveInputUtils.confirm;
import static java.util.Objects.nonNull;

public final class BuildCLIPluginManager {
  private static final Map<Class<? extends BuildCLIPlugin>, List<? extends BuildCLIPlugin>> PLUGINS = new HashMap<>();

  public static List<BuildCLICommandPlugin> getCommands() {


    return BuildCLIPluginManager.loadCommandPluginFromJar().stream()
        .filter(plugin -> plugin.getClass().isAnnotationPresent(Command.class))
        .filter(plugin -> nonNull(plugin.name()) && nonNull(plugin.version()))
        .toList();
  }

  public static List<BuildCLITemplatePlugin> getTemplates() {


    return BuildCLIPluginManager.loadTemplatePluginFromJar()
        .stream()
        .filter(plugin -> nonNull(plugin.name()) && nonNull(plugin.version()))
        .toList();
  }


  public static List<BuildCLITemplatePlugin> getTemplatesByType(TemplateType type) {
    if (type == null) {
      return getTemplates();
    }

    return getTemplates().stream()
        .filter(buildCLITemplatePlugin -> buildCLITemplatePlugin.type().equals(type))
        .toList();
  }

  private static String[] pluginPaths() {
    var defaultPath = System.getProperty("user.home") + "/.buildcli/plugins";
    var property = ConfigContextLoader.getAllConfigs().getProperty(PLUGIN_PATHS);

    return property.orElse("").concat((property.isPresent() ? "" : ";") + defaultPath).split(";");
  }

  private static List<Jar> loadJars() {
    return Arrays.stream(pluginPaths())
        .filter(Predicate.not(String::isBlank))
        .map(File::new)
        .map(FindFilesUtils::searchJarFiles)
        .flatMap(List::stream)
        .map(Jar::new)
        .toList();
  }

  private static List<BuildCLICommandPlugin> loadCommandPluginFromJar() {
    var pls = PF4JPluginLoader.getInstance().getPlugins(BuildCLICommandPlugin.class);

    System.out.println(pls);

    return pls;
  }

  private static List<BuildCLITemplatePlugin> loadTemplatePluginFromJar() {
    return PF4JPluginLoader.getInstance().getPlugins(BuildCLITemplatePlugin.class);
  }

  public static void registerPlugins(CommandLine commandLine) {
    //PluginLoader.registerClasses(loadJars());
    PF4JPluginLoader.getInstance().loadPlugins(loadJars());
    var plugins = getCommands();
    var subcommands = commandLine.getSubcommands();
    for (BuildCLICommandPlugin commandPlugin : plugins) {
      var command = CommandFactory.createCommandLine(commandPlugin);

      var commandName = command.getCommandName();
      if (subcommands.containsKey(commandName)) {
        var confirm = confirm("Do you want override the subcommand \"%s\"".formatted(blueFg(commandName)));
        if (confirm) {
          commandLine.getCommandSpec().removeSubcommand(commandName);
          commandLine.addSubcommand(command);
        }
      } else {
        commandLine.addSubcommand(command);
      }
    }
  }
}
