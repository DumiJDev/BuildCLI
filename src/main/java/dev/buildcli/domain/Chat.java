package dev.buildcli.domain;

import java.util.List;

/**
 * A conversation. A group has members and admins, like a WhatsApp group: a message nobody is mentioned in goes to an
 * admin, a mention goes to that member. A direct chat is with one agent, and its id is that agent's name.
 */
public record Chat(String id, String name, boolean group, List<String> members, List<String> admins) {

    public Chat {
        members = List.copyOf(members);
        admins = List.copyOf(admins);
    }

    public boolean has(String agent) {
        return members.contains(agent);
    }

    public boolean isAdmin(String agent) {
        return admins.contains(agent);
    }

    public Chat withMembers(List<String> m, List<String> a) {
        return new Chat(id, name, group, m, a);
    }
}
