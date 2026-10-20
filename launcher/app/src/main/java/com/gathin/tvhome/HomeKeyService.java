package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

/**
 * Handles remote Home and Quick Settings keys.
 * Short-press Home -> returns to clean tvhome launcher.
 * Long-press Home -> opens custom lateral Quick Settings panel.
 * Quick Settings key -> opens custom lateral Quick Settings panel.
 * Redirects stock Google TV home screen to tvhome immediately on boot and navigation.
 */
public class HomeKeyService extends AccessibilityService {
    private static final String TAG = "HomeKeyService";

    /** Scancodes of remote buttons that should open Settings (config.xml, settings_key_scancodes). */
    private int[] settingsScancodes = new int[0];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean homeLongPressTriggered = false;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            homeLongPressTriggered = true;
            openQuickSettingsPanel();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        Log.i(TAG, "HomeKeyService connected, ensuring clean home is displayed");
        openHome();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                openHome();
            }
        }, 1000);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        return false;
    }

    private boolean isHomeKey(KeyEvent event) {
        int code = event.getKeyCode();
        return code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_ALL_APPS;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                openQuickSettingsPanel();
            }
            return true;
        }

        if (isHomeKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    homeLongPressTriggered = false;
                    handler.removeCallbacks(longPressRunnable);
                    handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longPressRunnable);
                if (!homeLongPressTriggered && !event.isCanceled()) {
                    openHome();
                }
                homeLongPressTriggered = false;
                return true;
            }
        }

        return false;
    }

    private void openQuickSettingsPanel() {
        Intent panel = new Intent(this, MainActivity.class)
                .setAction("com.gathin.tvhome.action.QUICK_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(panel);
    }

    private void openHome() {
        Intent home = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            CharSequence pkg = event.getPackageName();
            CharSequence cls = event.getClassName();
            if (pkg != null && "com.google.android.apps.tv.launcherx".contentEquals(pkg)) {
                // If stock HomeActivity or any other launcherx activity appears, redirect
                Log.i(TAG, "Stock Google TV home activity detected (" + cls + "), redirecting to clean home");
                openHome();
            }
        }
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(longPressRunnable);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(longPressRunnable);
    }
}
