package dev.buildcli.spike.application;

public final class RunAborted extends RuntimeException {
    public RunAborted(String message) {
        super(message);
    }
}
