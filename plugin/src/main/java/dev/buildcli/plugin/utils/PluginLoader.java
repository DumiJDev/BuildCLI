package dev.buildcli.plugin.utils;

import dev.buildcli.core.domain.jar.Jar;
import dev.buildcli.plugin.BuildCLIPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class PluginLoader {
  private static final Logger logger = LoggerFactory.getLogger(PluginLoader.class);

  public static void registerClasses(final List<Jar> jars) {
    try {
      var uris = jars.stream().map(Jar::getFile).map(File::toURI).toList();

      var urls = new URL[uris.size()];

      for (int i = 0; i < uris.size(); i++) {
        urls[i] = uris.get(i).toURL();
      }

      var loader = SharedClassLoader.getInstance(urls, ClassLoader.getSystemClassLoader());

      jars.forEach(jar -> {
        preloadAllClasses(loader, jar);
      });

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static <T extends BuildCLIPlugin> List<T> load(Class<T> tClass) {
    var plugins = new LinkedList<T>();
    try {
      var serviceLoader = ServiceLoader.load(tClass, SharedClassLoader.getInstance());

      serviceLoader.forEach(plugins::add);

    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }

    return plugins;
  }

  public static <T extends BuildCLIPlugin> List<T> load(Class<T> tClass, Jar jar) {
    var plugins = new LinkedList<T>();
    try {
      var serviceLoader = ServiceLoader.load(tClass, new URLClassLoader(new URL[]{jar.getFile().toURI().toURL()}, PluginLoader.class.getClassLoader()));

      serviceLoader.forEach(plugins::add);

    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }

    return plugins;
  }

  private static void preloadAllClasses(URLClassLoader loader, Jar jar) {
    try {
      var jarFile = jar.getFile();
      try (JarFile jarFileObj = new JarFile(jarFile)) {
        List<JarEntry> classEntries = Collections.list(jarFileObj.entries())
            .parallelStream()
            .filter(entry -> entry.getName().endsWith(".class"))
            .toList();

        classEntries.forEach(entry -> {
          String entryName = entry.getName();
          String className = entryName.replace('/', '.')
              .replace('\\', '.')
              .replace(".class", "");

          try {
            Class<?> loadedClass = loader.loadClass(className);
            logger.debug("Class preloaded: {}", className);
          } catch (ClassNotFoundException | NoClassDefFoundError e) {
            logger.debug("Could not load class: {}", className, e);
          }
        });
      }
    } catch (Exception e) {
      logger.error("Occurred an error when loading JAR classes", e);
    }
  }
}
