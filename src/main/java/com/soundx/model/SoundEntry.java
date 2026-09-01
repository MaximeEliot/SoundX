package com.soundx.model;

import com.soundx.input.KeyCombo;

import java.io.File;

/**
 * Un son de la grille : le fichier sur disque + son raccourci global eventuel.
 */
public final class SoundEntry {

    private final File file;
    private KeyCombo hotkey; // nullable

    public SoundEntry(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    public String getFileName() {
        return file.getName();
    }

    /** Nom sans extension, pour l'affichage du bouton. */
    public String getDisplayName() {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public KeyCombo getHotkey() {
        return hotkey;
    }

    public void setHotkey(KeyCombo hotkey) {
        this.hotkey = hotkey;
    }
}
