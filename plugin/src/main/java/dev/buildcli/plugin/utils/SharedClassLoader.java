package dev.buildcli.plugin.utils;

import java.net.URL;
import java.net.URLClassLoader;

public class SharedClassLoader extends URLClassLoader {
  private static SharedClassLoader sharedClassLoader;

  public SharedClassLoader(URL[] urls, ClassLoader parent) {
    super(urls, parent);
  }

  public SharedClassLoader(URL urls, ClassLoader parent) {
    super(new URL[]{urls}, parent);
  }

  public static SharedClassLoader getInstance(URL[] urls, ClassLoader parent) {
    if (sharedClassLoader == null) {
      sharedClassLoader = new SharedClassLoader(urls, parent);
    }
    return sharedClassLoader;
  }

  public static URLClassLoader getInstance() {
    if (sharedClassLoader == null) {
      throw new Error("SharedClassLoader has not been initialized");
    }

    return sharedClassLoader;
  }

  @Override
  public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    Class<?> clazz = findLoadedClass(name);
    if (clazz == null) {
      try {
        clazz = findClass(name);
      } catch (ClassNotFoundException e) {
        clazz = super.loadClass(name, false);
      }
    }
    if (resolve) {
      resolveClass(clazz);
    }
    return clazz;
  }
}
