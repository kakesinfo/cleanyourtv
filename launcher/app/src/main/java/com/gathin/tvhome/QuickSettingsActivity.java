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

        // 3. Wifi
        setupRow(findViewById(R.id.row_wifi), () -> {
            launchSettingIntent("android.settings.WIFI_SETTINGS");
        });

        // 4. Bluetooth (List of paired devices, accessories & remote settings)
        setupRow(findViewById(R.id.row_bluetooth), () -> {
            Intent btIntent = new Intent("android.settings.SLICE_SETTINGS")
                    .putExtra("slice_uri", "content://com.android.tv.settings.accessories.sliceprovider/general");
            launchSettingIntent(btIntent);
        });

        // 5. Paramètres globaux
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
        row.setOnClickListener(v -> action.run());
    }

    private void launchSettingIntent(String action) {
        launchSettingIntent(new Intent(action));
    }

    private void launchSettingIntent(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Could not launch setting intent: " + intent, e);
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
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                finishWithSlide();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                View current = getCurrentFocus();
                if (current == null
                        || current.getId() == R.id.row_picture
                        || current.getId() == R.id.row_wifi
                        || current.getId() == R.id.row_settings) {
                    finishWithSlide();
                    return true;
                }
                // When on right column, let D-pad move focus to the left column
                return super.dispatchKeyEvent(event);
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
