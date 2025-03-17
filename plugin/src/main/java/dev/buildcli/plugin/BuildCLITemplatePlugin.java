package dev.buildcli.plugin;

import dev.buildcli.plugin.enums.TemplateType;

public interface BuildCLITemplatePlugin extends BuildCLIPlugin {
  TemplateType type();
  void execute();
}
