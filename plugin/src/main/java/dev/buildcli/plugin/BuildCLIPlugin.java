package dev.buildcli.plugin;

import org.pf4j.ExtensionPoint;

public interface BuildCLIPlugin extends ExtensionPoint {
  String name();
  String description();
  String version();
}
