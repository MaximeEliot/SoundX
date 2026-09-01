package com.soundx.model;

import java.io.File;

/** Extensions audio reconnues + petits utilitaires de detection. */
public final class SoundFiles {

    public static final String[] EXTENSIONS = {".wav", ".mp3", ".aif", ".aiff", ".au"};

    private SoundFiles() {
    }

    public static boolean isSound(String fileName) {
        String n = fileName.toLowerCase();
        for (String ext : EXTENSIONS) {
            if (n.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** Vrai si {@code dir} est un dossier contenant au moins un fichier audio reconnu. */
    public static boolean containsSound(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File[] files = dir.listFiles(f -> f.isFile() && isSound(f.getName()));
        return files != null && files.length > 0;
    }
}
