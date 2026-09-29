package dev.buildcli.spike.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.buildcli.spike.domain.Agent;
import dev.buildcli.spike.ports.LlmGateway;
import dev.buildcli.spike.ports.LlmMessage;
import dev.buildcli.spike.ports.LlmReply;
import dev.buildcli.spike.ports.ToolCall;
import dev.buildcli.spike.ports.ToolSpec;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaChatRequestParameters;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The only class that knows LangChain4j. Everything above it uses the neutral port types. */
public final class OllamaGateway implements LlmGateway {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatModel model;

    public OllamaGateway(String baseUrl, String modelName, int threads) {
        this.model = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .defaultRequestParameters(OllamaChatRequestParameters.builder()
                        .modelName(modelName)
                        .temperature(0.0)
                        .maxOutputTokens(512)
                        .numThread(threads)
                        .build())
                .timeout(Duration.ofMinutes(2))
                .build();
    }

    @Override
    public LlmReply chat(Agent agent, List<LlmMessage> messages, List<ToolSpec> tools) {
        ChatRequest.Builder request = ChatRequest.builder().messages(messages.stream().map(OllamaGateway::toLc).toList());
        if (!tools.isEmpty()) {
            request.toolSpecifications(tools.stream().map(OllamaGateway::toLc).toList());
        }
        ChatResponse response = model.chat(request.build());
        AiMessage ai = response.aiMessage();
        List<ToolCall> calls = new ArrayList<>();
        if (ai.hasToolExecutionRequests()) {
            for (ToolExecutionRequest r : ai.toolExecutionRequests()) {
                calls.add(new ToolCall(r.id() == null ? UUID.randomUUID().toString() : r.id(), r.name(), parseArgs(r.arguments())));
            }
        }
        var usage = response.tokenUsage();
        return new LlmReply(ai.text(), calls,
                usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount());
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("model returned malformed tool arguments: " + json);
        }
    }

    private static ChatMessage toLc(LlmMessage m) {
        return switch (m) {
            case LlmMessage.System s -> SystemMessage.from(s.text());
            case LlmMessage.User u -> UserMessage.from(u.text());
            case LlmMessage.Assistant a -> a.toolCalls().isEmpty()
                    ? AiMessage.from(a.text() == null ? "" : a.text())
                    : AiMessage.from(a.text() == null ? "" : a.text(), a.toolCalls().stream()
                            .map(c -> ToolExecutionRequest.builder().id(c.id()).name(c.name()).arguments(toJson(c.args())).build())
                            .toList());
            case LlmMessage.ToolResult r -> ToolExecutionResultMessage.from(r.callId(), r.toolName(), r.text());
        };
    }

    private static ToolSpecification toLc(ToolSpec spec) {
        JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
        for (ToolSpec.Param p : spec.params()) {
            if (p.array()) {
                schema.addProperty(p.name(), JsonArraySchema.builder()
                        .items(JsonStringSchema.builder().build()).description(p.description()).build());
            } else {
                schema.addStringProperty(p.name(), p.description());
            }
        }
        schema.required(spec.params().stream().filter(ToolSpec.Param::required).map(ToolSpec.Param::name).toList());
        return ToolSpecification.builder().name(spec.name()).description(spec.description()).parameters(schema.build()).build();
    }

    private static String toJson(Map<String, Object> args) {
        try {
            return JSON.writeValueAsString(args);
        } catch (Exception e) {
            return "{}";
        }
    }
}
