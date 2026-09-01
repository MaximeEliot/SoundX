package com.soundx;

import com.soundx.audio.AudioManager;
import com.soundx.config.AppConfig;
import com.soundx.config.ConfigManager;
import com.soundx.input.KeyHookManager;
import com.soundx.model.SoundFiles;
import com.soundx.ui.SoundboardUI;
import com.soundx.ui.Theme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Point d'entree de l'application.
 *
 * <p>La configuration ET le dossier des sons vivent dans un emplacement
 * <b>stable</b> ({@code %LOCALAPPDATA%\SoundX}), jamais efface par un
 * {@code mvn clean} ni par une mise a jour du .jar. Au tout premier lancement,
 * si un dossier {@code sounds/} deja rempli est trouve a cote du .jar (ou dans
 * le repertoire courant), il est adopte automatiquement et memorise.</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {

        // 1. Silence des logs JNativeHook.
        KeyHookManager.silenceLogging();

        // 2. Look & feel sombre (noir / rouge) — avant toute creation de composant.
        try {
            Theme.install();
        } catch (Exception ignored) {
            // on garde le L&F par defaut
        }

        // 3. Dossier de donnees stable + fichier de config.
        Path dataDir = resolveDataDir();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            System.err.println("Impossible de creer " + dataDir + " : " + e.getMessage());
        }
        Path configFile = dataDir.resolve("soundx-config.json");
        migrateLegacyConfig(configFile);

        // 4. Services.
        ConfigManager configManager = new ConfigManager(configFile);
        AppConfig config = configManager.load();

        File soundsDir = resolveSoundsDir(config, dataDir);
        configManager.save(); // persiste soundsDirectory des le 1er lancement

        AudioManager audio = new AudioManager();
        KeyHookManager hook = new KeyHookManager();

        try {
            hook.start();
        } catch (Throwable t) {
            JOptionPane.showMessageDialog(null,
                    "Impossible d'installer le hook clavier global :\n" + t.getMessage()
                            + "\n\nL'application demarre quand meme, mais les raccourcis\n"
                            + "globaux seront inactifs.",
                    "SoundX", JOptionPane.ERROR_MESSAGE);
        }

        // 5. Arret propre garanti (JNativeHook lance un thread non-daemon).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            hook.stop();
            audio.shutdown();
            configManager.save();
        }, "soundx-shutdown"));

        // 6. Interface graphique.
        final File soundsDirFinal = soundsDir;
        SwingUtilities.invokeLater(() -> {
            SoundboardUI ui = new SoundboardUI(soundsDirFinal, audio, hook, configManager);
            ui.setVisible(true);
        });
    }

    // ------------------------------------------------------------------

    /** {@code %LOCALAPPDATA%\SoundX} (Windows), sinon {@code ~/.soundx}. */
    private static Path resolveDataDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Paths.get(localAppData, "SoundX");
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, "SoundX");
        }
        return Paths.get(System.getProperty("user.home"), ".soundx");
    }

    /**
     * Determine le dossier des sons :
     *  1. le chemin memorise dans la config ;
     *  2. sinon, un dossier {@code sounds/} deja rempli trouve a cote du .jar
     *     ou dans le repertoire courant (adopte + memorise) ;
     *  3. sinon, {@code <dataDir>/sounds} (cree, stable).
     */
    private static File resolveSoundsDir(AppConfig config, Path dataDir) {
        if (config.soundsDirectory != null && !config.soundsDirectory.isBlank()) {
            File d = new File(config.soundsDirectory);
            d.mkdirs();
            return d;
        }
        for (Path candidate : candidateSoundsDirs()) {
            File d = candidate.toFile();
            if (SoundFiles.containsSound(d)) {
                config.soundsDirectory = d.getAbsolutePath();
                return d;
            }
        }
        File fallback = dataDir.resolve("sounds").toFile();
        fallback.mkdirs();
        config.soundsDirectory = fallback.getAbsolutePath();
        return fallback;
    }

    private static List<Path> candidateSoundsDirs() {
        List<Path> candidates = new ArrayList<>();
        Path jarDir = jarDirectory();
        if (jarDir != null) {
            candidates.add(jarDir.resolve("sounds"));                 // a cote du .jar
            if (jarDir.getParent() != null) {
                candidates.add(jarDir.getParent().resolve("sounds")); // ..\sounds (cas target\)
            }
        }
        candidates.add(Paths.get("sounds").toAbsolutePath());         // repertoire courant
        return candidates;
    }

    /** Dossier contenant le .jar, ou {@code null} si on ne tourne pas depuis un .jar. */
    private static Path jarDirectory() {
        try {
            Path self = Paths.get(
                    Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (self.toString().toLowerCase().endsWith(".jar") && self.getParent() != null) {
                return self.getParent();
            }
        } catch (Exception ignored) {
            // pas de source identifiable
        }
        return null;
    }

    /** Recupere une ancienne config (a cote du .jar / repertoire courant) si la nouvelle n'existe pas. */
    private static void migrateLegacyConfig(Path target) {
        if (Files.exists(target)) {
            return;
        }
        List<Path> legacy = new ArrayList<>();
        Path jarDir = jarDirectory();
        if (jarDir != null) {
            legacy.add(jarDir.resolve("soundx-config.json"));
        }
        legacy.add(Paths.get("soundx-config.json").toAbsolutePath());

        for (Path old : legacy) {
            if (Files.exists(old)) {
                try {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(old, target);
                } catch (IOException e) {
                    System.err.println("Migration de la config impossible : " + e.getMessage());
                }
                return;
            }
        }
    }
}
