package com.arsh.ultron;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;

/**
 * Invisible screen that wakes the phone so ULTRON can reply inside an app with no Reply button.
 * It only dismisses a lock screen that has NO PIN, pattern, password or fingerprint (swipe or none).
 * A secure lock screen is never bypassed: ULTRON waits until Arsh unlocks the phone himself.
 */
public class WakeActivity extends Activity {
    static volatile boolean shown = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        shown = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            Activity.class.getMethod("setShowWhenLocked", boolean.class).invoke(this, true);
            Activity.class.getMethod("setTurnScreenOn", boolean.class).invoke(this, true);
        } catch (Exception ignored) {
        }
        try {
            KeyguardManager k = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (k != null && !k.isKeyguardSecure()) {
                Class<?> cb = Class.forName("android.app.KeyguardManager$KeyguardDismissCallback");
                KeyguardManager.class.getMethod("requestDismissKeyguard", Activity.class, cb).invoke(k, this, null);
            }
        } catch (Exception ignored) {
        }
        new Handler().postDelayed(new Runnable() {
            public void run() {
                shown = false;
                finish();
            }
        }, 2500);
    }
}
