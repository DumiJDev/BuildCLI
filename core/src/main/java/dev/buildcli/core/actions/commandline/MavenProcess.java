package dev.buildcli.core.actions.commandline;

import dev.buildcli.core.constants.MavenConstants;

import java.io.File;
import java.util.Arrays;

public class MavenProcess extends AbstractCommandLineProcess {
  private MavenProcess(boolean printOutput) {
    super(MavenConstants.MAVEN_CMD, printOutput);
  }

  public static MavenProcess createProcessor(String... goals) {
    var processor = new MavenProcess(true);
    processor.commands.addAll(Arrays.asList(goals));
    return processor;
  }

  public static MavenProcess createPackageProcessor(File directory) {
    return createProcessor("clean", "package", "-f", directory.getAbsolutePath());
  }

  public static MavenProcess createCompileProcessor(File directory) {
    return createProcessor("clean", "compile", "-f", directory.getAbsolutePath());
  }

  public static MavenProcess createGetVersionProcessor() {
    var processor = new MavenProcess(false);

    processor.commands.add("-v");
    return processor;
  }

}
