package com.ampli.son;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_MIC = 42;
    private static final int GAIN_MIN = -10, GAIN_MAX = 40;
    private static final int THR_MIN = -30, THR_MAX = 0;

    // Types de sorties "casque" (valeurs numériques pour compatibilité API)
    private static final int TYPE_BLE_HEADSET = 26, TYPE_BLE_SPEAKER = 27, TYPE_BLE_BROADCAST = 30;

    private final AudioEngine engine = AudioEngine.get();
    private AudioManager am;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Spinner outSpinner, inSpinner, modeSpinner;
    private final List<AudioDeviceInfo> outDevices = new ArrayList<>();
    private final List<AudioDeviceInfo> inDevices = new ArrayList<>(); // index 0 = null (auto)
    private TextView gainLText, gainRText, thrText, banner, statusText, volText, noDeviceText;
    private SeekBar gainLBar, gainRBar, thrBar, volBar;
    private CheckBox linkBox, muteLBox, muteRBox, limBox, vibBox, hpBox, focusBox, agcBox;
    private TextView headsetNote, nrText, agcText;
    private SeekBar nrBar;
    private Button startBtn;
    private LevelMeter meter;
    private boolean syncing = false;
    private boolean volTouching = false;
    private int seenErrorSerial;
    private int tick = 0;

    private static final int[] MODE_SOURCES = {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION};
    private static final String[] MODE_LABELS = {
            "Standard",
            "Réduction de bruit / écho (voix)",
            "Son brut (moins de traitement)"};

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            refreshDevices();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            AudioDeviceInfo cur = engine.getOutputDevice();
            if (cur != null && engine.isRunning() && !engine.headsetMicActive) {
                for (AudioDeviceInfo d : removed) {
                    if (d.getId() == cur.getId()) {
                        stopAmp();
                        showError("Le casque « " + d.getProductName()
                                + " » s'est déconnecté. Amplification arrêtée par sécurité.");
                        break;
                    }
                }
            }
            refreshDevices();
        }
    };

    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            updateLive();
            handler.postDelayed(this, 50);
        }
    };

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0D47A1);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        prefs = getSharedPreferences("ampli", MODE_PRIVATE);
        seenErrorSerial = engine.errorSerial;
        loadPrefsIntoEngine();
        setContentView(buildUi());
        refreshDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        am.registerAudioDeviceCallback(deviceCallback, handler);
        refreshDevices();
        handler.post(uiTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        am.unregisterAudioDeviceCallback(deviceCallback);
        handler.removeCallbacks(uiTick);
        savePrefs();
    }

    // ------------------------------------------------------------------ interface

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String s, float size, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(12), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(14));
        c.setBackground(g);
        c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(6), dp(12), dp(6));
        parent.addView(c, lp);
        TextView t = label(title, 15, true, 0xFF0D47A1);
        t.setPadding(0, 0, 0, dp(6));
        c.addView(t);
        return c;
    }

    private SeekBar seek(int min, int max) {
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setPadding(dp(16), dp(10), dp(16), dp(10));
        return s;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF1F4F8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, dp(24));
        scroll.addView(root);

        // En-tête
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(0xFF0D47A1);
        head.setPadding(dp(20), dp(18), dp(20), dp(18));
        head.addView(label("Ampli Son", 24, true, 0xFFFFFFFF));
        head.addView(label("Amplification du micro vers votre casque", 14, false, 0xFFBBDEFB));
        root.addView(head);

        // Bouton marche / arrêt + statut
        LinearLayout top = card(root, "Amplification");
        startBtn = new Button(this);
        startBtn.setTextSize(18);
        startBtn.setAllCaps(false);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine.isRunning()) stopAmp();
                else requestStart();
            }
        });
        top.addView(startBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        statusText = label("", 13, false, 0xFF546E7A);
        statusText.setPadding(0, dp(6), 0, 0);
        top.addView(statusText);

        // Sortie
        LinearLayout outCard = card(root, "Casque (sortie)");
        outSpinner = new Spinner(this);
        outCard.addView(outSpinner);
        noDeviceText = label("Aucun casque connecté. Connectez vos écouteurs en Bluetooth "
                + "puis appuyez sur « Actualiser ».", 13, false, 0xFFC62828);
        outCard.addView(noDeviceText);
        Button refresh = new Button(this);
        refresh.setText("Actualiser la liste");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshDevices();
                Toast.makeText(MainActivity.this, "Liste actualisée", Toast.LENGTH_SHORT).show();
            }
        });
        outCard.addView(refresh);
        outSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < outDevices.size()) {
                    AudioDeviceInfo d = outDevices.get(pos);
                    engine.setOutputDevice(d);
                    prefs.edit().putString("out", deviceKey(d)).apply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // Micro
        LinearLayout inCard = card(root, "Micro (entrée)");
        inSpinner = new Spinner(this);
        inCard.addView(inSpinner);
        inSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < inDevices.size()) {
                    AudioDeviceInfo d = inDevices.get(pos);
                    boolean wasHeadset = AudioEngine.isHeadsetMic(engine.getInputDevice());
                    boolean isHeadset = AudioEngine.isHeadsetMic(d);
                    engine.setInputDevice(d);
                    prefs.edit().putString("in2", d == null ? "" : deviceKey(d)).apply();
                    headsetNote.setVisibility(isHeadset ? View.VISIBLE : View.GONE);
                    // changer de type de micro demande de relancer l'audio
                    if (engine.isRunning() && wasHeadset != isHeadset) {
                        stopAmp();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                requestStart();
                            }
                        }, 800);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        headsetNote = label("Micro des écouteurs : le Bluetooth passe en mode « appel ». "
                + "Le son devient mono (même niveau dans les deux oreilles) et en qualité "
                + "téléphone, mais il y a moins de retard. Attention : les écouteurs sont "
                + "souvent réglés pour capter surtout VOTRE voix ; essayez aussi le micro du "
                + "téléphone posé près de votre interlocuteur.", 12, false, 0xFF6A1B9A);
        headsetNote.setVisibility(View.GONE);
        inCard.addView(headsetNote);
        inCard.addView(label("Mode de captation du micro du téléphone (appliqué au prochain démarrage)",
                13, false, 0xFF546E7A));
        modeSpinner = new Spinner(this);
        ArrayAdapter<String> ma = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MODE_LABELS);
        ma.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(ma);
        modeSpinner.setSelection(Math.max(0, Math.min(2, prefs.getInt("mode", 0))));
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                engine.audioSource = MODE_SOURCES[pos];
                prefs.edit().putInt("mode", pos).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        inCard.addView(modeSpinner);
        hpBox = new CheckBox(this);
        hpBox.setText("Filtre anti-grondement (coupe les graves < 120 Hz)");
        hpBox.setChecked(engine.highPassEnabled);
        hpBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean c) {
                engine.highPassEnabled = c;
                savePrefs();
            }
        });
        inCard.addView(hpBox);

        // Voix
        LinearLayout vxCard = card(root, "Voix");
        focusBox = new CheckBox(this);
        focusBox.setText("Concentrer sur la voix (atténue graves et aigus, renforce les consonnes)");
        focusBox.setChecked(engine.voiceFocus);
        vxCard.addView(focusBox);
        nrText = label("", 15, true, 0xFF263238);
        nrText.setPadding(0, dp(8), 0, 0);
        vxCard.addView(nrText);
        nrBar = seek(0, 100);
        nrBar.setProgress(Math.round(engine.noiseReduction * 100));
        vxCard.addView(nrBar);
        agcBox = new CheckBox(this);
        agcBox.setText("Remonter automatiquement les voix faibles");
        agcBox.setChecked(engine.autoGain);
        vxCard.addView(agcBox);
        agcText = label("", 13, false, 0xFF546E7A);
        vxCard.addView(agcText);
        vxCard.addView(label("La réduction de bruit atténue les sons continus (ventilation, moteur, "
                + "brouhaha de fond). Trop forte, elle peut rendre la voix métallique : "
                + "50 à 70 % est un bon point de départ.", 12, false, 0xFF78909C));
        updateNrLabel();
        nrBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                engine.noiseReduction = p / 100f;
                updateNrLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                savePrefs();
            }
        });
        CompoundButton.OnCheckedChangeListener voiceListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean c) {
                engine.voiceFocus = focusBox.isChecked();
                engine.autoGain = agcBox.isChecked();
                savePrefs();
            }
        };
        focusBox.setOnCheckedChangeListener(voiceListener);
        agcBox.setOnCheckedChangeListener(voiceListener);

        // Gains par oreille
        LinearLayout gCard = card(root, "Amplification par oreille");
        gainLText = label("", 15, true, 0xFF263238);
        gCard.addView(gainLText);
        gainLBar = seek(GAIN_MIN, GAIN_MAX);
        gCard.addView(gainLBar);
        muteLBox = new CheckBox(this);
        muteLBox.setText("Couper l'oreille gauche");
        gCard.addView(muteLBox);

        gainRText = label("", 15, true, 0xFF263238);
        gainRText.setPadding(0, dp(10), 0, 0);
        gCard.addView(gainRText);
        gainRBar = seek(GAIN_MIN, GAIN_MAX);
        gCard.addView(gainRBar);
        muteRBox = new CheckBox(this);
        muteRBox.setText("Couper l'oreille droite");
        gCard.addView(muteRBox);

        linkBox = new CheckBox(this);
        linkBox.setText("Lier gauche et droite (même réglage)");
        linkBox.setChecked(prefs.getBoolean("link", false));
        gCard.addView(linkBox);

        gainLBar.setProgress(Math.round(engine.gainLeftDb) - GAIN_MIN);
        gainRBar.setProgress(Math.round(engine.gainRightDb) - GAIN_MIN);
        muteLBox.setChecked(engine.muteLeft);
        muteRBox.setChecked(engine.muteRight);
        updateGainLabels();

        SeekBar.OnSeekBarChangeListener gainListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (syncing) return;
                if (linkBox.isChecked()) {
                    syncing = true;
                    if (s == gainLBar) gainRBar.setProgress(p);
                    else gainLBar.setProgress(p);
                    syncing = false;
                }
                engine.gainLeftDb = gainLBar.getProgress() + GAIN_MIN;
                engine.gainRightDb = gainRBar.getProgress() + GAIN_MIN;
                updateGainLabels();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                savePrefs();
            }
        };
        gainLBar.setOnSeekBarChangeListener(gainListener);
        gainRBar.setOnSeekBarChangeListener(gainListener);
        CompoundButton.OnCheckedChangeListener muteListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean c) {
                engine.muteLeft = muteLBox.isChecked();
                engine.muteRight = muteRBox.isChecked();
                updateGainLabels();
                savePrefs();
            }
        };
        muteLBox.setOnCheckedChangeListener(muteListener);
        muteRBox.setOnCheckedChangeListener(muteListener);
        linkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean c) {
                if (c) {
                    // aligne sur la valeur la plus basse (prudence)
                    int p = Math.min(gainLBar.getProgress(), gainRBar.getProgress());
                    syncing = true;
                    gainLBar.setProgress(p);
                    gainRBar.setProgress(p);
                    syncing = false;
                    engine.gainLeftDb = p + GAIN_MIN;
                    engine.gainRightDb = p + GAIN_MIN;
                    updateGainLabels();
                }
                savePrefs();
            }
        });

        // Niveaux + sécurité
        LinearLayout sCard = card(root, "Niveau de sortie et sécurité");
        meter = new LevelMeter(this);
        sCard.addView(meter);
        banner = label("", 15, true, 0xFFFFFFFF);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(0xFFD32F2F);
        bb.setCornerRadius(dp(10));
        banner.setBackground(bb);
        banner.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(8), 0, dp(4));
        sCard.addView(banner, blp);

        thrText = label("", 15, true, 0xFF263238);
        thrText.setPadding(0, dp(8), 0, 0);
        sCard.addView(thrText);
        thrBar = seek(THR_MIN, THR_MAX);
        thrBar.setProgress(Math.round(engine.thresholdDb) - THR_MIN);
        sCard.addView(thrBar);
        updateThrLabel();
        thrBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                engine.thresholdDb = p + THR_MIN;
                updateThrLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                savePrefs();
            }
        });
        limBox = new CheckBox(this);
        limBox.setText("Limiter automatiquement le son au seuil");
        limBox.setChecked(engine.limiterEnabled);
        sCard.addView(limBox);
        vibBox = new CheckBox(this);
        vibBox.setText("Vibrer en cas de dépassement du seuil");
        vibBox.setChecked(engine.vibrateOnAlert);
        sCard.addView(vibBox);
        CompoundButton.OnCheckedChangeListener safety = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean c) {
                engine.limiterEnabled = limBox.isChecked();
                engine.vibrateOnAlert = vibBox.isChecked();
                savePrefs();
            }
        };
        limBox.setOnCheckedChangeListener(safety);
        vibBox.setOnCheckedChangeListener(safety);
        sCard.addView(label("Le seuil s'exprime en dBFS (0 = maximum numérique). Le volume réel "
                + "dans l'oreille dépend aussi du volume média ci-dessous.", 12, false, 0xFF78909C));

        // Volume média
        LinearLayout vCard = card(root, "Volume média du téléphone");
        volText = label("", 14, false, 0xFF263238);
        vCard.addView(volText);
        volBar = new SeekBar(this);
        volBar.setPadding(dp(16), dp(10), dp(16), dp(10));
        volBar.setMax(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volBar.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
        vCard.addView(volBar);
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    try {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
                    } catch (SecurityException ignored) {
                    }
                }
                updateVolLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                volTouching = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                volTouching = false;
            }
        });
        updateVolLabel();

        // Avertissement
        LinearLayout wCard = card(root, "À savoir");
        wCard.addView(label(
                "• Commencez avec un volume bas et montez progressivement.\n"
                        + "• Le Bluetooth ajoute un léger retard (environ 0,1 à 0,3 s) : "
                        + "c'est une limite du Bluetooth, pas de l'application.\n"
                        + "• Gardez le téléphone éloigné des écouteurs pour éviter le sifflement (larsen).\n"
                        + "• Pour mieux entendre une personne, posez le téléphone près d'elle et "
                        + "choisissez le micro du téléphone.\n"
                        + "• Si le casque se déconnecte, l'amplification s'arrête automatiquement.\n"
                        + "• Cette application n'est pas un dispositif médical et ne remplace pas "
                        + "un appareil auditif prescrit.",
                13, false, 0xFF455A64));

        updateRunState();
        return scroll;
    }

    private void updateGainLabels() {
        int l = gainLBar.getProgress() + GAIN_MIN;
        int r = gainRBar.getProgress() + GAIN_MIN;
        gainLText.setText("Oreille gauche : " + (muteLBox != null && muteLBox.isChecked()
                ? "coupée" : String.format(Locale.FRANCE, "%+d dB", l)));
        gainRText.setText("Oreille droite : " + (muteRBox != null && muteRBox.isChecked()
                ? "coupée" : String.format(Locale.FRANCE, "%+d dB", r)));
    }

    private void updateThrLabel() {
        thrText.setText(String.format(Locale.FRANCE, "Seuil d'alerte : %d dBFS",
                thrBar.getProgress() + THR_MIN));
    }

    private void updateNrLabel() {
        int p = nrBar.getProgress();
        nrText.setText(p == 0 ? "Réduction de bruit : désactivée"
                : String.format(Locale.FRANCE, "Réduction de bruit : %d %%", p));
    }

    private void updateVolLabel() {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        volText.setText(String.format(Locale.FRANCE, "Niveau %d / %d (%d %%)", v, max,
                max > 0 ? Math.round(100f * v / max) : 0));
    }

    private void updateRunState() {
        boolean on = engine.isRunning();
        startBtn.setText(on ? "■  Arrêter l'amplification" : "▶  Démarrer l'amplification");
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? 0xFFC62828 : 0xFF2E7D32);
        g.setCornerRadius(dp(12));
        startBtn.setBackground(g);
        startBtn.setTextColor(0xFFFFFFFF);
        if (on) {
            String routed = engine.routedOutputName;
            statusText.setText("En cours" + (engine.headsetMicActive ? " (micro des écouteurs, mono)" : "")
                    + (routed != null && routed.length() > 0 ? " – sortie : " + routed : ""));
        } else {
            statusText.setText("Arrêté");
        }
    }

    private void updateLive() {
        updateRunState();
        meter.setLevels(engine.levelLeftDb, engine.levelRightDb, engine.thresholdDb);
        if (engine.isRunning() && engine.alertActive) {
            banner.setText(engine.limiterEnabled
                    ? "⚠ Seuil atteint : le limiteur réduit le son"
                    : "⚠ ALERTE : son au-dessus du seuil !");
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }
        if (engine.errorSerial != seenErrorSerial) {
            seenErrorSerial = engine.errorSerial;
            if (engine.lastError != null) showError(engine.lastError);
        }
        if (engine.isRunning() && engine.autoGain) {
            agcText.setText(String.format(Locale.FRANCE, "Remontée automatique actuelle : %+.0f dB",
                    engine.agcDb));
        } else {
            agcText.setText("");
        }
        if (++tick % 10 == 0 && !volTouching) {
            volBar.setMax(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            volBar.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
            updateVolLabel();
        }
    }

    private void showError(String msg) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Ampli Son")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------------ périphériques

    private static boolean isHeadsetOutput(int t) {
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || t == TYPE_BLE_HEADSET || t == TYPE_BLE_SPEAKER || t == TYPE_BLE_BROADCAST
                || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || t == AudioDeviceInfo.TYPE_USB_HEADSET
                || t == AudioDeviceInfo.TYPE_HEARING_AID;
    }

    private static boolean isBluetooth(int t) {
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || t == TYPE_BLE_HEADSET || t == TYPE_BLE_SPEAKER || t == TYPE_BLE_BROADCAST
                || t == AudioDeviceInfo.TYPE_HEARING_AID;
    }

    private static String typeLabel(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth";
            case TYPE_BLE_HEADSET:
            case TYPE_BLE_SPEAKER:
            case TYPE_BLE_BROADCAST: return "Bluetooth LE Audio";
            case AudioDeviceInfo.TYPE_HEARING_AID: return "Aide auditive";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "filaire";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "intégré";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB";
            default: return "type " + t;
        }
    }

    private static String deviceKey(AudioDeviceInfo d) {
        return d.getType() + "|" + d.getProductName();
    }

    private void refreshDevices() {
        if (outSpinner == null) return;
        // Sorties
        outDevices.clear();
        List<AudioDeviceInfo> others = new ArrayList<>();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!isHeadsetOutput(d.getType())) continue;
            if (isBluetooth(d.getType())) outDevices.add(d);
            else others.add(d);
        }
        outDevices.addAll(others);
        List<String> labels = new ArrayList<>();
        for (AudioDeviceInfo d : outDevices) {
            labels.add(d.getProductName() + "  (" + typeLabel(d.getType()) + ")");
        }
        ArrayAdapter<String> oa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        oa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        outSpinner.setAdapter(oa);
        noDeviceText.setVisibility(outDevices.isEmpty() ? View.VISIBLE : View.GONE);
        outSpinner.setVisibility(outDevices.isEmpty() ? View.GONE : View.VISIBLE);
        int sel = -1;
        AudioDeviceInfo cur = engine.getOutputDevice();
        String saved = prefs.getString("out", "");
        for (int i = 0; i < outDevices.size(); i++) {
            AudioDeviceInfo d = outDevices.get(i);
            if (cur != null && d.getId() == cur.getId()) { sel = i; break; }
        }
        if (sel < 0) {
            for (int i = 0; i < outDevices.size(); i++) {
                if (deviceKey(outDevices.get(i)).equals(saved)) { sel = i; break; }
            }
        }
        if (sel < 0 && !outDevices.isEmpty()) sel = 0;
        if (sel >= 0) {
            outSpinner.setSelection(sel);
            engine.setOutputDevice(outDevices.get(sel));
        } else {
            engine.setOutputDevice(null);
        }

        // Entrées
        inDevices.clear();
        inDevices.add(null);
        List<String> inLabels = new ArrayList<>();
        inLabels.add("Micro du téléphone (automatique)");
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (AudioEngine.isHeadsetMic(d)) {
                inDevices.add(d);
                inLabels.add("Micro des écouteurs : " + d.getProductName() + " (Bluetooth)");
            }
        }
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BUILTIN_MIC || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                inDevices.add(d);
                String addr = "";
                try {
                    addr = d.getAddress();
                } catch (Throwable ignored) {
                }
                String name = t == AudioDeviceInfo.TYPE_BUILTIN_MIC ? "Micro du téléphone"
                        : String.valueOf(d.getProductName());
                inLabels.add(name + " (" + typeLabel(t)
                        + (addr != null && addr.length() > 0 ? ", " + addr : "") + ")");
            }
        }
        ArrayAdapter<String> ia = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, inLabels);
        ia.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        inSpinner.setAdapter(ia);
        String savedIn = prefs.getString("in2", null);
        int isel = 0;
        AudioDeviceInfo curIn = engine.getInputDevice();
        boolean found = false;
        for (int i = 1; i < inDevices.size(); i++) {
            AudioDeviceInfo d = inDevices.get(i);
            if (curIn != null && d.getId() == curIn.getId()) { isel = i; found = true; break; }
        }
        if (!found && savedIn != null) {
            for (int i = 1; i < inDevices.size(); i++) {
                if (deviceKey(inDevices.get(i)).equals(savedIn)) { isel = i; break; }
            }
        } else if (!found) {
            // premier lancement : micro des écouteurs par défaut s'il existe
            for (int i = 1; i < inDevices.size(); i++) {
                if (AudioEngine.isHeadsetMic(inDevices.get(i))) { isel = i; break; }
            }
        }
        headsetNote.setVisibility(AudioEngine.isHeadsetMic(inDevices.get(isel)) ? View.VISIBLE : View.GONE);
        inSpinner.setSelection(isel);
        engine.setInputDevice(inDevices.get(isel));
    }

    // ------------------------------------------------------------------ marche / arrêt

    private void requestStart() {
        if (outDevices.isEmpty() || engine.getOutputDevice() == null) {
            showError("Aucun casque sélectionné. Connectez vos écouteurs Bluetooth, puis "
                    + "choisissez-les dans la liste. (Par sécurité, l'application ne diffuse "
                    + "jamais le son amplifié sur le haut-parleur du téléphone.)");
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
            if (android.os.Build.VERSION.SDK_INT >= 33) need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (android.os.Build.VERSION.SDK_INT >= 31
                && AudioEngine.isHeadsetMic(engine.getInputDevice())
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.BLUETOOTH_CONNECT");
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_MIC);
            return;
        }
        startAmp();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_MIC) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                startAmp();
            } else {
                showError("L'accès au micro est nécessaire pour amplifier le son. "
                        + "Vous pouvez l'autoriser dans Paramètres > Applications > Ampli Son > Autorisations.");
            }
        }
    }

    private void startAmp() {
        savePrefs();
        Intent i = new Intent(this, AmpService.class);
        startForegroundService(i);
        Toast.makeText(this, "Amplification démarrée", Toast.LENGTH_SHORT).show();
    }

    private void stopAmp() {
        Intent i = new Intent(this, AmpService.class).setAction(AmpService.ACTION_STOP);
        startService(i);
        engine.stop();
    }

    // ------------------------------------------------------------------ préférences

    private void loadPrefsIntoEngine() {
        if (engine.isRunning()) return; // réglages déjà actifs
        engine.gainLeftDb = prefs.getInt("gl", 10);
        engine.gainRightDb = prefs.getInt("gr", 10);
        engine.muteLeft = prefs.getBoolean("ml", false);
        engine.muteRight = prefs.getBoolean("mr", false);
        engine.thresholdDb = prefs.getInt("thr", -3);
        engine.limiterEnabled = prefs.getBoolean("lim", true);
        engine.vibrateOnAlert = prefs.getBoolean("vib", true);
        engine.highPassEnabled = prefs.getBoolean("hp", true);
        engine.voiceFocus = prefs.getBoolean("focus", true);
        engine.autoGain = prefs.getBoolean("agc", true);
        engine.noiseReduction = prefs.getInt("nr", 60) / 100f;
        int mode = Math.max(0, Math.min(2, prefs.getInt("mode", 0)));
        engine.audioSource = MODE_SOURCES[mode];
    }

    private void savePrefs() {
        if (gainLBar == null) return;
        prefs.edit()
                .putInt("gl", gainLBar.getProgress() + GAIN_MIN)
                .putInt("gr", gainRBar.getProgress() + GAIN_MIN)
                .putBoolean("ml", muteLBox.isChecked())
                .putBoolean("mr", muteRBox.isChecked())
                .putInt("thr", thrBar.getProgress() + THR_MIN)
                .putBoolean("lim", limBox.isChecked())
                .putBoolean("vib", vibBox.isChecked())
                .putBoolean("hp", hpBox.isChecked())
                .putBoolean("link", linkBox.isChecked())
                .putBoolean("focus", focusBox.isChecked())
                .putBoolean("agc", agcBox.isChecked())
                .putInt("nr", nrBar.getProgress())
                .apply();
    }
}
