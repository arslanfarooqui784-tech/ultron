package com.arsh.ultron;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * ULTRON's call desk. What Android really allows an app like ULTRON to do with calls:
 *  - SIM calls: see who is calling (number + contact name), know if Arsh answered, missed or declined,
 *    decline unwanted callers, text a missed caller by SMS. It cannot speak into or listen to a phone call.
 *  - WhatsApp / Instagram / Snapchat / Telegram / Messenger calls: see the incoming-call and missed-call
 *    notifications only. They offer no way for another app to answer or hear the call.
 * Everything is logged, and Arsh gets told about the calls that matter.
 */
public class Calls {
    static final String[] CATS = {"family", "close", "important", "professional", "business", "known", "unknown", "unwanted", "restricted"};

    // ------------------------------ settings ------------------------------
    public static boolean enabled(Store s) { return !"0".equals(s.get("calls_on", "1")); }
    static boolean textBackKnown(Store s) { return !"0".equals(s.get("calls_tb_known", "1")); }
    static boolean textBackUnknown(Store s) { return "1".equals(s.get("calls_tb_unknown", "0")); }
    static boolean rejectUnwanted(Store s) { return "1".equals(s.get("calls_reject_unwanted", "0")); }
    static boolean missedAlerts(Store s) { return !"0".equals(s.get("calls_missed_alerts", "1")); }

    public static JSONObject info(Store s) throws Exception {
        return new JSONObject().put("on", enabled(s)).put("tbKnown", textBackKnown(s)).put("tbUnknown", textBackUnknown(s))
            .put("rejectUnwanted", rejectUnwanted(s)).put("missedAlerts", missedAlerts(s)).put("log", log(s));
    }

    public static void save(Store s, JSONObject in) {
        if (in.has("callsOn")) s.put("calls_on", in.optBoolean("callsOn") ? "1" : "0");
        if (in.has("tbKnown")) s.put("calls_tb_known", in.optBoolean("tbKnown") ? "1" : "0");
        if (in.has("tbUnknown")) s.put("calls_tb_unknown", in.optBoolean("tbUnknown") ? "1" : "0");
        if (in.has("rejectUnwanted")) s.put("calls_reject_unwanted", in.optBoolean("rejectUnwanted") ? "1" : "0");
        if (in.has("missedAlerts")) s.put("calls_missed_alerts", in.optBoolean("missedAlerts") ? "1" : "0");
    }

    // ------------------------------ contact categories ------------------------------
    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public static String category(Store s, String name) {
        String c = s.get("cat:" + key(name), "");
        if (!c.isEmpty()) return c;
        return name == null || name.replaceAll("[^\\p{L}]", "").isEmpty() ? "unknown" : "known";
    }

    public static void setCategory(Store s, String name, String cat) {
        boolean ok = false;
        for (String c : CATS) if (c.equals(cat)) ok = true;
        if (!ok || key(name).isEmpty()) return;
        s.put("cat:" + key(name), cat);
        // keep a list per category so "tell all family ..." knows who is in it
        try {
            for (String c : CATS) {
                JSONArray g = new JSONArray(s.get("grp:" + c, "[]")), keep = new JSONArray();
                for (int i = 0; i < g.length(); i++) if (!key(g.optString(i)).equals(key(name))) keep.put(g.optString(i));
                if (c.equals(cat)) keep.put(name.trim());
                s.put("grp:" + c, keep.toString());
            }
        } catch (Exception ignored) {
        }
        s.activity("settings", name + " is now in the category: " + cat);
    }

    static boolean vip(String cat) {
        return cat.equals("family") || cat.equals("close") || cat.equals("important");
    }

    static boolean blocked(String cat) {
        return cat.equals("unwanted") || cat.equals("restricted");
    }

    // ------------------------------ call log (encrypted) ------------------------------
    static JSONArray log(Store s) {
        try { return new JSONArray(s.getSecret("calls_log", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void record(Store s, String app, String who, String number, String status, String note) {
        try {
            JSONArray old = log(s), now = new JSONArray();
            for (int i = Math.max(0, old.length() - 199); i < old.length(); i++) now.put(old.get(i));
            now.put(new JSONObject().put("app", app).put("who", who).put("number", number == null ? "" : number)
                .put("status", status).put("note", note == null ? "" : note).put("at", System.currentTimeMillis()));
            s.putSecret("calls_log", now.toString());
        } catch (Exception ignored) {
        }
    }

    /** How many calls this person made in the last 15 minutes (this one included). */
    static int repeats(Store s, String who) {
        JSONArray a = log(s);
        int n = 0;
        long now = System.currentTimeMillis();
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject e = a.optJSONObject(i);
            if (e == null || now - e.optLong("at") > 15 * 60000L) break;
            if (key(e.optString("who")).equals(key(who)) && (e.optString("status").equals("missed") || e.optString("status").equals("ringing")
                || e.optString("status").equals("declined"))) n++;
        }
        return n;
    }

    // ------------------------------ SIM calls ------------------------------
    static PhoneStateListener listener;
    static int lastState = TelephonyManager.CALL_STATE_IDLE;
    static boolean answered = false;
    static String ringNumber = "", ringName = "";
    static long ringAt = 0;

    /** Starts watching SIM calls (called when ULTRON's notification reader connects). */
    public static synchronized void watch(final Context c) {
        if (listener != null) return;
        try {
            final TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            listener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String number) {
                    handle(c.getApplicationContext(), state, number);
                }
            };
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (Exception e) {
            listener = null;
        }
    }

    static void handle(final Context c, int state, String number) {
        final Store s = new Store(c);
        if (!enabled(s)) {
            lastState = state;
            return;
        }
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            ringNumber = number == null ? "" : number;
            ringName = name(c, ringNumber);
            ringAt = System.currentTimeMillis();
            answered = false;
            String who = ringName.isEmpty() ? (ringNumber.isEmpty() ? "Unknown number" : ringNumber) : ringName;
            String cat = category(s, ringName.isEmpty() ? ringNumber : ringName);
            record(s, "Phone", who, ringNumber, "ringing", cat);
            announce(c, s, who, "");
            ringingNotice(c, who);
            s.activity("call", "Incoming phone call from " + who + (ringNumber.isEmpty() || who.equals(ringNumber) ? "" : " (" + ringNumber + ")") + " · " + cat);
            if (blocked(cat) && rejectUnwanted(s)) {
                try {
                    android.telecom.TelecomManager t = (android.telecom.TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
                    Object ok = android.telecom.TelecomManager.class.getMethod("endCall").invoke(t);
                    s.activity("call", "Declined the call from " + who + " (" + cat + " contact)" + (Boolean.TRUE.equals(ok) ? "" : ": Android did not allow it"));
                    record(s, "Phone", who, ringNumber, "declined", "auto: " + cat);
                } catch (Exception e) {
                    s.activity("failed", "Could not decline the call from " + who + ": " + e.getMessage());
                }
            }
        } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            Speaker.stop();
            cancelRingingNotice(c);
            if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                answered = true;
                String who = ringName.isEmpty() ? (ringNumber.isEmpty() ? "Unknown number" : ringNumber) : ringName;
                record(s, "Phone", who, ringNumber, "answered", "Arsh answered");
                s.activity("call", "Arsh answered the call from " + who);
            }
        } else if (state == TelephonyManager.CALL_STATE_IDLE && lastState == TelephonyManager.CALL_STATE_RINGING && !answered) {
            cancelRingingNotice(c);
            final String num = ringNumber, nm = ringName;
            final long at = ringAt;
            // the call log tells missed from declined a moment after the call ends
            AutoReply.timer.schedule(new Runnable() {
                public void run() {
                    AutoReply.pool.execute(new Runnable() {
                        public void run() {
                            String type = logType(c, num, at);
                            missed(c, "Phone", nm.isEmpty() ? (num.isEmpty() ? "Unknown number" : num) : nm, num, type.equals("declined"), null);
                        }
                    });
                }
            }, 2500, TimeUnit.MILLISECONDS);
        }
        lastState = state;
    }

    /** "Arslan is calling" out loud (not during sleep, meetings or quiet hours, not for unwanted callers). */
    static void announce(Context c, Store s, String who, String app) {
        if (true) return; // Arsh: never say caller names out loud
        if (!Smart.on(s, "announce_calls", true) || AutoReply.quietNow(s) || Routines.active(s).matches("sleep|meeting|study")) return;
        if (blocked(category(s, who))) return;
        Speaker.say(c, (who.matches("[+\\d\\s-]+") ? "Unknown number" : who) + " is calling" + (app.isEmpty() ? "" : " on " + app));
    }

    static void ringingNotice(Context c, String who) {
        Smart.notify(c, 6900, "📞 " + who + " is calling", "Busy? Tap to decline and text them that you'll call back soon.",
            android.app.PendingIntent.getBroadcast(c, 6901, new android.content.Intent(c, AgendaReceiver.class).setAction("com.arsh.ultron.DECLINE_TEXT"),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
    }

    static void cancelRingingNotice(Context c) {
        try {
            ((android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(6900);
        } catch (Exception ignored) {
        }
    }

    /** Declines the ringing SIM call and texts the caller (Arsh asked for it by tapping or saying so). */
    public static String declineAndText(Context c) {
        Store s = new Store(c);
        String num = ringNumber, who = ringName.isEmpty() ? ringNumber : ringName;
        try {
            android.telecom.TelecomManager t = (android.telecom.TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            android.telecom.TelecomManager.class.getMethod("endCall").invoke(t);
        } catch (Exception e) {
            return "I couldn't decline the call (allow phone permission in Setup).";
        }
        cancelRingingNotice(c);
        if (num == null || num.isEmpty()) return "Call declined. I couldn't see the number, so no text was sent.";
        String msg = "Arsh can't talk right now and will call you back soon. - ULTRON, Arsh's AI Assistant";
        boolean ok = Owner.smsTo(c, s, num, msg);
        AutoReply.rememberSent(s, msg);
        record(s, "Phone", who, num, "declined", "declined with a text");
        s.activity("call", "Declined " + who + " and texted: " + msg);
        return ok ? "Declined. I texted " + who + " that you'll call back." : "Declined, but the text did not send.";
    }

    static String name(Context c, String number) {
        if (number == null || number.isEmpty()) return "";
        try {
            Uri u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            Cursor cur = c.getContentResolver().query(u, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) return cur.getString(0);
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    static String logType(Context c, String number, long since) {
        try {
            Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.TYPE, CallLog.Calls.NUMBER},
                CallLog.Calls.DATE + ">=?", new String[]{String.valueOf(since - 60000)}, CallLog.Calls.DATE + " DESC");
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        int t = cur.getInt(0);
                        return t == 5 /* REJECTED */ || t == 6 /* BLOCKED */ ? "declined" : t == CallLog.Calls.MISSED_TYPE ? "missed" : "other";
                    }
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ignored) {
        }
        return "missed";
    }

    // ------------------------------ app calls (from notifications) ------------------------------
    static final java.util.regex.Pattern RINGING = java.util.regex.Pattern.compile(
        "(?i)(incoming (voice |video )?call|is calling|calling you|ringing|wants to video chat|video chatting|audio call|voice call|video call)");
    static final java.util.regex.Pattern MISSED = java.util.regex.Pattern.compile("(?i)(missed (voice |video |audio )?calls?|missed a call|tried to call)");
    static final java.util.Map<String, Long> seenRing = new java.util.HashMap<>();

    /** True if this notification is about a call (ringing, ongoing or missed); handled here instead of as a message. */
    public static boolean fromNotification(Context c, android.service.notification.StatusBarNotification sbn, String app, String title, String text) {
        android.app.Notification n = sbn.getNotification();
        String cat = n.category == null ? "" : n.category;
        String all = title + " " + text;
        boolean missed = cat.equals("missed_call") || MISSED.matcher(all).find();
        boolean call = cat.equals("call") || RINGING.matcher(all).find();
        if (!missed && !call) return false;
        Store s = new Store(c);
        String who = title.replaceAll("(?i)^(missed (voice |video )?call from|incoming (voice |video )?call from)\\s*", "").trim();
        if (who.isEmpty() || who.equalsIgnoreCase(app)) who = text.replaceAll("(?i)(missed (voice |video )?calls?|incoming (voice |video )?call|from|:)", "").trim();
        if (missed) {
            String k = "m|" + sbn.getPackageName() + "|" + key(who) + "|" + text;
            synchronized (seenRing) {
                Long t = seenRing.get(k);
                if (t != null && System.currentTimeMillis() - t < 10 * 60000L) return true;
                seenRing.put(k, System.currentTimeMillis());
            }
            if (enabled(s)) missed(c, app, who, null, false, sbn.getPackageName());
            return true;
        }
        boolean ongoing = (n.flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0 && Pattern2.ONGOING.matcher(all).find();
        String k = "r|" + sbn.getPackageName() + "|" + key(who);
        synchronized (seenRing) {
            Long t = seenRing.get(k + ongoing);
            if (t != null && System.currentTimeMillis() - t < 2 * 60000L) return true;
            seenRing.put(k + ongoing, System.currentTimeMillis());
        }
        if (!enabled(s)) return true;
        if (ongoing) {
            record(s, app, who, null, "answered", "call in progress");
            s.activity("call", "Call in progress with " + who + " on " + app);
        } else {
            boolean video = all.toLowerCase(Locale.ROOT).contains("video");
            record(s, app, who, null, "ringing", video ? "video call" : "voice call");
            announce(c, s, who, app);
            s.activity("call", "Incoming " + app + (video ? " video" : " voice") + " call from " + who + " · " + category(s, who)
                + ". " + app + " does not let any other app answer its calls: Arsh answers personally.");
        }
        return true;
    }

    static class Pattern2 {
        static final java.util.regex.Pattern ONGOING = java.util.regex.Pattern.compile("(?i)(ongoing|on call|in call|tap to return|call in progress|\\d+:\\d{2})");
    }

    // ------------------------------ a missed call ------------------------------
    static void missed(Context c, String app, String who, String number, boolean declined, String pkg) {
        Store s = new Store(c);
        String cat = category(s, who);
        record(s, app, who, number, declined ? "declined" : "missed", cat);
        int times = repeats(s, who);
        String when = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date());
        s.activity("call", (declined ? "Declined call from " : "Missed call from ") + who + " on " + app + (times > 1 ? " · " + times + " calls in 15 min" : ""));
        if (blocked(cat)) return;                                         // unwanted / restricted: logged only

        // what did they write recently? (same person, any chat of that app)
        String recentText = recentMessages(s, who, pkg);
        boolean emergency = AutoReply.EMERGENCY.matcher(recentText).find();

        // text-back so the caller knows their call was noticed
        boolean known = !cat.equals("unknown");
        boolean textBack = !declined && AutoReply.mode(s).equals("auto") && (known ? textBackKnown(s) : textBackUnknown(s))
            && !AutoReply.quietNow(s);
        String sentNote = "";
        if (textBack && System.currentTimeMillis() - lastTextBack(s, who) > 30 * 60000L) {
            String msg = AutoReply.INTRO + " Arsh couldn't take your call just now. If you tell me what it's about, I'll make sure he gets it right away. 🙏";
            boolean sent = false;
            if (number != null && !number.isEmpty() && app.equals("Phone")) {
                sent = Owner.smsTo(c, s, number, msg);
            } else if (pkg != null) {
                for (NotifService.Note n : NotifService.notes()) {
                    if (n.pkg.equals(pkg) && n.reply != null && NotifService.exact(n.title).equals(NotifService.exact(who)) && n.senders.size() <= 1) {
                        sent = NotifService.sendReply(n, msg);
                        break;
                    }
                }
            }
            if (sent) {
                AutoReply.rememberSent(s, msg);
                s.put("textback:" + key(who), String.valueOf(System.currentTimeMillis()));
                sentNote = "\nULTRON texted them to ask what it is about.";
                s.activity("sent", "Told " + who + " (" + app + ") that Arsh missed the call and asked what it is about");
            }
        }

        if (emergency) {
            Owner.emergency(c, who, app, "Missed call(s) (" + times + " in 15 min) plus messages: " + recentText);
            return;
        }
        if (!missedAlerts(s)) return;
        String priority = times >= 3 || vip(cat) ? "high" : (times >= 2 ? "high" : "normal");
        String body = "Caller: " + who + (number != null && !number.isEmpty() && !number.equals(who) && known ? " (" + number + ")" : "")
            + "\nApp: " + app + "\nTime: " + when + "\nStatus: " + (declined ? "Declined" : "Missed")
            + (times > 1 ? "\nCalled " + times + " times in 15 minutes" : "")
            + (recentText.isEmpty() ? "" : "\nTheir recent messages: " + recentText)
            + sentNote
            + "\n\nRecommended: " + (times >= 2 || vip(cat) ? "call " + who + " back when you can." : "check if you want to call back.");
        Owner.report(c, priority, (declined ? "DECLINED CALL" : "MISSED CALL") + " · " + who, body);
    }

    static long lastTextBack(Store s, String who) {
        try { return Long.parseLong(s.get("textback:" + key(who), "0")); } catch (Exception e) { return 0; }
    }

    static String recentMessages(Store s, String who, String pkg) {
        StringBuilder b = new StringBuilder();
        JSONArray a = AutoReply.Events.all(s);
        long now = System.currentTimeMillis();
        for (int i = a.length() - 1; i >= 0 && b.length() < 400; i--) {
            JSONObject e = a.optJSONObject(i);
            if (e == null || now - e.optLong("received") > 30 * 60000L) continue;
            if ("outgoing".equals(e.optString("kind")) || "ultron".equals(e.optString("status")) || "arsh".equals(e.optString("status"))) continue;
            if (pkg != null && !pkg.equals(e.optString("pkg"))) continue;
            if (!NotifService.exact(e.optString("who")).equals(key(who)) && !NotifService.exact(e.optString("sender")).equals(key(who))) continue;
            b.insert(0, "\"" + e.optString("text") + "\" ");
        }
        return b.toString().trim();
    }
}
