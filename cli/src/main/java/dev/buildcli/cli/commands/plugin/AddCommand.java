package dev.buildcli.cli.commands.plugin;

import dev.buildcli.cli.BuildCLI;
import dev.buildcli.core.domain.BuildCLICommand;
import dev.buildcli.core.domain.configs.BuildCLIConfig;
import dev.buildcli.core.domain.jar.Jar;
import dev.buildcli.core.utils.ProjectUtils;
import dev.buildcli.core.utils.config.ConfigContextLoader;
import dev.buildcli.core.utils.filesystem.FindFilesUtils;
import dev.buildcli.core.utils.input.InteractiveInputUtils;
import dev.buildcli.core.utils.net.FileDownloader;
import dev.buildcli.plugin.utils.PluginUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Command(name = "add", aliases = {"a"}, description = "", mixinStandardHelpOptions = true)
public class AddCommand implements BuildCLICommand {
  private final Logger logger = LoggerFactory.getLogger("AddPluginCommand");

  private final BuildCLIConfig globalConfig = ConfigContextLoader.getAllConfigs();

  @Override
  public void run() {
    try {
      var pathOrUrl = InteractiveInputUtils.question("Enter Plugin path or URL", true);

      if (pathOrUrl.startsWith("http")) {
        if (pathOrUrl.endsWith(".git")) {
          var tempFile = Files.createTempDirectory(UUID.randomUUID().toString()).toFile();
          var gitClone = Git.cloneRepository().setURI(pathOrUrl).setDirectory(tempFile);

          try (var git = gitClone.call()) {
            buildAndCopyPlugins(tempFile);
          }
        } else {
          var tempFile = FileDownloader.download(pathOrUrl);

          if (tempFile.isFile() && tempFile.getName().endsWith(".jar")) {
            var jar = new Jar(tempFile);
            if (PluginUtils.isValid(jar)) {
              copyJarPlugin(jar);
            }
          } else {
            logger.warn("Plugin path or URL is invalid: {}", tempFile);
          }
        }
      } else {
        Path path = Path.of(pathOrUrl);
        if (Files.exists(path)) {
          if (Files.isDirectory(path)) {
            buildAndCopyPlugins(path.toFile());
          } else if (Files.isRegularFile(path) && pathOrUrl.endsWith(".jar")) {
            var jar = new Jar(path.toFile());
            if (PluginUtils.isValid(jar)) {
              copyJarPlugin(jar);
            }
          } else {
            throw new IllegalArgumentException("Plugin path or URL is not a file or a directory");
          }
        } else {
          throw new IllegalArgumentException("Plugin path or URL is not a file or a directory");
        }
      }
    } catch (IOException | GitAPIException e) {
      throw new RuntimeException(e);
    }

  }

  private void buildAndCopyPlugins(File tempFile) throws IOException {
    var jars = loadPluginFromDirectory(tempFile);

    for (var jar : jars) {
      copyJarPlugin(jar);
    }
  }

  private void copyJarPlugin(Jar jar) throws IOException {
    Files.copy(jar.getFile().toPath(), Path.of(System.getProperty("user.home") + ".buildcli", "plugins"));
  }

  private List<Jar> loadPluginFromDirectory(File directory) {
    if (directory.isFile()) {
      throw new IllegalArgumentException("Plugin path must be a directory");
    }

    if (!ProjectUtils.isValid(directory)) {
      throw new IllegalArgumentException("Plugin path must be a valid Java project directory (Maven or Gradle)");
    }

    var outJars = new ArrayList<Jar>();

    logger.info("Building project...");
    logger.info("Running build project...");

    var exitedCode = new CommandLine(new BuildCLI()).execute("project build -p " + directory.getAbsolutePath());

    if (exitedCode != 0) {
      logger.warn("Build failed with exit code {}", exitedCode);
      throw new RuntimeException("Build failed with exit code " + exitedCode);
    }

    logger.info("Build complete.");
    logger.info("Finding jars...");

    var jars = FindFilesUtils.searchJarFiles(directory);

    for (var jar : jars) {
      var jarFile = new Jar(jar);
      if (PluginUtils.isValid(jarFile)) {
        outJars.add(jarFile);
      }
    }

    return outJars;
  }
}
