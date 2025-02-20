package dev.buildcli.cli.actions.ai.factories;

import dev.buildcli.cli.actions.ai.AIService;
import dev.buildcli.cli.actions.ai.AIServiceParams;

public class GeneralAIServiceFactory implements AIServiceFactory {

  @SuppressWarnings({"unchecked"})
  @Override
  public AIService create(AIServiceParams params) {

    AIServiceFactory factory = switch (params.vendor().toLowerCase()) {
      case "ollama" -> new OllamaAIServiceFactory();
      default -> new JLamaAIServiceFactory();
    };

    return factory.create(params);
  }
}
