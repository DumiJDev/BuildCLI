package dev.buildcli.plugin.utils;

import dev.buildcli.core.domain.jar.Jar;
import dev.buildcli.plugin.BuildCLIPlugin;
import org.pf4j.DefaultPluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PF4JPluginLoader {
  private static final Logger logger = LoggerFactory.getLogger(PF4JPluginLoader.class);
  private static PF4JPluginLoader instance;
  private final org.pf4j.PluginManager pluginManager;

  private PF4JPluginLoader() {
    //Paths.get(System.getProperty("user.home"), ".buildcli", "plugins")
    this.pluginManager = new DefaultPluginManager();
  }


  public static synchronized PF4JPluginLoader getInstance() {
    if (instance == null) {
      instance = new PF4JPluginLoader();
    }
    return instance;
  }

  public void loadPlugins(List<Jar> jars) {
    try {
      // Carrega os plugins do diretório de plugins configurado
      pluginManager.loadPlugins();

      // Inicializa os plugins
      pluginManager.startPlugins();

      // Registra plugins adicionais de JARs específicos
      for (Jar jar : jars) {
        try {
          var jarFile = jar.getFile();
          String pluginId = jarFile.getName().replace(".jar", "");
          Path pluginPath = jarFile.toPath();

          // Carrega um plugin específico
          pluginManager.loadPlugin(pluginPath);
          //pluginManager.startPlugin(pluginId);

          logger.info("Plugin loaded: {}", pluginId);
        } catch (Exception e) {
          logger.error("Failed to load plugin JAR: {}", jar.getFile().getName(), e);
        }
      }

      // Log de plugins carregados
      pluginManager.getPlugins().forEach(plugin ->
          logger.info("Plugin loaded: {} ({})",
              plugin.getPluginId(),
              plugin.getDescriptor().getVersion()));

    } catch (Exception e) {
      logger.error("Error loading plugins", e);
    }
  }

  public <T extends BuildCLIPlugin> List<T> getPlugins(Class<T> type) {
    // Obtém todas as extensões do tipo especificado
    return new ArrayList<>(pluginManager.getExtensions(type));
  }

  public void unloadPlugins() {
    // Desativa todos os plugins
    pluginManager.stopPlugins();
  }
}
