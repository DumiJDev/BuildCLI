package dev.buildcli.ports;

import dev.buildcli.domain.Chat;
import java.util.List;

/** The groups the user made, per project, kept outside the project tree. */
public interface ChatStore {
    List<Chat> load();

    void save(List<Chat> groups);

    ChatStore NONE = new ChatStore() {
        @Override
        public List<Chat> load() {
            return List.of();
        }

        @Override
        public void save(List<Chat> groups) {
            // nothing to keep
        }
    };
}
