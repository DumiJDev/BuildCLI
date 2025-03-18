package dev.buildcli.plugin.utils;

import dev.buildcli.core.domain.jar.Jar;
import dev.buildcli.plugin.BuildCLIPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class PluginLoader {
  private static final Logger logger = LoggerFactory.getLogger(PluginLoader.class);

  public static <T extends BuildCLIPlugin> List<T> load(Class<T> tClass, Jar jar) {
    var plugins = new LinkedList<T>();
    try {
      var url = jar.getFile().toURI().toURL();
      try (var loader = new URLClassLoader(new URL[]{url}, PluginLoader.class.getClassLoader())) {

        preloadAllClasses(loader, jar);

        var serviceLoader = ServiceLoader.load(tClass, loader);

        serviceLoader.forEach(plugins::add);
      }
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }

    return plugins;
  }

  private static void preloadAllClasses(URLClassLoader loader, Jar jar) {
    try {
      var jarFile = jar.getFile();
      try (JarFile jarFileObj = new JarFile(jarFile)) {
        Enumeration<JarEntry> entries = jarFileObj.entries();

        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          String entryName = entry.getName();

          // Verificar se é um arquivo de classe
          if (entryName.endsWith(".class")) {
            // Converter path do arquivo para nome de classe
            String className = entryName.replace('/', '.')
                .replace('\\', '.')
                .replace(".class", "");

            try {
              // Carregar a classe
              Class<?> loadedClass = loader.loadClass(className);
              // Logger apenas para debug
              logger.debug("Pré-carregada classe: {}", className);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
              // Ignorar classes que não podem ser carregadas, mas logar para debug
              logger.debug("Não foi possível carregar a classe: {}", className, e);
            }
          }
        }
      }
    } catch (Exception e) {
      logger.error("Erro ao pré-carregar classes do JAR", e);
    }
  }
}
