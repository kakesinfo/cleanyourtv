package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Handles remote Home and Quick Settings keys.
 * Also intercepts stock HomeActivity to keep tvhome active while preserving
 * the Google TV lateral right-side Quick Settings panel (DashboardHandler).
 */
public class HomeKeyService extends AccessibilityService {

    /** Scancodes of remote buttons that should open Settings (config.xml, settings_key_scancodes). */
    private int[] settingsScancodes = new int[0];

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                openQuickSettingsPanel();
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME || event.getKeyCode() == KeyEvent.KEYCODE_ALL_APPS) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                openHome();
            }
            return true;
        }
        return false;
    }

    private void openQuickSettingsPanel() {
        Intent panel = new Intent("android.app.action.OPEN_NOTIFICATION_HANDLER_PANEL")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (panel.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(panel);
                return;
            } catch (Exception ignored) {
            }
        }
        startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void openHome() {
        Intent home = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(home);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            CharSequence cls = event.getClassName();
            if (pkg != null && "com.google.android.apps.tv.launcherx".contentEquals(pkg)) {
                // If stock HomeActivity is attempting to display, redirect to our launcher.
                // Do NOT redirect when DashboardHandler (Quick Settings panel) is opening.
                if (cls != null && cls.toString().contains("HomeActivity")) {
                    openHome();
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
    }
}
