package dev.buildcli.cli.actions.commandline;

import dev.buildcli.cli.constants.GradleConstants;

import java.util.Arrays;

public class GradleProcess extends AbstractCommandLineProcess {
  private GradleProcess() {
    super(GradleConstants.GRADLE_CMD);
  }

  public static GradleProcess createProcessor(String... tasks) {
    var processor = new GradleProcess();
    processor.commands.addAll(Arrays.asList(tasks));
    return processor;
  }

  public static GradleProcess createPackageProcessor() {
    return createProcessor("clean", "build");
  }

  public static GradleProcess createCompileProcessor() {
    return createProcessor("clean", "classes");
  }

  public static GradleProcess createGetVersionProcess() {
    return createProcessor("-version");
  }
}
