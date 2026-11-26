package com.arsh.ultron;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * ULTRON's app lock: shown over a locked app (WhatsApp, Gallery...). Fingerprint / phone PIN or ULTRON's password opens it.
 * Two wrong passwords: ULTRON quietly takes a front-camera photo of the person and alerts Arsh.
 */
public class LockGate extends Activity {
    static final Map<String, Long> unlockedUntil = new HashMap<>();
    static volatile boolean showing = false;
    String pkg;
    int wrong = 0;

    /** Called by the screen helper whenever an app comes to the front. */
    public static void check(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName()) || showing) return;
        Store s = new Store(c);
        String locked = "," + s.get("locked_apps", "") + ",";
        if (!locked.contains("," + pkg + ",")) return;
        Long until = unlockedUntil.get(pkg);
        if (until != null && until > System.currentTimeMillis()) return;
        showing = true;
        c.startActivity(new Intent(c, LockGate.class).putExtra("pkg", pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pkg = getIntent().getStringExtra("pkg");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String app = pkg;
        try {
            app = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception ignored) {
        }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(60, 60, 60, 60);
        l.setBackgroundColor(Color.rgb(6, 7, 10));
        TextView t = new TextView(this);
        t.setText("ULTRON\n\n" + app + " is locked");
        t.setTextColor(Color.rgb(255, 68, 82));
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        final EditText pw = new EditText(this);
        pw.setHint("ULTRON password");
        pw.setHintTextColor(Color.GRAY);
        pw.setTextColor(Color.WHITE);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        l.addView(pw);
        final TextView msg = new TextView(this);
        msg.setTextColor(Color.LTGRAY);
        msg.setGravity(Gravity.CENTER);
        Button go = new Button(this);
        go.setText("UNLOCK");
        go.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                Store s = new Store(LockGate.this);
                if (s.checkPassword(pw.getText().toString())) {
                    open();
                } else {
                    wrong++;
                    pw.setText("");
                    msg.setText("Wrong password");
                    if (wrong == 2) intruder(LockGate.this, "wrong password on the " + getIntent().getStringExtra("pkg") + " lock");
                }
            }
        });
        l.addView(go);
        Button bio = new Button(this);
        bio.setText("USE FINGERPRINT");
        bio.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                askBio();
            }
        });
        l.addView(bio);
        l.addView(msg);
        setContentView(l);
        askBio();
    }

    void askBio() {
        try {
            KeyguardManager k = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (!k.isKeyguardSecure()) return;
            Intent i = k.createConfirmDeviceCredentialIntent("Unlock " + pkg, "Fingerprint or phone PIN");
            if (i != null) startActivityForResult(i, 77);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        super.onActivityResult(req, res, d);
        if (req == 77 && res == RESULT_OK) open();
    }

    void open() {
        unlockedUntil.put(pkg, System.currentTimeMillis() + 5 * 60000L);   // stays open for 5 minutes
        showing = false;
        finish();
    }

    @Override
    public void onBackPressed() {
        showing = false;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        showing = false;
    }

    /** Front-camera photo of whoever is holding the phone, saved privately in ULTRON; Arsh is alerted. */
    public static void intruder(final Activity a, final String why) {
        final Store s = new Store(a);
        try {
            int front = -1;
            Camera.CameraInfo ci = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, ci);
                if (ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) front = i;
            }
            if (front < 0) throw new Exception("no front camera");
            final Camera cam = Camera.open(front);
            cam.setPreviewTexture(new SurfaceTexture(10));
            cam.startPreview();
            new android.os.Handler().postDelayed(new Runnable() {
                public void run() {
                    try {
                        cam.takePicture(null, null, new Camera.PictureCallback() {
                            public void onPictureTaken(byte[] data, Camera c) {
                                try {
                                    File d = new File(a.getFilesDir(), "intruders");
                                    d.mkdirs();
                                    String name = "intruder_" + System.currentTimeMillis() + ".jpg";
                                    FileOutputStream o = new FileOutputStream(new File(d, name));
                                    o.write(data);
                                    o.close();
                                    Smart.push(s, "intruders", new JSONObject().put("file", name).put("why", why).put("at", System.currentTimeMillis()), 30);
                                } catch (Exception ignored) {
                                }
                                c.release();
                                alert(a, s, why, true);
                            }
                        });
                    } catch (Exception e) {
                        cam.release();
                        alert(a, s, why, false);
                    }
                }
            }, 700);
        } catch (Exception e) {
            alert(a, s, why, false);
        }
    }

    static void alert(Context c, Store s, String why, boolean photo) {
        s.activity("security", "INTRUDER: " + why + (photo ? " - photo taken (ULTRON+ › Security)" : " - camera not available"));
        Smart.notify(c, 6990, "🚨 Someone tried to get in", why + (photo ? ". ULTRON took their photo: ULTRON+ › Security." : "."), null);
        if (!Owner.samePhone(s)) Owner.sms(c, s, Owner.HEAD + "Someone tried to unlock Arsh's phone/apps (" + why + ")" + (photo ? ". Their photo is saved in ULTRON." : "."));
    }
}
