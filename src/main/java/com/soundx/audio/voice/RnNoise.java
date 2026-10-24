package com.soundx.audio.voice;

/**
 * Portage Java de denoise.c / pitch.c / celt_lpc.c de RNNoise v0.1.1
 * (Xiph, licence BSD, voir rnnoise-COPYING.txt), version flottante.
 *
 * Traite des trames de {@link #FRAME_SIZE} echantillons mono a 48 kHz,
 * a l'echelle int16 (-32768 .. 32767). La sortie est retardee d'une trame.
 */
final class RnNoise {

    static final int FRAME_SIZE = 480;
    private static final int WINDOW_SIZE = 2 * FRAME_SIZE;
    private static final int FREQ_SIZE = FRAME_SIZE + 1;

    private static final int PITCH_MIN_PERIOD = 60;
    private static final int PITCH_MAX_PERIOD = 768;
    private static final int PITCH_FRAME_SIZE = 960;
    private static final int PITCH_BUF_SIZE = PITCH_MAX_PERIOD + PITCH_FRAME_SIZE;

    private static final int NB_BANDS = 22;
    private static final int CEPS_MEM = 8;
    private static final int NB_DELTA_CEPS = 6;
    private static final int NB_FEATURES = NB_BANDS + 3 * NB_DELTA_CEPS + 2;

    private static final int[] EBAND_5MS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 34, 40, 48, 60, 78, 100
    };
    private static final int SHIFT = 2; // FRAME_SIZE_SHIFT

    private static final float[] HALF_WINDOW = new float[FRAME_SIZE];
    private static final float[] DCT_TABLE = new float[NB_BANDS * NB_BANDS];
    private static final Fft FFT = new Fft(WINDOW_SIZE);

    static {
        for (int i = 0; i < FRAME_SIZE; i++) {
            double s = Math.sin(.5 * Math.PI * (i + .5) / FRAME_SIZE);
            HALF_WINDOW[i] = (float) Math.sin(.5 * Math.PI * s * s);
        }
        for (int i = 0; i < NB_BANDS; i++) {
            for (int j = 0; j < NB_BANDS; j++) {
                double v = Math.cos((i + .5) * j * Math.PI / NB_BANDS);
                if (j == 0) {
                    v *= Math.sqrt(.5);
                }
                DCT_TABLE[i * NB_BANDS + j] = (float) v;
            }
        }
    }

    // --- etat ---
    private final float[] analysisMem = new float[FRAME_SIZE];
    private final float[][] cepstralMem = new float[CEPS_MEM][NB_BANDS];
    private int memId;
    private final float[] synthesisMem = new float[FRAME_SIZE];
    private final float[] pitchBuf = new float[PITCH_BUF_SIZE];
    private float lastGain;
    private int lastPeriod;
    private final float[] memHpX = new float[2];
    private final float[] lastG = new float[NB_BANDS];
    private final RnnModel.State rnn = RnnModel.get().new State();

    // --- tampons de travail ---
    private final float[] xRe = new float[FREQ_SIZE];
    private final float[] xIm = new float[FREQ_SIZE];
    private final float[] pRe = new float[FREQ_SIZE];
    private final float[] pIm = new float[FREQ_SIZE];
    private final float[] ex = new float[NB_BANDS];
    private final float[] ep = new float[NB_BANDS];
    private final float[] exp = new float[NB_BANDS];
    private final float[] features = new float[NB_FEATURES];
    private final float[] g = new float[NB_BANDS];
    private final float[] hp = new float[FRAME_SIZE];

    private final float[] fftInRe = new float[WINDOW_SIZE];
    private final float[] fftInIm = new float[WINDOW_SIZE];
    private final float[] fftOutRe = new float[WINDOW_SIZE];
    private final float[] fftOutIm = new float[WINDOW_SIZE];

    /**
     * Debruite une trame. {@code in} et {@code out} font {@link #FRAME_SIZE}
     * echantillons (ils peuvent etre le meme tableau).
     * @return probabilite de presence de voix (0..1)
     */
    float processFrame(float[] out, float[] in) {
        biquadHighPass(hp, in);
        boolean silence = computeFrameFeatures(hp);
        float vad = 0;

        if (!silence) {
            vad = rnn.compute(g, features);
            pitchFilter();
            for (int i = 0; i < NB_BANDS; i++) {
                g[i] = Math.max(g[i], .6f * lastG[i]);
                lastG[i] = g[i];
            }
            float[] gf = new float[FREQ_SIZE];
            interpBandGain(gf, g);
            for (int i = 0; i < FREQ_SIZE; i++) {
                xRe[i] *= gf[i];
                xIm[i] *= gf[i];
            }
        }
        frameSynthesis(out);
        return vad;
    }

    // ==================================================================

    private void biquadHighPass(float[] y, float[] x) {
        final float b0 = -2, b1 = 1;
        final float a0 = -1.99599f, a1 = 0.99600f;
        for (int i = 0; i < FRAME_SIZE; i++) {
            float xi = x[i];
            float yi = x[i] + memHpX[0];
            memHpX[0] = (float) (memHpX[1] + (b0 * (double) xi - a0 * (double) yi));
            memHpX[1] = (float) (b1 * (double) xi - a1 * (double) yi);
            y[i] = yi;
        }
    }

    private void frameAnalysis(float[] in) {
        float[] x = new float[WINDOW_SIZE];
        System.arraycopy(analysisMem, 0, x, 0, FRAME_SIZE);
        System.arraycopy(in, 0, x, FRAME_SIZE, FRAME_SIZE);
        System.arraycopy(in, 0, analysisMem, 0, FRAME_SIZE);
        applyWindow(x);
        forwardTransform(xRe, xIm, x);
        computeBandEnergy(ex, xRe, xIm);
    }

    private boolean computeFrameFeatures(float[] in) {
        frameAnalysis(in);

        System.arraycopy(pitchBuf, FRAME_SIZE, pitchBuf, 0, PITCH_BUF_SIZE - FRAME_SIZE);
        System.arraycopy(in, 0, pitchBuf, PITCH_BUF_SIZE - FRAME_SIZE, FRAME_SIZE);

        float[] pitchLp = new float[PITCH_BUF_SIZE >> 1];
        pitchDownsample(pitchBuf, pitchLp, PITCH_BUF_SIZE);
        int pitchIndex = pitchSearch(pitchLp, PITCH_MAX_PERIOD >> 1, pitchLp,
                PITCH_FRAME_SIZE, PITCH_MAX_PERIOD - 3 * PITCH_MIN_PERIOD);
        pitchIndex = PITCH_MAX_PERIOD - pitchIndex;

        int[] t0 = {pitchIndex};
        float gain = removeDoubling(pitchLp, PITCH_MAX_PERIOD, PITCH_MIN_PERIOD,
                PITCH_FRAME_SIZE, t0, lastPeriod, lastGain);
        pitchIndex = t0[0];
        lastPeriod = pitchIndex;
        lastGain = gain;

        float[] p = new float[WINDOW_SIZE];
        System.arraycopy(pitchBuf, PITCH_BUF_SIZE - WINDOW_SIZE - pitchIndex, p, 0, WINDOW_SIZE);
        applyWindow(p);
        forwardTransform(pRe, pIm, p);
        computeBandEnergy(ep, pRe, pIm);
        computeBandCorr(exp, xRe, xIm, pRe, pIm);
        for (int i = 0; i < NB_BANDS; i++) {
            exp[i] = (float) (exp[i] / Math.sqrt(.001 + ex[i] * ep[i]));
        }
        float[] tmp = new float[NB_BANDS];
        dct(tmp, exp);
        for (int i = 0; i < NB_DELTA_CEPS; i++) {
            features[NB_BANDS + 2 * NB_DELTA_CEPS + i] = tmp[i];
        }
        features[NB_BANDS + 2 * NB_DELTA_CEPS] -= 1.3f;
        features[NB_BANDS + 2 * NB_DELTA_CEPS + 1] -= 0.9f;
        features[NB_BANDS + 3 * NB_DELTA_CEPS] = .01f * (pitchIndex - 300);

        float[] ly = new float[NB_BANDS];
        float logMax = -2;
        float follow = -2;
        float e = 0;
        for (int i = 0; i < NB_BANDS; i++) {
            ly[i] = (float) Math.log10(1e-2 + ex[i]);
            ly[i] = Math.max(logMax - 7, Math.max(follow - 1.5f, ly[i]));
            logMax = Math.max(logMax, ly[i]);
            follow = Math.max(follow - 1.5f, ly[i]);
            e += ex[i];
        }
        if (e < 0.04f) {
            java.util.Arrays.fill(features, 0f);
            return true;
        }
        dct(features, ly);
        features[0] -= 12;
        features[1] -= 4;

        float[] ceps0 = cepstralMem[memId];
        float[] ceps1 = cepstralMem[(memId + CEPS_MEM - 1) % CEPS_MEM];
        float[] ceps2 = cepstralMem[(memId + CEPS_MEM - 2) % CEPS_MEM];
        System.arraycopy(features, 0, ceps0, 0, NB_BANDS);
        memId++;
        for (int i = 0; i < NB_DELTA_CEPS; i++) {
            features[i] = ceps0[i] + ceps1[i] + ceps2[i];
            features[NB_BANDS + i] = ceps0[i] - ceps2[i];
            features[NB_BANDS + NB_DELTA_CEPS + i] = ceps0[i] - 2 * ceps1[i] + ceps2[i];
        }
        if (memId == CEPS_MEM) {
            memId = 0;
        }
        float specVariability = 0;
        for (int i = 0; i < CEPS_MEM; i++) {
            float minDist = 1e15f;
            for (int j = 0; j < CEPS_MEM; j++) {
                if (j == i) {
                    continue;
                }
                float dist = 0;
                for (int k = 0; k < NB_BANDS; k++) {
                    float d = cepstralMem[i][k] - cepstralMem[j][k];
                    dist += d * d;
                }
                minDist = Math.min(minDist, dist);
            }
            specVariability += minDist;
        }
        features[NB_BANDS + 3 * NB_DELTA_CEPS + 1] = specVariability / CEPS_MEM - 2.1f;
        return false;
    }

    private void frameSynthesis(float[] out) {
        float[] x = new float[WINDOW_SIZE];
        inverseTransform(x, xRe, xIm);
        applyWindow(x);
        for (int i = 0; i < FRAME_SIZE; i++) {
            out[i] = x[i] + synthesisMem[i];
        }
        System.arraycopy(x, FRAME_SIZE, synthesisMem, 0, FRAME_SIZE);
    }

    private void pitchFilter() {
        float[] r = new float[NB_BANDS];
        for (int i = 0; i < NB_BANDS; i++) {
            float v;
            if (exp[i] > g[i]) {
                v = 1;
            } else {
                float e2 = exp[i] * exp[i];
                float g2 = g[i] * g[i];
                v = e2 * (1 - g2) / (.001f + g2 * (1 - e2));
            }
            v = (float) Math.sqrt(Math.min(1, Math.max(0, v)));
            v *= (float) Math.sqrt(ex[i] / (1e-8 + ep[i]));
            r[i] = v;
        }
        float[] rf = new float[FREQ_SIZE];
        interpBandGain(rf, r);
        for (int i = 0; i < FREQ_SIZE; i++) {
            xRe[i] += rf[i] * pRe[i];
            xIm[i] += rf[i] * pIm[i];
        }
        float[] newE = new float[NB_BANDS];
        computeBandEnergy(newE, xRe, xIm);
        float[] norm = new float[NB_BANDS];
        for (int i = 0; i < NB_BANDS; i++) {
            norm[i] = (float) Math.sqrt(ex[i] / (1e-8 + newE[i]));
        }
        float[] normf = new float[FREQ_SIZE];
        interpBandGain(normf, norm);
        for (int i = 0; i < FREQ_SIZE; i++) {
            xRe[i] *= normf[i];
            xIm[i] *= normf[i];
        }
    }

    // ==================================================================
    //  Spectre
    // ==================================================================

    private static void applyWindow(float[] x) {
        for (int i = 0; i < FRAME_SIZE; i++) {
            x[i] *= HALF_WINDOW[i];
            x[WINDOW_SIZE - 1 - i] *= HALF_WINDOW[i];
        }
    }

    /** FFT directe normalisee par 1/N (comme rnn_fft), on garde FREQ_SIZE bins. */
    private void forwardTransform(float[] outRe, float[] outIm, float[] in) {
        System.arraycopy(in, 0, fftInRe, 0, WINDOW_SIZE);
        java.util.Arrays.fill(fftInIm, 0f);
        FFT.forward(fftInRe, fftInIm, fftOutRe, fftOutIm);
        float scale = 1f / WINDOW_SIZE;
        for (int i = 0; i < FREQ_SIZE; i++) {
            outRe[i] = fftOutRe[i] * scale;
            outIm[i] = fftOutIm[i] * scale;
        }
    }

    private void inverseTransform(float[] out, float[] inRe, float[] inIm) {
        for (int i = 0; i < FREQ_SIZE; i++) {
            fftInRe[i] = inRe[i];
            fftInIm[i] = inIm[i];
        }
        for (int i = FREQ_SIZE; i < WINDOW_SIZE; i++) {
            fftInRe[i] = fftInRe[WINDOW_SIZE - i];
            fftInIm[i] = -fftInIm[WINDOW_SIZE - i];
        }
        FFT.forward(fftInRe, fftInIm, fftOutRe, fftOutIm);
        // FFT directe puis ordre inverse = IFFT (rnn_fft : 1/N puis *N, qui s'annulent)
        out[0] = fftOutRe[0];
        for (int i = 1; i < WINDOW_SIZE; i++) {
            out[i] = fftOutRe[WINDOW_SIZE - i];
        }
    }

    private static void computeBandEnergy(float[] bandE, float[] re, float[] im) {
        float[] sum = new float[NB_BANDS];
        for (int i = 0; i < NB_BANDS - 1; i++) {
            int bandSize = (EBAND_5MS[i + 1] - EBAND_5MS[i]) << SHIFT;
            int base = EBAND_5MS[i] << SHIFT;
            for (int j = 0; j < bandSize; j++) {
                float frac = (float) j / bandSize;
                float tmp = re[base + j] * re[base + j] + im[base + j] * im[base + j];
                sum[i] += (1 - frac) * tmp;
                sum[i + 1] += frac * tmp;
            }
        }
        sum[0] *= 2;
        sum[NB_BANDS - 1] *= 2;
        System.arraycopy(sum, 0, bandE, 0, NB_BANDS);
    }

    private static void computeBandCorr(float[] bandE, float[] xr, float[] xi, float[] pr, float[] pi) {
        float[] sum = new float[NB_BANDS];
        for (int i = 0; i < NB_BANDS - 1; i++) {
            int bandSize = (EBAND_5MS[i + 1] - EBAND_5MS[i]) << SHIFT;
            int base = EBAND_5MS[i] << SHIFT;
            for (int j = 0; j < bandSize; j++) {
                float frac = (float) j / bandSize;
                float tmp = xr[base + j] * pr[base + j] + xi[base + j] * pi[base + j];
                sum[i] += (1 - frac) * tmp;
                sum[i + 1] += frac * tmp;
            }
        }
        sum[0] *= 2;
        sum[NB_BANDS - 1] *= 2;
        System.arraycopy(sum, 0, bandE, 0, NB_BANDS);
    }

    private static void interpBandGain(float[] gOut, float[] bandE) {
        for (int i = 0; i < NB_BANDS - 1; i++) {
            int bandSize = (EBAND_5MS[i + 1] - EBAND_5MS[i]) << SHIFT;
            int base = EBAND_5MS[i] << SHIFT;
            for (int j = 0; j < bandSize; j++) {
                float frac = (float) j / bandSize;
                gOut[base + j] = (1 - frac) * bandE[i] + frac * bandE[i + 1];
            }
        }
    }

    private static void dct(float[] out, float[] in) {
        float norm = (float) Math.sqrt(2. / 22);
        for (int i = 0; i < NB_BANDS; i++) {
            float sum = 0;
            for (int j = 0; j < NB_BANDS; j++) {
                sum += in[j] * DCT_TABLE[j * NB_BANDS + i];
            }
            out[i] = sum * norm;
        }
    }

    // ==================================================================
    //  Analyse de pitch (pitch.c / celt_lpc.c, version flottante)
    // ==================================================================

    private static void pitchDownsample(float[] x, float[] xLp, int len) {
        int half = len >> 1;
        for (int i = 1; i < half; i++) {
            xLp[i] = .5f * (.5f * (x[2 * i - 1] + x[2 * i + 1]) + x[2 * i]);
        }
        xLp[0] = .5f * (.5f * x[1] + x[0]);

        float[] ac = new float[5];
        for (int k = 0; k <= 4; k++) {
            float d = 0;
            for (int i = k; i < half; i++) {
                d += xLp[i] * xLp[i - k];
            }
            ac[k] = d;
        }
        ac[0] *= 1.0001f;
        for (int i = 1; i <= 4; i++) {
            ac[i] -= ac[i] * (.008f * i) * (.008f * i);
        }

        float[] lpc = new float[4];
        lpc(lpc, ac, 4);
        float tmp = 1f;
        for (int i = 0; i < 4; i++) {
            tmp *= .9f;
            lpc[i] *= tmp;
        }
        final float c1 = .8f;
        float[] lpc2 = {
                lpc[0] + .8f,
                lpc[1] + c1 * lpc[0],
                lpc[2] + c1 * lpc[1],
                lpc[3] + c1 * lpc[2],
                c1 * lpc[3]
        };
        fir5(xLp, lpc2, half);
    }

    private static void fir5(float[] x, float[] num, int n) {
        float mem0 = 0, mem1 = 0, mem2 = 0, mem3 = 0, mem4 = 0;
        for (int i = 0; i < n; i++) {
            float xi = x[i];
            float sum = xi + num[0] * mem0 + num[1] * mem1 + num[2] * mem2
                    + num[3] * mem3 + num[4] * mem4;
            mem4 = mem3;
            mem3 = mem2;
            mem2 = mem1;
            mem1 = mem0;
            mem0 = xi;
            x[i] = sum;
        }
    }

    private static void lpc(float[] lpc, float[] ac, int p) {
        float error = ac[0];
        java.util.Arrays.fill(lpc, 0f);
        if (ac[0] == 0) {
            return;
        }
        for (int i = 0; i < p; i++) {
            float rr = 0;
            for (int j = 0; j < i; j++) {
                rr += lpc[j] * ac[i - j];
            }
            rr += ac[i + 1];
            float r = -rr / error;
            lpc[i] = r;
            for (int j = 0; j < (i + 1) >> 1; j++) {
                float tmp1 = lpc[j];
                float tmp2 = lpc[i - 1 - j];
                lpc[j] = tmp1 + r * tmp2;
                lpc[i - 1 - j] = tmp2 + r * tmp1;
            }
            error = error - r * r * error;
            if (error < .001f * ac[0]) {
                break;
            }
        }
    }

    private static float innerProd(float[] a, int aOff, float[] b, int bOff, int n) {
        float sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[aOff + i] * b[bOff + i];
        }
        return sum;
    }

    /** Renvoie la periode trouvee ; {@code x} commence a {@code xOff} dans {@code xBuf}. */
    private static int pitchSearch(float[] xBuf, int xOff, float[] y, int len, int maxPitch) {
        int lag = len + maxPitch;
        float[] xLp4 = new float[len >> 2];
        float[] yLp4 = new float[lag >> 2];
        float[] xcorr = new float[maxPitch >> 1];
        int[] bestPitch = {0, 0};

        for (int j = 0; j < len >> 2; j++) {
            xLp4[j] = xBuf[xOff + 2 * j];
        }
        for (int j = 0; j < lag >> 2; j++) {
            yLp4[j] = y[2 * j];
        }

        // recherche grossiere (decimation x4)
        for (int i = 0; i < maxPitch >> 2; i++) {
            xcorr[i] = innerProd(xLp4, 0, yLp4, i, len >> 2);
        }
        findBestPitch(xcorr, yLp4, len >> 2, maxPitch >> 2, bestPitch);

        // recherche fine (decimation x2)
        for (int i = 0; i < maxPitch >> 1; i++) {
            xcorr[i] = 0;
            if (Math.abs(i - 2 * bestPitch[0]) > 2 && Math.abs(i - 2 * bestPitch[1]) > 2) {
                continue;
            }
            float sum = innerProd(xBuf, xOff, y, i, len >> 1);
            xcorr[i] = Math.max(-1, sum);
        }
        findBestPitch(xcorr, y, len >> 1, maxPitch >> 1, bestPitch);

        int offset = 0;
        if (bestPitch[0] > 0 && bestPitch[0] < (maxPitch >> 1) - 1) {
            float a = xcorr[bestPitch[0] - 1];
            float b = xcorr[bestPitch[0]];
            float c = xcorr[bestPitch[0] + 1];
            if ((c - a) > .7f * (b - a)) {
                offset = 1;
            } else if ((a - c) > .7f * (b - c)) {
                offset = -1;
            }
        }
        return 2 * bestPitch[0] - offset;
    }

    private static void findBestPitch(float[] xcorr, float[] y, int len, int maxPitch, int[] bestPitch) {
        float syy = 1;
        float[] bestNum = {-1, -1};
        float[] bestDen = {0, 0};
        bestPitch[0] = 0;
        bestPitch[1] = 1;
        for (int j = 0; j < len; j++) {
            syy += y[j] * y[j];
        }
        for (int i = 0; i < maxPitch; i++) {
            if (xcorr[i] > 0) {
                float xcorr16 = xcorr[i] * 1e-12f;
                float num = xcorr16 * xcorr16;
                if (num * bestDen[1] > bestNum[1] * syy) {
                    if (num * bestDen[0] > bestNum[0] * syy) {
                        bestNum[1] = bestNum[0];
                        bestDen[1] = bestDen[0];
                        bestPitch[1] = bestPitch[0];
                        bestNum[0] = num;
                        bestDen[0] = syy;
                        bestPitch[0] = i;
                    } else {
                        bestNum[1] = num;
                        bestDen[1] = syy;
                        bestPitch[1] = i;
                    }
                }
            }
            syy += y[i + len] * y[i + len] - y[i] * y[i];
            syy = Math.max(1, syy);
        }
    }

    private static float pitchGain(float xy, float xx, float yy) {
        return (float) (xy / Math.sqrt(1 + (double) xx * yy));
    }

    private static final int[] SECOND_CHECK = {0, 0, 3, 2, 3, 2, 5, 2, 3, 2, 3, 2, 5, 2, 3, 2};

    private static float removeDoubling(float[] xBuf, int maxPeriod, int minPeriod, int n,
                                        int[] t0Ref, int prevPeriod, float prevGain) {
        int minPeriod0 = minPeriod;
        maxPeriod /= 2;
        minPeriod /= 2;
        t0Ref[0] /= 2;
        prevPeriod /= 2;
        n /= 2;
        final int x = maxPeriod; // x = xBuf + maxperiod
        if (t0Ref[0] >= maxPeriod) {
            t0Ref[0] = maxPeriod - 1;
        }

        int t = t0Ref[0];
        int t0 = t;
        float[] yyLookup = new float[maxPeriod + 1];
        float xx = innerProd(xBuf, x, xBuf, x, n);
        float xy = innerProd(xBuf, x, xBuf, x - t0, n);
        yyLookup[0] = xx;
        float yy = xx;
        for (int i = 1; i <= maxPeriod; i++) {
            yy = yy + xBuf[x - i] * xBuf[x - i] - xBuf[x + n - i] * xBuf[x + n - i];
            yyLookup[i] = Math.max(0, yy);
        }
        yy = yyLookup[t0];
        float bestXy = xy;
        float bestYy = yy;
        float g0 = pitchGain(xy, xx, yy);
        float g = g0;

        for (int k = 2; k <= 15; k++) {
            int t1 = (2 * t0 + k) / (2 * k);
            if (t1 < minPeriod) {
                break;
            }
            int t1b;
            if (k == 2) {
                t1b = (t1 + t0 > maxPeriod) ? t0 : t0 + t1;
            } else {
                t1b = (2 * SECOND_CHECK[k] * t0 + k) / (2 * k);
            }
            float xy1 = innerProd(xBuf, x, xBuf, x - t1, n);
            float xy2 = innerProd(xBuf, x, xBuf, x - t1b, n);
            xy = .5f * (xy1 + xy2);
            yy = .5f * (yyLookup[t1] + yyLookup[t1b]);
            float g1 = pitchGain(xy, xx, yy);
            float cont;
            if (Math.abs(t1 - prevPeriod) <= 1) {
                cont = prevGain;
            } else if (Math.abs(t1 - prevPeriod) <= 2 && 5 * k * k < t0) {
                cont = .5f * prevGain;
            } else {
                cont = 0;
            }
            float thresh = Math.max(.3f, .7f * g0 - cont);
            if (t1 < 3 * minPeriod) {
                thresh = Math.max(.4f, .85f * g0 - cont);
            } else if (t1 < 2 * minPeriod) {
                thresh = Math.max(.5f, .9f * g0 - cont);
            }
            if (g1 > thresh) {
                bestXy = xy;
                bestYy = yy;
                t = t1;
                g = g1;
            }
        }
        bestXy = Math.max(0, bestXy);
        float pg = (bestYy <= bestXy) ? 1f : bestXy / (bestYy + 1);

        float[] xcorr = new float[3];
        for (int k = 0; k < 3; k++) {
            xcorr[k] = innerProd(xBuf, x, xBuf, x - (t + k - 1), n);
        }
        int offset;
        if ((xcorr[2] - xcorr[0]) > .7f * (xcorr[1] - xcorr[0])) {
            offset = 1;
        } else if ((xcorr[0] - xcorr[2]) > .7f * (xcorr[1] - xcorr[2])) {
            offset = -1;
        } else {
            offset = 0;
        }
        if (pg > g) {
            pg = g;
        }
        t0Ref[0] = 2 * t + offset;
        if (t0Ref[0] < minPeriod0) {
            t0Ref[0] = minPeriod0;
        }
        return pg;
    }
}
