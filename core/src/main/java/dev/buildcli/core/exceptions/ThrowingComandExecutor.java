package dev.buildcli.cli.exceptions;

import java.io.IOException;

@Deprecated( forRemoval = true)
@FunctionalInterface
public interface ThrowingComandExecutor {
	void exec() throws IOException;
}
