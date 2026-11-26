package com.arsh.ultron;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Keeps Arsh informed: reports to his own number (SMS and WhatsApp) plus a notification on this phone,
 * and the emergency escalation (alarm, SMS, WhatsApp, then a direct call within strict limits).
 * Nothing is ever called "delivered" unless Android confirms it.
 */
public class Owner {
    public static final String DEFAULT_NUMBER = "";
    public static final String HEAD = "ULTRON — ";
    static final String SENT = "com.arsh.ultron.SMS_SENT", DELIVERED = "com.arsh.ultron.SMS_DELIVERED";
    static final Map<String, Long> recent = new HashMap<>();
    static int smsId = 1;
    static int alarmId = 5000;

    // ------------------------------ settings ------------------------------
    public static String number(Store s) {
        String n = s.get("owner_number", DEFAULT_NUMBER).replaceAll("[^+0-9]", "");
        return n.isEmpty() ? DEFAULT_NUMBER : n;
    }

    static boolean on(Store s, String key, boolean def) {
        return "1".equals(s.get(key, def ? "1" : "0"));
    }

    public static boolean viaSms(Store s) { return on(s, "owner_sms", true); }
    public static boolean viaWhatsApp(Store s) { return on(s, "owner_wa", true); }
    public static boolean directCall(Store s) { return on(s, "owner_call", true); }
    public static boolean samePhone(Store s) { return on(s, "owner_samephone", false); }
    public static int digestMin(Store s) {
        try { return Math.max(15, Integer.parseInt(s.get("owner_digest", "60"))); } catch (Exception e) { return 60; }
    }
    /** low = only clear, AI-confirmed emergencies; normal; high = any emergency word escalates at once. */
    public static String sensitivity(Store s) { return s.get("emerg_sens", "normal"); }

    public static JSONObject info(Store s) throws Exception {
        return new JSONObject().put("number", number(s)).put("sms", viaSms(s)).put("wa", viaWhatsApp(s)).put("call", directCall(s))
            .put("samePhone", samePhone(s)).put("digest", digestMin(s)).put("sens", sensitivity(s))
            .put("maxCalls", maxCalls(s)).put("pendingWa", pendingWa(s).length()).put("digestItems", digest(s).length());
    }

    public static void save(Store s, JSONObject in) {
        if (in.has("ownerNumber")) {
            String n = in.optString("ownerNumber").replaceAll("[^+0-9]", "");
            if (n.length() >= 10) {
                if (!n.startsWith("+")) n = n.length() == 10 ? "+91" + n : "+" + n;
                s.put("owner_number", n);
                s.activity("settings", "Owner number set to " + n);
            }
        }
        if (in.has("ownerSms")) s.put("owner_sms", in.optBoolean("ownerSms") ? "1" : "0");
        if (in.has("ownerWa")) s.put("owner_wa", in.optBoolean("ownerWa") ? "1" : "0");
        if (in.has("ownerCall")) s.put("owner_call", in.optBoolean("ownerCall") ? "1" : "0");
        if (in.has("samePhone")) s.put("owner_samephone", in.optBoolean("samePhone") ? "1" : "0");
        if (in.has("digest")) s.put("owner_digest", String.valueOf(Math.max(15, in.optInt("digest", 60))));
        if (in.has("sens")) s.put("emerg_sens", in.optString("sens").matches("low|normal|high") ? in.optString("sens") : "normal");
        if (in.has("maxCalls")) s.put("owner_maxcalls", String.valueOf(Math.max(0, Math.min(5, in.optInt("maxCalls", 3)))));
    }

    static int maxCalls(Store s) {
        try { return Integer.parseInt(s.get("owner_maxcalls", "3")); } catch (Exception e) { return 3; }
    }

    /** True if this text came from ULTRON itself (so a report arriving on this same phone is never answered). */
    public static boolean ours(String text) {
        return text != null && text.trim().startsWith(HEAD.trim());
    }

    // ------------------------------ reports ------------------------------
    /**
     * priority: "emergency" | "high" (sent now) | "normal" (grouped into the digest).
     */
    public static void report(Context c, String priority, String title, String body) {
        Store s = new Store(c);
        String key = AutoReply.sha(title + "|" + body);
        synchronized (recent) {
            Long last = recent.get(key);
            if (last != null && System.currentTimeMillis() - last < 10 * 60000L) return;   // never the same alert twice
            recent.put(key, System.currentTimeMillis());
        }
        if (priority.equals("normal")) {
            addDigest(s, title + ": " + body);
            scheduleDigest(c);
            return;
        }
        String text = HEAD + title + "\n\n" + body;
        notifyHere(c, title, body, priority.equals("emergency"));
        sendAll(c, s, text, priority);
    }

    static void sendAll(Context c, Store s, String text, String priority) {
        AutoReply.rememberSent(s, text);
        if (viaSms(s) && !samePhoneSkipsSms(s)) sms(c, s, text);
        if (viaWhatsApp(s)) whatsapp(c, s, text);
        s.activity("owner", "Report for Arsh (" + priority + "): " + text.replace("\n", " ").substring(0, Math.min(160, text.length())));
    }

    /** A report SMS to the number of this very phone would just arrive here again: skip it, the notification is enough. */
    static boolean samePhoneSkipsSms(Store s) {
        return samePhone(s);
    }

    static void notifyHere(Context c, String title, String body, boolean alarm) {
        try {
            Intent open = new Intent(c, MainActivity.class).putExtra("page", "control.html").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Notification.Builder b = Approvals.builder(c, alarm ? Approvals.EMERGENCY_CHANNEL : Approvals.ATTENTION_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher).setContentTitle((alarm ? "🚨 " : "") + title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body)).setColor(0xFFFF1728).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, alarmId, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
            if (alarm) {
                b.setCategory(Notification.CATEGORY_ALARM).setPriority(Notification.PRIORITY_MAX)
                    .setFullScreenIntent(PendingIntent.getActivity(c, alarmId + 1, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000), true)
                    .addAction(new Notification.Action.Builder(0, "I'M ON IT", Approvals.action(c, "ACK", 0)).build());
            }
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(alarmId++, b.build());
        } catch (Exception ignored) {
        }
    }

    // ------------------------------ SMS (works even when the phone is locked) ------------------------------
    static BroadcastReceiver smsResult;

    static synchronized void listen(Context c) {
        if (smsResult != null) return;
        smsResult = new BroadcastReceiver() {
            public void onReceive(Context ctx, Intent i) {
                Store s = new Store(ctx);
                boolean ok = getResultCode() == android.app.Activity.RESULT_OK;
                if (SENT.equals(i.getAction())) s.activity(ok ? "owner" : "failed", ok ? "Report SMS sent to Arsh's number (waiting for delivery confirmation)" : "Report SMS to Arsh FAILED to send (no signal or no SMS balance?)");
                else s.activity("owner", ok ? "Report SMS DELIVERED to Arsh's number" : "Report SMS was not delivered");
            }
        };
        IntentFilter f = new IntentFilter(SENT);
        f.addAction(DELIVERED);
        try {
            c.getApplicationContext().registerReceiver(smsResult, f);
        } catch (Exception ignored) {
        }
    }

    public static boolean sms(Context c, Store s, String text) {
        return smsTo(c, s, number(s), text);
    }

    public static boolean smsTo(Context c, Store s, String to, String text) {
        try {
            listen(c);
            SmsManager m = SmsManager.getDefault();
            ArrayList<String> parts = m.divideMessage(text);
            ArrayList<PendingIntent> sent = new ArrayList<>(), got = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                int id = smsId++;
                sent.add(i == parts.size() - 1 ? PendingIntent.getBroadcast(c, id, new Intent(SENT).setPackage(c.getPackageName()), 0x04000000) : null);
                got.add(i == parts.size() - 1 ? PendingIntent.getBroadcast(c, 100000 + id, new Intent(DELIVERED).setPackage(c.getPackageName()), 0x04000000) : null);
            }
            m.sendMultipartTextMessage(to, null, parts, sent, got);
            return true;
        } catch (Exception e) {
            s.activity("failed", "Could not send SMS to " + to + ": " + e.getMessage());
            return false;
        }
    }

    // ------------------------------ WhatsApp (needs the screen helper and an unlocked phone) ------------------------------
    static JSONArray pendingWa(Store s) {
        try { return new JSONArray(s.getSecret("owner_wa_queue", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static void whatsapp(final Context c, final Store s, final String text) {
        synchronized (Owner.class) {
            JSONArray q = pendingWa(s);
            if (q.length() < 20) q.put(text);
            s.putSecret("owner_wa_queue", q.toString());
        }
        flushWhatsApp(c);
    }

    /** Sends queued WhatsApp reports now if the phone is unlocked; otherwise they wait for the next unlock. */
    public static void flushWhatsApp(final Context c) {
        AutoReply.pool.execute(new Runnable() {
            public void run() {
                Store s = new Store(c);
                if (!TapService.on() || AutoReply.locked(c)) return;
                JSONArray q;
                synchronized (Owner.class) {
                    q = pendingWa(s);
                    s.putSecret("owner_wa_queue", "[]");
                }
                if (q.length() == 0) return;
                StringBuilder all = new StringBuilder();
                for (int i = 0; i < q.length(); i++) all.append(i > 0 ? "\n\n——————\n\n" : "").append(q.optString(i));
                String num = number(s).replace("+", "");
                Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + num)).setPackage("com.whatsapp")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                String why = TapService.sendOnScreen(open, "com.whatsapp", all.toString());
                if (why == null) s.activity("owner", "WhatsApp report sent to Arsh (" + q.length() + " item" + (q.length() == 1 ? "" : "s") + ", confirmed on screen)");
                else {
                    s.activity("failed", "WhatsApp report to Arsh not sent: " + why + ". The SMS / notification copy stands.");
                }
            }
        });
    }

    // ------------------------------ digest of routine items ------------------------------
    static JSONArray digest(Store s) {
        try { return new JSONArray(s.getSecret("owner_digest_items", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void addDigest(Store s, String line) {
        JSONArray d = digest(s);
        if (d.length() < 40) d.put(new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date()) + "  " + line);
        s.putSecret("owner_digest_items", d.toString());
    }

    static volatile boolean digestScheduled = false;

    static void scheduleDigest(final Context c) {
        if (digestScheduled) return;
        digestScheduled = true;
        AutoReply.timer.schedule(new Runnable() {
            public void run() {
                digestScheduled = false;
                sendDigest(c);
            }
        }, digestMin(new Store(c)), TimeUnit.MINUTES);
    }

    public static void sendDigest(Context c) {
        Store s = new Store(c);
        JSONArray d;
        synchronized (Owner.class) {
            d = digest(s);
            s.putSecret("owner_digest_items", "[]");
        }
        if (d.length() == 0) return;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length(); i++) b.append("• ").append(d.optString(i)).append("\n");
        String text = HEAD + "SUMMARY 🤖 (" + d.length() + " update" + (d.length() == 1 ? "" : "s") + ")\n\n" + b.toString().trim();
        notifyHere(c, "ULTRON summary", b.toString().trim(), false);
        AutoReply.rememberSent(s, text);
        if (viaWhatsApp(s)) whatsapp(c, s, text);
        else if (viaSms(s) && !samePhone(s)) sms(c, s, text);
        s.activity("owner", "Summary of " + d.length() + " routine updates prepared for Arsh");
    }

    // ------------------------------ emergencies ------------------------------
    static final ArrayList<Long> ownerCalls = new ArrayList<>();
    static volatile String openIncident = null;

    /**
     * A genuine emergency: alarm here, SMS + WhatsApp to Arsh at once, then a direct call if he has not
     * acknowledged within 45 seconds. At most one call per incident and maxCalls per hour; SMS repeated
     * after 5 and 15 minutes until he taps I'M ON IT.
     */
    public static void emergency(final Context c, String who, String app, String details) {
        final Store s = new Store(c);
        final String incident = AutoReply.sha(who + "|" + app + "|" + (System.currentTimeMillis() / (10 * 60000L)));
        if (incident.equals(openIncident)) return;                     // same emergency already being escalated
        openIncident = incident;
        s.put("emerg_ack", "");
        String when = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date());
        final String text = HEAD + "🚨 EMERGENCY ALERT\n\nFrom: " + who + "\nApp: " + app + "\nTime: " + when
            + "\n\nWhat they said:\n" + details + "\n\nThis may need your immediate attention. Please check and contact them.";
        s.activity("emergency", "EMERGENCY from " + who + " (" + app + "): " + details);
        notifyHere(c, "EMERGENCY · " + who + " (" + app + ")", details, true);
        AutoReply.rememberSent(s, text);
        if (viaSms(s) && !samePhone(s)) sms(c, s, text);
        if (viaWhatsApp(s)) whatsapp(c, s, text);
        // escalate: call Arsh if he has not acknowledged
        AutoReply.timer.schedule(new Runnable() {
            public void run() {
                if (!incident.equals(openIncident) || !s.get("emerg_ack", "").isEmpty()) return;
                callOwner(c, s, "Emergency from " + who(text));
            }
        }, 45, TimeUnit.SECONDS);
        for (final int min : new int[]{5, 15}) {
            AutoReply.timer.schedule(new Runnable() {
                public void run() {
                    if (!incident.equals(openIncident) || !s.get("emerg_ack", "").isEmpty()) return;
                    s.activity("emergency", "Arsh has not acknowledged the emergency yet: reminder " + min + " min");
                    notifyHere(c, "STILL WAITING · Emergency", text.replace(HEAD, ""), true);
                    if (viaSms(s) && !samePhone(s)) sms(c, s, HEAD + "REMINDER (" + min + " min): " + text.replace(HEAD, ""));
                }
            }, min, TimeUnit.MINUTES);
        }
    }

    static String who(String text) {
        int i = text.indexOf("From: ");
        return i < 0 ? "someone" : text.substring(i + 6, text.indexOf('\n', i));
    }

    public static void acknowledge(Context c) {
        Store s = new Store(c);
        s.put("emerg_ack", String.valueOf(System.currentTimeMillis()));
        openIncident = null;
        s.activity("emergency", "Arsh acknowledged the emergency: escalation stopped");
    }

    static void callOwner(Context c, Store s, String why) {
        if (!directCall(s)) {
            s.activity("emergency", "Direct call to Arsh is switched off: alerts only");
            return;
        }
        if (samePhone(s)) {
            s.activity("emergency", "Owner number is this same phone: alarm notification used instead of a call");
            return;
        }
        synchronized (ownerCalls) {
            long now = System.currentTimeMillis();
            while (!ownerCalls.isEmpty() && now - ownerCalls.get(0) > 3600000L) ownerCalls.remove(0);
            if (ownerCalls.size() >= maxCalls(s)) {
                s.activity("emergency", "Owner call limit reached (" + maxCalls(s) + " per hour): not calling again");
                return;
            }
            ownerCalls.add(now);
        }
        try {
            Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number(s))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(call);
            s.activity("emergency", "Calling Arsh at " + number(s) + " (" + why + "). Whether he answers cannot be confirmed by ULTRON.");
        } catch (Exception e) {
            s.activity("failed", "Could not call Arsh: " + e.getMessage());
        }
    }
}
