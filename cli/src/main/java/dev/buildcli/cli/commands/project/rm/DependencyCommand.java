package dev.buildcli.cli.commands.project.rm;

import dev.buildcli.core.constants.MavenConstants;
import dev.buildcli.core.domain.BuildCLICommand;
import dev.buildcli.core.log.SystemOutLogger;
import dev.buildcli.core.utils.tools.maven.PomReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

@Command(name = "dependency", aliases = {"d"}, description = "Removes one or more dependencies from the project's "
        + "pom.xml file.", mixinStandardHelpOptions = true)
public class DependencyCommand implements BuildCLICommand {
  private final Logger logger = Logger.getLogger(DependencyCommand.class.getName());
  @Parameters
  private String[] dependencies;

  @Override
  public void run() {
    try {
      var pom = PomReader.read(MavenConstants.FILE);
      var pomData = PomReader.readAsString(MavenConstants.FILE);
      Stream.of(dependencies).forEach(pom::rmDependency);

      try {
        String pomContent = pomData.replace(MavenConstants.DEPENDENCIES_PATTERN, pom.getDependencyFormatted());
        Files.write(Paths.get(MavenConstants.FILE), pomContent.getBytes());
        SystemOutLogger.log("Dependency removed from pom.xml.");
      } catch (IOException e) {
        logger.log(Level.SEVERE, "Error removing dependency from pom.xml", e);
      }

    } catch (Exception e) {
      logger.log(Level.SEVERE, "Error removing dependency from pom.xml", e);
    }
  }
}
