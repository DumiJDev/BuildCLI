package dev.buildcli.infrastructure.tui;

import dev.buildcli.application.ChatSession;
import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Capability;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * AgentFather: the built-in contact you talk to in order to create and manage agents, like Telegram's BotFather. It is not
 * an agent and uses no model, so it works before any model is connected. It follows a short script of questions, shows what
 * it is about to do and does it only after you say yes; permissions come from the capabilities you choose, never from text.
 */
final class AgentFather {
    /** The commands it understands, for the menu of its chat. */
    static final List<ChatCommands.Command> COMMANDS = List.of(
            new ChatCommands.Command("newagent", "[name]", "Create an agent, step by step", ""),
            new ChatCommands.Command("agents", "", "List your agents with their model and what they may do", ""),
            new ChatCommands.Command("deleteagent", "<name>", "Delete an agent (asks to confirm)", ""),
            new ChatCommands.Command("samples", "", "Add the sample agents: wheslley, breno, matheus and dumildes", ""),
            new ChatCommands.Command("cancel", "", "Stop what we were doing", ""),
            new ChatCommands.Command("back", "", "Go back one question", ""));

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]*");

    private enum Step { IDLE, NAME, ROLE, CAPS, HOW, CONFIRM, DELETE }

    private final ChatSession session;
    private final SettingsServices services;
    private Step step = Step.IDLE;
    private String name = "";
    private String role = "";
    private List<String> caps = List.of(Capability.FILESYSTEM_READ, Capability.SEARCH);
    private String how = "";

    AgentFather(ChatSession session, SettingsServices services) {
        this.session = session;
        this.services = services;
    }

    private void say(String text) {
        session.post(ChatSession.FATHER, ChatSession.FATHER_NAME, text);
    }

    /** Says hello the first time the chat is opened. */
    void greet() {
        if (session.messages().stream().anyMatch(m -> m.thread().equals(ChatSession.FATHER))) {
            return;
        }
        say("Hi, I'm AgentFather. I create and manage your agents. I am not a model, so I work even before you have connected one.\n"
                + "/newagent creates an agent, step by step\n/agents lists them\n/deleteagent <name> deletes one\n/samples adds the sample agents\n"
                + "/connect picks a provider and a model\n/help shows everything.");
    }

    /** What you wrote in the chat with AgentFather. */
    void handle(String text) {
        String t = text.strip();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.equals("/cancel") || lower.equals("cancel")) {
            step = Step.IDLE;
            say("Okay, nothing was changed.");
            return;
        }
        if (lower.equals("/back") || lower.equals("back")) {
            back();
            return;
        }
        if (t.startsWith("/")) {
            command(t);
            return;
        }
        if (step == Step.IDLE) {
            say("I only follow commands: /newagent, /agents, /deleteagent, /samples, /help. Type / to see them.");
            return;
        }
        answer(t);
    }

    // ---- commands ----

    private void command(String text) {
        String[] parts = text.substring(1).split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].strip() : "";
        switch (cmd) {
            case "newagent" -> {
                name = "";
                role = "";
                caps = List.of(Capability.FILESYSTEM_READ, Capability.SEARCH);
                how = "";
                String wanted = arg.startsWith("@") ? arg.substring(1) : arg;
                if (wanted.isBlank()) {
                    askName();
                } else {
                    acceptName(wanted);
                }
            }
            case "agents" -> listAgents();
            case "deleteagent" -> {
                String who = arg.startsWith("@") ? arg.substring(1) : arg;
                if (session.contact(who) == null) {
                    say(who.isBlank() ? "Which agent? For example /deleteagent rita" : "There is no agent called " + who + ". /agents lists them.");
                    return;
                }
                name = who;
                step = Step.DELETE;
                say("Delete " + who + "? This removes its file, and it leaves its groups; what it already said stays in the chats.\nReply yes or no.");
            }
            case "samples" -> samples();
            case "help" -> say("/newagent [name]: create an agent, step by step\n/agents: list your agents\n/deleteagent <name>: delete one\n"
                    + "/samples: add wheslley, breno, matheus and dumildes\n/cancel: stop what we were doing · /back: go back one question\n"
                    + "Other commands work here too: /connect, /model, /newgroup, /add, /settings.");
            default -> say("I don't know /" + cmd + ". Type / to see what I understand.");
        }
    }

    private void listAgents() {
        List<Agent> all = session.contacts();
        if (all.isEmpty()) {
            say("You have no agents yet. /newagent creates one, /samples adds four to start with.");
            return;
        }
        StringBuilder sb = new StringBuilder(all.size() + (all.size() == 1 ? " agent" : " agents") + ":\n");
        for (Agent a : all) {
            String model = services.settings().modelFor(a.name());
            if (model == null) {
                model = services.settings().defaultModel();
            }
            sb.append("• ").append(a.name()).append(" · ").append(a.role()).append(" · ").append(model == null ? "no model yet (/connect)" : model)
                    .append("\n    may: ").append(String.join(", ", a.capabilities().stream().sorted().toList())).append('\n');
        }
        say(sb.toString().stripTrailing());
    }

    private void samples() {
        try {
            List<String> added = services.createSampleAgents();
            say("Added " + String.join(", ", added) + " and a group for them. Open the group and say something to start.");
        } catch (Exception e) {
            say("I could not add them: " + e.getMessage());
        }
    }

    // ---- the questions of /newagent ----

    private void askName() {
        step = Step.NAME;
        say("What should the new agent be called? Lowercase letters, digits, - and _ (for example rita).");
    }

    private void acceptName(String candidate) {
        String n = candidate.strip().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(n).matches()) {
            say("A name is lowercase letters, digits, - and _, starting with a letter. Try another.");
            step = Step.NAME;
            return;
        }
        if (session.contact(n) != null) {
            say("There is already an agent called " + n + ". Pick another name.");
            step = Step.NAME;
            return;
        }
        name = n;
        askRole();
    }

    private void askRole() {
        step = Step.ROLE;
        say("What is " + name + "'s role? For example reviewer, tester, writer. Say skip for developer.");
    }

    private void askCaps() {
        step = Step.CAPS;
        StringBuilder sb = new StringBuilder("What may " + name + " do? Reply with numbers, like 1,3, or default (read files and search).\n");
        int i = 1;
        for (String c : Capability.KNOWN.stream().sorted().toList()) {
            sb.append(' ').append(i++).append(". ").append(c).append(": ").append(CapabilityInfo.describe(c)).append('\n');
        }
        say(sb.toString().stripTrailing());
    }

    private void askHow() {
        step = Step.HOW;
        say("In one sentence, how should " + name + " work? Or say skip.");
    }

    private void askConfirm() {
        step = Step.CONFIRM;
        say("I will create " + name + " (" + role + ") for this project. It may: " + String.join(", ", caps) + ".\n"
                + (how.isBlank() ? "" : "It will work like this: " + how + "\n")
                + "It starts able to read the whole project, and with no folder to write in and no command allowed; to give it more, edit its file"
                + " afterwards. Every write and command still asks you first.\nReply yes to create it, back to change something, or cancel.");
    }

    private void answer(String t) {
        switch (step) {
            case NAME -> acceptName(t);
            case ROLE -> {
                role = t.equalsIgnoreCase("skip") || t.isBlank() ? "developer" : t.strip();
                askCaps();
            }
            case CAPS -> {
                List<String> chosen = parseCaps(t);
                if (chosen == null) {
                    say("I did not understand that. Reply with numbers from the list, like 1,3, or default.");
                    return;
                }
                caps = chosen;
                askHow();
            }
            case HOW -> {
                how = t.equalsIgnoreCase("skip") ? "" : t.strip();
                askConfirm();
            }
            case CONFIRM -> {
                if (yes(t)) {
                    create();
                } else if (no(t)) {
                    step = Step.IDLE;
                    say("Okay, nothing was changed.");
                } else {
                    say("Reply yes to create it, back to change something, or cancel.");
                }
            }
            case DELETE -> {
                if (yes(t)) {
                    delete();
                } else {
                    step = Step.IDLE;
                    say("Okay, " + name + " stays.");
                }
            }
            default -> { }
        }
    }

    private void back() {
        switch (step) {
            case ROLE -> askName();
            case CAPS -> askRole();
            case HOW -> askCaps();
            case CONFIRM -> askHow();
            case NAME -> {
                step = Step.IDLE;
                say("Okay, nothing was changed.");
            }
            default -> say("There is nothing to go back to.");
        }
    }

    /** "default", or numbers and capability names separated by commas or spaces; null if something is not understood. */
    private static List<String> parseCaps(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        if (t.equals("default") || t.isBlank()) {
            return List.of(Capability.FILESYSTEM_READ, Capability.SEARCH);
        }
        List<String> known = Capability.KNOWN.stream().sorted().toList();
        List<String> out = new ArrayList<>();
        for (String token : t.split("[,\\s]+")) {
            String pick = null;
            if (token.matches("\\d+")) {
                int n = Integer.parseInt(token);
                pick = n >= 1 && n <= known.size() ? known.get(n - 1) : null;
            } else if (known.contains(token)) {
                pick = token;
            }
            if (pick == null) {
                return null;
            }
            if (!out.contains(pick)) {
                out.add(pick);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static boolean yes(String t) {
        String l = t.strip().toLowerCase(Locale.ROOT);
        return l.equals("yes") || l.equals("y") || l.equals("sim") || l.equals("ok");
    }

    private static boolean no(String t) {
        String l = t.strip().toLowerCase(Locale.ROOT);
        return l.equals("no") || l.equals("n") || l.equals("não") || l.equals("nao");
    }

    private void create() {
        try {
            String file = services.createAgent(name, role, how, caps, false);
            step = Step.IDLE;
            session.openDirect(name);
            say("Created " + name + ". Its file is " + file + ".\nSay hello in its chat, or put it in a group with /newgroup <name> @" + name + ".");
        } catch (Exception e) {
            say("I could not create it: " + e.getMessage());
            askName();
        }
    }

    private void delete() {
        try {
            services.deleteAgent(name);
            say("Deleted " + name + ".");
        } catch (Exception e) {
            say("I could not delete it: " + e.getMessage());
        }
        step = Step.IDLE;
    }
}
