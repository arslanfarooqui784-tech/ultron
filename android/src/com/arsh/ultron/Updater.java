package com.arsh.ultron;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ULTRON keeps itself up to date: when a newer ULTRON.apk is put on the PC, the phone notices it
 * (every 6 hours and whenever ULTRON opens, on the home Wi-Fi), downloads it, checks it is really a newer ULTRON
 * signed the same way, and shows "Update ready - tap to install". Android always asks Arsh to confirm an install.
 */
public class Updater {
    static volatile boolean busy = false;

    public static void schedule(Context c) {
        Agenda.at(c, System.currentTimeMillis() + 6 * 3600000L, Agenda.alarmIntent(c, "UPDATE", "check"));
    }

    public static void check(final Context ctx, final boolean manual) {
        final Context c = ctx.getApplicationContext();
        final Store s = new Store(c);
        if (!manual && !Smart.on(s, "auto_update", true)) return;
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String r = Updater.run(c, s, manual);
                    s.put("update_status", r);
                    if (manual) s.activity("update", r);
                } finally {
                    busy = false;
                }
            }
        }, "ultron-update").start();
    }

    static String run(Context c, Store s, boolean manual) {
        if (!PcLink.onWifi(c)) return "Waiting for home Wi-Fi to check for updates.";
        String addr = PcLink.addr(s);
        try {
            // cheap check first: has the file on the PC changed?
            HttpURLConnection h = (HttpURLConnection) new URL("http://" + addr + "/app/info").openConnection();
            h.setConnectTimeout(5000);
            h.setReadTimeout(8000);
            int code = h.getResponseCode();
            if (code != 200) return "The PC is not offering an update right now (update the PC ULTRON too).";
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            InputStream hi = h.getInputStream();
            byte[] hb = new byte[1024];
            int hn;
            while ((hn = hi.read(hb)) > 0) bo.write(hb, 0, hn);
            JSONObject info = new JSONObject(bo.toString("UTF-8"));
            String tag = info.optLong("size") + "|" + info.optLong("mtime");
            if (!manual && tag.equals(s.get("update_tag", ""))) return "Up to date (checked " + now() + ").";
            File dir = new File(c.getCacheDir(), "update");
            dir.mkdirs();
            File apk = new File(dir, "ULTRON-update.apk");
            HttpURLConnection g = (HttpURLConnection) new URL("http://" + addr + "/app").openConnection();
            g.setConnectTimeout(5000);
            g.setReadTimeout(60000);
            InputStream in = g.getInputStream();
            FileOutputStream o = new FileOutputStream(apk);
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                total += n;
                if (total > 80L * 1024 * 1024) throw new Exception("file too large");
            }
            o.close();
            in.close();
            PackageInfo got = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNATURES);
            PackageInfo mine = c.getPackageManager().getPackageInfo(c.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
            if (got == null || !c.getPackageName().equals(got.packageName)) {
                apk.delete();
                return "The file on the PC is not ULTRON. Ignored.";
            }
            if (got.signatures != null && got.signatures.length > 0 && mine.signatures != null && mine.signatures.length > 0
                && !got.signatures[0].equals(mine.signatures[0])) {
                apk.delete();
                return "The update on the PC is not signed like this ULTRON. Ignored for safety.";
            }
            s.put("update_tag", tag);
            if (got.versionCode <= mine.versionCode) {
                apk.delete();
                return "Up to date: version " + mine.versionName + " (checked " + now() + ").";
            }
            Uri u = Uri.parse("content://" + UpdateProvider.AUTHORITY + "/ULTRON-update.apk");
            Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            Smart.notify(c, 9300, "⬆️ ULTRON " + got.versionName + " is ready",
                "A newer ULTRON is on your PC (you have " + mine.versionName + "). Tap to install. If Play Protect blocks it, pause Play Protect, install, then turn it back on.",
                PendingIntent.getActivity(c, 9300, install, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
            s.activity("update", "ULTRON " + got.versionName + " downloaded from the PC: tap the notification to install");
            return "Version " + got.versionName + " is ready to install: tap the notification.";
        } catch (Exception e) {
            return "Couldn't reach the PC for updates (" + e.getClass().getSimpleName() + ").";
        } finally {
            schedule(c);
        }
    }

    static String now() {
        return new java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.ENGLISH).format(new java.util.Date());
    }
}
