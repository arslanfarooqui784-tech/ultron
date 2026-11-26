package com.arsh.ultron;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ULTRON's long-term memory. Everything is stored encrypted on the phone (Android Keystore).
 * - The conversation log: every exchange between Arsh and ULTRON, with time stamps.
 * - Memories: facts, preferences, people, projects, decisions, goals, tasks, routines Arsh stated,
 *   each with category, source, time, confidence and edit history.
 * Answers about the past are built only from these records; nothing is invented.
 */
public class Memory {
    public static final String[] CATEGORIES = {"preferences", "communication", "people", "work", "projects", "decisions", "goals",
        "tasks", "routines", "instructions", "personal", "conversations", "temporary"};
    static final Pattern SECRET = Pattern.compile("(?i)(otp|password|passcode|pin|cvv|card number|account number|ifsc|aadhaar|pan card|\\b\\d{6}\\b.*code|code.*\\b\\d{4,8}\\b)");
    static final Set<String> STOP = new HashSet<>(Arrays.asList(("a an the and or but if to of in on at for from by with about is are was were be been am do does did "
        + "i me my mine you your we our us it its this that these those what which who whom when where why how can could would should will shall "
        + "tell say said ultron please remember recall know told discuss discussed talk talked last time yesterday today previous before ago "
        + "hai hain tha thi ka ki ke ko se mein me kya").split(" ")));

    private final Store s;
    private final Brain brain;

    public Memory(Store s, Brain brain) {
        this.s = s;
        this.brain = brain;
    }

    // ---------- settings ----------
    public boolean paused() {
        return "1".equals(s.get("mem_paused", "0"));
    }

    public boolean categoryOn(String c) {
        for (String x : s.get("mem_off", "").split(",")) if (x.equals(c)) return false;
        return true;
    }

    // ---------- conversation log ----------
    synchronized JSONArray log() {
        try {
            return new JSONArray(s.getSecret("chat_log", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public synchronized void logTurn(String said, String reply, String brainName) {
        if (paused()) return;
        try {
            JSONArray old = log(), now = new JSONArray();
            for (int i = Math.max(0, old.length() - 1999); i < old.length(); i++) now.put(old.get(i));
            now.put(new JSONObject().put("t", System.currentTimeMillis()).put("arsh", clip(said, 1500)).put("ultron", clip(reply, 1500))
                .put("brain", brainName));
            s.putSecret("chat_log", now.toString());
        } catch (Exception ignored) {
        }
    }

    /** Recent turns as chat messages, so ULTRON continues where it left off after a restart. */
    public synchronized JSONArray recentMessages(int turns) {
        JSONArray out = new JSONArray(), l = log();
        try {
            for (int i = Math.max(0, l.length() - turns); i < l.length(); i++) {
                JSONObject t = l.getJSONObject(i);
                out.put(new JSONObject().put("role", "user").put("content", t.optString("arsh")));
                out.put(new JSONObject().put("role", "assistant").put("content", t.optString("ultron")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---------- memories ----------
    synchronized JSONArray items() {
        try {
            JSONArray all = new JSONArray(s.getSecret("mem_items", "[]")), keep = new JSONArray();
            long now = System.currentTimeMillis();
            for (int i = 0; i < all.length(); i++) {
                JSONObject m = all.getJSONObject(i);
                if (m.optLong("expires", 0) > 0 && m.optLong("expires") < now) continue;   // temporary notes fade out
                keep.put(m);
            }
            return keep;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    synchronized void saveItems(JSONArray a) {
        s.putSecret("mem_items", a.toString());
    }

    /** Adds a memory, or updates a very similar one. Returns the stored memory or null when refused. */
    public synchronized JSONObject add(String category, String text, String source, double confidence, boolean temporary) {
        text = text == null ? "" : text.trim();
        if (text.length() < 3 || SECRET.matcher(text).find()) return null;      // never store codes or credentials
        if (!Arrays.asList(CATEGORIES).contains(category)) category = "personal";
        if (temporary) category = "temporary";
        if (!categoryOn(category)) return null;
        try {
            JSONArray all = items();
            long now = System.currentTimeMillis();
            for (int i = 0; i < all.length(); i++) {
                JSONObject m = all.getJSONObject(i);
                if (similar(m.optString("text"), text) > 0.8) {
                    m.put("seen", m.optInt("seen", 1) + 1).put("updated", now).put("confidence", Math.min(1.0, m.optDouble("confidence", 0.7) + 0.1));
                    saveItems(all);
                    return m;
                }
            }
            JSONObject m = new JSONObject().put("id", UUID.randomUUID().toString().substring(0, 8)).put("category", category).put("text", text)
                .put("source", source).put("created", now).put("updated", now).put("confidence", confidence).put("seen", 1)
                .put("history", new JSONArray());
            if (temporary) m.put("expires", now + 7L * 86400000);
            all.put(m);
            saveItems(all);
            s.activity("memory", "Remembered (" + category + "): " + text);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized boolean edit(String id, String text, String category) {
        try {
            JSONArray all = items();
            for (int i = 0; i < all.length(); i++) {
                JSONObject m = all.getJSONObject(i);
                if (!m.optString("id").equals(id)) continue;
                JSONArray h = m.optJSONArray("history");
                if (h == null) h = new JSONArray();
                h.put(new JSONObject().put("t", System.currentTimeMillis()).put("old", m.optString("text")));
                m.put("history", h).put("updated", System.currentTimeMillis()).put("confidence", 1.0).put("source", "corrected by Arsh");
                if (text != null && !text.trim().isEmpty()) m.put("text", text.trim());
                if (category != null && Arrays.asList(CATEGORIES).contains(category)) m.put("category", category);
                saveItems(all);
                s.activity("memory", "Corrected memory: " + m.optString("text"));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public synchronized boolean delete(String id) {
        JSONArray all = items(), keep = new JSONArray();
        boolean gone = false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m != null && m.optString("id").equals(id)) gone = true;
            else keep.put(m);
        }
        if (gone) {
            saveItems(keep);
            s.activity("memory", "Deleted a memory");
        }
        return gone;
    }

    // ---------- search ----------
    static List<String> words(String text) {
        List<String> w = new ArrayList<>();
        for (String x : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (x.length() > 1 && !STOP.contains(x)) w.add(x);
        return w;
    }

    static double similar(String a, String b) {
        List<String> wa = words(a), wb = words(b);
        if (wa.isEmpty() || wb.isEmpty()) return a.trim().equalsIgnoreCase(b.trim()) ? 1 : 0;
        Set<String> sa = new HashSet<>(wa), sb = new HashSet<>(wb);
        Set<String> both = new HashSet<>(sa);
        both.retainAll(sb);
        return (double) both.size() / Math.max(sa.size(), sb.size());
    }

    static int score(String text, List<String> q) {
        String t = text.toLowerCase(Locale.ROOT);
        int n = 0;
        for (String w : q) {
            if (t.contains(w)) n += 3;
            else if (w.length() > 4 && t.contains(w.substring(0, w.length() - 1))) n += 1;   // plan / plans
        }
        return n;
    }

    public JSONArray search(String query, String category, int limit) {
        final List<String> q = words(query == null ? "" : query);
        JSONArray all = items();
        List<JSONObject> hits = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m == null) continue;
            if (category != null && !category.isEmpty() && !category.equals(m.optString("category"))) continue;
            int sc = q.isEmpty() ? 1 : score(m.optString("text") + " " + m.optString("category"), q);
            if (sc > 0) {
                try {
                    m.put("_score", sc);
                } catch (Exception ignored) {
                }
                hits.add(m);
            }
        }
        Collections.sort(hits, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                int d = b.optInt("_score") - a.optInt("_score");
                return d != 0 ? d : Long.compare(b.optLong("updated"), a.optLong("updated"));
            }
        });
        JSONArray out = new JSONArray();
        for (int i = 0; i < hits.size() && i < limit; i++) out.put(hits.get(i));
        return out;
    }

    /** Conversation turns matching words and/or a time window like "yesterday", "last week", "3 days ago". */
    public JSONArray searchLog(String query, int limit) {
        long[] win = window(query == null ? "" : query);
        List<String> q = words(query == null ? "" : query);
        JSONArray l = log(), out = new JSONArray();
        List<JSONObject> hits = new ArrayList<>();
        for (int i = l.length() - 1; i >= 0; i--) {
            JSONObject t = l.optJSONObject(i);
            if (t == null) continue;
            long at = t.optLong("t");
            if (win != null && (at < win[0] || at > win[1])) continue;
            int sc = q.isEmpty() ? 1 : score(t.optString("arsh") + " " + t.optString("ultron"), q);
            if (win != null && sc == 0) sc = 1;
            if (sc > 0) hits.add(t);
            if (hits.size() >= limit) break;
        }
        for (int i = hits.size() - 1; i >= 0; i--) out.put(hits.get(i));
        return out;
    }

    static long[] window(String q) {
        q = q.toLowerCase(Locale.ROOT);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        long today = c.getTimeInMillis(), day = 86400000L, now = System.currentTimeMillis();
        if (q.contains("yesterday") || q.contains("kal ")) return new long[]{today - day, today};
        if (q.contains("today") || q.contains("aaj")) return new long[]{today, now};
        if (q.contains("last week") || q.contains("this week")) return new long[]{now - 8 * day, now};
        if (q.contains("last month")) return new long[]{now - 32 * day, now};
        Matcher m = Pattern.compile("(\\d+) days? ago").matcher(q);
        if (m.find()) {
            long d = Long.parseLong(m.group(1));
            return new long[]{today - d * day, today - (d - 1) * day};
        }
        return null;
    }

    static boolean asksAboutPast(String q) {
        return Pattern.compile("(?i)\\b(remember|recall|discuss|discussed|talked|told you|tell you|said|last time|previous|earlier|yesterday|"
            + "before|decision|decided|we agreed|continue|like last time|you know (how|what)|as usual|my plans?|what did i|what did we|kal|pichli)\\b")
            .matcher(q).find();
    }

    // ---------- giving the brain the right context ----------
    /** Text added to ULTRON's instructions so it answers with Arsh's real history. */
    public String contextFor(String question) {
        StringBuilder b = new StringBuilder();
        JSONArray prefs = search("", "preferences", 8), comm = search("", "communication", 5), instr = search("", "instructions", 6);
        JSONArray rel = search(question, null, 10);
        if (prefs.length() + comm.length() + instr.length() + rel.length() > 0) {
            b.append("\n\nWHAT YOU KNOW ABOUT ARSH (from his memory records; use only when relevant, never invent beyond them):\n");
            appendItems(b, instr);
            appendItems(b, prefs);
            appendItems(b, comm);
            appendItems(b, rel);
        }
        if (asksAboutPast(question)) {
            JSONArray turns = searchLog(question, 12);
            SimpleDateFormat f = new SimpleDateFormat("EEE dd MMM, hh:mm a", Locale.ENGLISH);
            if (turns.length() > 0) {
                b.append("\nPAST CONVERSATIONS WITH ARSH THAT MATCH (exact records):\n");
                int chars = 0;
                for (int i = 0; i < turns.length() && chars < 5000; i++) {
                    JSONObject t = turns.optJSONObject(i);
                    String line = "[" + f.format(new Date(t.optLong("t"))) + "] Arsh: " + t.optString("arsh") + " | ULTRON: " + t.optString("ultron") + "\n";
                    b.append(line);
                    chars += line.length();
                }
            } else {
                b.append("\nPAST CONVERSATIONS: no saved conversation matches this question.\n");
            }
            b.append("If the answer is not in these records, say honestly that you have no saved record of it. Never pretend to remember.\n");
        }
        return b.toString();
    }

    static void appendItems(StringBuilder b, JSONArray a) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m == null || !seen.add(m.optString("id")) || b.indexOf(m.optString("text")) >= 0) continue;
            b.append("- (").append(m.optString("category")).append(") ").append(m.optString("text")).append("\n");
        }
    }

    // ---------- learning ----------
    /** After a conversation turn, quietly pick out lasting facts Arsh stated. */
    public void learn(final String said, final String reply) {
        if (paused() || said.length() < 18) return;
        if (!Pattern.compile("(?i)\\b(i|i'm|im|my|me|mine|mera|meri|mere|mujhe|main|always|never|prefer|like|love|hate|don't|dont|want|plan|goal|"
            + "decided|remember|from now on|every|usually|work|project|brother|sister|mother|father|friend|wife|girlfriend|boss)\\b").matcher(said).find()) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String raw = brain.quick("Arsh said to his assistant ULTRON: \"" + said + "\"\nULTRON answered: \"" + reply + "\"\n\n"
                            + "Extract ONLY lasting facts that Arsh himself clearly stated about himself, his preferences, his communication style, "
                            + "important people, work, projects, decisions, goals, tasks, routines or standing instructions to ULTRON. "
                            + "Do not include questions, one-off requests, moods or feelings of the moment, guesses, or anything ULTRON said. "
                            + "Never include passwords, codes, OTPs, bank or card numbers. Write each fact as a short sentence about Arsh. "
                            + "Mark temporary true for things that only matter for a few days. "
                            + "Categories: " + Arrays.toString(CATEGORIES).replace("[", "").replace("]", "") + ".\n"
                            + "Answer ONLY with JSON: {\"memories\": [{\"category\": \"...\", \"text\": \"...\", \"temporary\": false}]} "
                            + "or {\"memories\": []} when there is nothing lasting.",
                        "You extract facts for a personal memory. Output only JSON.");
                    JSONObject j = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
                    JSONArray mems = j.optJSONArray("memories");
                    for (int i = 0; mems != null && i < mems.length() && i < 5; i++) {
                        JSONObject m = mems.getJSONObject(i);
                        add(m.optString("category"), m.optString("text"), "learned from conversation", 0.7, m.optBoolean("temporary"));
                    }
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    /** "Remember this conversation": a short summary of the last exchanges, saved as a memory. */
    public String rememberConversation() {
        JSONArray recent = new JSONArray(), l = log();
        for (int i = Math.max(0, l.length() - 12); i < l.length(); i++) recent.put(l.opt(i));
        if (recent.length() == 0) return "There is no conversation to remember yet, Arsh.";
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < recent.length(); i++) {
            JSONObject x = recent.optJSONObject(i);
            t.append("Arsh: ").append(x.optString("arsh")).append("\nULTRON: ").append(x.optString("ultron")).append("\n");
        }
        try {
            String sum = brain.quick("Summarise this conversation between Arsh and his assistant ULTRON in 2 to 4 sentences, keeping decisions, "
                + "plans and facts Arsh stated. No passwords or codes.\n\n" + t, "You write short factual summaries.");
            String date = new SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(new Date());
            JSONObject m = add("conversations", date + ": " + sum, "saved on Arsh's request", 1.0, false);
            return m != null ? "Saved. I will remember this conversation, Arsh." : "I could not save that conversation, Arsh.";
        } catch (Exception e) {
            return "I need my brain online to summarise the conversation, Arsh. Please try again.";
        }
    }

    // ---------- Understanding Arsh ----------
    public JSONObject profile() {
        JSONObject p = new JSONObject();
        try {
            JSONArray all = items();
            for (String c : CATEGORIES) p.put(c, new JSONArray());
            for (int i = 0; i < all.length(); i++) {
                JSONObject m = all.getJSONObject(i);
                p.getJSONArray(p.has(m.optString("category")) ? m.optString("category") : "personal").put(m);
            }
            p.put("summary", s.getSecret("profile_summary", "")).put("summaryAt", s.get("profile_summary_at", ""));
        } catch (Exception ignored) {
        }
        return p;
    }

    public String buildSummary() throws Exception {
        JSONArray all = items();
        if (all.length() == 0) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.getJSONObject(i);
            b.append("- (").append(m.optString("category")).append(") ").append(m.optString("text")).append("\n");
        }
        String sum = brain.quick("Here is everything Arsh has told his assistant ULTRON, as memory records:\n" + b
                + "\nWrite a short 'Understanding Arsh' profile in plain sentences, grouped by: communication style, preferences, "
                + "important people, work and projects, goals and plans, routines. Only use these records. No guesses, no psychology, "
                + "no labels he did not give himself. Say 'not known yet' for empty groups.",
            "You write factual personal profiles from records only.");
        s.putSecret("profile_summary", sum);
        s.put("profile_summary_at", new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(new Date()));
        return sum;
    }

    // ---------- export, import, wipe ----------
    public JSONObject export() throws Exception {
        return new JSONObject().put("ultron_memory_export", 1).put("exported", System.currentTimeMillis())
            .put("memories", items()).put("conversations", log()).put("summary", s.getSecret("profile_summary", ""));
    }

    public int importJson(String text) throws Exception {
        JSONObject j = new JSONObject(text.trim());
        JSONArray m = j.optJSONArray("memories");
        int n = 0;
        for (int i = 0; m != null && i < m.length(); i++) {
            JSONObject x = m.getJSONObject(i);
            if (add(x.optString("category"), x.optString("text"), "restored from backup", x.optDouble("confidence", 0.8), false) != null) n++;
        }
        JSONArray c = j.optJSONArray("conversations");
        if (c != null && c.length() > 0 && log().length() == 0) s.putSecret("chat_log", c.toString());
        return n;
    }

    public void wipe(boolean memories, boolean conversations) {
        if (memories) {
            s.putSecret("mem_items", "[]");
            s.putSecret("profile_summary", "");
        }
        if (conversations) s.putSecret("chat_log", "[]");
        s.activity("memory", "Deleted " + (memories && conversations ? "all memories and conversations" : memories ? "all memories" : "all conversation records"));
    }

    static String clip(String t, int n) {
        return t == null ? "" : t.length() > n ? t.substring(0, n) : t;
    }
}
