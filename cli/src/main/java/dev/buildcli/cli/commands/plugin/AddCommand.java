package dev.buildcli.cli.commands.plugin;

import dev.buildcli.cli.utils.CommandUtils;
import dev.buildcli.core.domain.BuildCLICommand;
import dev.buildcli.core.domain.configs.BuildCLIConfig;
import dev.buildcli.core.domain.jar.Jar;
import dev.buildcli.core.utils.ProjectUtils;
import dev.buildcli.core.utils.config.ConfigContextLoader;
import dev.buildcli.core.utils.filesystem.FindFilesUtils;
import dev.buildcli.core.utils.net.FileDownloader;
import dev.buildcli.plugin.utils.PluginUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.buildcli.core.utils.input.InteractiveInputUtils.question;

@Command(name = "add", aliases = {"a"}, description = "Add plugin from git repository, jar (web or local) or a valid Java project", mixinStandardHelpOptions = true)
public class AddCommand implements BuildCLICommand {
  private final Logger logger = LoggerFactory.getLogger("AddPluginCommand");

  @Option(names = {"--file", "-f"})
  private String file;

  private final BuildCLIConfig globalConfig = ConfigContextLoader.getAllConfigs();

  @Override
  public void run() {
    try {
      var pathOrUrl = file == null ? question("Enter Plugin path or URL", true) : file;

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
            logger.info("Validating jar, {}", tempFile);
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
            logger.info("Validating jar: {}", jar.getFile());
            if (PluginUtils.isValid(jar)) {
              logger.info("Jar is a valid plugin...");
              copyJarPlugin(jar);
            } else {
              logger.info("Jar is not a valid plugin...");
            }
          } else {
            throw new IllegalArgumentException("Plugin path or URL is not a file or a directory (" + pathOrUrl + ")");
          }
        } else {
          throw new IllegalArgumentException("Plugin path or URL is not a file or a directory (" + pathOrUrl + ")");
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
    logger.info("Copying jar {}...", jar.getFile());
    var destPath = Path.of(System.getProperty("user.home"), ".buildcli", "plugins", jar.getFile().getName());
    Files.copy(jar.getFile().toPath(), destPath);
    logger.info("Jar copied to {}...", destPath);
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

    var exitedCode = CommandUtils.call("project", "build", "-p", directory.getAbsolutePath());

    if (exitedCode != 0) {
      logger.warn("Build failed with exit code {}", exitedCode);
      throw new RuntimeException("Build failed with exit code " + exitedCode);
    }

    logger.info("Build complete.");
    logger.info("Finding jars...");

    var jars = FindFilesUtils.searchJarFiles(directory);

    for (var jar : jars) {
      var jarFile = new Jar(jar);
      logger.info("Validating jar {}", jar);
      if (PluginUtils.isValid(jarFile)) {
        logger.info("Validated jar, is a valid jar plugin");
        outJars.add(jarFile);
        continue;
      }
      logger.info("Jar is not a valid plugin...");
    }

    return outJars;
  }
}
