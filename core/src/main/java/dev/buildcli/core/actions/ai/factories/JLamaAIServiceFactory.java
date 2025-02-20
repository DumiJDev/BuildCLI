package dev.buildcli.cli.actions.ai.factories;

import dev.buildcli.cli.actions.ai.params.JlamaAIServiceParams;
import dev.buildcli.cli.actions.ai.service.JLamaAIService;

public class JLamaAIServiceFactory implements AIServiceFactory<JLamaAIService, JlamaAIServiceParams> {

  @Override
  public JLamaAIService create(JlamaAIServiceParams params) {

    return JLamaAIService.builder().modelName(params.model().orElse("tjake/Qwen2.5-0.5B-Instruct-JQ4")).build();
  }
}
