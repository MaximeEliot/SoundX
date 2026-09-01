package com.soundx.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contenu du fichier {@code soundx-config.json} (serialise tel quel par Gson).
 */
public final class AppConfig {

    /** Dossier des sons (chemin absolu) ; {@code null} = choisi automatiquement au 1er lancement. */
    public String soundsDirectory;

    /** Nom du {@code Mixer.Info} de SORTIE choisi ; {@code null} = sortie par defaut. */
    public String outputDeviceName;

    /** Volume maitre des sons, 0.0 .. 1.0. */
    public double masterVolume = 0.85;

    /** Ecoute locale : jouer aussi les sons sur un 2e peripherique (le casque). */
    public boolean monitorEnabled = true;

    /** Nom du {@code Mixer.Info} d'ecoute locale ; {@code null} = sortie par defaut. */
    public String monitorDeviceName;

    /** Volume de l'ecoute locale, 0.0 .. 1.0. */
    public double monitorVolume = 0.85;

    /** Nom du {@code Mixer.Info} d'ENTREE (micro) a renvoyer vers la sortie. */
    public String inputDeviceName;

    /** Renvoi micro -> sortie actif (mode "Voicemod" : micro + sons dans le cable). */
    public boolean micPassthrough = false;

    /** Volume du renvoi micro, 0.0 .. 2.0 (1.0 = neutre). */
    public double micVolume = 1.0;

    /** Raccourci global du bouton d'urgence STOP ; {@code null} = aucun. */
    public HotkeyData stopHotkey;

    /** Cle = nom de fichier du son, valeur = raccourci associe. */
    public Map<String, HotkeyData> bindings = new LinkedHashMap<>();

    /** Raccourci serialise : masque de modificateurs + code touche JNativeHook. */
    public static final class HotkeyData {
        public int modifiers;
        public int keyCode;

        public HotkeyData() {
        }

        public HotkeyData(int modifiers, int keyCode) {
            this.modifiers = modifiers;
            this.keyCode = keyCode;
        }
    }
}
