package com.soundx.audio;

import javax.sound.sampled.Mixer;

/**
 * Petit adaptateur autour de {@link Mixer.Info} pour l'affichage dans une
 * {@code JComboBox}. Un {@code info} nul represente le peripherique par defaut
 * du systeme (l'intitule affiche est alors {@code defaultLabel}).
 */
public final class AudioDevice {

    private final Mixer.Info info;
    private final String defaultLabel;

    public AudioDevice(Mixer.Info info) {
        this(info, "Sortie par defaut du systeme");
    }

    public AudioDevice(Mixer.Info info, String defaultLabel) {
        this.info = info;
        this.defaultLabel = defaultLabel;
    }

    public Mixer.Info getInfo() {
        return info;
    }

    public boolean isSystemDefault() {
        return info == null;
    }

    @Override
    public String toString() {
        return info == null ? defaultLabel : info.getName();
    }
}
