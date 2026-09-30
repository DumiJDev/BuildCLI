package dev.buildcli.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.buildcli.domain.Chat;
import dev.buildcli.ports.ChatStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code chats.yaml} in the project's state directory. A broken file means no saved groups, never a crash. */
public final class FileChatStore implements ChatStore {
    public static final String FILE_NAME = "chats.yaml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    /** How the team's own group (whose id is empty) is written in the file. */
    private static final String TEAM_KEY = "~team";
    private final Path file;

    public FileChatStore(Path projectStateDir) {
        this.file = projectStateDir.resolve(FILE_NAME);
    }

    @Override
    public List<Chat> load() {
        List<Chat> out = new ArrayList<>();
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 256 * 1024) {
                return out;
            }
            JsonNode root = YAML.readTree(file.toFile());
            for (JsonNode g : root.path("groups")) {
                List<String> members = new ArrayList<>();
                g.path("members").forEach(m -> members.add(m.asText()));
                List<String> admins = new ArrayList<>();
                g.path("admins").forEach(m -> admins.add(m.asText()));
                String raw = g.path("id").asText("");
                String id = raw.equals(TEAM_KEY) ? "" : raw;
                if (!raw.isBlank()) {
                    out.add(new Chat(id, g.path("name").asText(id), true, members, admins));
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return out;
    }

    @Override
    public void save(List<Chat> groups) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Chat c : groups) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id().isEmpty() ? TEAM_KEY : c.id());
            m.put("name", c.name());
            m.put("members", c.members());
            m.put("admins", c.admins());
            list.add(m);
        }
        try {
            Files.createDirectories(file.getParent());
            YAML.writeValue(file.toFile(), Map.of("schema", 1, "groups", list));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot save " + file + ": " + e.getMessage(), e);
        }
    }
}
