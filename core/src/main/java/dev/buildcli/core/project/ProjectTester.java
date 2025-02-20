package dev.buildcli.core.core;

public class ProjectTester extends ProjectExecutor {

	@Override
	protected void addMvnCommand() {
		this.command.add("test");
	}

	@Override
	protected String getErrorMessage() {
		return "Failed to run tests";
	}

}
