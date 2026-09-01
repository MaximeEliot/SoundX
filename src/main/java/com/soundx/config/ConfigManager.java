package com.soundx.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * Charge / enregistre {@link AppConfig} en JSON. Tolerant : un fichier absent
 * ou corrompu redonne une configuration neuve.
 */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private AppConfig config;

    public ConfigManager(Path path) {
        this.path = path;
    }

    public synchronized AppConfig load() {
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(r, AppConfig.class);
            } catch (Exception e) {
                System.err.println("Configuration illisible (" + e.getMessage()
                        + "), reinitialisation.");
            }
        }
        if (config == null) {
            config = new AppConfig();
        }
        if (config.bindings == null) {
            config.bindings = new LinkedHashMap<>();
        }
        return config;
    }

    public AppConfig get() {
        return config;
    }

    public synchronized void save() {
        if (config == null) {
            return;
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(config, w);
            }
        } catch (IOException e) {
            System.err.println("Ecriture de la configuration impossible : " + e.getMessage());
        }
    }
}
