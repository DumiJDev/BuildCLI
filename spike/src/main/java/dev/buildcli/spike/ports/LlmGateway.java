package dev.buildcli.spike.ports;

import dev.buildcli.spike.domain.Agent;
import java.util.List;

public interface LlmGateway {
    /** Implementations throw a RuntimeException on provider failure. */
    LlmReply chat(Agent agent, List<LlmMessage> messages, List<ToolSpec> tools);
}
