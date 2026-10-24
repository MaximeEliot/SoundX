package com.soundx.audio;

import com.soundx.audio.voice.VoiceIsolator;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.function.BiConsumer;

/**
 * Renvoi micro -> sortie facon Voicemod : capture en continu le peripherique
 * d'entree choisi et le reinjecte dans le peripherique de sortie (typiquement
 * le cable audio virtuel), ou il se melange aux sons de la soundboard.
 *
 * Une instance = une session active. {@link #stopWorker()} la termine
 * definitivement ; on en cree une nouvelle a chaque changement de reglage.
 */
final class MicWorker {

    private final TargetDataLine in;
    private final SourceDataLine out;
    private final AudioFormat format;
    private final Thread thread;
    private final BiConsumer<String, Boolean> status;

    private volatile boolean running = true;
    /** Gain applique en logiciel, 0.0 .. 2.0 (1.0 = neutre). */
    volatile double gain;
    /** Niveau crete du signal capture (0.0 .. 1.0), AVANT gain, avec decroissance. */
    volatile double level;
    /** Isolation de la voix (RNNoise + porte), basculable a chaud. */
    volatile boolean isolateVoice;
    /** Cree et utilise uniquement par le thread micro. */
    private VoiceIsolator isolator;

    MicWorker(Mixer.Info inputInfo, Mixer.Info outputInfo, double gain, boolean isolateVoice,
              BiConsumer<String, Boolean> status) throws LineUnavailableException {
        this.gain = gain;
        this.isolateVoice = isolateVoice;
        this.status = status;

        AudioFormat fmt = negotiate(inputInfo, outputInfo);
        if (fmt == null) {
            throw new LineUnavailableException(
                    "aucun format audio commun entre le micro et la sortie");
        }
        this.format = fmt;

        Mixer inputMixer = AudioSystem.getMixer(inputInfo);
        this.in = (TargetDataLine) inputMixer.getLine(new DataLine.Info(TargetDataLine.class, fmt));

        DataLine.Info outLineInfo = new DataLine.Info(SourceDataLine.class, fmt);
        if (outputInfo != null && AudioSystem.getMixer(outputInfo).isLineSupported(outLineInfo)) {
            this.out = (SourceDataLine) AudioSystem.getMixer(outputInfo).getLine(outLineInfo);
        } else {
            this.out = (SourceDataLine) AudioSystem.getLine(outLineInfo);
        }

        int lineBuffer = format.getFrameSize() * Math.round(format.getSampleRate() / 10f); // ~100 ms
        in.open(format, lineBuffer);
        out.open(format, lineBuffer);

        this.thread = new Thread(this::loop, "soundx-mic");
        this.thread.setDaemon(true);
    }

    void start() {
        in.start();
        out.start();
        thread.start();
        status.accept("Renvoi micro actif  (" + describe(format) + ")", true);
    }

    void stopWorker() {
        running = false;
        closeQuietly(in);
        try {
            out.stop();
            out.flush();
        } catch (Exception ignored) {
        }
        closeQuietly(out);
        try {
            thread.join(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean isAlive() {
        return running && thread.isAlive();
    }

    // ------------------------------------------------------------------

    private void loop() {
        int chunk = format.getFrameSize() * Math.round(format.getSampleRate() / 50f); // ~20 ms
        byte[] buffer = new byte[Math.max(chunk, 1024)];
        try {
            while (running) {
                int n = in.read(buffer, 0, buffer.length);
                if (n <= 0) {
                    continue;
                }
                double peak = peak16LE(buffer, n);
                level = Math.max(peak, level * 0.75); // attaque rapide, chute douce

                if (isolateVoice) {
                    if (isolator == null) {
                        isolator = new VoiceIsolator(format.getSampleRate(), format.getChannels());
                    }
                    isolator.process(buffer, n);
                } else {
                    isolator = null; // repart d'un etat propre a la prochaine activation
                }

                double g = gain;
                if (Math.abs(g - 1.0) > 0.01) {
                    applyGain16LE(buffer, n, g);
                }
                out.write(buffer, 0, n);
            }
        } catch (Exception e) {
            if (running) {
                status.accept("Renvoi micro interrompu : " + e.getMessage(), false);
            }
        }
    }

    /** Niveau crete (0..1) d'un bloc PCM 16 bits little-endian. */
    private static double peak16LE(byte[] buffer, int len) {
        int max = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            int abs = Math.abs(sample);
            if (abs > max) {
                max = abs;
            }
        }
        return max / 32768.0;
    }

    /** Gain logiciel sur des echantillons PCM 16 bits little-endian. */
    private static void applyGain16LE(byte[] buffer, int len, double gain) {
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            sample = (int) Math.round(sample * gain);
            if (sample > 32767) {
                sample = 32767;
            } else if (sample < -32768) {
                sample = -32768;
            }
            buffer[i] = (byte) sample;
            buffer[i + 1] = (byte) (sample >> 8);
        }
    }

    /** Cherche un format PCM 16 bits accepte a la fois par le micro et la sortie. */
    private static AudioFormat negotiate(Mixer.Info inputInfo, Mixer.Info outputInfo) {
        float[] rates = {48_000f, 44_100f};
        int[] channelCounts = {2, 1};

        Mixer inputMixer = AudioSystem.getMixer(inputInfo);
        for (float rate : rates) {
            for (int channels : channelCounts) {
                AudioFormat f = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels,
                        channels * 2, rate, false);

                boolean inputOk = inputMixer.isLineSupported(
                        new DataLine.Info(TargetDataLine.class, f));

                DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, f);
                boolean outputOk = outputInfo != null
                        ? AudioSystem.getMixer(outputInfo).isLineSupported(sourceInfo)
                        : AudioSystem.isLineSupported(sourceInfo);

                if (inputOk && outputOk) {
                    return f;
                }
            }
        }
        return null;
    }

    private static String describe(AudioFormat f) {
        return Math.round(f.getSampleRate() / 1000f) + " kHz / "
                + (f.getChannels() == 1 ? "mono" : "stereo");
    }

    private static void closeQuietly(javax.sound.sampled.Line line) {
        try {
            line.close();
        } catch (Exception ignored) {
        }
    }
}
