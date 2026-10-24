package com.soundx.audio.voice;

/**
 * Isolation de la voix pour un flux micro PCM 16 bits little-endian
 * (mono ou stereo, 48 kHz de preference) — traitement en place.
 *
 * Deux etages :
 *  1. RNNoise retire le bruit (clavier, souffle, ventilateur) sous la voix ;
 *  2. une porte pilotee par la detection de voix de RNNoise coupe tout ce qui
 *     n'est pas de la parole (frappes entre les phrases). Elle regarde
 *     {@link #LOOKAHEAD} trames en avance pour ne pas manger le debut des mots.
 *
 * Latence ajoutee : 10 ms (RNNoise) + (1 + LOOKAHEAD) x 10 ms = 40 ms.
 */
public final class VoiceIsolator {

    private static final int FRAME = RnNoise.FRAME_SIZE;
    private static final float RATE = 48_000f;

    private static final int LOOKAHEAD = 2;
    private static final float OPEN_THRESHOLD = 0.5f;
    private static final int MIN_VOICED_FRAMES = 2;
    private static final int HOLD_FRAMES = 25;       // 250 ms apres la derniere syllabe
    private static final float RELEASE_PER_FRAME = 1f / 8; // fermeture en ~80 ms

    private final int channels;
    private final boolean resample;
    private final RnNoise denoiser = new RnNoise();

    // trames 48 kHz en attente de sortie (file circulaire de LOOKAHEAD+1 trames)
    private final float[][] pending = new float[LOOKAHEAD + 1][FRAME];
    private final float[] pendingVad = new float[LOOKAHEAD + 1];
    private int pendingCount;
    private int pendingHead;

    private final float[] inFrame = new float[FRAME];
    private int inFill;

    private float gateGain;
    private int hold;
    private volatile float voiceProbability;

    // sortie au rythme du peripherique
    private final float[] outFifo = new float[1 << 15];
    private int outRead;
    private int outWrite;

    private final LinearResampler toModel;
    private final LinearResampler toDevice;
    private final FloatSink modelSink = this::pushModelSample;
    private final FloatSink deviceSink = this::pushOut;

    public VoiceIsolator(float sampleRate, int channels) {
        this.channels = Math.max(1, channels);
        this.resample = Math.abs(sampleRate - RATE) > 1f;
        this.toModel = new LinearResampler(sampleRate / RATE);
        this.toDevice = new LinearResampler(RATE / sampleRate);

        int prime = (int) Math.ceil((LOOKAHEAD + 1) * FRAME * sampleRate / RATE) + 2;
        for (int i = 0; i < prime; i++) {
            pushOut(0f);
        }
    }

    /** Derniere probabilite de voix estimee (0..1). */
    public float voiceProbability() {
        return voiceProbability;
    }

    /** Traite {@code len} octets PCM16LE entrelaces, en place. */
    public void process(byte[] buf, int len) {
        int frameBytes = 2 * channels;
        int frames = len / frameBytes;

        for (int f = 0; f < frames; f++) {
            int base = f * frameBytes;
            float sum = 0;
            for (int c = 0; c < channels; c++) {
                int i = base + 2 * c;
                sum += (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
            }
            float mono = sum / channels;
            if (resample) {
                toModel.push(mono, modelSink);
            } else {
                pushModelSample(mono);
            }
        }

        for (int f = 0; f < frames; f++) {
            float v = outRead != outWrite ? popOut() : 0f;
            int s = Math.round(v);
            if (s > 32767) {
                s = 32767;
            } else if (s < -32768) {
                s = -32768;
            }
            int base = f * frameBytes;
            for (int c = 0; c < channels; c++) {
                int i = base + 2 * c;
                buf[i] = (byte) s;
                buf[i + 1] = (byte) (s >> 8);
            }
        }
    }

    // ------------------------------------------------------------------

    private void pushModelSample(float v) {
        inFrame[inFill++] = v;
        if (inFill == FRAME) {
            inFill = 0;
            onFrame();
        }
    }

    private void onFrame() {
        int slot = (pendingHead + pendingCount) % pending.length;
        float vad = denoiser.processFrame(pending[slot], inFrame);
        pendingVad[slot] = vad;
        voiceProbability = vad;
        pendingCount++;
        if (pendingCount <= LOOKAHEAD) {
            return;
        }

        // la plus ancienne trame sort, decidee avec LOOKAHEAD trames d'avance
        int voiced = 0;
        for (int k = 0; k < pendingCount; k++) {
            if (pendingVad[(pendingHead + k) % pending.length] >= OPEN_THRESHOLD) {
                voiced++;
            }
        }
        float target;
        // une trame "voix" isolee = souvent un clic : il en faut au moins 2
        if (voiced >= MIN_VOICED_FRAMES) {
            hold = HOLD_FRAMES;
            target = 1f;
        } else if (hold > 0) {
            hold--;
            target = 1f;
        } else {
            target = Math.max(0f, gateGain - RELEASE_PER_FRAME);
        }

        float[] frame = pending[pendingHead];
        float start = gateGain;
        for (int i = 0; i < FRAME; i++) {
            float gain = start + (target - start) * (i + 1) / FRAME;
            float v = frame[i] * gain;
            if (resample) {
                toDevice.push(v, deviceSink);
            } else {
                pushOut(v);
            }
        }
        gateGain = target;
        pendingHead = (pendingHead + 1) % pending.length;
        pendingCount--;
    }

    private void pushOut(float v) {
        int next = (outWrite + 1) & (outFifo.length - 1);
        if (next == outRead) {
            outRead = (outRead + 1) & (outFifo.length - 1); // ne devrait pas arriver
        }
        outFifo[outWrite] = v;
        outWrite = next;
    }

    private float popOut() {
        float v = outFifo[outRead];
        outRead = (outRead + 1) & (outFifo.length - 1);
        return v;
    }

    // ------------------------------------------------------------------

    private interface FloatSink {
        void accept(float v);
    }

    /** Reechantillonneur lineaire en flux (suffisant pour la voix). */
    private static final class LinearResampler {
        private final double step;
        private double pos;
        private float prev;
        private boolean primed;

        /** @param step echantillons d'entree consommes par echantillon de sortie */
        LinearResampler(double step) {
            this.step = step;
        }

        void push(float cur, FloatSink out) {
            if (!primed) {
                prev = cur;
                primed = true;
                return;
            }
            while (pos < 1.0) {
                out.accept(prev + (cur - prev) * (float) pos);
                pos += step;
            }
            pos -= 1.0;
            prev = cur;
        }
    }
}
