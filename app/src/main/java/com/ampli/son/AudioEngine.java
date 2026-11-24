package com.ampli.son;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.List;

/**
 * Moteur audio : micro -> traitement voix -> gain par oreille -> limiteur -> casque.
 *
 * Deux modes :
 *  - micro du téléphone : sortie stéréo haute qualité (A2DP), réglage G/D indépendant ;
 *  - micro du casque Bluetooth : le téléphone passe en mode "communication" (profil
 *    mains-libres). Le Bluetooth classique impose alors un son mono en bande vocale
 *    (16 kHz) dans les deux sens.
 */
public final class AudioEngine {

    public interface Callback {
        void onEngineStopped(String error);
    }

    private static AudioEngine sInstance;

    public static synchronized AudioEngine get() {
        if (sInstance == null) sInstance = new AudioEngine();
        return sInstance;
    }

    // ---- Réglages (écrits par l'interface) ----
    public volatile float gainLeftDb = 10f;
    public volatile float gainRightDb = 10f;
    public volatile boolean muteLeft = false;
    public volatile boolean muteRight = false;
    public volatile float thresholdDb = -3f;
    public volatile boolean limiterEnabled = true;
    public volatile boolean highPassEnabled = true;
    public volatile boolean vibrateOnAlert = true;
    public volatile int audioSource = MediaRecorder.AudioSource.MIC;
    // Voix
    public volatile boolean voiceFocus = true;
    public volatile float noiseReduction = 0.6f;
    public volatile boolean autoGain = true;

    // ---- Mesures (lues par l'interface) ----
    public volatile float levelLeftDb = -100f;
    public volatile float levelRightDb = -100f;
    public volatile float agcDb = 0f;
    public volatile boolean alertActive = false;
    public volatile boolean limiting = false;
    public volatile String lastError = null;
    public volatile int errorSerial = 0;
    public volatile String routedOutputName = "";
    public volatile boolean headsetMicActive = false;

    private volatile AudioDeviceInfo outputDevice;
    private volatile AudioDeviceInfo inputDevice;
    private volatile boolean running = false;
    private volatile Thread thread;
    private volatile AudioRecord recorder;
    private volatile AudioTrack track;
    private volatile Callback callback;
    private Context appCtx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AudioEngine() {
    }

    public static boolean isHeadsetMic(AudioDeviceInfo d) {
        if (d == null) return false;
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == 26 /* TYPE_BLE_HEADSET */;
    }

    public boolean isRunning() {
        return running;
    }

    public void setCallback(Callback cb) {
        callback = cb;
    }

    public AudioDeviceInfo getOutputDevice() {
        return outputDevice;
    }

    public AudioDeviceInfo getInputDevice() {
        return inputDevice;
    }

    public void setOutputDevice(AudioDeviceInfo d) {
        outputDevice = d;
        AudioTrack t = track;
        if (t != null && !headsetMicActive) {
            try { t.setPreferredDevice(d); } catch (Exception ignored) { }
        }
    }

    public void setInputDevice(AudioDeviceInfo d) {
        inputDevice = d;
        AudioRecord r = recorder;
        if (r != null && !headsetMicActive && !isHeadsetMic(d)) {
            try { r.setPreferredDevice(d); } catch (Exception ignored) { }
        }
    }

    public synchronized void start(Context ctx) {
        if (running) return;
        appCtx = ctx.getApplicationContext();
        lastError = null;
        alertActive = false;
        limiting = false;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "AmpliSonAudio");
        thread = t;
        t.start();
    }

    /** Arrêt demandé de l'extérieur (interface / notification). */
    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(2500); } catch (InterruptedException ignored) { }
        }
        thread = null;
    }

    private static float dbToLin(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    private static float linToDb(float lin) {
        if (lin < 1e-5f) return -100f;
        return (float) (20.0 * Math.log10(lin));
    }

    private void fail(String msg) {
        lastError = msg;
        errorSerial++;
        running = false;
    }

    // ------------------------------------------------------------------ mode communication

    @SuppressWarnings("deprecation")
    private boolean enterHeadsetMicMode(AudioManager am, AudioDeviceInfo mic) {
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (Build.VERSION.SDK_INT >= 31) {
            List<AudioDeviceInfo> devs = am.getAvailableCommunicationDevices();
            AudioDeviceInfo best = null;
            for (AudioDeviceInfo d : devs) {
                if (!isHeadsetMic(d)) continue;
                if (best == null) best = d;
                if (String.valueOf(d.getProductName()).equals(String.valueOf(mic.getProductName()))) {
                    best = d;
                    break;
                }
            }
            if (best == null || !am.setCommunicationDevice(best)) return false;
        } else {
            am.startBluetoothSco();
            am.setBluetoothScoOn(true);
        }
        // attend que la liaison mains-libres soit établie (jusqu'à 4 s)
        for (int i = 0; i < 40 && running; i++) {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo c = am.getCommunicationDevice();
                if (c != null && isHeadsetMic(c)) return true;
            } else if (am.isBluetoothScoOn()) {
                return true;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { return false; }
        }
        return Build.VERSION.SDK_INT < 31; // anciens Android : on tente quand même
    }

    @SuppressWarnings("deprecation")
    private void leaveHeadsetMicMode(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice();
            } else {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
            }
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ boucle audio

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo mic = inputDevice;
        final boolean headsetMic = isHeadsetMic(mic);
        headsetMicActive = headsetMic;
        final int sr = headsetMic ? 16000 : 48000;
        final VoiceProcessor vp = new VoiceProcessor(sr);
        final int frames = vp.hopSize();

        AudioRecord rec = null;
        AudioTrack trk = null;
        NoiseSuppressor ns = null;
        try {
            if (headsetMic && !enterHeadsetMicMode(am, mic)) {
                if (running) {
                    fail("Impossible d'activer le micro des écouteurs. Vérifiez qu'ils sont "
                            + "bien connectés pour les appels (profil « appels téléphoniques » "
                            + "activé dans les réglages Bluetooth).");
                }
                return;
            }
            int minIn = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int minOut = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minIn <= 0 || minOut <= 0) {
                fail("Format audio non supporté par ce téléphone.");
                return;
            }
            int source = headsetMic ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : audioSource;
            rec = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minIn, frames * 2 * 4))
                    .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                fail("Impossible d'ouvrir le micro (autorisation refusée ou micro occupé).");
                return;
            }
            int usage = headsetMic ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                    : AudioAttributes.USAGE_MEDIA;
            trk = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minOut, frames * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            if (trk.getState() != AudioTrack.STATE_INITIALIZED) {
                fail("Impossible d'ouvrir la sortie audio.");
                return;
            }
            recorder = rec;
            track = trk;
            if (headsetMic) {
                rec.setPreferredDevice(mic);
            } else {
                if (mic != null) rec.setPreferredDevice(mic);
                AudioDeviceInfo out = outputDevice;
                if (out != null) trk.setPreferredDevice(out);
            }
            // Réduction de bruit matérielle d'Android, si le téléphone en propose une
            if (NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(rec.getAudioSessionId());
                    if (ns != null) ns.setEnabled(true);
                } catch (Exception ignored) {
                    ns = null;
                }
            }

            rec.startRecording();
            trk.play();
            process(rec, trk, sr, frames, vp, headsetMic);
        } catch (SecurityException se) {
            fail("Autorisation refusée (micro ou Bluetooth).");
        } catch (Exception e) {
            fail("Erreur audio : " + e.getMessage());
        } finally {
            recorder = null;
            track = null;
            if (ns != null) {
                try { ns.release(); } catch (Exception ignored) { }
            }
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
            if (trk != null) {
                try { trk.pause(); trk.flush(); trk.stop(); } catch (Exception ignored) { }
                trk.release();
            }
            if (headsetMic) leaveHeadsetMicMode(am);
            headsetMicActive = false;
            running = false;
            levelLeftDb = -100f;
            levelRightDb = -100f;
            agcDb = 0f;
            alertActive = false;
            limiting = false;
            final Callback cb = callback;
            final String err = lastError;
            if (cb != null) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onEngineStopped(err);
                    }
                });
            }
        }
    }

    private void process(AudioRecord rec, AudioTrack trk, int sr, int frames,
                         VoiceProcessor vp, boolean headsetMic) {
        short[] in = new short[frames];
        short[] out = new short[frames * 2];
        float[] buf = new float[frames];
        final float blockMs = 1000f * frames / sr;

        // Filtre passe-haut 1er ordre ~120 Hz
        final double rc = 1.0 / (2 * Math.PI * 120.0);
        final double dt = 1.0 / sr;
        final float hpA = (float) (rc / (rc + dt));
        float hpPrevX = 0f, hpPrevY = 0f;

        // Limiteur crête : attaque instantanée, relâchement ~250 ms
        final float release = (float) (1.0 - Math.exp(-1.0 / (0.25 * sr)));
        float limGain = 1f;

        // Fondu d'entrée
        float fade = 0f;
        final float fadeStep = 1f / (0.5f * sr);

        float dispL = -100f, dispR = -100f;
        float overMs = 0f;
        long lastVibrate = 0;
        int blockCount = 0;
        int badRouteChecks = 0;
        int filled = 0;

        while (running) {
            // lit exactement "frames" échantillons
            int n = rec.read(in, filled, frames - filled);
            if (n < 0) {
                fail("Lecture micro interrompue (code " + n + ").");
                break;
            }
            filled += n;
            if (filled < frames) continue;
            filled = 0;

            boolean hp = highPassEnabled;
            for (int i = 0; i < frames; i++) {
                float x = in[i] / 32768f;
                if (hp) {
                    float y = hpA * (hpPrevY + x - hpPrevX);
                    hpPrevX = x;
                    hpPrevY = y;
                    x = y;
                } else {
                    hpPrevX = x;
                    hpPrevY = 0f;
                }
                buf[i] = x;
            }

            vp.voiceFocus = voiceFocus;
            vp.noiseReduction = noiseReduction;
            vp.autoGain = autoGain;
            vp.process(buf, buf);
            agcDb = vp.agcGainDb();

            float gl = muteLeft ? 0f : dbToLin(gainLeftDb);
            float gr = muteRight ? 0f : dbToLin(gainRightDb);
            if (headsetMic) {
                // sortie mono : on applique la même amplification aux deux oreilles
                float g = Math.max(gl, gr);
                gl = g;
                gr = g;
            }
            float thr = dbToLin(thresholdDb);
            boolean lim = limiterEnabled;

            float preMax = 0f, peakL = 0f, peakR = 0f;
            boolean limitedThisBlock = false;
            for (int i = 0; i < frames; i++) {
                float x = buf[i];
                if (fade < 1f) {
                    fade += fadeStep;
                    if (fade > 1f) fade = 1f;
                    x *= fade;
                }
                float l = x * gl;
                float r = x * gr;
                float m = Math.max(Math.abs(l), Math.abs(r));
                if (m > preMax) preMax = m;
                if (lim) {
                    if (m * limGain > thr) {
                        limGain = thr / m;
                        limitedThisBlock = true;
                    } else {
                        limGain += (1f - limGain) * release;
                    }
                    l *= limGain;
                    r *= limGain;
                } else {
                    limGain = 1f;
                }
                if (l > 0.999f) l = 0.999f; else if (l < -0.999f) l = -0.999f;
                if (r > 0.999f) r = 0.999f; else if (r < -0.999f) r = -0.999f;
                float al = Math.abs(l), ar = Math.abs(r);
                if (al > peakL) peakL = al;
                if (ar > peakR) peakR = ar;
                out[2 * i] = (short) (l * 32767f);
                out[2 * i + 1] = (short) (r * 32767f);
            }
            int w = trk.write(out, 0, frames * 2);
            if (w < 0) {
                fail("Écriture vers le casque impossible (code " + w + ").");
                break;
            }

            float dbL = linToDb(peakL), dbR = linToDb(peakR);
            dispL = Math.max(dbL, dispL - 0.6f);
            dispR = Math.max(dbR, dispR - 0.6f);
            levelLeftDb = dispL;
            levelRightDb = dispR;
            limiting = limitedThisBlock || (lim && limGain < 0.9f);

            // Alerte : niveau demandé au-dessus du seuil pendant >= 150 ms
            if (preMax > thr) overMs += blockMs;
            else overMs = Math.max(0f, overMs - blockMs);
            if (overMs > 400f) overMs = 400f;
            boolean alert = overMs >= 150f;
            alertActive = alert;
            if (alert && vibrateOnAlert) {
                long now = System.currentTimeMillis();
                if (now - lastVibrate > 2500) {
                    lastVibrate = now;
                    vibrate();
                }
            }

            // Sécurité : sortie basculée sur le haut-parleur -> arrêt (vérifié ~toutes les 0,5 s)
            if (++blockCount % Math.max(1, (int) (500f / blockMs)) == 0) {
                AudioDeviceInfo routed = trk.getRoutedDevice();
                if (routed != null) {
                    routedOutputName = String.valueOf(routed.getProductName());
                    int t = routed.getType();
                    if (t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                            || t == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        badRouteChecks++;
                        if (badRouteChecks >= 3) {
                            fail("Le son est parti vers le haut-parleur du téléphone "
                                    + "(casque déconnecté ?). Amplification arrêtée par sécurité.");
                            break;
                        }
                    } else {
                        badRouteChecks = 0;
                    }
                }
            }
        }
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) appCtx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 180, 120, 180}, -1));
            }
        } catch (Exception ignored) {
        }
    }
}
