package com.arsh.ultron;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Phone and PC as one ULTRON (same Wi-Fi). Pairing uses the PC's own code ("pair phone" on the PC).
 *  - The PC can ask the phone for its notifications and SMS, and to open apps. Calls and SMS the PC asks for
 *    always wait for Arsh's APPROVE on the phone.
 *  - Memories are shared both ways, and the PC can see the phone's status (messages waiting, missed calls, routine).
 * Only the paired PC (secret token, kept encrypted) can talk to the phone, and only over the home network.
 */
public class PcLink {
    static volatile Thread loop;
    static volatile boolean running = false;
    static final Map<String, Boolean> answers = new HashMap<>();
    static final Map<String, JSONObject> pendingJobs = new HashMap<>();
    static volatile long lastOk = 0;
    static volatile String lastError = "";

    public static String addr(Store s) {
        return s.get("pc_addr", "192.168.1.10:8765").replaceAll("^https?://", "").replaceAll("/+$", "");
    }

    public static boolean paired(Store s) {
        return !s.getSecret("pc_token", "").isEmpty();
    }

    public static JSONObject info(Store s) throws Exception {
        return new JSONObject().put("addr", addr(s)).put("paired", paired(s)).put("on", !"0".equals(s.get("pc_on", "1")))
            .put("online", System.currentTimeMillis() - lastOk < 90000).put("error", lastError)
            .put("lastSync", s.get("pc_last_sync", ""));
    }

    // ------------------------------ HTTP ------------------------------
    static JSONObject post(Store s, String path, JSONObject body, int timeoutSec) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL("http://" + addr(s) + path).openConnection();
        h.setConnectTimeout(5000);
        h.setReadTimeout(timeoutSec * 1000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/json");
        String tok = s.getSecret("pc_token", "");
        if (!tok.isEmpty()) h.setRequestProperty("X-Ultron-Phone", tok);
        OutputStream o = h.getOutputStream();
        o.write(body.toString().getBytes("UTF-8"));
        o.close();
        int code = h.getResponseCode();
        InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
        String txt = b.toString("UTF-8");
        if (code == 401) throw new SecurityException("The PC does not know this phone any more. Pair again.");
        if (code >= 400) throw new Exception(new JSONObject(txt.isEmpty() ? "{}" : txt).optString("error", "PC error " + code));
        return new JSONObject(txt.isEmpty() ? "{}" : txt);
    }

    static boolean onWifi(Context c) {
        try {
            NetworkInfo n = ((ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
            return n != null && n.isConnected() && (n.getType() == ConnectivityManager.TYPE_WIFI || n.getType() == ConnectivityManager.TYPE_ETHERNET);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------ pairing ------------------------------
    public static String pair(Context c, String address, String code) {
        Store s = new Store(c);
        if (address != null && address.trim().matches("[0-9a-zA-Z.\\-]+(:\\d+)?")) s.put("pc_addr", address.trim());
        try {
            JSONObject r = post(s, "/phone/pair", new JSONObject().put("code", code.replaceAll("\\D", "")), 10);
            String tok = r.optString("token");
            if (tok.length() < 20) return "The PC did not accept the code.";
            s.putSecret("pc_token", tok);
            s.put("pc_on", "1");
            s.activity("pc", "Paired with the PC at " + addr(s));
            start(c);
            return "Paired with your PC, Arsh. Phone and PC are now one ULTRON when you're on the home Wi-Fi.";
        } catch (Exception e) {
            return "Pairing failed: " + e.getMessage() + ". On the PC say 'pair phone', then type the 6-digit code here within 10 minutes.";
        }
    }

    public static void unpair(Context c) {
        Store s = new Store(c);
        s.putSecret("pc_token", "");
        running = false;
        s.activity("pc", "Unpaired from the PC");
    }

    // ------------------------------ background loop ------------------------------
    public static synchronized void start(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        Store s = new Store(c);
        if (!paired(s) || "0".equals(s.get("pc_on", "1"))) return;
        if (loop != null && loop.isAlive()) return;
        running = true;
        loop = new Thread(new Runnable() {
            public void run() {
                long nextSync = 0;
                while (running) {
                    Store st = new Store(c);
                    if (!paired(st) || "0".equals(st.get("pc_on", "1"))) break;
                    if (!onWifi(c)) {
                        sleep(120000);
                        continue;
                    }
                    try {
                        if (System.currentTimeMillis() > nextSync) {
                            sync(c);
                            nextSync = System.currentTimeMillis() + 10 * 60000L;
                        }
                        JSONObject r = post(st, "/phone/poll", new JSONObject(), 35);
                        lastOk = System.currentTimeMillis();
                        lastError = "";
                        JSONObject job = r.optJSONObject("job");
                        if (job != null) {
                            JSONObject res = handle(c, st, job);
                            res.put("id", job.opt("id"));
                            post(st, "/phone/report", res, 15);
                        }
                    } catch (SecurityException e) {
                        lastError = e.getMessage();
                        st.activity("pc", lastError);
                        sleep(300000);
                    } catch (Exception e) {
                        lastError = "PC not reachable at " + addr(st) + " (" + e.getClass().getSimpleName() + ")";
                        sleep(60000);
                    }
                }
                running = false;
            }
        }, "ultron-pc");
        loop.setDaemon(true);
        loop.start();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------ PC jobs ------------------------------
    static JSONObject handle(final Context c, Store s, JSONObject job) throws Exception {
        String type = job.optString("type");
        s.activity("pc", "PC asked the phone: " + type);
        switch (type) {
            case "notifications": {
                JSONArray a = new JSONArray();
                for (NotifService.Note n : NotifService.notes())
                    a.put(new JSONObject().put("app", n.pkg).put("title", n.title).put("text", n.text));
                if (!NotifService.connected()) return new JSONObject().put("ok", false).put("data", "notification access is off on the phone");
                return new JSONObject().put("ok", true).put("data", a);
            }
            case "sms_list": {
                JSONArray a = new JSONArray();
                Cursor cur = c.getContentResolver().query(Uri.parse("content://sms/inbox"), new String[]{"address", "body"}, null, null, "date DESC");
                if (cur != null) {
                    try {
                        int max = Math.max(1, Math.min(20, job.optInt("count", 5)));
                        while (cur.moveToNext() && a.length() < max) {
                            String body = cur.getString(1) == null ? "" : cur.getString(1);
                            if (Memory.SECRET.matcher(body).find()) body = "(a code or banking message, hidden)";
                            a.put(new JSONObject().put("from", cur.getString(0)).put("text", body));
                        }
                    } finally {
                        cur.close();
                    }
                }
                return new JSONObject().put("ok", true).put("data", a);
            }
            case "sms_send":
            case "call": {
                String num = job.optString("number"), what = type.equals("call") ? "Call " + num : "Send SMS to " + num + ": " + job.optString("text");
                String id = String.valueOf(System.currentTimeMillis() % 100000000);
                synchronized (pendingJobs) {
                    pendingJobs.put(id, job);
                }
                askArsh(c, id, "PC asks · " + what);
                Boolean ok = waitAnswer(id, 110000);
                if (ok == null) return new JSONObject().put("ok", false).put("data", "Arsh did not approve it on the phone in time");
                if (!ok) return new JSONObject().put("ok", false).put("data", "Arsh rejected it on the phone");
                return new JSONObject().put("ok", true).put("data", "done");
            }
            case "clipboard": {
                final String text = job.optString("text");
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try {
                            ((ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ULTRON", text));
                        } catch (Exception ignored) {
                        }
                    }
                });
                return new JSONObject().put("ok", true).put("data", "copied");
            }
            case "open":
            case "open_dual": {
                Intent i = null;
                String pkg = job.optString("package"), url = job.optString("url"), act = job.optString("action");
                if (!url.isEmpty()) i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                else if (!act.isEmpty()) i = new Intent(act);
                else if (!pkg.isEmpty()) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return new JSONObject().put("ok", false).put("data", "that app is not installed");
                if (!pkg.isEmpty() && !url.isEmpty()) i.setPackage(pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(i);
                    return new JSONObject().put("ok", true).put("data", "opened");
                } catch (Exception e) {
                    return new JSONObject().put("ok", false).put("data", "Android did not let ULTRON open it from the background (turn on ULTRON's screen helper)");
                }
            }
            case "file": {
                String r = Advanced.receiveFromPc(c, s, job.optString("path"), job.optString("name", "file"));
                return new JSONObject().put("ok", r.equals("saved")).put("data", r);
            }
            case "status":
                return new JSONObject().put("ok", true).put("data", status(c, s));
            default:
                return new JSONObject().put("ok", false).put("data", "unknown job");
        }
    }

    /** Called from the APPROVE / REJECT buttons. The approved call or SMS happens right here (Arsh just tapped). */
    public static void answer(String id, boolean yes) {
        JSONObject job;
        synchronized (pendingJobs) {
            job = pendingJobs.remove(id);
        }
        if (job != null && yes && appContext != null) {
            try {
                Store s = new Store(appContext);
                if (job.optString("type").equals("call")) {
                    appContext.startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + job.optString("number"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    s.activity("pc", "Calling " + job.optString("number") + " (asked by the PC, approved by Arsh)");
                } else {
                    Owner.smsTo(appContext, s, job.optString("number"), job.optString("text"));
                    s.activity("pc", "SMS sent to " + job.optString("number") + " (asked by the PC, approved by Arsh)");
                }
            } catch (Exception e) {
                yes = false;
            }
        }
        try {
            ((NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(9500 + Math.abs(id.hashCode() % 400));
        } catch (Exception ignored) {
        }
        synchronized (answers) {
            answers.put(id, yes);
            answers.notifyAll();
        }
    }

    static Context appContext;

    static void askArsh(Context c, String id, String what) {
        appContext = c.getApplicationContext();
        try {
            Notification.Builder b = Approvals.builder(c, Approvals.CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(what)
                .setContentText("Your PC asked ULTRON to do this on the phone. Approve?").setColor(0xFFFF1728).setAutoCancel(true)
                .addAction(new Notification.Action.Builder(0, "APPROVE", Agenda.alarmIntent(c, "PC_OK", id)).build())
                .addAction(new Notification.Action.Builder(0, "REJECT", Agenda.alarmIntent(c, "PC_NO", id)).build());
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(9500 + Math.abs(id.hashCode() % 400), b.build());
        } catch (Exception ignored) {
        }
    }

    static Boolean waitAnswer(String id, long ms) {
        long end = System.currentTimeMillis() + ms;
        synchronized (answers) {
            while (!answers.containsKey(id) && System.currentTimeMillis() < end) {
                try {
                    answers.wait(Math.max(100, end - System.currentTimeMillis()));
                } catch (InterruptedException ignored) {
                }
            }
            if (!answers.containsKey(id)) {
                synchronized (pendingJobs) {
                    pendingJobs.remove(id);                               // never act late after the PC gave up
                }
            }
            return answers.remove(id);
        }
    }

    /** Runs a command on the PC ("open Chrome", "play music", "shut down") and returns the PC's answer. */
    public static String ask(Context c, String text) {
        Store s = new Store(c);
        if (!paired(s)) return "I'm not paired with your PC yet, Arsh. Open ULTRON+ to pair.";
        if (!onWifi(c)) return "Connect to your home Wi-Fi so I can reach your PC, Arsh.";
        try {
            JSONObject r = post(s, "/phone/ask", new JSONObject().put("text", text), 60);
            lastOk = System.currentTimeMillis();
            s.activity("pc", "Phone → PC: " + text + " → " + Memory.clip(r.optString("reply"), 120));
            return r.optString("reply", "Done on your PC.");
        } catch (Exception e) {
            return "I couldn't reach your PC at " + addr(s) + ": " + e.getMessage() + ". Is ULTRON running on the PC?";
        }
    }

    // ------------------------------ status + memory sync ------------------------------
    static JSONObject status(Context c, Store s) throws Exception {
        JSONArray a = AutoReply.Events.all(s);
        int waiting = 0, today = 0;
        long since = System.currentTimeMillis() - 24 * 3600000L;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null || e.optLong("received") < since || "outgoing".equals(e.optString("kind"))) continue;
            if ("personal".equals(e.optString("kind"))) today++;
            if ("attention".equals(e.optString("status")) || "awaiting_approval".equals(e.optString("status"))) waiting++;
        }
        int missed = 0;
        JSONArray cl = Calls.log(s);
        for (int i = 0; i < cl.length(); i++) {
            JSONObject e = cl.optJSONObject(i);
            if (e != null && e.optLong("at") >= since && "missed".equals(e.optString("status"))) missed++;
        }
        return new JSONObject().put("messages24h", today).put("waitingForArsh", waiting).put("missedCalls24h", missed)
            .put("routine", Routines.active(s)).put("replyMode", AutoReply.mode(s))
            .put("nextReminders", Agenda.upcomingReminders(s, System.currentTimeMillis() + 24 * 3600000L))
            .put("briefing", s.getSecret("brief_text", "")).put("at", System.currentTimeMillis());
    }

    public static String sync(Context c) throws Exception {
        Store s = new Store(c);
        Memory mem = new Memory(s, null);
        JSONArray mine = new JSONArray(), items = mem.items();
        for (int i = 0; i < items.length(); i++) {
            JSONObject m = items.optJSONObject(i);
            if (m == null || "temporary".equals(m.optString("category")) || "pc".equals(m.optString("source"))) continue;
            mine.put(new JSONObject().put("text", m.optString("text")).put("category", m.optString("category")));
        }
        JSONObject r = post(s, "/phone/sync", new JSONObject().put("memories", mine).put("status", status(c, s)), 20);
        JSONArray theirs = r.optJSONArray("memories");
        int added = 0;
        for (int i = 0; theirs != null && i < theirs.length(); i++) {
            JSONObject m = theirs.optJSONObject(i);
            if (m == null || "phone".equals(m.optString("from"))) continue;
            boolean known = false;
            for (int k = 0; k < items.length() && !known; k++) known = Memory.similar(items.optJSONObject(k).optString("text"), m.optString("text")) > 0.8;
            if (known) continue;
            if (mem.add(m.optString("category", "personal"), m.optString("text"), "pc", 0.8, false) != null) added++;
        }
        s.put("pc_last_sync", String.valueOf(System.currentTimeMillis()));
        lastOk = System.currentTimeMillis();
        if (added > 0) s.activity("pc", "Synced with the PC: " + added + " memories from the PC");
        return "Synced with your PC: sent " + mine.length() + " memories, received " + added + " new ones.";
    }
}
