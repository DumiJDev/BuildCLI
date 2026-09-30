package dev.buildcli.ports;

public record ApprovalRequest(String agent, String kind, String summary, String detail) {}
