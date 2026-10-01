package dev.buildcli.infrastructure.tui;

import dev.buildcli.application.ChatSession;
import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Capability;
import dev.buildcli.infrastructure.AgentFileEditor;
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
            new ChatCommands.Command("editagent", "<name>", "Change an agent: role, what it may do, how it works, where it writes", ""),
            new ChatCommands.Command("deleteagent", "<name>", "Delete an agent (asks to confirm)", ""),
            new ChatCommands.Command("samples", "", "Add the sample agents: wheslley, breno, matheus and dumildes", ""),
            new ChatCommands.Command("cancel", "", "Stop what we were doing", ""),
            new ChatCommands.Command("back", "", "Go back one question", ""));

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]*");

    private enum Step { IDLE, NAME, ROLE, CAPS, HOW, CONFIRM, DELETE, EDIT_PICK, EDIT_VALUE, EDIT_CONFIRM }

    private final ChatSession session;
    private final SettingsServices services;
    private Step step = Step.IDLE;
    private String name = "";
    private String role = "";
    private List<String> caps = List.of(Capability.FILESYSTEM_READ, Capability.SEARCH);
    private String how = "";
    /** While editing: which setting is being asked (1 to 5), and the change waiting for your yes. */
    private int editing;
    private AgentFileEditor.Edit change;

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
                + "/newagent creates an agent, step by step\n/agents lists them\n/editagent <name> changes one\n/deleteagent <name> deletes one\n/samples adds the sample agents\n"
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
            say("I only follow commands: /newagent, /agents, /editagent, /deleteagent, /samples, /help. Type / to see them.");
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
            case "editagent" -> startEdit(arg.startsWith("@") ? arg.substring(1) : arg);
            case "help" -> say("/newagent [name]: create an agent, step by step\n/agents: list your agents\n/editagent <name>: change an agent\n"
                    + "/deleteagent <name>: delete one\n"
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
            case EDIT_PICK -> pickEdit(t);
            case EDIT_VALUE -> acceptEdit(t);
            case EDIT_CONFIRM -> {
                if (yes(t)) {
                    applyEdit();
                } else if (no(t)) {
                    change = null;
                    editMenu("Okay, " + name + " is unchanged.");
                } else {
                    say("Reply yes to make the change, or no.");
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
            case EDIT_VALUE, EDIT_CONFIRM -> {
                change = null;
                editMenu("");
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

    // ---- /editagent ----

    private void startEdit(String who) {
        Agent a = session.contact(who);
        if (a == null) {
            say(who.isBlank() ? "Which agent? For example /editagent rita" : "There is no agent called " + who + ". /agents lists them.");
            return;
        }
        if (a.source() == null || a.source().isBlank() || !a.source().endsWith(".md")) {
            say(who + " is not defined by a Markdown file in your project or folder, so I cannot edit it. Change its file by hand.");
            return;
        }
        name = who;
        editMenu("");
    }

    private void editMenu(String lead) {
        step = Step.EDIT_PICK;
        Agent a = session.contact(name);
        if (a == null) {
            step = Step.IDLE;
            say("That agent is gone.");
            return;
        }
        var p = a.permissions();
        say((lead.isEmpty() ? "" : lead + "\n") + name + " now:\n"
                + "  role: " + a.role() + "\n  may: " + String.join(", ", a.capabilities().stream().sorted().toList()) + "\n"
                + "  writes in: " + (p.writeGlobs().isEmpty() ? "nowhere" : code(p.writeGlobs())) + "\n"
                + "  runs without asking: " + (p.commandAllow().isEmpty() ? "nothing" : p.commandAllow().stream().map(c -> String.join(" ", c))
                        .collect(java.util.stream.Collectors.joining("; "))) + "\n"
                + "What do you want to change?\n 1. role\n 2. what it may do\n 3. how it works\n 4. folders it may write in\n"
                + " 5. commands it may run without asking\nReply with a number, or done.");
    }

    private void pickEdit(String t) {
        String l = t.strip().toLowerCase(Locale.ROOT);
        if (l.equals("done") || l.equals("0")) {
            step = Step.IDLE;
            say("Okay, " + name + " is as you left it.");
            return;
        }
        editing = l.matches("[1-5]") ? Integer.parseInt(l) : 0;
        if (editing == 0) {
            say("Reply with a number from 1 to 5, or done.");
            return;
        }
        step = Step.EDIT_VALUE;
        switch (editing) {
            case 1 -> say("The new role for " + name + "? For example reviewer, tester, writer.");
            case 2 -> {
                StringBuilder sb = new StringBuilder("What may " + name + " do? Reply with numbers, like 1,3, or default (read files and search).\n");
                int i = 1;
                for (String c : Capability.KNOWN.stream().sorted().toList()) {
                    sb.append(' ').append(i++).append(". ").append(c).append(": ").append(CapabilityInfo.describe(c)).append('\n');
                }
                say(sb.toString().stripTrailing());
            }
            case 3 -> say("In one sentence or a short paragraph, how should " + name + " work? This replaces what its file says now.");
            case 4 -> say("Which folders may " + name + " write in? Patterns inside the project, separated by commas, like `src/**`, `docs/**`. "
                    + "Say none to take writing away. Every write is still shown to you as a diff and needs your yes, and it needs the capability "
                    + "filesystem.write (option 2).");
            default -> say("Which commands may " + name + " run WITHOUT asking you first? One per command, separated by ;, like mvn -q test; mvn -q verify. "
                    + "Anything else still asks. Say none to take this away. It needs the capability command.execute (option 2).");
        }
    }

    private void acceptEdit(String t) {
        try {
            switch (editing) {
                case 1 -> change = AgentFileEditor.Edit.role(t.strip());
                case 2 -> {
                    List<String> chosen = parseCaps(t);
                    if (chosen == null) {
                        say("I did not understand that. Reply with numbers from the list, like 1,3, or default.");
                        return;
                    }
                    change = AgentFileEditor.Edit.capabilities(chosen);
                }
                case 3 -> change = AgentFileEditor.Edit.instructions(t.strip());
                case 4 -> {
                    List<String> globs = new ArrayList<>();
                    if (!t.strip().equalsIgnoreCase("none")) {
                        for (String g : t.split("[,\\s]+")) {
                            if (!g.isBlank()) {
                                globs.add(AgentFileEditor.checkGlob(g));
                            }
                        }
                    }
                    change = AgentFileEditor.Edit.writeGlobs(globs);
                }
                default -> {
                    List<List<String>> commands = new ArrayList<>();
                    if (!t.strip().equalsIgnoreCase("none")) {
                        for (String one : t.split(";")) {
                            if (!one.isBlank()) {
                                commands.add(List.of(one.strip().split("\\s+")));
                            }
                        }
                    }
                    change = AgentFileEditor.Edit.commands(commands);
                }
            }
        } catch (IllegalArgumentException e) {
            say(e.getMessage() + ". Try again, or /back.");
            return;
        }
        step = Step.EDIT_CONFIRM;
        say("I will change " + name + ": " + describe(change) + "\nReply yes to do it, or no.");
    }

    /** Patterns like src/** would be read as Markdown bold, so they are shown as code. */
    private static String code(List<String> globs) {
        return globs.stream().map(g -> "`" + g + "`").collect(java.util.stream.Collectors.joining(", "));
    }

    private static String describe(AgentFileEditor.Edit e) {
        if (e.role() != null) {
            return "role -> " + e.role() + ".";
        }
        if (e.capabilities() != null) {
            return "it may " + String.join(", ", e.capabilities()) + ".";
        }
        if (e.instructions() != null) {
            return "how it works -> " + e.instructions() + "\n(the text in its file is replaced; comments in its header are lost.)";
        }
        if (e.writeGlobs() != null) {
            return e.writeGlobs().isEmpty() ? "it may write nowhere." : "it may write in " + code(e.writeGlobs())
                    + ". Each write still needs your yes.";
        }
        return e.commands().isEmpty() ? "it runs nothing without asking."
                : "it runs these WITHOUT asking you: " + e.commands().stream().map(c -> String.join(" ", c)).collect(java.util.stream.Collectors.joining("; ")) + ".";
    }

    private void applyEdit() {
        try {
            services.updateAgent(name, change);
            change = null;
            editMenu("Done.");
        } catch (Exception e) {
            say("I could not change it: " + e.getMessage());
            editMenu("");
        }
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
