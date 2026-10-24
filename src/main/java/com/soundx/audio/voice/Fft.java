package com.soundx.audio.voice;

/**
 * FFT complexe a radix mixte (Cooley-Tukey, decimation temporelle) pour une
 * taille quelconque factorisable en 2/3/4/5 — ici 960 = 4*4*4*3*5.
 * Transformee directe NON normalisee : X[k] = somme x[n] e^(-2i pi kn/N).
 */
final class Fft {

    private final int n;
    private final int[] factors;
    private final float[] twRe;
    private final float[] twIm;

    Fft(int n) {
        this.n = n;
        this.factors = factorize(n);
        this.twRe = new float[n];
        this.twIm = new float[n];
        for (int k = 0; k < n; k++) {
            double phase = -2.0 * Math.PI * k / n;
            twRe[k] = (float) Math.cos(phase);
            twIm[k] = (float) Math.sin(phase);
        }
    }

    int size() {
        return n;
    }

    void forward(float[] inRe, float[] inIm, float[] outRe, float[] outIm) {
        work(inRe, inIm, 0, 1, outRe, outIm, 0, n, 0);
    }

    private void work(float[] inRe, float[] inIm, int inOff, int stride,
                      float[] outRe, float[] outIm, int outOff, int len, int level) {
        int p = factors[level];
        int m = len / p;

        if (m == 1) {
            for (int q = 0; q < p; q++) {
                outRe[outOff + q] = inRe[inOff + q * stride];
                outIm[outOff + q] = inIm[inOff + q * stride];
            }
        } else {
            for (int q = 0; q < p; q++) {
                work(inRe, inIm, inOff + q * stride, stride * p,
                        outRe, outIm, outOff + q * m, m, level + 1);
            }
        }

        // papillon generique : X[u + q1*m] = somme_q2 W^(stride*q2*(u+q1*m)) * Y_q2[u]
        float[] sRe = new float[p];
        float[] sIm = new float[p];
        for (int u = 0; u < m; u++) {
            for (int q = 0; q < p; q++) {
                sRe[q] = outRe[outOff + q * m + u];
                sIm[q] = outIm[outOff + q * m + u];
            }
            for (int q1 = 0; q1 < p; q1++) {
                int k = u + q1 * m;
                float accRe = sRe[0];
                float accIm = sIm[0];
                for (int q2 = 1; q2 < p; q2++) {
                    int t = (int) (((long) stride * q2 * k) % n);
                    float wr = twRe[t];
                    float wi = twIm[t];
                    accRe += sRe[q2] * wr - sIm[q2] * wi;
                    accIm += sRe[q2] * wi + sIm[q2] * wr;
                }
                outRe[outOff + k] = accRe;
                outIm[outOff + k] = accIm;
            }
        }
    }

    private static int[] factorize(int n) {
        int[] tmp = new int[32];
        int count = 0;
        int rest = n;
        for (int f : new int[]{4, 2, 3, 5}) {
            while (rest % f == 0) {
                tmp[count++] = f;
                rest /= f;
            }
        }
        if (rest != 1) {
            throw new IllegalArgumentException("taille FFT non supportee : " + n);
        }
        int[] out = new int[count];
        System.arraycopy(tmp, 0, out, 0, count);
        return out;
    }
}
