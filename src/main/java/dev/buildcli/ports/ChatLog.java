package dev.buildcli.ports;

import dev.buildcli.domain.ChatEntry;
import java.util.List;

/** The conversation history of one project, so chats survive a restart. */
public interface ChatLog {
    /** The newest {@code limit} messages, oldest first. */
    List<ChatEntry> recent(int limit);

    /** Inserts the message, or replaces the one with the same id (its state or text changed). */
    void save(ChatEntry entry);

    /** Deletes every message of one chat. */
    void clear(String thread);

    ChatLog NONE = new ChatLog() {
        @Override
        public List<ChatEntry> recent(int limit) {
            return List.of();
        }

        @Override
        public void save(ChatEntry entry) {
            // nothing kept
        }

        @Override
        public void clear(String thread) {
            // nothing kept
        }
    };
}
