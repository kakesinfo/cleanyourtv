package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

/**
 * Translucent lateral right-side Quick Settings panel.
 * Designed to overlay seamlessly on top of any running application or the launcher.
 */
public class QuickSettingsActivity extends Activity {
    private static final String TAG = "QuickSettingsActivity";
    private int[] settingsScancodes = new int[0];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_settings);

        try {
            settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        } catch (Exception ignored) {
        }

        View scrim = findViewById(R.id.scrim);
        if (scrim != null) {
            scrim.setOnClickListener(v -> finishWithSlide());
        }

        // 1. Image (Philips lateral picture menu: Dolby Vision, HDR, picture styles, calibration)
        setupRow(findViewById(R.id.row_picture), () -> {
            launchSettingIntent("android.settings.PICTURE_ACTIVITY");
        });

        // 2. Sortie Audio (Philips lateral audio menu: eARC, haut-parleurs, format numérique)
        setupRow(findViewById(R.id.row_audio), () -> {
            launchSettingIntent("android.settings.AUDIO_OUTPUT_SETTINGS");
        });

        // 3. Réseau et Wi-Fi
        setupRow(findViewById(R.id.row_wifi), () -> {
            launchSettingIntent("android.settings.WIFI_SETTINGS");
        });

        // 4. Bluetooth et Accessoires (Télécommandes, manettes, casques)
        setupRow(findViewById(R.id.row_bluetooth), () -> {
            launchSettingIntent("com.google.android.intent.action.CONNECT_INPUT");
        });

        // 5. Tous les Réglages globaux
        setupRow(findViewById(R.id.row_settings), () -> {
            launchSettingIntent("android.settings.SETTINGS");
        });

        View firstRow = findViewById(R.id.row_picture);
        if (firstRow != null) {
            firstRow.requestFocus();
        }
    }

    private void setupRow(View row, Runnable action) {
        if (row == null) return;
        row.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate()
                    .scaleX(hasFocus ? 1.02f : 1.0f)
                    .scaleY(hasFocus ? 1.02f : 1.0f)
                    .setDuration(140)
                    .start();
        });
        row.setOnClickListener(v -> action.run());
    }

    private void launchSettingIntent(String action) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Could not launch setting action: " + action, e);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) {
            if (event.getScanCode() == code) return true;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_BACK) {
                finishWithSlide();
                return true;
            }
            if (isSettingsKey(event)) {
                finishWithSlide();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        finishWithSlide();
    }

    private void finishWithSlide() {
        finish();
        overridePendingTransition(0, R.anim.slide_out_right);
    }
}
