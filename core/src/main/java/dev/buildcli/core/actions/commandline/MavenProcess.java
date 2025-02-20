package dev.buildcli.core.actions.commandline;

import dev.buildcli.core.constants.MavenConstants;

import java.util.Arrays;

public class MavenProcess extends AbstractCommandLineProcess {
  private MavenProcess() {
    super(MavenConstants.MAVEN_CMD);
  }

  public static MavenProcess createProcessor(String... goals) {
    var processor = new MavenProcess();
    processor.commands.addAll(Arrays.asList(goals));
    return processor;
  }

  public static MavenProcess createPackageProcessor() {
    return createProcessor("clean", "package");
  }

  public static MavenProcess createCompileProcessor() {
    return createProcessor("clean", "compile");
  }

  public static MavenProcess createGetVersionProcessor() {
    return createProcessor("--version");
  }

}
