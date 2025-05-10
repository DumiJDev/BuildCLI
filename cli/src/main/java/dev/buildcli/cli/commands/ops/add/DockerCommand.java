package dev.buildcli.cli.commands.ops.add;

import dev.buildcli.core.actions.commandline.JavaProcess;
import dev.buildcli.core.domain.BuildCLICommand;
import dev.buildcli.core.domain.docker.compose.*;
import dev.buildcli.core.domain.docker.file.*;
import dev.buildcli.core.utils.docker.DockerHubUtils;
import dev.buildcli.core.utils.docker.DockerHubUtils.DockerImage;
import dev.buildcli.plugin.BuildCLIPlugin;
import dev.buildcli.plugin.enums.TemplateType;
import dev.buildcli.plugin.utils.BuildCLIPluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static dev.buildcli.core.domain.docker.compose.NetworkDriver.BRIDGE;
import static dev.buildcli.core.utils.docker.DockerHubUtils.searchImagesWithTags;
import static dev.buildcli.core.utils.input.InteractiveInputUtils.*;
import static java.util.Optional.ofNullable;

@Command(name = "dockerfile", aliases = {"docker", "df"}, description = "Generates a Dockerfile for the project. "
    + "Alias: 'docker' and 'df'. Allows customizing the base image, exposed ports, and file name.",
    mixinStandardHelpOptions = true)
public class DockerCommand implements BuildCLICommand {
  private final Logger logger = LoggerFactory.getLogger(DockerCommand.class.getName());

  @Option(names = {"--name", "-n"}, description = "Name of the file to write docker build instructions.", defaultValue = "Dockerfile")
  private String name;
  @Option(names = {"--from", "-f"}, description = "Specifies the base image for the docker build.", defaultValue = "openjdk:17-jdk-slim")
  private String fromImage;
  @Option(names = {"--port", "-p"}, description = "Specifies the port used to run the docker application", defaultValue = "8080", split = ",")
  private List<Integer> ports;
  @Option(names = {"--env", "-e"}, description = "Environment variables for docker build and runtime usage. "
      + "Multiple variables can be passed as key=value pairs separated by ';'", defaultValue = "")
  private String envVariable;
  @Option(names = {"--force"}, description = "Use to overwrite existing dockerfile specified by name option.", defaultValue = "false")
  private Boolean force;

  @Option(names = {"--template", "-t"}, description = "Docker template, internal or plugin", defaultValue = "false")
  private boolean template;

  @Override
  public void run() {

    if (template) {
      var templates = BuildCLIPluginManager.getTemplatesByType(TemplateType.DOCKER);
      var dockerTemplate = options("Choose a Docker template", templates, BuildCLIPlugin::name);

      dockerTemplate.execute();
    } else {
      var dockerType = options("Choose a Docker type", List.of("Dockerfile", "Docker Compose"));
      switch (dockerType) {
        case "Dockerfile":
          generateDockerfile();
          break;
        case "Docker Compose":
          generateDockerCompose();
          break;
        default:
          logger.error("Error: Invalid Docker type.");
          break;
      }
    }


    try {
      File dockerfile = new File(name);
      if (dockerfile.createNewFile() || force) {
        try (FileWriter writer = new FileWriter(dockerfile, false)) {

          String[] envVars = processEnvVariables(envVariable);

          var builder = new StringBuilder("FROM ").append(fromImage).append("\n");
          builder.append("WORKDIR ").append("/app").append("\n");
          builder.append("COPY ").append("target/*.jar app.jar").append("\n");
          ports.forEach(port -> {
            builder.append("EXPOSE ").append(port).append("\n");
          });
          if (envVars != null) {
            for (String s : envVars) {
              if (s != null) builder.append("ENV ").append(s).append("\n");
            }
          }
          builder.append("ENTRYPOINT ").append("[\"java\", \"-jar\", \"app.jar\"]").append("\n");

          writer.write(builder.toString());
          System.out.println("Dockerfile generated.");
        }
      } else {
        System.out.println("Dockerfile already exists.");
      }
      System.out.println("Dockerfile created successfully.");
      System.out.println("Use 'buildcli project run docker' to build and run the Docker container.");
    } catch (IOException e) {
      logger.error("Failed to setup Docker", e);
      System.err.println("Error: Could not setup Docker environment.");
    }
  }

  private void generateDockerCompose() {
    var dockerComposeFileName = question("Docker Compose file name", false);
    var dockerComposeFile = new File(dockerComposeFileName);
    if (dockerComposeFile.exists() && !force) {
      System.out.println("Docker Compose file already exists.");
      return;
    }


    var version = question("Docker Compose version", false);
    var services = new LinkedHashMap<String, Service>();
    do {
      var serviceName = question("Service name", false);
      var isUsingImage = confirm("Do you want to use image?");
      String imageName = null;
      DockerImage image = null;
      String dockerfile = null;

      if (isUsingImage) {
        imageName = question("Image name", true);
        var images = DockerHubUtils.searchImagesWithTags(imageName);
        image = options("Choose an image", images);
      } else {
        dockerfile = question("Dockerfile name", false);
      }


      var ports = new ArrayList<String>();
      do {
        var port = question("Port", false);
        if (port == null || !port.matches("\\d{2,6}")) {
          break;
        }
        ports.add(port);
      } while (confirm("Add another port?"));

      var commands = new ArrayList<String>();
      do {
        var command = question("Command", false);
        if (command == null || command.isBlank()) {
          break;
        }
        commands.add(command);
      } while (confirm("Add another command?"));


      var dependsOn = new ArrayList<String>();
      do {
        var keys = services.keySet();
        var dependency = keys.isEmpty() ? question("Depends on?") : options("Depends on?", keys.stream().toList());
        if (dependency == null || dependency.isBlank()) {
          break;
        }
        dependsOn.add(dependency);
      } while (confirm("Add another dependency?"));

      var environments = new LinkedHashMap<String, String>();
      do {
        var envVariable = question("Environment variable", false);
        if (envVariable == null || !envVariable.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
          break;
        }
        environments.put(envVariable, question("Environment variable value", false));
      } while (confirm("Add another environment variable?"));

      var volumes = new ArrayList<String>();
      do {
        var volume = question("Volume", false);
        if (volume == null || volume.isBlank()) {
          break;
        }
        volumes.add(volume);
      } while (confirm("Add another volume?"));

      var networks = new ArrayList<String>();
      do {
        var network = question("Network", false);
        if (network == null || network.isBlank()) {
          break;
        }
        networks.add(network);
      } while (confirm("Add another network?"));

      var restartPolicy = options("Restart policy", List.of(RestartPolicy.values()), s -> s.name().toLowerCase());

      var labels = new LinkedHashMap<String, String>();
      do {
        var label = question("Label", false);
        if (label == null || label.isBlank()) {
          break;
        }
        labels.put(label, question("Label value", false));
      } while (confirm("Add another label?"));

      HealthCheck healthcheck = null;
      if (confirm("Do you want to add healthcheck?")) {
        healthcheck = new HealthCheck(
            List.of(question("Healthcheck command", false)),
            question("Healthcheck interval", false),
            question("Healthcheck timeout", false),
            ofNullable(question("Healthcheck retries", false)).map(Integer::parseInt).orElse(0),
            question("Healthcheck start period", false)
        );
      }


      services.put(serviceName, new Service(
              serviceName,
              dockerfile,
              commands,
              dependsOn,
              environments,
              ports,
              volumes,
              networks,
              restartPolicy,
              labels,
              healthcheck
          )
      );

    } while (confirm("Add another service?"));

    Map<String, Network> networks = new LinkedHashMap<>();

    do {
      var networkName = question("Network name", true);
      var driver = options("Network driver", List.of(NetworkDriver.values()), s -> s.name().toLowerCase());
      var external = confirm("Is this network external?");
      var driverOpts = new LinkedHashMap<String, String>();
      do {
        var driverOpt = question("Network driver option", false);
        if (driverOpt == null || driverOpt.isBlank()) {
          break;
        }
        driverOpts.put(driverOpt, question("Network driver option value", false));
      } while (confirm("Add another network driver option?"));

      var labels = new LinkedHashMap<String, String>();
      do {
        var label = question("Label", false);
        if (label == null || label.isBlank()) {
          break;
        }
        labels.put(label, question("Label value", false));
      } while (confirm("Add another label?"));

      networks.put(networkName, new Network(ofNullable(driver).orElse(BRIDGE).name().toLowerCase(), external, driverOpts, labels));
    } while (confirm("Add another network?"));


    Map<String, Volume> volumes = new LinkedHashMap<>();
    do {
      var volumeName = question("Volume name", true);
      var external = confirm("Is this volume external?");
      var driver = options("Volume driver", List.of(VolumeDriver.values()), s -> s.name().toLowerCase());
      var driverOpts = new LinkedHashMap<String, String>();
      do {
        var driverOpt = question("Volume driver option", false);
        if (driverOpt == null || driverOpt.isBlank()) {
          break;
        }
        driverOpts.put(driverOpt, question("Volume driver option value", false));
      } while (confirm("Add another volume driver option?"));

      var labels = new LinkedHashMap<String, String>();
      do {
        var label = question("Label", false);
        if (label == null || label.isBlank()) {
          break;
        }
        labels.put(label, question("Label value", false));
      } while (confirm("Add another label?"));

      volumes.put(volumeName, new Volume(ofNullable(driver).orElse(VolumeDriver.LOCAL).name().toLowerCase(), external, driverOpts, labels));
    } while (confirm("Add another volume?"));

    var dockerCompose = new DockerCompose(dockerComposeFileName, version, services, networks, volumes);

    try {
      Files.writeString(new File(new File("."), dockerComposeFileName).toPath(), dockerCompose.toString());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void generateDockerfile() {
    var dockerfileName = question("Dockerfile name", false);
    var dockerfileFile = new File(dockerfileName);
    if (dockerfileFile.exists() && !force) {
      System.out.println("Dockerfile already exists.");
      return;
    }

    var stages = new ArrayList<DockerStage>();

    do {
      var stageName = question("Stage name", false);
      var imageName = question("Image name", true);

      var images = searchImagesWithTags(imageName);

      var image = options("Choose an image", images, DockerImage::name);
      var from = new From(image.toString(), stageName);

      var resources = new ArrayList<DockerfileResource>();
      do {
        var resourceType = options("Choose a Docker resource type", List.of(DockerfileResourceType.values()), s -> s.name().toLowerCase());
        var resource = switch (resourceType) {
          case COPY -> {
            var copyFrom = question("Copy from", false);
            var copyTo = question("Copy to", false);

            if ((copyFrom == null || copyFrom.isBlank()) || (copyTo == null || copyTo.isBlank()))
              yield null;

            yield new Copy(copyFrom, copyTo);
          }
          case RUN -> {
            var runCommand = question("Run command", false);

            if (runCommand == null || runCommand.isBlank())
              yield null;

            yield new Run(runCommand);
          }
          case EXPOSE -> {

            var exposePort = question("Expose port", false);
            if (exposePort == null || !exposePort.matches("\\d{2,6}"))
              yield null;

            yield new Expose(Integer.parseInt(exposePort));
          }
          case ENV -> {

            var envVariable = question("Environment variable", false);

            if (envVariable == null || !envVariable.matches("^[a-zA-Z_][a-zA-Z0-9_]*$"))
              yield null;

            yield new Env(envVariable);
          }
          case WORKDIR -> {
            var workDir = question("Work directory", false);

            if (workDir == null || workDir.isBlank())
              yield null;

            yield new Workdir(workDir);
          }
          case ADD -> {

            var addFrom = question("Add from", false);
            var addTo = question("Add to", false);

            if ((addFrom == null || addFrom.isBlank()) || (addTo == null || addTo.isBlank()))
              yield null;

            yield new Add(addFrom, addTo);
          }
          case ENTRYPOINT -> {
            var entrypoint = question("Entrypoint", false);

            if (entrypoint == null || entrypoint.isBlank())
              yield null;

            yield new Entrypoint(entrypoint);
          }
          default -> null;
        };

        if (resource == null) {
          break;
        }

        resources.add(resource);

      } while (true);

      stages.add(new DockerStage(stageName, from, resources));

    } while (confirm("Add another stage?"));

    var dockerfile = new Dockerfile(dockerfileName, stages);

    try {
      Files.writeString(new File(new File("."), dockerfileName).toPath(), dockerfile.toString());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

  }

  private String[] processEnvVariables(String envVariable) {
    String[] envVars = null;
    if (!"".equals(envVariable)) {
      envVars = envVariable.split(";");
      for (int i = 0; i < envVars.length; i++) {
        if (envVars[i].contains("JAVA_TOOL_OPTIONS")) {
          String java_tool_options = "";
          java_tool_options = envVars[i].split("=")[1];
          java_tool_options = validateJvmOptions(java_tool_options);
          if (java_tool_options == null) {
            envVars[i] = null;
            continue;
          }
          envVars[i] = new StringBuffer().append("JAVA_TOOL_OPTIONS=\"").append(java_tool_options).append("\"").toString();
          continue;
        }
        envVars[i] = new StringBuffer(envVars[i].split("=")[0]).append("=\"").append(envVars[i].split("=")[1]).append("\"").toString();
      }
    }

    return envVars;
  }

  private String validateJvmOptions(String options) {
    if (!"".equals(options)) {
      try {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("--dry-run");
        command.addAll(Arrays.asList(options.split(" ")));
        command.add("-version");
        var process = JavaProcess.createProcess("--dry-run", options, "--version");
        var code = process.run();
        if (code != 0) {
          return null;
        }
      } catch (Exception e) {
        e.printStackTrace();
        return null;
      }
    }

    return options;
  }
}
