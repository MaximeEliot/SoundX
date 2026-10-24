package com.soundx.audio.voice;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reseau de neurones de RNNoise v0.1.1 (Jean-Marc Valin / Xiph, licence BSD,
 * voir rnnoise-COPYING.txt) : dense 42->24, GRU VAD 24, GRU bruit 48,
 * GRU debruitage 96, sorties 22 gains de bandes + 1 probabilite de voix.
 *
 * Les poids int8 du modele d'origine sont lus depuis rnnoise-weights.bin,
 * dans l'ordre de declaration de rnn_data.c.
 */
final class RnnModel {

    static final int INPUT_SIZE = 42;
    private static final float WEIGHTS_SCALE = 1f / 256;

    private static final int TANH = 0;
    private static final int SIGMOID = 1;
    private static final int RELU = 2;

    static final class Dense {
        final byte[] bias;
        final byte[] weights;
        final int inputs;
        final int neurons;
        final int activation;

        Dense(byte[] bias, byte[] weights, int inputs, int neurons, int activation) {
            this.bias = bias;
            this.weights = weights;
            this.inputs = inputs;
            this.neurons = neurons;
            this.activation = activation;
        }
    }

    static final class Gru {
        final byte[] bias;
        final byte[] weights;
        final byte[] recurrent;
        final int inputs;
        final int neurons;
        final int activation;

        Gru(byte[] bias, byte[] weights, byte[] recurrent, int inputs, int neurons, int activation) {
            this.bias = bias;
            this.weights = weights;
            this.recurrent = recurrent;
            this.inputs = inputs;
            this.neurons = neurons;
            this.activation = activation;
        }
    }

    final Dense inputDense;
    final Gru vadGru;
    final Gru noiseGru;
    final Gru denoiseGru;
    final Dense denoiseOutput;
    final Dense vadOutput;

    private static volatile RnnModel shared;

    static RnnModel get() {
        RnnModel m = shared;
        if (m == null) {
            synchronized (RnnModel.class) {
                m = shared;
                if (m == null) {
                    m = load();
                    shared = m;
                }
            }
        }
        return m;
    }

    private RnnModel(DataInputStream in) throws IOException {
        byte[] idW = read(in, 1008);
        byte[] idB = read(in, 24);
        byte[] vgW = read(in, 1728);
        byte[] vgR = read(in, 1728);
        byte[] vgB = read(in, 72);
        byte[] ngW = read(in, 12960);
        byte[] ngR = read(in, 6912);
        byte[] ngB = read(in, 144);
        byte[] dgW = read(in, 32832);
        byte[] dgR = read(in, 27648);
        byte[] dgB = read(in, 288);
        byte[] doW = read(in, 2112);
        byte[] doB = read(in, 22);
        byte[] voW = read(in, 24);
        byte[] voB = read(in, 1);
        if (in.read() != -1) {
            throw new IOException("rnnoise-weights.bin : taille inattendue");
        }
        inputDense = new Dense(idB, idW, 42, 24, TANH);
        vadGru = new Gru(vgB, vgW, vgR, 24, 24, RELU);
        noiseGru = new Gru(ngB, ngW, ngR, 90, 48, RELU);
        denoiseGru = new Gru(dgB, dgW, dgR, 114, 96, RELU);
        denoiseOutput = new Dense(doB, doW, 96, 22, SIGMOID);
        vadOutput = new Dense(voB, voW, 24, 1, SIGMOID);
    }

    private static RnnModel load() {
        try (InputStream raw = RnnModel.class.getResourceAsStream("rnnoise-weights.bin")) {
            if (raw == null) {
                throw new IllegalStateException("rnnoise-weights.bin introuvable dans le jar");
            }
            return new RnnModel(new DataInputStream(raw));
        } catch (IOException e) {
            throw new IllegalStateException("lecture du modele RNNoise impossible", e);
        }
    }

    private static byte[] read(DataInputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    // ------------------------------------------------------------------

    /** Etat recurrent d'une instance (un par flux micro). */
    final class State {
        final float[] vad = new float[vadGru.neurons];
        final float[] noise = new float[noiseGru.neurons];
        final float[] denoise = new float[denoiseGru.neurons];

        private final float[] denseOut = new float[inputDense.neurons];
        private final float[] noiseIn = new float[noiseGru.inputs];
        private final float[] denoiseIn = new float[denoiseGru.inputs];
        private final float[] vadOut = new float[1];

        /** Calcule les 22 gains de bandes ; renvoie la probabilite de voix. */
        float compute(float[] gains, float[] features) {
            dense(inputDense, denseOut, features);
            gru(vadGru, vad, denseOut);
            dense(vadOutput, vadOut, vad);

            int k = 0;
            for (float v : denseOut) noiseIn[k++] = v;
            for (float v : vad) noiseIn[k++] = v;
            for (int i = 0; i < INPUT_SIZE; i++) noiseIn[k++] = features[i];
            gru(noiseGru, noise, noiseIn);

            k = 0;
            for (float v : vad) denoiseIn[k++] = v;
            for (float v : noise) denoiseIn[k++] = v;
            for (int i = 0; i < INPUT_SIZE; i++) denoiseIn[k++] = features[i];
            gru(denoiseGru, denoise, denoiseIn);
            dense(denoiseOutput, gains, denoise);
            return vadOut[0];
        }
    }

    private static void dense(Dense layer, float[] out, float[] in) {
        int n = layer.neurons;
        for (int i = 0; i < n; i++) {
            float sum = layer.bias[i];
            for (int j = 0; j < layer.inputs; j++) {
                sum += layer.weights[j * n + i] * in[j];
            }
            out[i] = activate(layer.activation, WEIGHTS_SCALE * sum);
        }
    }

    private static void gru(Gru g, float[] state, float[] in) {
        int n = g.neurons;
        int m = g.inputs;
        int stride = 3 * n;
        float[] z = new float[n];
        float[] r = new float[n];
        float[] h = new float[n];
        for (int i = 0; i < n; i++) {
            float sum = g.bias[i];
            for (int j = 0; j < m; j++) sum += g.weights[j * stride + i] * in[j];
            for (int j = 0; j < n; j++) sum += g.recurrent[j * stride + i] * state[j];
            z[i] = sigmoid(WEIGHTS_SCALE * sum);
        }
        for (int i = 0; i < n; i++) {
            float sum = g.bias[n + i];
            for (int j = 0; j < m; j++) sum += g.weights[n + j * stride + i] * in[j];
            for (int j = 0; j < n; j++) sum += g.recurrent[n + j * stride + i] * state[j];
            r[i] = sigmoid(WEIGHTS_SCALE * sum);
        }
        for (int i = 0; i < n; i++) {
            float sum = g.bias[2 * n + i];
            for (int j = 0; j < m; j++) sum += g.weights[2 * n + j * stride + i] * in[j];
            for (int j = 0; j < n; j++) sum += g.recurrent[2 * n + j * stride + i] * state[j] * r[j];
            h[i] = z[i] * state[i] + (1 - z[i]) * activate(g.activation, WEIGHTS_SCALE * sum);
        }
        System.arraycopy(h, 0, state, 0, n);
    }

    private static float activate(int activation, float x) {
        switch (activation) {
            case SIGMOID:
                return sigmoid(x);
            case TANH:
                return (float) Math.tanh(x);
            default:
                return x < 0 ? 0 : x;
        }
    }

    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }
}
