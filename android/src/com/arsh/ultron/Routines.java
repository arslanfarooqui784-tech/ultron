package com.arsh.ultron;

import android.content.Context;
import android.media.AudioManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Routines: one tap or one sentence switches the phone and ULTRON into a mode, and back.
 *  sleep    - Do Not Disturb (priority), vibrate, ULTRON answers for Arsh with his sleep status
 *  driving  - sound on, ULTRON reads new messages aloud and answers with his driving status
 *  meeting  - Do Not Disturb (priority), vibrate, meeting status
 *  study    - Do Not Disturb (priority), study status
 * The status text is set by Arsh, so ULTRON may share exactly that, and nothing more.
 */
public class Routines {
    public static final String[] NAMES = {"sleep", "driving", "meeting", "study"};

    static JSONObject defaults(String name) throws Exception {
        switch (name) {
            case "sleep":
                return new JSONObject().put("dnd", true).put("ringer", "vibrate").put("reply", "auto").put("readAloud", false)
                    .put("status", "Arsh is sleeping right now and will reply when he wakes up.");
            case "driving":
                return new JSONObject().put("dnd", false).put("ringer", "normal").put("reply", "auto").put("readAloud", true)
                    .put("status", "Arsh is driving right now and will reply when he has safely reached.");
            case "meeting":
                return new JSONObject().put("dnd", true).put("ringer", "vibrate").put("reply", "auto").put("readAloud", false)
                    .put("status", "Arsh is in a meeting right now and will reply after it.");
            default:
                return new JSONObject().put("dnd", true).put("ringer", "vibrate").put("reply", "auto").put("readAloud", false)
                    .put("status", "Arsh is studying right now and will reply after his study session.");
        }
    }

    public static JSONObject config(Store s, String name) {
        try {
            JSONObject d = defaults(name), saved = new JSONObject(s.get("routine_cfg:" + name, "{}"));
            java.util.Iterator<String> k = saved.keys();
            while (k.hasNext()) {
                String key = k.next();
                d.put(key, saved.get(key));
            }
            return d;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static String active(Store s) {
        return s.get("routine_active", "");
    }

    /** The status Arsh set for the current routine (given to the reply AI), or "". */
    public static String status(Store s) {
        String a = active(s);
        String custom = s.get("status_custom", "");
        if (!custom.isEmpty()) return custom;
        return a.isEmpty() ? "" : config(s, a).optString("status", "");
    }

    public static boolean readAloud(Store s) {
        String a = active(s);
        return !a.isEmpty() && config(s, a).optBoolean("readAloud");
    }

    public static String label(String n) {
        return n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1) + " mode";
    }

    public static synchronized String start(Context c, String name, String by) {
        if (!java.util.Arrays.asList(NAMES).contains(name)) return "I don't know that routine.";
        Store s = new Store(c);
        if (!active(s).isEmpty()) stop(c, "switch");
        JSONObject cfg = config(s, name);
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        // remember how things were, to put them back afterwards
        s.put("routine_prev_ringer", String.valueOf(am.getRingerMode()));
        s.put("routine_prev_mode", AutoReply.mode(s));
        int filter = NotifService.instance != null ? NotifService.instance.getCurrentInterruptionFilter() : 1;
        s.put("routine_prev_filter", String.valueOf(filter));
        StringBuilder done = new StringBuilder();
        try {
            String r = cfg.optString("ringer", "keep");
            if (r.equals("vibrate")) am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
            else if (r.equals("normal")) am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            if (!r.equals("keep")) done.append(r).append(" sound, ");
        } catch (Exception ignored) {
        }
        if (cfg.optBoolean("dnd") && NotifService.instance != null) {
            try {
                NotifService.instance.requestInterruptionFilter(2 /* priority only */);
                done.append("Do Not Disturb, ");
            } catch (Exception ignored) {
            }
        }
        String reply = cfg.optString("reply", "keep");
        if (reply.matches("auto|approve|manual|off")) {
            s.put("auto_mode", reply);
            done.append("replies ").append(reply.equals("auto") ? "automatic" : reply).append(", ");
        }
        s.put("routine_active", name);
        if (name.equals("sleep")) Smart.sleepStart(s);
        s.put("routine_since", String.valueOf(System.currentTimeMillis()));
        s.activity("routine", label(name) + " ON (" + by + "): " + done + "status: " + cfg.optString("status"));
        AutoReply.refreshStatus(c);
        return label(name) + " is on, Arsh. " + (done.length() > 0 ? capital(done.substring(0, done.length() - 2)) + ". " : "")
            + (cfg.optBoolean("readAloud") ? "I'll read new messages aloud. " : "") + "I'll tell people: " + cfg.optString("status");
    }

    public static synchronized String stop(Context c, String by) {
        Store s = new Store(c);
        String a = active(s);
        if (a.isEmpty()) return "No routine is running, Arsh.";
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        try {
            am.setRingerMode(Integer.parseInt(s.get("routine_prev_ringer", String.valueOf(AudioManager.RINGER_MODE_NORMAL))));
        } catch (Exception ignored) {
        }
        try {
            if (NotifService.instance != null) NotifService.instance.requestInterruptionFilter(Integer.parseInt(s.get("routine_prev_filter", "1")));
        } catch (Exception ignored) {
        }
        String prev = s.get("routine_prev_mode", "");
        if (prev.matches("auto|approve|manual|off")) s.put("auto_mode", prev);
        if (a.equals("sleep")) Smart.sleepEnd(s);
        s.put("routine_active", "");
        s.put("status_custom", "");
        Speaker.stop();
        s.activity("routine", label(a) + " OFF (" + by + "): sound, Do Not Disturb and reply mode put back");
        AutoReply.refreshStatus(c);
        return label(a) + " is off. Everything is back to how it was, Arsh.";
    }

    static String capital(String t) {
        return t.isEmpty() ? t : t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }

    // ---------------- schedule (sleep mode every night, for example) ----------------
    public static void schedule(Context c) {
        Store s = new Store(c);
        for (String n : NAMES) {
            JSONObject cfg = config(s, n);
            android.app.PendingIntent on = Agenda.alarmIntent(c, "ROUTINE_START", n), off = Agenda.alarmIntent(c, "ROUTINE_END", n);
            String from = cfg.optString("from", ""), to = cfg.optString("to", "");
            if (cfg.optBoolean("scheduled") && from.matches("\\d{1,2}:\\d{2}") && to.matches("\\d{1,2}:\\d{2}")) {
                Agenda.at(c, Agenda.nextTime(from), on);
                Agenda.at(c, Agenda.nextTime(to), off);
            } else {
                Agenda.cancel(c, on);
                Agenda.cancel(c, off);
            }
        }
    }

    public static JSONObject info(Store s) throws Exception {
        JSONArray list = new JSONArray();
        for (String n : NAMES) list.put(config(s, n).put("name", n));
        return new JSONObject().put("active", active(s)).put("status", status(s)).put("routines", list);
    }

    public static void save(Context c, JSONObject in) {
        Store s = new Store(c);
        String n = in.optString("name");
        if (!java.util.Arrays.asList(NAMES).contains(n)) return;
        try {
            JSONObject cfg = new JSONObject(s.get("routine_cfg:" + n, "{}"));
            for (String k : new String[]{"dnd", "readAloud", "scheduled"}) if (in.has(k)) cfg.put(k, in.optBoolean(k));
            if (in.has("ringer") && in.optString("ringer").matches("keep|vibrate|normal")) cfg.put("ringer", in.optString("ringer"));
            if (in.has("reply") && in.optString("reply").matches("keep|auto|approve|manual|off")) cfg.put("reply", in.optString("reply"));
            if (in.has("status")) cfg.put("status", Memory.clip(in.optString("status").trim(), 200));
            for (String k : new String[]{"from", "to"}) if (in.has(k) && in.optString(k).matches("\\d{1,2}:\\d{2}")) cfg.put(k, in.optString(k));
            s.put("routine_cfg:" + n, cfg.toString());
            s.activity("settings", label(n) + " settings saved");
        } catch (Exception ignored) {
        }
        schedule(c);
    }
}
