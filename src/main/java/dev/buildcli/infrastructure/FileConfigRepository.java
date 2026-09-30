package dev.buildcli.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Capability;
import dev.buildcli.domain.Limits;
import dev.buildcli.domain.ModelRef;
import dev.buildcli.domain.ModelRouting;
import dev.buildcli.domain.Origin;
import dev.buildcli.domain.Permissions;
import dev.buildcli.domain.Team;
import dev.buildcli.ports.ConfigException;
import dev.buildcli.ports.ConfigRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads agents ({@code agents/*.md|yaml}) and teams ({@code teams/*.yaml}) from the global directory and from the
 * project's {@code .buildcli/}; project definitions override global ones of the same name. Definitions are validated
 * strictly (unknown keys are errors, since a typo in a permission must never silently widen or drop it) and every
 * problem is reported at once. {@code AGENTS.md} in the project root is read as context only.
 *
 * <p>File format, schema 1: see docs/reference/agents-and-teams.md.
 */
public final class FileConfigRepository implements ConfigRepository {
    public static final int SCHEMA = 1;
    public static final int MAX_CONTEXT_CHARS = 8000;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h)");
    private static final Set<String> PROVIDERS = Set.of("ollama", "openai");
    private static final Set<String> AGENT_KEYS = Set.of("schema", "name", "role", "description", "instructions",
            "capabilities", "permissions", "memory", "personality");
    private static final Set<String> TEAM_KEYS = Set.of("schema", "name", "description", "lead", "agents", "runtime", "limits");
    private static final Set<String> LIMIT_KEYS = Set.of("max_retries", "max_steps", "max_depth", "max_tokens_per_task",
            "max_handoffs_per_attempt");

    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final String context;
    private final List<String> problems = new ArrayList<>();

    /**
     * @param projectDir the project root (holds {@code .buildcli/} and {@code AGENTS.md})
     * @param globalDir  the user-level directory, normally {@code ~/.buildcli}; may not exist
     * @throws ConfigException listing every invalid file
     */
    public FileConfigRepository(Path projectDir, Path globalDir) {
        Path project = projectDir.toAbsolutePath().normalize();
        loadAgents(globalDir.resolve("agents"), Origin.GLOBAL);
        loadAgents(project.resolve(".buildcli").resolve("agents"), Origin.PROJECT);
        List<RawTeam> raw = new ArrayList<>();
        loadTeams(globalDir.resolve("teams"), raw);
        loadTeams(project.resolve(".buildcli").resolve("teams"), raw);
        for (RawTeam r : raw) {
            buildTeam(r);
        }
        context = readContext(project.resolve("AGENTS.md"));
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
    }

    @Override
    public List<Agent> agents() {
        return List.copyOf(agents.values());
    }

    @Override
    public Optional<Agent> agent(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    @Override
    public List<Team> teams() {
        return List.copyOf(teams.values());
    }

    @Override
    public Optional<Team> team(String name) {
        return Optional.ofNullable(teams.get(name));
    }

    @Override
    public String projectContext() {
        return context;
    }

    // ---- agents ----

    private void loadAgents(Path dir, Origin origin) {
        Map<String, Path> seen = new LinkedHashMap<>();
        for (Path file : files(dir, ".md", ".yaml", ".yml")) {
            Agent a = parseAgent(file, origin);
            if (a == null) {
                continue;
            }
            Path clash = seen.put(a.name(), file);
            if (clash != null) {
                problem(file, "agent '" + a.name() + "' is also defined in " + clash.getFileName());
                continue;
            }
            agents.put(a.name(), a); // a project definition replaces a global one of the same name
        }
    }

    private Agent parseAgent(Path file, Origin origin) {
        String text = read(file);
        if (text == null) {
            return null;
        }
        String body = "";
        String yaml = text;
        if (file.getFileName().toString().endsWith(".md")) {
            Matcher m = Pattern.compile("\\A---\\R(.*?)\\R---[ \\t]*(?:\\R(.*))?\\z", Pattern.DOTALL).matcher(text);
            if (!m.matches()) {
                problem(file, "a Markdown agent must start with a YAML front matter block delimited by '---' lines");
                return null;
            }
            yaml = m.group(1);
            body = m.group(2) == null ? "" : m.group(2).strip();
        }
        JsonNode n = parseYaml(file, yaml);
        if (n == null) {
            return null;
        }
        int before = problems.size();
        checkSchema(file, n);
        checkKeys(file, n, AGENT_KEYS, "agent");
        String name = requiredText(file, n, "name");
        String role = requiredText(file, n, "role");
        if (name != null && !NAME.matcher(name).matches()) {
            problem(file, "name '" + name + "' must match [a-z][a-z0-9_-]*");
        }
        String instructions = optionalText(file, n, "instructions");
        if (!body.isEmpty()) {
            instructions = instructions.isEmpty() ? body : instructions.strip() + "\n\n" + body;
        }
        Set<String> capabilities = capabilities(file, n.get("capabilities"));
        Permissions permissions = permissions(file, n.get("permissions"));
        if (problems.size() > before) {
            return null;
        }
        return new Agent(name, role, instructions, capabilities, permissions, origin, file.toString());
    }

    private Set<String> capabilities(Path file, JsonNode node) {
        Set<String> out = new TreeSet<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            problem(file, "'capabilities' must be a list");
            return out;
        }
        for (JsonNode c : node) {
            String cap = c.isTextual() ? c.asText() : null;
            if (cap == null || !Capability.KNOWN.contains(cap)) {
                problem(file, "unknown capability " + c + "; known capabilities: " + new TreeSet<>(Capability.KNOWN));
            } else {
                out.add(cap);
            }
        }
        return out;
    }

    private Permissions permissions(Path file, JsonNode node) {
        List<String> read = Permissions.READ_EVERYTHING;
        List<String> write = List.of();
        List<List<String>> allow = List.of();
        Duration timeout = Duration.ofSeconds(30);
        if (node == null || node.isNull()) {
            return new Permissions(read, write, allow, timeout);
        }
        if (!node.isObject()) {
            problem(file, "'permissions' must be a mapping");
            return null;
        }
        for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
            String k = it.next();
            if (k.equals("network")) {
                problem(file, "permissions.network is not supported in 1.0: it cannot be enforced without the command sandbox"
                        + " planned for 1.2, and an unenforced permission would be misleading");
            } else if (!k.equals("filesystem") && !k.equals("command")) {
                problem(file, "unknown key permissions." + k + " (expected: filesystem, command)");
            }
        }
        JsonNode fs = node.get("filesystem");
        if (fs != null && fs.isObject()) {
            unknownKeys(file, fs, Set.of("read", "write"), "permissions.filesystem");
            if (fs.has("read")) {
                read = stringList(file, fs.get("read"), "permissions.filesystem.read");
            }
            if (fs.has("write")) {
                write = stringList(file, fs.get("write"), "permissions.filesystem.write");
            }
        } else if (fs != null) {
            problem(file, "'permissions.filesystem' must be a mapping");
        }
        JsonNode cmd = node.get("command");
        if (cmd != null && cmd.isObject()) {
            unknownKeys(file, cmd, Set.of("allow", "timeout"), "permissions.command");
            allow = argvList(file, cmd.get("allow"));
            if (cmd.has("timeout")) {
                timeout = duration(file, cmd.get("timeout"));
            }
        } else if (cmd != null) {
            problem(file, "'permissions.command' must be a mapping");
        }
        return new Permissions(read, write, allow, timeout);
    }

    private List<List<String>> argvList(Path file, JsonNode node) {
        List<List<String>> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            problem(file, "'permissions.command.allow' must be a list of argv arrays, e.g. [[\"mvn\", \"test\"]]");
            return out;
        }
        for (JsonNode entry : node) {
            if (!entry.isArray() || entry.isEmpty()) {
                problem(file, "command allow entry " + entry + " must be a non-empty array of strings (argv), not a shell string");
                continue;
            }
            List<String> argv = new ArrayList<>();
            for (JsonNode part : entry) {
                if (!part.isTextual()) {
                    problem(file, "command allow entry " + entry + " must contain only strings");
                    break;
                }
                argv.add(part.asText());
            }
            out.add(List.copyOf(argv));
        }
        return out;
    }

    private Duration duration(Path file, JsonNode node) {
        Matcher m = DURATION.matcher(node.asText());
        if (!m.matches()) {
            problem(file, "invalid timeout '" + node.asText() + "'; use e.g. 500ms, 30s, 10m or 1h");
            return Duration.ofSeconds(30);
        }
        long v = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(v);
            case "s" -> Duration.ofSeconds(v);
            case "m" -> Duration.ofMinutes(v);
            default -> Duration.ofHours(v);
        };
    }

    // ---- teams ----

    private record RawTeam(Path file, JsonNode node) {}

    private void loadTeams(Path dir, List<RawTeam> out) {
        for (Path file : files(dir, ".yaml", ".yml")) {
            String text = read(file);
            JsonNode n = text == null ? null : parseYaml(file, text);
            if (n != null) {
                out.add(new RawTeam(file, n));
            }
        }
    }

    private void buildTeam(RawTeam raw) {
        Path file = raw.file();
        JsonNode n = raw.node();
        int before = problems.size();
        checkSchema(file, n);
        checkKeys(file, n, TEAM_KEYS, "team");
        String name = requiredText(file, n, "name");
        if (name != null && !NAME.matcher(name).matches()) {
            problem(file, "name '" + name + "' must match [a-z][a-z0-9_-]*");
        }
        List<Agent> members = new ArrayList<>();
        JsonNode list = n.get("agents");
        if (list == null || !list.isArray() || list.isEmpty()) {
            problem(file, "'agents' must be a non-empty list of agent names");
        } else {
            for (JsonNode a : list) {
                Agent agent = agents.get(a.asText());
                if (agent == null) {
                    problem(file, "agent '" + a.asText() + "' is not defined; defined agents: " + new TreeSet<>(agents.keySet()));
                } else if (members.contains(agent)) {
                    problem(file, "agent '" + a.asText() + "' is listed twice");
                } else {
                    members.add(agent);
                }
            }
        }
        String lead = requiredText(file, n, "lead");
        if (lead != null && members.stream().noneMatch(m -> m.name().equals(lead))) {
            problem(file, "lead '" + lead + "' must be one of the team's agents");
        }
        ModelRouting routing = routing(file, n.get("runtime"), members);
        Limits limits = limits(file, n.get("limits"));
        if (problems.size() > before) {
            return;
        }
        teams.put(name, new Team(name, lead, List.copyOf(members), limits, routing));
    }

    private ModelRouting routing(Path file, JsonNode node, List<Agent> members) {
        if (node == null || node.isNull()) {
            return ModelRouting.unspecified();
        }
        if (!node.isObject()) {
            problem(file, "'runtime' must be a mapping of 'default' and agent names to {provider, model}");
            return ModelRouting.unspecified();
        }
        ModelRef def = null;
        Map<String, ModelRef> overrides = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            ModelRef ref = modelRef(file, e.getKey(), e.getValue());
            if (e.getKey().equals("default")) {
                def = ref;
            } else if (members.stream().noneMatch(m -> m.name().equals(e.getKey()))) {
                problem(file, "runtime." + e.getKey() + " refers to an agent that is not in the team");
            } else if (ref != null) {
                overrides.put(e.getKey(), ref);
            }
        }
        return new ModelRouting(def, overrides);
    }

    private ModelRef modelRef(Path file, String key, JsonNode node) {
        String provider = node.path("provider").asText("");
        String model = node.path("model").asText("");
        if (!PROVIDERS.contains(provider) || model.isBlank()) {
            problem(file, "runtime." + key + " needs provider (one of " + new TreeSet<>(PROVIDERS) + ") and a model");
            return null;
        }
        return new ModelRef(provider, model);
    }

    private Limits limits(Path file, JsonNode node) {
        Limits d = Limits.defaults();
        if (node == null || node.isNull()) {
            return d;
        }
        if (!node.isObject()) {
            problem(file, "'limits' must be a mapping");
            return d;
        }
        unknownKeys(file, node, LIMIT_KEYS, "limits");
        return new Limits(
                bounded(file, node, "max_retries", d.maxRetries(), 0, 10),
                bounded(file, node, "max_steps", d.maxSteps(), 1, 100),
                bounded(file, node, "max_depth", d.maxDepth(), 1, 10),
                bounded(file, node, "max_tokens_per_task", d.maxTokensPerTask(), 1000, 10_000_000),
                bounded(file, node, "max_handoffs_per_attempt", d.maxHandoffsPerAttempt(), 1, 20));
    }

    private int bounded(Path file, JsonNode node, String key, int fallback, int min, int max) {
        if (!node.has(key)) {
            return fallback;
        }
        JsonNode v = node.get(key);
        if (!v.isInt() || v.asInt() < min || v.asInt() > max) {
            problem(file, "limits." + key + " must be an integer between " + min + " and " + max);
            return fallback;
        }
        return v.asInt();
    }

    // ---- shared helpers ----

    private String readContext(Path agentsMd) {
        if (!Files.isRegularFile(agentsMd)) {
            return "";
        }
        String text = read(agentsMd);
        if (text == null) {
            return "";
        }
        text = text.strip();
        return text.length() <= MAX_CONTEXT_CHARS ? text
                : text.substring(0, MAX_CONTEXT_CHARS) + "\n[AGENTS.md truncated at " + MAX_CONTEXT_CHARS + " characters]";
    }

    private List<Path> files(Path dir, String... extensions) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String f = p.getFileName().toString();
                        return List.of(extensions).stream().anyMatch(f::endsWith);
                    })
                    .sorted().toList();
        } catch (IOException e) {
            problem(dir, "cannot list directory: " + e.getMessage());
            return List.of();
        }
    }

    private String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            problem(file, "cannot read file: " + e.getMessage());
            return null;
        }
    }

    private JsonNode parseYaml(Path file, String yaml) {
        try {
            JsonNode n = YAML.readTree(yaml);
            if (n == null || !n.isObject()) {
                problem(file, "expected a YAML mapping at the top level");
                return null;
            }
            return n;
        } catch (IOException e) {
            problem(file, "invalid YAML: " + firstLine(e.getMessage()));
            return null;
        }
    }

    private void checkSchema(Path file, JsonNode n) {
        JsonNode v = n.get("schema");
        if (v == null) {
            problem(file, "missing 'schema' (add 'schema: " + SCHEMA + "')");
        } else if (!v.isInt() || v.asInt() != SCHEMA) {
            problem(file, "unsupported schema " + v + "; this BuildCLI reads schema " + SCHEMA);
        }
    }

    private void checkKeys(Path file, JsonNode n, Set<String> allowed, String what) {
        unknownKeys(file, n, allowed, what);
    }

    private void unknownKeys(Path file, JsonNode n, Set<String> allowed, String where) {
        for (Iterator<String> it = n.fieldNames(); it.hasNext();) {
            String k = it.next();
            if (!allowed.contains(k)) {
                problem(file, "unknown key '" + k + "' in " + where + " (allowed: " + new TreeSet<>(allowed) + ")");
            }
        }
    }

    private String requiredText(Path file, JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            problem(file, "'" + key + "' is required and must be a non-empty string");
            return null;
        }
        return v.asText().strip();
    }

    private String optionalText(Path file, JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return "";
        }
        if (!v.isTextual()) {
            problem(file, "'" + key + "' must be a string");
            return "";
        }
        return v.asText();
    }

    private List<String> stringList(Path file, JsonNode node, String where) {
        List<String> out = new ArrayList<>();
        if (!node.isArray()) {
            problem(file, "'" + where + "' must be a list of glob strings");
            return out;
        }
        for (JsonNode v : node) {
            if (v.isTextual()) {
                out.add(v.asText());
            } else {
                problem(file, "'" + where + "' must contain only strings");
            }
        }
        return out;
    }

    private void problem(Path file, String message) {
        problems.add(file + ": " + message);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
