package dev.buildcli.core.utils;

import dev.buildcli.core.exceptions.DockerException;
import dev.buildcli.core.exceptions.DockerException.DockerComposeFileNotFoundException;
import dev.buildcli.core.exceptions.DockerException.DockerEngineNotRunningException;
import dev.buildcli.core.model.DockerComposeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static dev.buildcli.core.actions.commandline.DockerProcess.createInfoProcess;
import static dev.buildcli.core.actions.commandline.DockerProcess.createProcess;
import static dev.buildcli.core.exceptions.DockerException.DockerBuildException;
import static dev.buildcli.core.exceptions.DockerException.DockerfileNotFoundException;

public class DockerManager {
    private static final Logger logger = LoggerFactory.getLogger(DockerManager.class.getName());

    public void setupDocker() {
        try {
            createDockerfile();
            System.out.println("Dockerfile created successfully.");
            System.out.println("Use 'buildcli --docker-build' to build and run the Docker container.");
        } catch (IOException e) {
            System.err.println("Error: Could not setup Docker environment.");
        }
    }

    private void createDockerfile() throws IOException {
        File dockerfile = new File("Dockerfile");
        if (dockerfile.createNewFile()) {
            try (FileWriter writer = new FileWriter(dockerfile)) {
                writer.write("""
                        FROM openjdk:17-jdk-slim
                        WORKDIR /app
                        COPY target/*.jar app.jar
                        EXPOSE 8080
                        CMD ["java", "-jar", "app.jar"]
                        """);
                System.out.println("Dockerfile generated.");
            }
        } else {
            System.out.println("Dockerfile already exists.");
        }
    }

    public static void setupDockerCompose(DockerComposeConfig config) throws DockerException {

        if (!new File(config.dockerFilePath()).exists()) {
            String errorMessage = "Dockerfile not found: " + config.dockerFilePath();
            logger.error(errorMessage);
            throw new DockerfileNotFoundException(errorMessage);
        }

        createDockercontent(config);
    }

    private static void createDockercontent(DockerComposeConfig config) {
        String contentContent = buildcontentContent(config);
        writeToFile(contentContent);
    }

    private static void writeToFile(String contentContent) {
        try(FileWriter writer = new FileWriter("docker-compose.yml")) {
            writer.write(contentContent);
        } catch (IOException e) {
            String errorMessage = "Failed to setup docker-compose.yml%s".formatted(e.getMessage());
            logger.error(errorMessage);
        }
    }

    private static String buildcontentContent(DockerComposeConfig config) {
        StringBuilder content = new StringBuilder();

        content.append("services:\n");
        content.append("  app:\n");
        content.append("    build:\n");
        content.append("      context: .\n");
        content.append("      dockerfile: ").append(config.dockerFilePath()).append("\n");

        if (config.ports() != null && !config.ports().isEmpty()) {
            content.append("    ports:\n");
            for (String port : config.ports()) {
                content.append("      - \"").append(port).append("\"\n");
            }
        }

        if (config.volumes() != null && !config.volumes().isEmpty()) {
            content.append("    volumes:\n");
            for (String volume : config.volumes()) {
                content.append("      - \"").append(volume).append("\"\n");
            }
        }

        if (config.cpu() != null || config.memory() != null) {
            content.append("    deploy:\n");
            content.append("      resources:\n");
            content.append("        limits:\n");
            if (config.cpu() != null) {
                content.append("          cpus: '").append(config.cpu()).append("'\n");
            }
            if (config.memory() != null) {
                content.append("          memory: ").append(config.memory()).append("\n");
            }
        }

        return content.toString();
    }

    public static void startContainer(String containerName, boolean rebuild) throws DockerException {

        if (!isDockerEngineRunning()) {
            throw new DockerEngineNotRunningException("Docker Engine is not running. Please start Docker and try again.");
        }

        if (!new File("docker-compose.yml").exists()) {
            throw new DockerComposeFileNotFoundException("docker-compose.yml not found in the project root directory ");
        }

        List<String> commandArgs = new ArrayList<>();
        commandArgs.add("compose");
        commandArgs.add("up");
        if (rebuild) {
            commandArgs.add("--build");
        }
        commandArgs.add("-d");

        if (containerName != null && !containerName.isBlank()) {
            commandArgs.add(containerName);
        }

        try {

            var buildExitCode = createProcess(commandArgs.toArray(new String[0])).run();

            if(buildExitCode != 0) {
                String errorMessage = "Failed to start container(s). Command exited with code: %d".formatted(buildExitCode);
                logger.error(errorMessage);
                throw new DockerException(errorMessage);
            }
            logger.info("Successfully started container(s).");
        } catch (DockerBuildException e) {
            String errorMessage = "Failed to start containers%s".formatted(e.getMessage());
            logger.error(errorMessage);
            throw new DockerBuildException(errorMessage, e);
        }
    }

    private static boolean isDockerEngineRunning() {
        try {
            int buildExitCode = createInfoProcess().run();
            return buildExitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
