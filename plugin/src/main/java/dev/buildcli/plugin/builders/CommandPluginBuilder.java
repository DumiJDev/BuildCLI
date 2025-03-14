package dev.buildcli.plugin.builders;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class CommandPluginBuilder implements PluginBuilder {
  private final String[] paths = new String[]{
      "src/main/java/dev/buildcli/plugin",
      "src/main/resources",
      "src/main/resources/META-INF/services",
      "src/test/java/dev/buildcli/plugin",
      "src/test/resources"
  };

  private static final String pluginContent = """
      package dev.buildcli.plugin.%s;
      
      import dev.buildcli.plugin.BuildCLICommandPlugin;
      import picocli.CommandLine.Command;
      
      @Command(name = "%s", mixinStandardHelpOptions = true)
      public class %sCommand implements BuildCLICommandPlugin {
        @Override
        public void run() {
          //Put your code here
          System.out.println("Hello World, %s!");
        }
      
        @Override
        public String version() {
          return "0.0.1-SNAPSHOT";
        }
      
        @Override
        public String name() {
          return "%s";
        }
      
        @Override
        public String description() {
          return "Build CLI Plugin";
        }
      
        @Override
        public String[] parents() {
          return null;
        }
      }
      """;

  private static final String pluginPom = """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
        <modelVersion>4.0.0</modelVersion>
        <groupId>dev.buildcli.plugin</groupId>
        <artifactId>buildcli-plugin-%s</artifactId>
        <version>0.0.1-SNAPSHOT</version>
      
        <dependencies>
          <dependency>
            <groupId>dev.buildcli</groupId>
            <artifactId>buildcli-plugin</artifactId>
            <version>0.14.0</version>
          </dependency>
          <dependency>
            <groupId>dev.buildcli</groupId>
            <artifactId>buildcli-core</artifactId>
            <version>0.14.0</version>
          </dependency>
          <dependency>
            <groupId>info.picocli</groupId>
            <artifactId>picocli</artifactId>
            <version>4.7.6</version>
          </dependency>
        </dependencies>
      
        <build>
          <finalName>%s</finalName>
          <plugins>
            <plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-compiler-plugin</artifactId>
              <version>3.13.0</version>
            </plugin>
          </plugins>
         </build>
      </project>
      """;

  @Override
  public File build(File directory, String name) {
    try {
      for (var path : paths) {
        new File(directory, path.contains("java") ? path + "/" + name.toLowerCase().trim() : path).mkdirs();
      }

      var capitalizedName = (name.substring(0, 1).toUpperCase() + name.substring(1)).trim();

      var nameLowerCase = name.toLowerCase().trim();
      var formattedContent = pluginContent.formatted(nameLowerCase, nameLowerCase, capitalizedName, nameLowerCase, nameLowerCase);
      var formattedPom = pluginPom.formatted(nameLowerCase, nameLowerCase);
      var absolutePath = directory.getAbsolutePath();

      Files.writeString(Paths.get(absolutePath, paths[0], nameLowerCase, capitalizedName + ".java"), formattedContent);
      Files.writeString(Paths.get(absolutePath, paths[2], "dev.buildcli.plugin.BuildCLICommandPlugin"), "dev.buildcli.plugin.%s.%sCommand".formatted(nameLowerCase, capitalizedName));
      Files.writeString(Paths.get(absolutePath, "pom.xml"), formattedPom);

      return directory;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
