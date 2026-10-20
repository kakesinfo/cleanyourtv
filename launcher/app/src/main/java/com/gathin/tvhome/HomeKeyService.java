package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import java.lang.reflect.Method;

/**
 * Handles remote Home and Quick Settings keys.
 * Short-press Home -> returns to clean tvhome launcher.
 * Long-press Home -> opens lateral right-side Quick Settings panel.
 * Quick Settings key -> opens lateral right-side Quick Settings panel.
 */
public class HomeKeyService extends AccessibilityService {

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
        // 1. Try invoking expandNotificationsPanel() on StatusBarManager (native Android TV method)
        try {
            Object sb = getSystemService("statusbar");
            if (sb != null) {
                Method m = sb.getClass().getMethod("expandNotificationsPanel");
                m.invoke(sb);
                return;
            }
        } catch (Exception ignored) {
        }

        // 2. Try launching OPEN_NOTIFICATION_HANDLER_PANEL directly targeting Google TV launcherx
        try {
            Intent panel = new Intent("android.app.action.OPEN_NOTIFICATION_HANDLER_PANEL")
                    .setPackage("com.google.android.apps.tv.launcherx")
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(panel);
            return;
        } catch (Exception ignored) {
        }

        // 3. Try launching explicit DashboardHandler component
        try {
            Intent panel = new Intent()
                    .setComponent(new ComponentName("com.google.android.apps.tv.launcherx",
                            "com.google.android.apps.tv.launcherx.dashboard.DashboardHandler"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(panel);
            return;
        } catch (Exception ignored) {
        }

        // Fallback to Settings only if all lateral panel attempts fail
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
        handler.removeCallbacks(longPressRunnable);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(longPressRunnable);
    }
}
