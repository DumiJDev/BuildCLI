package dev.buildcli.spike.ports;

public record ApprovalRequest(String agent, String kind, String summary, String detail) {}
