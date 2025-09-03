package dev.buildcli.core.utils;

import java.util.logging.Logger;

public abstract class OS {
  private static final Logger logger = Logger.getLogger(OS.class.getName());
  private static RuntimeCommandExecutor runtimeCommandExecutor = new RuntimeCommandExecutor();

  private OS() {
  }

  private static final String OS = System.getProperty("os.name").toLowerCase();

  public static boolean isWindows() {
    return OS.contains("win");
  }

  public static boolean isMac() {
    return OS.contains("mac");
  }

  public static boolean isLinux() {
    return OS.contains("linux") || OS.contains("nix") || OS.contains("nux") || OS.contains("aix");
  }

<<<<<<< HEAD
  public static String getOSName() {
    return System.getProperty("os.name").toLowerCase();
  }
=======
    public static String getOSName() {
        return System.getProperty("os.name");
    }
>>>>>>> 5f864b19d7c788254b0fa7d6e2ab9cabbf137083

  public static String getArchitecture() {
    return System.getProperty("os.arch");
  }

  public static void cdDirectory(String path) {
    try {
<<<<<<< HEAD
      CommandMan command = CommandMan.create()
          .addCommand("cd " + path);
      executeCommand(command);
=======
        String[] command;
        if (isWindows()) {
            command = new String[]{"cmd", "/c", "cd", path};
        } else {
            command = new String[]{"sh", "-c", "cd", path};
        }
        Runtime.getRuntime().exec(command);
>>>>>>> 5f864b19d7c788254b0fa7d6e2ab9cabbf137083
    } catch (Exception e) {
      logger.severe("Error changing directory: " + e.getMessage());
    }
  }

  public static void cpDirectoryOrFile(String source, String destination) {
    try {
      String[] command;
      if (isWindows()) {
        command = new String[]{"cmd", "/c", "copy", source, destination};
      } else {
        command = new String[]{"sh", "-c", "cp",  source, destination};
      }
      Runtime.getRuntime().exec(command);
    } catch (Exception e) {
      logger.severe("Error copying directory: " + e.getMessage());
    }
  }

  public static String getHomeBinDirectory() {
    String homeBin = "";
    if (isWindows()) {
      homeBin = System.getenv("HOMEPATH") + "//bin";
    } else {
      homeBin = System.getenv("HOME") + "/bin";
    }
    return homeBin;
  }

  public static void chmodX(String path){
      if(!isWindows()){
            try {
                String chmodCommand = "chmod +x " + path;
                String[] command = new String[]{"sh", "-c", chmodCommand};
                Runtime.getRuntime().exec(command);
            } catch (Exception e) {
                logger.severe("Error changing directory: " + e.getMessage());
            }
      }

<<<<<<< HEAD
  private static void executeCommand(CommandMan commandMan) throws CommandExecutorRuntimeException {
    try {
      for (String cmd : commandMan.getCommands()) {
        String[] command = isLinux() ? new String[]{"sh", "-c", cmd}
            : new String[]{"cmd", "/c", cmd};
        runtimeCommandExecutor.execute(command);
      }
    } catch (Exception e) {
      String errorMessage = "Error executing command: " + e.getMessage();
      logger.severe(errorMessage);
      throw new CommandExecutorRuntimeException(e.getMessage());
    }
=======
>>>>>>> 5f864b19d7c788254b0fa7d6e2ab9cabbf137083
  }
}
