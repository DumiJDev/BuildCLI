package dev.buildcli.plugin.utils;

import dev.buildcli.core.domain.jar.Jar;
import dev.buildcli.plugin.BuildCLICommandPlugin;
import dev.buildcli.plugin.BuildCLIPlugin;

import java.util.Optional;

public final class PluginUtils {
  public static boolean isValid(Jar jar) {
    return !PluginLoader.load(BuildCLIPlugin.class, jar).isEmpty() || !PluginLoader.load(BuildCLICommandPlugin.class, jar).isEmpty();
  }

  public static Optional<String> getPluginVersion(Jar jar) {
    var plugins = PluginLoader.load(BuildCLIPlugin.class, jar);
    plugins.addAll(PluginLoader.load(BuildCLICommandPlugin.class, jar));

    var plugin = plugins.getFirst();

    if (plugin == null) {
      return Optional.empty();
    } else {
      return Optional.ofNullable(plugin.version());
    }
  }

  public static Optional<String> getPluginDescription(Jar jar) {
    var plugins = PluginLoader.load(BuildCLIPlugin.class, jar);
    plugins.addAll(PluginLoader.load(BuildCLICommandPlugin.class, jar));

    var plugin = plugins.getFirst();

    if (plugin == null) {
      return Optional.empty();
    } else {
      return Optional.ofNullable(plugin.description());
    }
  }

  public static Optional<String> getPluginName(Jar jar) {
    var plugins = PluginLoader.load(BuildCLIPlugin.class, jar);
    plugins.addAll(PluginLoader.load(BuildCLICommandPlugin.class, jar));

     var plugin = plugins.getFirst();

     if (plugin == null) {
       return Optional.empty();
     } else {
       return Optional.ofNullable(plugin.name());
     }
  }
}
