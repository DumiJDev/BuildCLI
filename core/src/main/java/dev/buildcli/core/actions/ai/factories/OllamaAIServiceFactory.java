package dev.buildcli.cli.actions.ai.factories;

import dev.buildcli.cli.actions.ai.params.OllamaAIServiceParams;
import dev.buildcli.cli.actions.ai.service.OllamaAIService;

public class OllamaAIServiceFactory implements AIServiceFactory<OllamaAIService, OllamaAIServiceParams>{
  @Override
  public OllamaAIService create(OllamaAIServiceParams params) {
    return OllamaAIService.builder()
        .url(params.url())
        .modelName(params.model().orElse("llama3.2"))
        .build();
  }
}
