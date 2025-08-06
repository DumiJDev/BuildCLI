package dev.buildcli.core.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

class PomUtilsTest {

  @TempDir
  Path tempDir;

  private Path pomFile;
  private Path nonDependenciesPomFile;

  @BeforeEach
  void setUp() throws IOException {
    // Copy the test POM files to the temporary directory
    try (InputStream isPom = getClass().getResourceAsStream("/pom-utils-test/pom.xml");
         InputStream isNonDependenciesPom = getClass().getResourceAsStream("/pom-utils-test/non-dependencies-pom.xml")) {

      Files.copy(isPom, tempDir.resolve("pom.xml"), StandardCopyOption.REPLACE_EXISTING);
      Files.copy(isNonDependenciesPom, tempDir.resolve("non-dependencies-pom.xml"), StandardCopyOption.REPLACE_EXISTING);

      pomFile = tempDir.resolve("pom.xml");
      nonDependenciesPomFile = tempDir.resolve("non-dependencies-pom.xml");
    }
  }

  @AfterEach
  void tearDown() throws IOException {
    Files.deleteIfExists(pomFile);
    Files.deleteIfExists(nonDependenciesPomFile);
  }

  @Test
  void shouldRemoveExistingDependency() {
    var groupId = "org.junit.jupiter";
    var artifactId = "junit-jupiter-api";
    var changedPom = PomUtils.rmDependencyToPom(pomFile.toFile().getAbsolutePath(),
        new String[]{groupId.concat(":").concat(artifactId)});
    assertFalse(changedPom.hasDependency(groupId, artifactId));
    assertEquals(2, changedPom.countDependencies()); // Expect 2 dependencies after removing one
  }

  @Test
  void shouldNotRemoveNonExistentDependency() {
    var groupId = "org.hibernate";
    var artifactId = "hibernate-core";
    var changedPom = PomUtils.rmDependencyToPom(pomFile.toFile().getAbsolutePath(),
        new String[]{groupId.concat(":").concat(artifactId)});
    assertFalse(changedPom.hasDependency(groupId, artifactId));
    assertEquals(3, changedPom.countDependencies()); // Expect 3 dependencies, as none were removed
  }

  @Test
  void shouldAddNonExistentDependency() {
    var groupId = "org.hibernate";
    var artifactId = "hibernate-core";
    var changedPom = PomUtils.addDependencyToPom(pomFile.toFile().getAbsolutePath(),
        new String[]{groupId.concat(":").concat(artifactId)});
    assertTrue(changedPom.hasDependency(groupId, artifactId));
    assertEquals(4, changedPom.countDependencies()); // Expect 4 dependencies after adding one
  }

  @Test
  void shouldAddDependencyToNonDependenciesPom() {
    var groupId = "org.hibernate";
    var artifactId = "hibernate-core";
    var changedPom = PomUtils.addDependencyToPom(nonDependenciesPomFile.toFile().getAbsolutePath(),
        new String[]{groupId.concat(":").concat(artifactId)});
    assertTrue(changedPom.hasDependency(groupId, artifactId));
    assertEquals(1, changedPom.countDependencies()); // Expect 1 dependency after adding to an empty POM
  }
}