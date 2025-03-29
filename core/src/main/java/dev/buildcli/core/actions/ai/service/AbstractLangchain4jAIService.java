package dev.buildcli.core.actions.ai.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.buildcli.core.actions.ai.AIChat;
import dev.buildcli.core.actions.ai.AIService;
import dev.langchain4j.model.chat.request.ChatRequest;

public abstract class AbstractLangchain4jAIService implements AIService {
  private final ChatLanguageModel model;

  protected AbstractLangchain4jAIService() {
    this(null);
  }

  protected AbstractLangchain4jAIService(ChatLanguageModel model) {
    this.model = model;
  }

  @Override
  public String generate(AIChat chat) {
    var aiMessageResponse = model.chat(
        new SystemMessage(chat.getSystemMessage()),
        new UserMessage(chat.getUserMessage())
    );

    return aiMessageResponse.aiMessage().text();
  }
}
