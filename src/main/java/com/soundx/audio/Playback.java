package com.soundx.audio;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Une lecture en cours. Le flux PCM decode est pousse vers une ou plusieurs
 * {@link SourceDataLine} en parallele :
 *  - la sortie principale (cable virtuel) ;
 *  - eventuellement l'ecoute locale (casque).
 *
 * Chaque instance tourne sur son propre thread (fourni par l'{@link AudioManager}),
 * ce qui autorise la superposition d'un nombre illimite de sons.
 */
final class Playback implements Runnable {

    /** Une destination physique pour cette lecture + son gain propre. */
    static final class Target {
        final SourceDataLine line;
        final float gainDb;

        Target(SourceDataLine line, float gainDb) {
            this.line = line;
            this.gainDb = gainDb;
        }
    }

    private final AudioInputStream stream;
    private final List<Target> targets;
    private final Runnable onFinished;

    private volatile boolean stopRequested;

    Playback(AudioInputStream stream, List<Target> targets, Runnable onFinished) {
        this.stream = stream;
        this.targets = targets;
        this.onFinished = onFinished;
    }

    /** Coupe immediatement cette lecture (appelable depuis n'importe quel thread). */
    void requestStop() {
        stopRequested = true;
        for (Target t : targets) {
            try {
                t.line.stop();
                t.line.flush();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void run() {
        List<Target> open = new ArrayList<>(targets.size());
        try {
            for (Target t : targets) {
                try {
                    t.line.open(stream.getFormat());
                    applyGain(t);
                    t.line.start();
                    open.add(t);
                } catch (Exception e) {
                    closeQuietly(t.line);
                    System.err.println("Sortie ignoree pour cette lecture : " + e.getMessage());
                }
            }
            if (open.isEmpty()) {
                return;
            }

            byte[] buffer = new byte[8192];
            int n;
            while (!stopRequested && (n = stream.read(buffer, 0, buffer.length)) != -1) {
                for (Target t : open) {
                    try {
                        t.line.write(buffer, 0, n);
                    } catch (Exception ignored) {
                        // cette sortie a lache : on continue avec les autres
                    }
                }
            }
            if (!stopRequested) {
                for (Target t : open) {
                    try {
                        t.line.drain();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Lecture audio interrompue : " + e.getMessage());
        } finally {
            for (Target t : targets) {
                try {
                    t.line.stop();
                } catch (Exception ignored) {
                }
                closeQuietly(t.line);
            }
            try {
                stream.close();
            } catch (Exception ignored) {
            }
            if (onFinished != null) {
                onFinished.run();
            }
        }
    }

    private static void applyGain(Target t) {
        try {
            if (t.line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl c = (FloatControl) t.line.getControl(FloatControl.Type.MASTER_GAIN);
                float v = Math.max(c.getMinimum(), Math.min(c.getMaximum(), t.gainDb));
                c.setValue(v);
            }
        } catch (Exception ignored) {
            // pas de controle de gain sur ce peripherique : volume systeme uniquement
        }
    }

    private static void closeQuietly(SourceDataLine line) {
        try {
            line.close();
        } catch (Exception ignored) {
        }
    }
}
