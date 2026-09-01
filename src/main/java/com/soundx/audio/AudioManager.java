package com.soundx.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coeur audio de l'application.
 *
 * <h3>Sons</h3>
 *  - {@link #listOutputDevices()} : peripheriques de sortie (+ entree "defaut") ;
 *  - {@link #setOutputDevice(Mixer.Info)} : sortie cible (souvent un cable virtuel) ;
 *  - {@link #play(File)} : decode (WAV natif, MP3 via SPI) puis joue, sans limite ;
 *  - {@link #stopAll()} : bouton d'urgence (ne coupe PAS le renvoi micro).
 *
 * <h3>Renvoi micro (facon Voicemod)</h3>
 *  - {@link #listInputDevices()} : peripheriques de capture ;
 *  - {@link #configureMic(Mixer.Info, boolean, double)} : (re)configure le renvoi ;
 *  - le renvoi suit automatiquement la sortie choisie, pour que micro + sons
 *    arrivent ensemble dans le cable virtuel.
 *
 * <h3>Ecoute locale (monitoring)</h3>
 *  - {@link #configureMonitor(Mixer.Info, boolean, double)} : jouer aussi chaque
 *    son sur un 2e peripherique (le casque), en plus de la sortie principale.
 */
public final class AudioManager {

    /** Retour d'etat du renvoi micro vers l'UI. */
    public interface MicStatusListener {
        void onMicStatus(String message, boolean active);
    }

    private final ExecutorService playbackPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "soundx-playback");
        t.setDaemon(true);
        return t;
    });

    private final Set<Playback> active = ConcurrentHashMap.newKeySet();

    /** {@code null} => sortie par defaut du systeme. */
    private volatile Mixer.Info outputDevice;
    /** 0.0 .. 1.0 */
    private volatile double masterVolume = 0.85;

    // --- ecoute locale (monitoring casque) ---
    private volatile Mixer.Info monitorDevice;   // null => sortie par defaut
    private volatile boolean monitorEnabled = true;
    private volatile double monitorVolume = 0.85;

    // --- renvoi micro ---
    private final Object micLock = new Object();
    private volatile Mixer.Info inputDevice;
    private volatile boolean micEnabled;
    private volatile double micVolume = 1.0;
    private MicWorker micWorker;
    private final AtomicLong micGeneration = new AtomicLong();
    private volatile MicStatusListener micStatusListener;
    private volatile String micStatus = "Renvoi micro inactif.";

    // ==================================================================
    //  Peripheriques de sortie
    // ==================================================================

    public List<AudioDevice> listOutputDevices() {
        List<AudioDevice> result = new ArrayList<>();
        result.add(new AudioDevice(null, "Sortie par defaut du systeme"));

        Line.Info wanted = new Line.Info(SourceDataLine.class);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(info).isLineSupported(wanted)) {
                    result.add(new AudioDevice(info));
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public void setOutputDevice(Mixer.Info info) {
        this.outputDevice = info;
        reconfigureMic(); // le renvoi micro suit la sortie
    }

    public Mixer.Info getOutputDevice() {
        return outputDevice;
    }

    public void setMasterVolume(double volume) {
        this.masterVolume = Math.max(0.0, Math.min(1.0, volume));
    }

    public double getMasterVolume() {
        return masterVolume;
    }

    // ==================================================================
    //  Ecoute locale (monitoring casque)
    // ==================================================================

    /** Meme liste que {@link #listOutputDevices()} : le casque est un peripherique de sortie. */
    public List<AudioDevice> listMonitorDevices() {
        return listOutputDevices();
    }

    /** Configure l'ecoute locale : jouer aussi chaque son sur {@code device}. */
    public void configureMonitor(Mixer.Info device, boolean enabled, double volume) {
        this.monitorDevice = device;
        this.monitorEnabled = enabled;
        this.monitorVolume = Math.max(0.0, Math.min(1.0, volume));
    }

    public void setMonitorDevice(Mixer.Info info) {
        this.monitorDevice = info;
    }

    public Mixer.Info getMonitorDevice() {
        return monitorDevice;
    }

    public void setMonitorEnabled(boolean enabled) {
        this.monitorEnabled = enabled;
    }

    public boolean isMonitorEnabled() {
        return monitorEnabled;
    }

    public void setMonitorVolume(double volume) {
        this.monitorVolume = Math.max(0.0, Math.min(1.0, volume));
    }

    public double getMonitorVolume() {
        return monitorVolume;
    }

    // ==================================================================
    //  Peripheriques d'entree + renvoi micro
    // ==================================================================

    public List<AudioDevice> listInputDevices() {
        List<AudioDevice> result = new ArrayList<>();
        Line.Info wanted = new Line.Info(TargetDataLine.class);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(info).isLineSupported(wanted)) {
                    result.add(new AudioDevice(info, "Entree par defaut du systeme"));
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public void setMicStatusListener(MicStatusListener listener) {
        this.micStatusListener = listener;
    }

    public String getMicStatus() {
        return micStatus;
    }

    /** (Re)configure le renvoi micro en une seule fois (evite les redemarrages en cascade). */
    public void configureMic(Mixer.Info input, boolean enabled, double volume) {
        this.inputDevice = input;
        this.micEnabled = enabled;
        this.micVolume = clampMicVolume(volume);
        reconfigureMic();
    }

    public void setInputDevice(Mixer.Info info) {
        this.inputDevice = info;
        reconfigureMic();
    }

    public Mixer.Info getInputDevice() {
        return inputDevice;
    }

    public void setMicPassthroughEnabled(boolean enabled) {
        this.micEnabled = enabled;
        reconfigureMic();
    }

    public boolean isMicPassthroughEnabled() {
        return micEnabled;
    }

    /** Volume du renvoi micro (0.0 .. 2.0). Applique a chaud, sans redemarrage. */
    public void setMicVolume(double volume) {
        this.micVolume = clampMicVolume(volume);
        MicWorker w = micWorker;
        if (w != null) {
            w.gain = this.micVolume;
        }
    }

    public double getMicVolume() {
        return micVolume;
    }

    public boolean isMicRunning() {
        MicWorker w = micWorker;
        return w != null && w.isAlive();
    }

    /** Niveau crete du signal micro capture (0.0 .. 1.0), 0 si le renvoi est inactif. */
    public double getMicLevel() {
        MicWorker w = micWorker;
        return (w != null && w.isAlive()) ? w.level : 0.0;
    }

    /**
     * (Re)demarre le renvoi micro sur un thread dedie (ouvrir/fermer des lignes
     * peut bloquer plusieurs dizaines de ms : on ne le fait jamais sur l'EDT).
     * Un compteur de generation garantit que seule la derniere demande gagne.
     */
    private void reconfigureMic() {
        final long generation = micGeneration.incrementAndGet();
        final Mixer.Info input = this.inputDevice;
        final Mixer.Info output = this.outputDevice;
        final boolean enabled = this.micEnabled;
        final double volume = this.micVolume;

        Thread worker = new Thread(() -> {
            synchronized (micLock) {
                if (generation != micGeneration.get()) {
                    return; // une demande plus recente a pris le relais
                }
                if (micWorker != null) {
                    micWorker.stopWorker();
                    micWorker = null;
                }
                if (generation != micGeneration.get()) {
                    return;
                }
                if (!enabled) {
                    publishMicStatus("Renvoi micro inactif.", false);
                    return;
                }
                if (input == null) {
                    publishMicStatus("Renvoi micro : selectionnez une entree micro.", false);
                    return;
                }
                try {
                    MicWorker w = new MicWorker(input, output, volume, this::publishMicStatus);
                    w.start();
                    micWorker = w;
                } catch (Exception e) {
                    publishMicStatus("Renvoi micro impossible : " + e.getMessage(), false);
                }
            }
        }, "soundx-mic-config");
        worker.setDaemon(true);
        worker.start();
    }

    private void publishMicStatus(String message, boolean active) {
        this.micStatus = message;
        MicStatusListener l = this.micStatusListener;
        if (l != null) {
            l.onMicStatus(message, active);
        }
    }

    private static double clampMicVolume(double v) {
        return Math.max(0.0, Math.min(2.0, v));
    }

    // ==================================================================
    //  Lecture des sons
    // ==================================================================

    public void play(File file) {
        try {
            AudioInputStream decoded = openDecoded(file);
            AudioFormat format = decoded.getFormat();

            List<Playback.Target> targets = new ArrayList<>(2);
            // 1. sortie principale (obligatoire)
            targets.add(new Playback.Target(
                    openLine(format, outputDevice), volumeToDecibels(masterVolume)));

            // 2. ecoute locale (casque), si activee et distincte de la sortie principale
            if (monitorEnabled && !sameDevice(monitorDevice, outputDevice)) {
                SourceDataLine monitorLine = tryOpenLine(format, monitorDevice);
                if (monitorLine != null) {
                    targets.add(new Playback.Target(
                            monitorLine, volumeToDecibels(monitorVolume)));
                }
            }

            Playback[] holder = new Playback[1];
            Playback playback = new Playback(decoded, targets, () -> active.remove(holder[0]));
            holder[0] = playback;

            active.add(playback);
            playbackPool.submit(playback);
        } catch (Exception e) {
            System.err.println("Impossible de jouer " + file.getName() + " : " + e.getMessage());
        }
    }

    private static boolean sameDevice(Mixer.Info a, Mixer.Info b) {
        return Objects.equals(a, b);
    }

    /** Bouton d'urgence : coupe toutes les lectures de sons (le renvoi micro continue). */
    public void stopAll() {
        for (Playback p : active) {
            p.requestStop();
        }
        active.clear();
    }

    public int activeCount() {
        return active.size();
    }

    public void shutdown() {
        stopAll();
        micGeneration.incrementAndGet();
        synchronized (micLock) {
            if (micWorker != null) {
                micWorker.stopWorker();
                micWorker = null;
            }
        }
        playbackPool.shutdownNow();
    }

    // ==================================================================
    //  Interne
    // ==================================================================

    private AudioInputStream openDecoded(File file) throws Exception {
        AudioInputStream in = AudioSystem.getAudioInputStream(file.getCanonicalFile());
        AudioFormat base = in.getFormat();

        if (base.getEncoding() == AudioFormat.Encoding.PCM_SIGNED) {
            return in;
        }

        float sampleRate = base.getSampleRate() > 0 ? base.getSampleRate() : 44_100f;
        int channels = base.getChannels() > 0 ? base.getChannels() : 2;

        AudioFormat target = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, 16, channels, channels * 2, sampleRate, false);

        return AudioSystem.getAudioInputStream(target, in);
    }

    /** Ouvre une ligne de sortie sur {@code device} (repli sur la sortie par defaut si besoin). */
    private SourceDataLine openLine(AudioFormat format, Mixer.Info device)
            throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        if (device != null) {
            try {
                Mixer mixer = AudioSystem.getMixer(device);
                if (mixer.isLineSupported(info)) {
                    return (SourceDataLine) mixer.getLine(info);
                }
                System.err.println("Le peripherique \"" + device.getName()
                        + "\" ne supporte pas ce format : repli sur la sortie par defaut.");
            } catch (Exception e) {
                System.err.println("Peripherique \"" + device.getName()
                        + "\" indisponible (" + e.getMessage()
                        + ") : repli sur la sortie par defaut.");
            }
        }
        return (SourceDataLine) AudioSystem.getLine(info);
    }

    /** Comme {@link #openLine} mais renvoie {@code null} en cas d'echec (ecoute locale non bloquante). */
    private SourceDataLine tryOpenLine(AudioFormat format, Mixer.Info device) {
        try {
            return openLine(format, device);
        } catch (Exception e) {
            System.err.println("Ecoute locale impossible : " + e.getMessage());
            return null;
        }
    }

    private static float volumeToDecibels(double volume) {
        if (volume <= 0.0001) {
            return -80f;
        }
        return (float) (20.0 * Math.log10(volume));
    }
}
