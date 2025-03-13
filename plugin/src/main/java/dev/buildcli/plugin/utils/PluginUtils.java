package dev.buildcli.plugin.utils;

import dev.buildcli.core.domain.jar.Jar;
import dev.buildcli.plugin.BuildCLIPlugin;

public final class PluginUtils {
  public static boolean isValid(Jar jar) {
    return !PluginLoader.load(BuildCLIPlugin.class, jar).isEmpty();
  }
}
