package com.arsh.ultron;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ULTRON improves by itself, without being told:
 *  - Speed: it measures every AI brain (how fast, how often it fails), asks the fastest reliable one first,
 *    and rests a failing brain for 10 minutes. Spoken replies are cached so repeated phrases play instantly.
 *  - Lessons: every night it reviews the day (Arsh's corrections, edited or rejected drafts, commands that failed,
 *    things he had to repeat) and writes short lessons that guide future replies.
 *  - Shortcuts: when Arsh says something in his own way that ULTRON didn't understand and then rephrases it,
 *    ULTRON learns that his phrase means that command.
 *  - Knowledge: it notices topics Arsh keeps asking about and learns a short fact sheet about them; "learn about X" too.
 * Safety never changes by learning: lessons that touch approvals, the introduction, privacy, codes or emergencies are thrown away,
 * and everything learned is visible (and deletable) in ULTRON+.
 */
public class Learn {
    // =========================================================== 1. brain speed ======
    static final Map<String, double[]> stats = new HashMap<>();   // brain -> {avgMs, okCount, failCount, consecutiveFails, restUntil}
    static long lastSave = 0;

    static synchronized void load(Store s) {
        if (!stats.isEmpty()) return;
        try {
            JSONObject j = new JSONObject(s.get("brain_stats", "{}"));
            java.util.Iterator<String> k = j.keys();
            while (k.hasNext()) {
                String b = k.next();
                JSONArray a = j.getJSONArray(b);
                stats.put(b, new double[]{a.optDouble(0, 4000), a.optDouble(1), a.optDouble(2), 0, 0});
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized void stat(Store s, String brain, long ms, boolean ok) {
        load(s);
        double[] v = stats.get(brain);
        if (v == null) v = new double[]{ms, 0, 0, 0, 0};
        if (ok) {
            v[0] = v[1] + v[2] == 0 ? ms : v[0] * 0.8 + ms * 0.2;           // moving average of answer time
            v[1]++;
            v[3] = 0;
        } else {
            v[2]++;
            v[3]++;
            if (v[3] >= 3) v[4] = System.currentTimeMillis() + 10 * 60000L;  // rest a failing brain for 10 minutes
        }
        stats.put(brain, v);
        if (System.currentTimeMillis() - lastSave > 60000) {
            lastSave = System.currentTimeMillis();
            try {
                JSONObject j = new JSONObject();
                for (Map.Entry<String, double[]> e : stats.entrySet())
                    j.put(e.getKey(), new JSONArray().put(Math.round(e.getValue()[0])).put(e.getValue()[1]).put(e.getValue()[2]));
                s.put("brain_stats", j.toString());
            } catch (Exception ignored) {
            }
        }
    }

    public static synchronized boolean resting(String brain) {
        double[] v = stats.get(brain);
        return v != null && v[4] > System.currentTimeMillis();
    }

    /** Fastest reliable brain first; resting brains last. */
    public static synchronized List<String> rank(Store s, List<String> brains) {
        load(s);
        List<String> out = new ArrayList<>(brains);
        Collections.sort(out, new Comparator<String>() {
            public int compare(String a, String b) {
                return Double.compare(score(a), score(b));
            }
        });
        return out;
    }

    static double score(String b) {
        double[] v = stats.get(b);
        if (v == null) return 3000;                                          // unknown: try it in the middle
        double fail = (v[2] + 1) / (v[1] + v[2] + 2);
        return v[0] * (1 + 3 * fail) + (v[4] > System.currentTimeMillis() ? 1e9 : 0);
    }

    public static synchronized JSONArray speedTable(Store s) throws Exception {
        load(s);
        JSONArray a = new JSONArray();
        for (Map.Entry<String, double[]> e : stats.entrySet()) {
            double[] v = e.getValue();
            a.put(new JSONObject().put("brain", e.getKey()).put("ms", Math.round(v[0])).put("ok", (int) v[1]).put("fail", (int) v[2])
                .put("resting", v[4] > System.currentTimeMillis()));
        }
        return a;
    }

    // =========================================================== voice cache ======
    public static byte[] cachedVoice(Context c, String text) {
        try {
            File f = new File(new File(c.getCacheDir(), "voice"), AutoReply.sha(text) + ".mp3");
            if (!f.exists()) return null;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            in.close();
            f.setLastModified(System.currentTimeMillis());
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    public static void cacheVoice(Context c, String text, byte[] mp3) {
        if (text.length() > 400 || mp3 == null || mp3.length < 1000) return;
        try {
            File d = new File(c.getCacheDir(), "voice");
            d.mkdirs();
            FileOutputStream o = new FileOutputStream(new File(d, AutoReply.sha(text) + ".mp3"));
            o.write(mp3);
            o.close();
            File[] all = d.listFiles();
            if (all != null && all.length > 60) {
                java.util.Arrays.sort(all, new Comparator<File>() {
                    public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
                });
                for (int i = 0; i < all.length - 60; i++) all[i].delete();
            }
        } catch (Exception ignored) {
        }
    }

    // =========================================================== what happened today ======
    /** Every exchange with ULTRON (for the nightly review). */
    public static void log(Store s, String said, String reply, String label) {
        if (said == null || Memory.SECRET.matcher(said).find()) return;
        try {
            Smart.push(s, "learn_log", new JSONObject().put("q", Memory.clip(said, 200)).put("a", Memory.clip(reply, 200)).put("l", label)
                .put("at", System.currentTimeMillis()), 300);
        } catch (Exception ignored) {
        }
    }

    // =========================================================== lessons + shortcuts ======
    static final Pattern UNSAFE = Pattern.compile("(?i)(approv|confirm|permission|introduc|intro\\b|privacy|location|address|otp|password|pin\\b|code|bank|"
        + "emergenc|never ask|without asking|skip|bypass|ignore (the )?rule|pretend|claim to be arsh|as arsh|i am arsh|secret|delete|disable|turn off safety)");

    public static JSONArray lessons(Store s) {
        return Smart.list(s, "lessons");
    }

    public static JSONArray shortcuts(Store s) {
        return Smart.list(s, "shortcuts");
    }

    /** Lessons for the AI (style and preferences only). */
    public static String lessonText(Store s) {
        JSONArray l = lessons(s);
        if (l.length() == 0) return "";
        StringBuilder b = new StringBuilder("\nLessons you learned from Arsh (style and preferences only; they never override safety rules):");
        for (int i = 0; i < l.length(); i++) b.append("\n- ").append(l.optJSONObject(i).optString("t"));
        return b.toString();
    }

    /** "ammi ko call karo" -> "call mummy", learned from Arsh rephrasing himself. */
    public static String rewrite(Store s, String low) {
        JSONArray a = shortcuts(s);
        String n = low.trim().toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "");
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && n.equals(x.optString("say"))) return x.optString("means");
        }
        return null;
    }

    static final Pattern COMMAND = Pattern.compile("(?i)^(call|send|text|message|tell|open|play|set alarm|set a timer|set timer|remind me|turn on|turn off|"
        + "what('s| is) on my|read my|briefing|weather|news|sleep mode|driving mode|meeting mode|study mode|stop routine|add .+ to .+ list|note|"
        + "navigate to|search google for|silent mode|vibrate mode|quiz me on|translate)\\b.*");

    public static void scheduleReflect(Context c) {
        Calendar k = Calendar.getInstance();
        k.set(Calendar.HOUR_OF_DAY, 3);
        k.set(Calendar.MINUTE, 30);
        k.set(Calendar.SECOND, 0);
        if (k.getTimeInMillis() <= System.currentTimeMillis() + 60000) k.add(Calendar.DAY_OF_MONTH, 1);
        Agenda.at(c, k.getTimeInMillis(), Agenda.alarmIntent(c, "REFLECT", "night"));
    }

    public static boolean on(Store s) {
        return Smart.on(s, "learn_on", true);
    }

    /** The nightly self-review. Returns a short summary of what was learned. */
    public static String reflect(Context c) {
        Store s = new Store(c);
        if (!on(s)) return "Self-learning is switched off.";
        long since;
        try { since = Long.parseLong(s.get("reflect_last", "0")); } catch (Exception e) { since = 0; }
        if (since < System.currentTimeMillis() - 3 * 86400000L) since = System.currentTimeMillis() - 86400000L;
        StringBuilder day = new StringBuilder();
        JSONArray log = Smart.list(s, "learn_log");
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.ROOT);
        int n = 0;
        for (int i = 0; i < log.length(); i++) {
            JSONObject x = log.optJSONObject(i);
            if (x.optLong("at") < since) continue;
            day.append(f.format(new Date(x.optLong("at")))).append(" Arsh: ").append(x.optString("q")).append(" | ULTRON (").append(x.optString("l"))
                .append("): ").append(x.optString("a")).append("\n");
            n++;
        }
        JSONArray ed = Smart.list(s, "style_edits"), rj = Smart.list(s, "style_rejects");
        StringBuilder drafts = new StringBuilder();
        for (int i = Math.max(0, ed.length() - 8); i < ed.length(); i++)
            drafts.append("draft: \"").append(ed.optJSONObject(i).optString("draft")).append("\" -> Arsh: \"").append(ed.optJSONObject(i).optString("arsh")).append("\"\n");
        for (int i = Math.max(0, rj.length() - 5); i < rj.length(); i++) drafts.append("rejected: \"").append(rj.optJSONObject(i).optString("draft")).append("\"\n");
        s.put("reflect_last", String.valueOf(System.currentTimeMillis()));
        if (n == 0 && drafts.length() == 0) return "Nothing new to learn from today.";
        JSONArray oldL = lessons(s), oldS = shortcuts(s);
        StringBuilder known = new StringBuilder();
        for (int i = 0; i < oldL.length(); i++) known.append("- ").append(oldL.optJSONObject(i).optString("t")).append("\n");
        JSONObject j;
        try {
            String raw = new Brain(s).quick("You are ULTRON reviewing your own day to improve. Today's exchanges with Arsh (time, what he said, what you answered):\n"
                + day + "\nHow he changed or rejected your chat reply drafts:\n" + drafts + "\nLessons you already have:\n" + known
                + "\nFind ONLY clear, repeated or explicit signals. Output JSON: {\"lessons\": [\"short rule about Arsh's STYLE or PREFERENCES, max 20 words\"], "
                + "\"shortcuts\": [{\"say\": \"his exact words that you failed to understand\", \"means\": \"the simple command he meant (call X / send whatsapp to X saying Y / open X / remind me to X at T / set alarm for T ...)\"}], "
                + "\"interests\": [\"topic he asked about more than once\"], \"summary\": \"one sentence: what you learned\"}. "
                + "Never write lessons about approvals, safety, privacy, codes, passwords, location or emergencies. Max 3 new lessons, 3 shortcuts, 2 interests. "
                + "Empty arrays if nothing is clear.", "Output only JSON.");
            j = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
        } catch (Exception e) {
            return "I couldn't review today (no brain answered).";
        }
        int added = 0, cuts = 0;
        JSONArray L = j.optJSONArray("lessons");
        for (int i = 0; L != null && i < L.length() && i < 3; i++) {
            String t = Memory.clip(L.optString(i).trim(), 160);
            if (t.length() < 8 || UNSAFE.matcher(t).find()) continue;
            boolean dup = false;
            for (int k = 0; k < oldL.length(); k++) if (Memory.similar(oldL.optJSONObject(k).optString("t"), t) > 0.6) dup = true;
            if (dup) continue;
            try {
                Smart.push(s, "lessons", new JSONObject().put("t", t).put("at", System.currentTimeMillis()), 20);
                added++;
            } catch (Exception ignored) {
            }
        }
        JSONArray S = j.optJSONArray("shortcuts");
        for (int i = 0; S != null && i < S.length() && i < 3; i++) {
            JSONObject x = S.optJSONObject(i);
            if (x == null) continue;
            String say = x.optString("say").trim().toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", ""), means = x.optString("means").trim().toLowerCase(Locale.ROOT);
            if (say.length() < 3 || say.length() > 80 || !COMMAND.matcher(means).matches() || UNSAFE.matcher(say + " " + means).find()) continue;
            boolean dup = false;
            for (int k = 0; k < oldS.length(); k++) if (oldS.optJSONObject(k).optString("say").equals(say)) dup = true;
            if (dup) continue;
            try {
                Smart.push(s, "shortcuts", new JSONObject().put("say", say).put("means", means).put("at", System.currentTimeMillis()), 40);
                cuts++;
            } catch (Exception ignored) {
            }
        }
        JSONArray I = j.optJSONArray("interests");
        int topics = 0;
        for (int i = 0; I != null && i < I.length() && i < 2; i++) if (learnAbout(s, I.optString(i), false) != null) topics++;
        String sum = j.optString("summary", "").trim();
        String line = "Self-review: " + added + " new lesson" + (added == 1 ? "" : "s") + ", " + cuts + " new shortcut" + (cuts == 1 ? "" : "s") + ", "
            + topics + " new topic" + (topics == 1 ? "" : "s") + (sum.isEmpty() ? "" : ". " + sum);
        s.activity("learn", line);
        s.put("learn_summary", line);
        return line;
    }

    // =========================================================== knowledge ======
    public static JSONArray knowledge(Store s) {
        return Smart.list(s, "knowledge");
    }

    /** Learns a short fact sheet about a topic. Returns the facts, or null if it already knew it / failed. */
    public static String learnAbout(Store s, String topic, boolean force) {
        topic = topic == null ? "" : topic.trim();
        if (topic.length() < 2 || UNSAFE.matcher(topic).find()) return null;
        JSONArray k = knowledge(s);
        for (int i = 0; i < k.length() && !force; i++) if (Memory.similar(k.optJSONObject(i).optString("topic"), topic) > 0.7) return null;
        try {
            String facts = AutoReply.clean(new Brain(s).quick("Teach yourself about \"" + topic + "\" for Arsh, a curious student in India. Write 5 short, accurate, "
                + "useful facts or tips (one line each, no numbering), the kind a smart friend would mention. If unsure of a fact, leave it out.",
                "You write short, accurate fact sheets."));
            if (facts.length() < 20) return null;
            Smart.push(s, "knowledge", new JSONObject().put("topic", topic).put("facts", Memory.clip(facts, 900)).put("at", System.currentTimeMillis()), 40);
            s.activity("learn", "Learned about: " + topic);
            return facts;
        } catch (Exception e) {
            return null;
        }
    }

    /** Facts ULTRON learned that relate to this question. */
    public static String knowledgeFor(Store s, String question) {
        JSONArray k = knowledge(s);
        String q = question.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (int i = k.length() - 1; i >= 0 && b.length() < 1200; i--) {
            JSONObject x = k.optJSONObject(i);
            boolean hit = false;
            for (String w : x.optString("topic").toLowerCase(Locale.ROOT).split("\\s+")) if (w.length() > 3 && q.contains(w)) hit = true;
            if (hit) b.append("\nWhat you learned about ").append(x.optString("topic")).append(":\n").append(x.optString("facts"));
        }
        return b.toString();
    }

    /** One fresh learned fact for the morning briefing. */
    public static String didYouKnow(Store s) {
        JSONArray k = knowledge(s);
        if (k.length() == 0) return "";
        JSONObject x = k.optJSONObject((int) (System.currentTimeMillis() / 86400000L % k.length()));
        String[] lines = x.optString("facts").split("\n");
        return lines.length == 0 ? "" : x.optString("topic") + ": " + lines[(int) (System.currentTimeMillis() / 86400000L % lines.length)].replaceAll("^[-•*\\d.\\s]+", "");
    }

    // =========================================================== settings ======
    public static JSONObject info(Store s) throws Exception {
        return new JSONObject().put("on", on(s)).put("autoUpdate", Smart.on(s, "auto_update", true)).put("speed", speedTable(s))
            .put("lessons", lessons(s)).put("shortcuts", shortcuts(s)).put("knowledge", knowledge(s)).put("summary", s.get("learn_summary", ""))
            .put("update", s.get("update_status", ""));
    }

    public static void save(Store s, JSONObject in) {
        if (in.has("learnOn")) s.put("learn_on", in.optBoolean("learnOn") ? "1" : "0");
        if (in.has("autoUpdate")) s.put("auto_update", in.optBoolean("autoUpdate") ? "1" : "0");
        try {
            if (in.has("forgetLesson")) remove(s, "lessons", in.optInt("forgetLesson"));
            if (in.has("forgetShortcut")) remove(s, "shortcuts", in.optInt("forgetShortcut"));
            if (in.has("forgetTopic")) remove(s, "knowledge", in.optInt("forgetTopic"));
        } catch (Exception ignored) {
        }
    }

    static synchronized void remove(Store s, String key, int idx) {
        JSONArray a = Smart.list(s, key), keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (i != idx) keep.put(a.opt(i));
        s.putSecret(key, keep.toString());
        s.activity("learn", "Arsh removed a learned " + (key.equals("lessons") ? "lesson" : key.equals("shortcuts") ? "shortcut" : "topic"));
    }
}
