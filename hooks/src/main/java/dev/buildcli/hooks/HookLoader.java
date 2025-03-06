package dev.buildcli.hooks;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class HookLoader {
    private static final Logger log = LoggerFactory.getLogger(HookLoader.class);
    private final Gson gson = new Gson();
    private final String hooksFilePath;

    public HookLoader(String hooksFilePath) {
        this.hooksFilePath = hooksFilePath;
    }

    public List<Hook> loadHooks() {
        log.debug("Loading hooks from JSON file: {}", hooksFilePath);
        File file = new File(hooksFilePath);
        if (!file.exists()) {
            try {
                if (file.createNewFile()) {
                    try (FileWriter writer = new FileWriter(file)) {
                        writer.write("[]");
                    }
                }
            } catch (IOException e) {
                log.error("Error loading hooks file: \n{}", e.getMessage());
            }
        }

        Type type = new TypeToken<List<Hook>>() {}.getType();
        try (FileReader reader = new FileReader(file)) {
            List<Hook> loadedHooks = gson.fromJson(reader, type);
            return loadedHooks != null ? loadedHooks : new ArrayList<>();
        } catch (IOException e) {
            log.warn("Error loading hooks: \n{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public void saveHooks(List<Hook> hooks) {
        log.info("Saving hooks to JSON file: {}", hooksFilePath);
        File file = new File(hooksFilePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            var created = parent.mkdirs();
            log.info("Created a json file at {} {}", parent,created);
        }
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(hooks, writer);
            log.info("Hooks saved successfully.");
        } catch (IOException e) {
            log.error("Error saving hooks: {}", e.getMessage());
        }
    }
}