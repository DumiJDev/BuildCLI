package dev.buildcli.ports;

import java.util.List;

/** Provider-neutral conversation messages. LangChain4j never leaks past the gateway adapter. */
public sealed interface LlmMessage {
    record System(String text) implements LlmMessage {}
    record User(String text) implements LlmMessage {}
    record Assistant(String text, List<ToolCall> toolCalls) implements LlmMessage {}
    record ToolResult(String callId, String toolName, String text) implements LlmMessage {}
}
