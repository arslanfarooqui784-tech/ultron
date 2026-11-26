package com.arsh.ultron;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.BatteryManager;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ULTRON's smarter side: learns Arsh's reply style, knows the people he talks to, remembers chats,
 * spots scams, tracks spending, nudges him at the right moment, and sums up his week.
 * Everything is stored encrypted on the phone.
 */
public class Smart {
    // =============================================================== 1. Arsh's reply style ======
    static JSONArray list(Store s, String key) {
        try { return new JSONArray(s.getSecret(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void push(Store s, String key, Object item, int max) {
        JSONArray a = list(s, key), keep = new JSONArray();
        for (int i = Math.max(0, a.length() - (max - 1)); i < a.length(); i++) keep.put(a.opt(i));
        keep.put(item);
        s.putSecret(key, keep.toString());
    }

    /** Arsh edited a draft before approving: the best possible lesson in how he writes. */
    public static void learnEdit(Store s, String who, String draft, String mine) {
        if (mine == null || draft == null || mine.trim().equals(draft.trim())) return;
        try {
            push(s, "style_edits", new JSONObject().put("draft", Memory.clip(draft.replace(AutoReply.INTRO, "").trim(), 200))
                .put("arsh", Memory.clip(mine.replace(AutoReply.INTRO, "").trim(), 200)).put("who", who), 25);
            s.activity("learn", "Learned from your edit to the reply for " + who);
        } catch (Exception ignored) {
        }
    }

    public static void learnReject(Store s, String who, String draft) {
        try {
            push(s, "style_rejects", new JSONObject().put("draft", Memory.clip(draft.replace(AutoReply.INTRO, "").trim(), 160)).put("who", who), 15);
        } catch (Exception ignored) {
        }
    }

    /** A message Arsh typed himself in a chat. */
    public static void learnOwn(Store s, String who, String text) {
        if (text == null || text.trim().length() < 2 || Memory.SECRET.matcher(text).find()) return;
        try {
            push(s, "style_own", new JSONObject().put("text", Memory.clip(text.trim(), 160)).put("who", who), 40);
        } catch (Exception ignored) {
        }
    }

    /** Examples for the reply AI. */
    public static String styleHints(Store s, String who) {
        StringBuilder b = new StringBuilder();
        JSONArray e = list(s, "style_edits");
        for (int i = Math.max(0, e.length() - 5); i < e.length(); i++) {
            JSONObject x = e.optJSONObject(i);
            b.append("  ULTRON wrote: \"").append(x.optString("draft")).append("\" -> Arsh changed it to: \"").append(x.optString("arsh")).append("\"\n");
        }
        JSONArray o = list(s, "style_own");
        int n = 0;
        StringBuilder own = new StringBuilder();
        for (int i = o.length() - 1; i >= 0 && n < 6; i--) {
            JSONObject x = o.optJSONObject(i);
            if (who != null && NotifService.exact(who).equals(NotifService.exact(x.optString("who")))) { own.insert(0, "  \"" + x.optString("text") + "\" (to this person)\n"); n++; }
        }
        for (int i = o.length() - 1; i >= 0 && n < 6; i--) {
            JSONObject x = o.optJSONObject(i);
            if (who == null || !NotifService.exact(who).equals(NotifService.exact(x.optString("who")))) { own.insert(0, "  \"" + x.optString("text") + "\"\n"); n++; }
        }
        JSONArray r = list(s, "style_rejects");
        StringBuilder rej = new StringBuilder();
        for (int i = Math.max(0, r.length() - 3); i < r.length(); i++) rej.append("  \"").append(r.optJSONObject(i).optString("draft")).append("\"\n");
        if (b.length() == 0 && own.length() == 0 && rej.length() == 0) return "";
        return "ARSH'S OWN STYLE (sound like his assistant writing the way he likes, keep his tone, length and slang):\n" + b
            + (own.length() > 0 ? " Messages Arsh typed himself:\n" + own : "")
            + (rej.length() > 0 ? " Drafts Arsh REJECTED (avoid replies like these):\n" + rej : "");
    }

    // =============================================================== 3. people ======
    static String pkey(String who) {
        return "person:" + NotifService.exact(who);
    }

    public static JSONObject person(Store s, String who) {
        try { return new JSONObject(s.getSecret(pkey(who), "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    public static synchronized void touch(Store s, String who, String app, String lastText) {
        if (NotifService.exact(who).isEmpty()) return;
        try {
            JSONObject p = person(s, who);
            long now = System.currentTimeMillis();
            if (!p.has("name")) p.put("name", who).put("first", now);
            JSONArray apps = p.optJSONArray("apps");
            if (apps == null) apps = new JSONArray();
            boolean has = false;
            for (int i = 0; i < apps.length(); i++) if (apps.optString(i).equals(app)) has = true;
            if (!has) apps.put(app);
            p.put("apps", apps).put("last", now).put("count", p.optInt("count") + 1).put("lastText", Memory.clip(lastText, 120));
            s.putSecret(pkey(who), p.toString());
            JSONArray idx = list(s, "people_index");
            boolean in = false;
            for (int i = 0; i < idx.length(); i++) if (idx.optString(i).equals(NotifService.exact(who))) in = true;
            if (!in) push(s, "people_index", NotifService.exact(who), 400);
        } catch (Exception ignored) {
        }
    }

    public static synchronized void fact(Store s, String who, String key, String value) {
        if (value == null || value.trim().isEmpty() || NotifService.exact(who).isEmpty()) return;
        try {
            JSONObject p = person(s, who);
            if (!p.has("name")) p.put("name", who);
            if (key.equals("topic")) p.put("topic", Memory.clip(value, 120));
            else if (key.equals("birthday") && value.matches("\\d{2}-\\d{2}")) {
                if (!value.equals(p.optString("birthday"))) s.activity("people", who + "'s birthday noted: " + value);
                p.put("birthday", value);
            }
            s.putSecret(pkey(who), p.toString());
        } catch (Exception ignored) {
        }
    }

    /** Birthday (MM-DD) of a phone contact, from the Contacts app, or "". */
    static String contactBirthday(Context c, String name) {
        try {
            Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, new String[]{ContactsContract.CommonDataKinds.Event.START_DATE},
                ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.CommonDataKinds.Event.TYPE + "=? AND " + ContactsContract.Data.DISPLAY_NAME + "=?",
                new String[]{ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, String.valueOf(ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY), name}, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        Matcher m = Pattern.compile("(\\d{2})-(\\d{2})$").matcher(cur.getString(0));
                        if (m.find()) return m.group(1) + "-" + m.group(2);
                    }
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** What ULTRON knows about this person, for the reply AI and for "tell me about X". */
    public static String personContext(Context c, Store s, String who) {
        JSONObject p = person(s, who);
        StringBuilder b = new StringBuilder();
        String cat = Calls.category(s, who);
        if (!cat.equals("known") && !cat.equals("unknown")) b.append("category: ").append(cat).append("; ");
        if (p.optInt("count") > 0) b.append(p.optInt("count")).append(" messages since ")
            .append(new SimpleDateFormat("d MMM", Locale.ENGLISH).format(new Date(p.optLong("first")))).append("; ");
        JSONArray apps = p.optJSONArray("apps");
        if (apps != null && apps.length() > 0) {
            StringBuilder ap = new StringBuilder();
            for (int i = 0; i < apps.length(); i++) ap.append(i > 0 ? ", " : "").append(apps.optString(i));
            b.append("talks on ").append(ap).append("; ");
        }
        if (!p.optString("topic").isEmpty()) b.append("last topic: ").append(p.optString("topic")).append("; ");
        String bd = p.optString("birthday");
        if (bd.isEmpty()) bd = contactBirthday(c, who);
        if (!bd.isEmpty()) {
            b.append("birthday: ").append(bd).append(bd.equals(new SimpleDateFormat("MM-dd", Locale.ROOT).format(new Date())) ? " (TODAY!)" : "").append("; ");
        }
        return b.toString().trim();
    }

    // =============================================================== 4. chat memory + search ======
    public static void archive(Store s, String who, String app, String from, String text) {
        if (text == null || text.trim().isEmpty() || Memory.SECRET.matcher(text).find()) return;
        try {
            push(s, "chat_archive", new JSONObject().put("w", who).put("a", app).put("f", from).put("t", Memory.clip(text, 300))
                .put("at", System.currentTimeMillis()), 2500);
        } catch (Exception ignored) {
        }
    }

    /** "What did Arslan say about the trip?" — answered from the chat archive only. */
    public static String search(Brain brain, Store s, String question) {
        JSONArray a = list(s, "chat_archive");
        if (a.length() == 0) return "I don't have any chats saved yet, Arsh. I remember chats from the moment ULTRON started reading them.";
        String who = "";
        try {
            String raw = brain.quick("Question: \"" + question + "\". Which person is it about (a name, or empty) and which keywords should I "
                + "search chats for? Answer ONLY with JSON {\"person\": \"\", \"keywords\": [\"\"]}", "Output only JSON.");
            JSONObject j = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
            who = NotifService.exact(j.optString("person"));
            JSONArray k = j.optJSONArray("keywords");
            List<String> keys = new ArrayList<>();
            for (int i = 0; k != null && i < k.length(); i++) if (k.optString(i).trim().length() > 2) keys.add(k.optString(i).toLowerCase(Locale.ROOT).trim());
            List<JSONObject> hits = new ArrayList<>();
            for (int i = a.length() - 1; i >= 0 && hits.size() < 60; i--) {
                JSONObject m = a.optJSONObject(i);
                boolean personOk = who.isEmpty() || NotifService.exact(m.optString("w")).contains(who) || NotifService.exact(m.optString("f")).contains(who);
                if (!personOk) continue;
                String t = m.optString("t").toLowerCase(Locale.ROOT);
                boolean kw = keys.isEmpty();
                for (String x : keys) if (t.contains(x)) kw = true;
                if (kw || (!who.isEmpty() && hits.size() < 25)) hits.add(m);
            }
            if (hits.isEmpty()) return "I couldn't find anything about that in your saved chats, Arsh.";
            Collections.reverse(hits);
            StringBuilder ctx = new StringBuilder();
            SimpleDateFormat f = new SimpleDateFormat("d MMM h:mm a", Locale.ENGLISH);
            for (JSONObject m : hits) ctx.append(f.format(new Date(m.optLong("at")))).append(" | ").append(m.optString("a")).append(" | ")
                .append(m.optString("w")).append(" | ").append(m.optString("f")).append(": ").append(m.optString("t")).append("\n");
            return AutoReply.clean(brain.quick("Arsh asks: \"" + question + "\"\nHere are matching messages from his chats (date | app | chat | from: text):\n"
                + ctx + "\nAnswer in 1 to 4 spoken sentences using ONLY these messages, mention when it was said. If they don't answer it, say so.",
                "You are ULTRON. Be exact; never invent."));
        } catch (Exception e) {
            return "I couldn't search your chats right now: " + e.getMessage();
        }
    }

    // =============================================================== 5. scams ======
    static final Pattern SCAM = Pattern.compile("(?i)(you have won|you('ve| have) been selected|lottery|lucky draw|claim (your )?(prize|reward|gift)|"
        + "kyc (update|expired|pending|blocked)|update (your )?kyc|account (will be|has been|is) (blocked|suspended|deactivated)|pan (card )?(blocked|update)|"
        + "electricity (connection|bill).*(disconnect|cut)|power will be (cut|disconnected)|share (the |your )?otp|send (me )?(the |your )?otp|"
        + "work from home.*(earn|daily)|earn (rs\\.?|₹|inr)? ?\\d+.*(daily|per day)|part[- ]time job.*(earn|salary)|investment.*(guaranteed|double)|"
        + "double your money|crypto.*(profit|return)|refund.*(click|link)|income tax refund|parcel.*(held|customs).*(pay|fee)|"
        + "your (sim|number) will be (blocked|deactivated)|customs (duty|fee)|digital arrest|cbi|narcotics.*parcel)");
    static final Pattern LINK = Pattern.compile("(?i)(https?://\\S+|\\b(?:bit\\.ly|tinyurl|t\\.ly|cutt\\.ly|rb\\.gy|is\\.gd|wa\\.me/\\+?\\d)\\S*|\\b\\S+\\.(?:xyz|top|club|online|site|info|buzz|icu|live|shop)\\b\\S*)");
    static final Pattern URGENT = Pattern.compile("(?i)(urgent|immediately|within 24 hours|today itself|last chance|act now|click (here|below|the link))");

    /** Why this looks like a scam, or null. */
    public static String scamReason(String sender, String text, boolean knownContact) {
        if (text == null) return null;
        Matcher m = SCAM.matcher(text);
        boolean link = LINK.matcher(text).find(), urgent = URGENT.matcher(text).find();
        if (m.find()) return "it says \"" + m.group() + "\"" + (link ? " and has a link" : "");
        if (!knownContact && link && urgent) return "an unknown sender, an urgent tone and a link";
        return null;
    }

    public static void warnScam(Context c, String who, String app, String text, String why) {
        Store s = new Store(c);
        String k = "scam:" + AutoReply.sha(who + "|" + text);
        if (!s.get(k, "").isEmpty()) return;
        s.put(k, "1");
        s.activity("scam", "⚠️ Possible scam from " + who + " (" + app + "): " + why + ". ULTRON did not reply.");
        notify(c, 6100 + Math.abs(k.hashCode() % 300), "⚠️ Possible scam · " + who, "Looks like a scam because " + why
            + ". Don't click links or share OTPs. ULTRON did not reply.\n\n\"" + Memory.clip(text, 200) + "\"", null);
    }

    // =============================================================== 6. money ======
    static final Pattern AMOUNT = Pattern.compile("(?i)(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d{1,2})?)");
    static final Pattern DEBIT = Pattern.compile("(?i)\\b(debited|spent|paid|sent|withdrawn|purchase|txn of|payment of|dr\\.?)\\b");
    static final Pattern CREDIT = Pattern.compile("(?i)\\b(credited|received|deposited|refund(ed)?|cr\\.?)\\b");
    static final Pattern MERCHANT = Pattern.compile("(?i)(?:\\bto\\b|\\bat\\b|\\btowards\\b|\\bon\\b|\\bvpa\\b|\\bfor\\b)\\s+([A-Za-z0-9&._'@ -]{3,40}?)(?=\\s+(?:on|ref|upi|via|avl|avbl|bal|from|txn|using)\\b|[.,;]|$)");

    static String category(String m) {
        String x = m.toLowerCase(Locale.ROOT);
        if (x.matches(".*(swiggy|zomato|restaurant|cafe|food|domino|pizza|kfc|mcdonald|burger|blinkit|zepto|instamart|bigbasket|dunzo|grocery|mart).*")) return "food & groceries";
        if (x.matches(".*(uber|ola|rapido|irctc|redbus|indigo|air|metro|fuel|petrol|hpcl|iocl|bpcl|fastag).*")) return "travel & fuel";
        if (x.matches(".*(amazon|flipkart|myntra|ajio|meesho|nykaa|shop|store).*")) return "shopping";
        if (x.matches(".*(airtel|jio|vodafone|vi |bsnl|recharge|electric|bill|broadband|gas|water|insurance|emi|loan).*")) return "bills & recharges";
        if (x.matches(".*(netflix|prime|hotstar|spotify|youtube|bookmyshow|pvr|inox).*")) return "entertainment";
        if (x.matches(".*(pharma|medical|hospital|clinic|apollo|1mg|pharmeasy|netmeds).*")) return "health";
        return "other";
    }

    /** A bank / UPI alert: amount, direction and merchant only (no balances, no account numbers). */
    public static void money(Store s, String text) {
        if (text == null || Pattern.compile("(?i)\\botp\\b|one time password|will be debited|due on|is due|requested|request of|mandate|autopay set").matcher(text).find()) return;
        Matcher a = AMOUNT.matcher(text);
        if (!a.find()) return;
        boolean debit = DEBIT.matcher(text).find(), credit = CREDIT.matcher(text).find();
        if (!debit && !credit) return;
        String low = text.toLowerCase(Locale.ROOT);
        Matcher dm = DEBIT.matcher(low), cm = CREDIT.matcher(low);
        int di = dm.find() ? dm.start() : Integer.MAX_VALUE, ci = cm.find() ? cm.start() : Integer.MAX_VALUE;
        String type = di <= ci ? "debit" : "credit";
        double amt;
        try {
            amt = Double.parseDouble(a.group(1).replace(",", ""));
        } catch (Exception e) {
            return;
        }
        if (amt <= 0 || amt > 10000000) return;
        String merchant = "";
        Matcher vpa = Pattern.compile("(?i)\\b(?:vpa|upi id)\\s+([\\w.\\-]+)@").matcher(text);
        if (vpa.find()) merchant = vpa.group(1);
        Matcher m = MERCHANT.matcher(text);
        while (merchant.isEmpty() && m.find()) {
            String g = m.group(1).trim().replaceAll("(?i)^vpa\\s+", "");
            if (g.matches("(?i).*(a/c|acct|account|xx|\\d{4,}).*") || g.matches("(?i)^(your|the|a|you)\\b.*") || g.matches("^[\\d/ -].*")) continue;
            merchant = g.contains("@") ? g.substring(0, g.indexOf('@')) : g;
            break;
        }
        String id = AutoReply.sha(text);
        JSONArray all = list(s, "money");
        for (int i = Math.max(0, all.length() - 50); i < all.length(); i++) if (id.equals(all.optJSONObject(i).optString("id"))) return;
        try {
            push(s, "money", new JSONObject().put("id", id).put("amt", amt).put("type", type).put("m", merchant)
                .put("cat", type.equals("debit") ? category(merchant + " " + text) : "income").put("at", System.currentTimeMillis()), 1500);
            s.activity("money", (type.equals("debit") ? "Spent ₹" : "Received ₹") + String.format(Locale.ENGLISH, "%.0f", amt) + (merchant.isEmpty() ? "" : " · " + merchant));
        } catch (Exception ignored) {
        }
    }

    public static String spending(Store s, long since, String label) {
        JSONArray a = list(s, "money");
        double out = 0, in = 0;
        Map<String, Double> cats = new HashMap<>();
        Map<String, Double> shops = new HashMap<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null || x.optLong("at") < since) continue;
            if (x.optString("type").equals("debit")) {
                out += x.optDouble("amt");
                cats.put(x.optString("cat"), (cats.containsKey(x.optString("cat")) ? cats.get(x.optString("cat")) : 0) + x.optDouble("amt"));
                if (!x.optString("m").isEmpty()) shops.put(x.optString("m"), (shops.containsKey(x.optString("m")) ? shops.get(x.optString("m")) : 0) + x.optDouble("amt"));
            } else in += x.optDouble("amt");
        }
        if (out == 0 && in == 0) return "I haven't seen any bank or UPI payments " + label + ", Arsh. I count them from your bank notifications.";
        StringBuilder b = new StringBuilder("You spent ₹" + String.format(Locale.ENGLISH, "%,.0f", out) + " " + label);
        List<Map.Entry<String, Double>> l = new ArrayList<>(cats.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Double>>() {
            public int compare(Map.Entry<String, Double> x, Map.Entry<String, Double> y) { return Double.compare(y.getValue(), x.getValue()); }
        });
        if (!l.isEmpty()) {
            b.append(", mostly on ");
            for (int i = 0; i < Math.min(3, l.size()); i++)
                b.append(i > 0 ? (i == Math.min(3, l.size()) - 1 ? " and " : ", ") : "").append(l.get(i).getKey()).append(" (₹").append(String.format(Locale.ENGLISH, "%,.0f", l.get(i).getValue())).append(")");
        }
        b.append(".");
        if (in > 0) b.append(" Money received: ₹").append(String.format(Locale.ENGLISH, "%,.0f", in)).append(".");
        return b.toString();
    }

    // =============================================================== notifications helper ======
    static void notify(Context c, int id, String title, String text, PendingIntent tap) {
        try {
            if (tap == null) tap = PendingIntent.getActivity(c, id, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
            Notification.Builder b = Approvals.builder(c, Approvals.ATTENTION_CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title)
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setColor(0xFFFF1728).setAutoCancel(true).setContentIntent(tap);
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, b.build());
        } catch (Exception ignored) {
        }
    }

    static boolean once(Store s, String key, long everyMs) {
        long last;
        try { last = Long.parseLong(s.get("nudge:" + key, "0")); } catch (Exception e) { last = 0; }
        if (System.currentTimeMillis() - last < everyMs) return false;
        s.put("nudge:" + key, String.valueOf(System.currentTimeMillis()));
        return true;
    }

    // =============================================================== 2 + 10. nudges (every 30 minutes) ======
    public static void scheduleNudges(Context c) {
        Agenda.at(c, System.currentTimeMillis() + 30 * 60000L, Agenda.alarmIntent(c, "NUDGE", "tick"));
    }

    public static boolean on(Store s, String k, boolean def) {
        return "1".equals(s.get(k, def ? "1" : "0"));
    }

    public static void nudgeCheck(final Context c) {
        scheduleNudges(c);
        AutoReply.pool.execute(new Runnable() {
            public void run() {
                Store s = new Store(c);
                try {
                    nudges(c, s);
                } catch (Exception ignored) {
                }
                Widget.refresh(c);
            }
        });
    }

    static void nudges(Context c, Store s) {
        try {
            Daily.calendarSilent(c);
        } catch (Exception ignored) {
        }
        Calendar k = Calendar.getInstance();
        int h = k.get(Calendar.HOUR_OF_DAY);
        boolean quiet = AutoReply.quietNow(s) || Routines.active(s).equals("sleep") || Routines.active(s).equals("meeting");
        if (on(s, "nudge_on", true) && !quiet) {
            // someone waiting a long time
            Map<String, Integer> waiting = new HashMap<>();
            Map<String, Long> oldest = new HashMap<>();
            JSONArray a = AutoReply.Events.all(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null || !("attention".equals(e.optString("status")) || "awaiting_approval".equals(e.optString("status")))) continue;
                if (!"personal".equals(e.optString("kind"))) continue;
                String w = e.optString("who") + " (" + e.optString("app") + ")";
                waiting.put(w, (waiting.containsKey(w) ? waiting.get(w) : 0) + 1);
                if (!oldest.containsKey(w) || e.optLong("received") < oldest.get(w)) oldest.put(w, e.optLong("received"));
            }
            for (Map.Entry<String, Integer> w : waiting.entrySet()) {
                if (w.getValue() >= 2 && System.currentTimeMillis() - oldest.get(w.getKey()) > 60 * 60000L && once(s, "wait:" + w.getKey(), 6 * 3600000L)) {
                    notify(c, 6500 + Math.abs(w.getKey().hashCode() % 200), "💬 " + w.getKey() + " is waiting for you",
                        w.getValue() + " messages need your personal reply. Open CONTROL › Needs you, or tell ULTRON what to answer.",
                        PendingIntent.getActivity(c, 6499, new Intent(c, MainActivity.class).putExtra("page", "control.html").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
                    s.activity("nudge", w.getKey() + " has been waiting over an hour");
                }
            }
            // battery
            try {
                Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                boolean charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                if (lvl >= 0 && lvl <= 20 && !charging) {
                    JSONArray ev = Agenda.events(c, System.currentTimeMillis(), System.currentTimeMillis() + 3 * 3600000L);
                    if ((ev.length() > 0 || h >= 21) && once(s, "battery", 4 * 3600000L)) {
                        notify(c, 6400, "🔋 Battery " + lvl + "%", ev.length() > 0 ? "You have " + ev.optJSONObject(0).optString("title")
                            + " soon. Charge your phone now." : "Charge your phone before you sleep, Arsh.", null);
                    }
                }
            } catch (Exception ignored) {
            }
            // rain today (morning, once a day)
            if (h >= 7 && h < 11 && once(s, "rain", 20 * 3600000L)) {
                int rain = Weather.todayRain(Weather.home(s));
                if (rain >= 60) notify(c, 6401, "☔ Rain likely in " + Weather.home(s), rain + "% chance of rain today. Take an umbrella, Arsh.", null);
            }
            // birthdays (morning, once a day)
            if (h >= 8 && h < 12 && once(s, "bday", 20 * 3600000L)) {
                List<String> who = birthdaysToday(c, s);
                if (!who.isEmpty()) notify(c, 6402, "🎂 Birthday today", android.text.TextUtils.join(", ", who) + (who.size() == 1 ? " has" : " have")
                    + " a birthday today. Say \"send WhatsApp to " + who.get(0) + " saying happy birthday\" and ULTRON will send your wishes.", null);
            }
        }
        // health
        if (!quiet && h >= 9 && h < 22 && on(s, "health_water", true) && once(s, "water", 2 * 3600000L - 60000))
            notify(c, 6403, "💧 Water break", "Time for a glass of water, Arsh.", null);
        if (!quiet && h >= 10 && h < 21 && on(s, "health_stretch", false) && once(s, "stretch", 2 * 3600000L - 60000))
            notify(c, 6404, "🧘 Stretch break", "Stand up and stretch for a minute, Arsh. Your back will thank you.", null);
    }

    static List<String> birthdaysToday(Context c, Store s) {
        List<String> out = new ArrayList<>();
        String today = new SimpleDateFormat("MM-dd", Locale.ROOT).format(new Date());
        try {
            Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, new String[]{ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Event.START_DATE}, ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.CommonDataKinds.Event.TYPE + "=?",
                new String[]{ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, String.valueOf(ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)}, null);
            if (cur != null) {
                try {
                    while (cur.moveToNext()) if (cur.getString(1) != null && cur.getString(1).endsWith(today) && !out.contains(cur.getString(0))) out.add(cur.getString(0));
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ignored) {
        }
        JSONArray idx = list(s, "people_index");
        for (int i = 0; i < idx.length(); i++) {
            JSONObject p;
            try { p = new JSONObject(s.getSecret("person:" + idx.optString(i), "{}")); } catch (Exception e) { continue; }
            if (today.equals(p.optString("birthday")) && !out.contains(p.optString("name"))) out.add(p.optString("name"));
        }
        return out;
    }

    // =============================================================== sleep log (from Sleep mode) ======
    public static void sleepStart(Store s) {
        s.put("sleep_start", String.valueOf(System.currentTimeMillis()));
    }

    public static void sleepEnd(Store s) {
        try {
            long st = Long.parseLong(s.get("sleep_start", "0"));
            long dur = System.currentTimeMillis() - st;
            if (st > 0 && dur > 30 * 60000L && dur < 18 * 3600000L) {
                SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
                String line = "Slept (sleep mode) from " + f.format(new Date(st)) + " to " + f.format(new Date()) + ": " + (dur / 3600000L) + "h " + (dur / 60000L % 60) + "m";
                s.put("sleep_last", line);
                push(s, "sleep_log", new JSONObject().put("from", st).put("to", System.currentTimeMillis()), 60);
            }
            s.put("sleep_start", "0");
        } catch (Exception ignored) {
        }
    }

    // =============================================================== 13. weekly report (Sunday evening) ======
    public static void scheduleWeekly(Context c) {
        Store s = new Store(c);
        PendingIntent p = Agenda.alarmIntent(c, "WEEKLY", "sunday");
        if (!on(s, "weekly_on", true)) {
            Agenda.cancel(c, p);
            return;
        }
        Calendar k = Calendar.getInstance();
        k.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        k.set(Calendar.HOUR_OF_DAY, 20);
        k.set(Calendar.MINUTE, 0);
        k.set(Calendar.SECOND, 0);
        if (k.getTimeInMillis() <= System.currentTimeMillis() + 60000) k.add(Calendar.WEEK_OF_YEAR, 1);
        Agenda.at(c, k.getTimeInMillis(), p);
    }

    public static String weekly(Context c) {
        Store s = new Store(c);
        long since = System.currentTimeMillis() - 7 * 86400000L;
        Map<String, Integer> people = new HashMap<>();
        JSONArray a = list(s, "chat_archive");
        int total = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m == null || m.optLong("at") < since || "Arsh".equals(m.optString("f"))) continue;
            String w = m.optString("w") + " (" + m.optString("a") + ")";
            people.put(w, (people.containsKey(w) ? people.get(w) : 0) + 1);
            total++;
        }
        List<Map.Entry<String, Integer>> l = new ArrayList<>(people.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> x, Map.Entry<String, Integer> y) { return y.getValue() - x.getValue(); }
        });
        StringBuilder f = new StringBuilder("Messages received this week: " + total + ". Top people: ");
        for (int i = 0; i < Math.min(5, l.size()); i++) f.append(l.get(i).getKey()).append(" ").append(l.get(i).getValue()).append(", ");
        int missed = 0;
        JSONArray cl = Calls.log(s);
        for (int i = 0; i < cl.length(); i++) if (cl.optJSONObject(i).optLong("at") >= since && "missed".equals(cl.optJSONObject(i).optString("status"))) missed++;
        f.append("\nMissed calls: ").append(missed).append("\nMoney: ").append(spending(s, since, "this week"));
        JSONArray sl = list(s, "sleep_log");
        long sleepMs = 0;
        int nights = 0;
        for (int i = 0; i < sl.length(); i++) if (sl.optJSONObject(i).optLong("from") >= since) { sleepMs += sl.optJSONObject(i).optLong("to") - sl.optJSONObject(i).optLong("from"); nights++; }
        if (nights > 0) f.append("\nSleep (sleep mode): average ").append(sleepMs / nights / 3600000L).append("h ").append(sleepMs / nights / 60000L % 60).append("m over ").append(nights).append(" nights");
        JSONArray q = list(s, "quiz_log");
        int qz = 0, right = 0, asked = 0;
        for (int i = 0; i < q.length(); i++) if (q.optJSONObject(i).optLong("at") >= since) { qz++; right += q.optJSONObject(i).optInt("score"); asked += q.optJSONObject(i).optInt("of"); }
        if (qz > 0) f.append("\nStudy quizzes: ").append(qz).append(", ").append(right).append(" of ").append(asked).append(" correct");
        f.append("\nNext 7 days on the calendar: ").append(Agenda.describeEvents(Agenda.events(c, System.currentTimeMillis(), System.currentTimeMillis() + 7 * 86400000L)));
        String text;
        try {
            text = AutoReply.clean(new Brain(s).quick("You are ULTRON. Write Arsh's weekly report to be SPOKEN, 80 to 150 words, warm and sharp, starting "
                + "'Here is your week, Arsh.'. Use ONLY these facts, add one helpful observation or suggestion for next week. No lists, no emojis.\n\nFACTS:\n" + f,
                "You write spoken weekly reports."));
            if (text.length() < 20) throw new Exception("short");
        } catch (Exception e) {
            text = "Here is your week, Arsh. " + f.toString().replace("\n", ". ");
        }
        s.putSecret("brief_text", text);
        s.activity("briefing", "Weekly report prepared");
        return text;
    }

    static void fireWeekly(final Context c) {
        scheduleWeekly(c);
        AutoReply.pool.execute(new Runnable() {
            public void run() {
                String text = weekly(c);
                Intent listen = new Intent(c, MainActivity.class).putExtra("say", "briefing").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Smart.notify(c, 9010, "📊 Your week with ULTRON", text, PendingIntent.getActivity(c, 9011, listen, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
            }
        });
    }

    // =============================================================== settings ======
    public static JSONObject info(Store s) throws Exception {
        return new JSONObject().put("nudges", on(s, "nudge_on", true)).put("water", on(s, "health_water", true)).put("stretch", on(s, "health_stretch", false))
            .put("weekly", on(s, "weekly_on", true)).put("scam", on(s, "scam_on", true)).put("money", on(s, "money_on", true))
            .put("styleExamples", list(s, "style_edits").length() + list(s, "style_own").length()).put("people", list(s, "people_index").length())
            .put("archived", list(s, "chat_archive").length()).put("sleepLast", s.get("sleep_last", ""));
    }

    public static void save(Context c, JSONObject in) {
        Store s = new Store(c);
        String[][] m = {{"nudges", "nudge_on"}, {"water", "health_water"}, {"stretch", "health_stretch"}, {"weekly", "weekly_on"}, {"scam", "scam_on"}, {"money", "money_on"}};
        for (String[] x : m) if (in.has(x[0])) s.put(x[1], in.optBoolean(x[0]) ? "1" : "0");
        scheduleWeekly(c);
    }
}
