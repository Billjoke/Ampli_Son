package com.ampli.son;

/**
 * Traitement "voix" sur un signal mono :
 *  1. Analyse spectrale (STFT, fenêtres racine-de-Hann, recouvrement 50 %).
 *  2. Réduction de bruit (soustraction spectrale avec estimation continue du bruit).
 *  3. Focalisation voix : atténue les graves et les aigus hors de la bande de la parole,
 *     renforce la zone 1,5 - 4 kHz qui porte l'intelligibilité des consonnes.
 *  4. Remontée automatique des voix (AGC) gelée pendant les silences.
 * Latence ajoutée : une fenêtre d'analyse (environ 10 à 16 ms).
 */
final class VoiceProcessor {

    // Réglages (lus à chaque bloc)
    volatile float noiseReduction = 0.6f;   // 0..1
    volatile boolean voiceFocus = true;
    volatile boolean autoGain = true;
    volatile float agcMaxBoostDb = 24f;

    private final int sr, n, hop, bins;
    private final float[] window;
    private final float[] inBuf;    // dernières n entrées
    private final float[] outAcc;   // accumulateur de sortie (overlap-add)
    private final float[] re, im;
    private final float[] noise, smooth, gainPrev, bandWeight;
    private final int[] bitrev;
    private final float[] cosT, sinT;
    private int frameCount = 0;
    private final float noiseUp;

    // AGC
    private float env = 0f, agcGainDb = 0f, floorDb = -60f;
    private final float envAtt, envRel, agcUpPerSample, agcDownPerSample, floorRise;

    VoiceProcessor(int sampleRate) {
        sr = sampleRate;
        n = sampleRate >= 32000 ? 512 : 256;
        hop = n / 2;
        bins = n / 2 + 1;
        window = new float[n];
        for (int i = 0; i < n; i++) {
            window[i] = (float) Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
        }
        inBuf = new float[n];
        outAcc = new float[n];
        re = new float[n];
        im = new float[n];
        noise = new float[bins];
        smooth = new float[bins];
        gainPrev = new float[bins];
        bandWeight = new float[bins];
        for (int k = 0; k < bins; k++) {
            noise[k] = 1e-9f;
            gainPrev[k] = 1f;
            float f = (float) k * sr / n;
            float w;
            if (f < 150f) w = 0.08f;
            else if (f < 300f) w = 0.08f + 0.92f * (f - 150f) / 150f;
            else if (f < 1500f) w = 1f;
            else if (f < 4000f) w = 1.6f;          // présence : +4 dB
            else if (f < 6000f) w = 1.6f - 1.0f * (f - 4000f) / 2000f;
            else w = 0.5f;
            bandWeight[k] = w;
        }
        int bitsN = Integer.numberOfTrailingZeros(n);
        bitrev = new int[n];
        for (int i = 0; i < n; i++) bitrev[i] = Integer.reverse(i) >>> (32 - bitsN);
        cosT = new float[n / 2];
        sinT = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cosT[i] = (float) Math.cos(-2 * Math.PI * i / n);
            sinT[i] = (float) Math.sin(-2 * Math.PI * i / n);
        }
        float framesPerSec = (float) sr / hop;
        noiseUp = (float) Math.pow(10.0, (4.0 / 10.0) / framesPerSec); // bruit peut monter de 4 dB/s

        envAtt = (float) (1 - Math.exp(-1.0 / (0.005 * sr)));
        envRel = (float) (1 - Math.exp(-1.0 / (0.150 * sr)));
        agcUpPerSample = 6f / sr;     // +6 dB/s max (remontée lente)
        agcDownPerSample = 60f / sr;  // -60 dB/s (baisse rapide si ça crie)
        floorRise = 3f / sr;          // plancher de bruit remonte de 3 dB/s
    }

    int hopSize() {
        return hop;
    }

    /** Traite exactement hop échantillons (in -> out, même tableau possible). */
    void process(float[] in, float[] out) {
        // décale le tampon d'entrée et ajoute les nouveaux échantillons
        System.arraycopy(inBuf, hop, inBuf, 0, n - hop);
        System.arraycopy(in, 0, inBuf, n - hop, hop);

        for (int i = 0; i < n; i++) {
            re[i] = inBuf[i] * window[i];
            im[i] = 0f;
        }
        fft(re, im, false);

        float strength = noiseReduction;
        boolean focus = voiceFocus;
        float alpha = 1f + 2.5f * strength;                                  // sur-soustraction
        float floor = (float) Math.pow(10.0, -(4.0 + 16.0 * strength) / 20.0); // -4 à -20 dB
        frameCount++;
        boolean warm = frameCount > 20;

        for (int k = 0; k < bins; k++) {
            float p = re[k] * re[k] + im[k] * im[k];
            smooth[k] = 0.75f * smooth[k] + 0.25f * p;
            // estimation du bruit : suit les minima, remonte lentement
            if (!warm) {
                noise[k] = Math.max(noise[k], smooth[k]);
            } else if (smooth[k] < noise[k]) {
                noise[k] = 0.85f * noise[k] + 0.15f * smooth[k];
            } else {
                noise[k] *= noiseUp;
            }
            float g = 1f;
            if (strength > 0.01f && p > 0f) {
                g = 1f - alpha * noise[k] / (p + 1e-12f);
                if (g < floor) g = floor;
                // lissage temporel (limite le "bruit musical")
                if (g < gainPrev[k]) g = 0.5f * gainPrev[k] + 0.5f * g;
                else g = 0.3f * gainPrev[k] + 0.7f * g;
            }
            gainPrev[k] = g;
            if (focus) g *= bandWeight[k];
            re[k] *= g;
            im[k] *= g;
            if (k > 0 && k < n / 2) { // symétrie hermitienne
                re[n - k] = re[k];
                im[n - k] = -im[k];
            }
        }
        fft(re, im, true);

        for (int i = 0; i < n; i++) outAcc[i] += re[i] * window[i];
        boolean agc = autoGain;
        float maxBoost = agcMaxBoostDb;
        for (int i = 0; i < hop; i++) {
            float y = outAcc[i];
            if (agc) {
                float a = Math.abs(y);
                env += (a > env ? envAtt : envRel) * (a - env);
                float envDb = env > 1e-6f ? (float) (20 * Math.log10(env)) : -120f;
                // plancher de bruit : descend vite, remonte lentement
                if (envDb < floorDb) floorDb = envDb;
                else floorDb += floorRise;
                float target = -20f - envDb;   // vise environ -20 dBFS crête
                if (target > maxBoost) target = maxBoost;
                if (target < -12f) target = -12f;
                boolean speech = envDb > floorDb + 8f && envDb > -70f;
                if (target < agcGainDb) {
                    agcGainDb = Math.max(target, agcGainDb - agcDownPerSample);
                } else if (speech) {
                    agcGainDb = Math.min(target, agcGainDb + agcUpPerSample);
                }
                y *= (float) Math.pow(10.0, agcGainDb / 20.0);
            }
            out[i] = y;
        }
        System.arraycopy(outAcc, hop, outAcc, 0, n - hop);
        for (int i = n - hop; i < n; i++) outAcc[i] = 0f;
    }

    float agcGainDb() {
        return agcGainDb;
    }

    /** FFT radix-2 en place. inverse = true -> IFFT normalisée. */
    private void fft(float[] xr, float[] xi, boolean inverse) {
        for (int i = 0; i < n; i++) {
            int j = bitrev[i];
            if (j > i) {
                float t = xr[i]; xr[i] = xr[j]; xr[j] = t;
                t = xi[i]; xi[i] = xi[j]; xi[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1;
            int step = n / size;
            for (int start = 0; start < n; start += size) {
                for (int j = 0; j < half; j++) {
                    float wr = cosT[j * step];
                    float wi = inverse ? -sinT[j * step] : sinT[j * step];
                    int a = start + j, b = a + half;
                    float tr = xr[b] * wr - xi[b] * wi;
                    float ti = xr[b] * wi + xi[b] * wr;
                    xr[b] = xr[a] - tr;
                    xi[b] = xi[a] - ti;
                    xr[a] += tr;
                    xi[a] += ti;
                }
            }
        }
        if (inverse) {
            float s = 1f / n;
            for (int i = 0; i < n; i++) {
                xr[i] *= s;
                xi[i] *= s;
            }
        }
    }
}
