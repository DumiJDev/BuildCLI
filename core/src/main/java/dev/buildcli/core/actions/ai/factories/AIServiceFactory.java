package dev.buildcli.cli.actions.ai.factories;

import dev.buildcli.cli.actions.ai.AIService;
import dev.buildcli.cli.actions.ai.AIServiceParams;

public interface AIServiceFactory<S extends AIService,T extends AIServiceParams> {
  S create(T params);
}
