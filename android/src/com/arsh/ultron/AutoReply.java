package com.arsh.ultron;

import android.app.Notification;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * ULTRON's notification intelligence and reply engine.
 *
 * Every distinct incoming message becomes an EVENT with a stable id (app + chat + time + sender + text), stored encrypted:
 * pending -> processing -> (sending -> replied) | awaiting_approval | skipped | attention | handover | failed | inaccessible.
 * Messages from one chat are collected for at most N seconds (default 15) from the FIRST message (new messages never reset the
 * timer), then answered with ONE reply. Before sending, the batch is checked so one message can never get two replies, also
 * after a crash or restart. ULTRON speaks as Arsh's AI Assistant and never pretends to be him.
 */
public class AutoReply {
    static final String[][] APPS = {
        {"com.whatsapp", "WhatsApp"}, {"com.whatsapp.w4b", "WhatsApp Business"}, {"com.instagram.android", "Instagram"},
        {"com.facebook.orca", "Messenger"}, {"com.facebook.katana", "Facebook"}, {"com.snapchat.android", "Snapchat"},
        {"com.imo.android.imoim", "IMO"}, {"org.telegram.messenger", "Telegram"},
        {"com.samsung.android.messaging", "Messages (SMS)"}, {"com.google.android.apps.messaging", "Google Messages"},
        {"com.google.android.gm", "Gmail"}, {"com.microsoft.office.outlook", "Outlook"}, {"com.linkedin.android", "LinkedIn"}};
    static final String DEFAULT_APPS = "com.whatsapp,com.instagram.android,com.facebook.orca,com.snapchat.android,com.imo.android.imoim,org.telegram.messenger,com.samsung.android.messaging,com.google.android.apps.messaging";
    static final String DEFAULT_STYLE = "Extremely polite, warm, natural and engaging; friendly and a little entertaining when it fits. "
        + "Concise when a short reply is enough, detailed only when needed.";
    static final String INTRO = "I am ULTRON, Arsh's AI Assistant. I am currently communicating with you on his behalf.";
    static final String HOLDING = "I have received your message and passed it on to Arsh. He will get back to you personally as soon as possible.";

    static final Pattern OTP = Pattern.compile("(?i)(otp|one.time|verification|verify|passcode|password|\\bpin\\b|code)\\D{0,40}\\d{4,8}|\\d{4,8}\\D{0,40}(otp|code)|\\bdo not share\\b");
    static final Pattern BANK = Pattern.compile("(?i)\\b(debited|credited|a/c|acct|account no|avl bal|available balance|txn|transaction|upi ref|neft|imps|emi due|credit card|statement)\\b");
    static final Pattern SECURITY = Pattern.compile("(?i)(reset your password|password reset|new sign.?in|login attempt|security alert|suspicious|unusual activity|verify your account|2.?step)");
    static final Pattern PROMO = Pattern.compile("(?i)(\\d+% off|\\bsale\\b|cashback|\\boffer\\b|\\bdeal\\b|coupon|discount|limited time|shop now|subscribe)");
    static final Pattern DELIVERY = Pattern.compile("(?i)(out for delivery|shipped|has been delivered|order #|order id|your order|tracking)");
    static final Pattern EMERGENCY = Pattern.compile("(?i)\\b(emergency|accident|hospital|ambulance|urgent|help me|sos|police|bachao|jaldi aao)\\b");
    static final Pattern MONEY = Pattern.compile("(?i)\\b(upi|send money|transfer|bank|account number|card number|cvv|payment|pay me|loan|invest|paisa|paise|rupees|udhaar)\\b");
    static final Pattern MEDIA = Pattern.compile("(?i)^\\s*(📷|🎥|🎤|🎵|📄|🖼|📎)?\\s*(photo|image|video|gif|sticker|voice message|audio|document|file|location|contact)s?\\s*$");
    static final Pattern NO_NEED = Pattern.compile("(?i)^\\s*(ok+|okay|k+|kk|thanks?|thank you|thx|ty|good ?night|gn|bye|👍|🙏|❤️|😂|😊|done|hmm+|acha|accha|theek hai|thik hai)[.!\\s]*$");

    static final String HOLDING_DEV = "मुझे आपका संदेश मिल गया है और मैंने इसे Arsh तक पहुँचा दिया है। वह जल्द ही आपसे खुद संपर्क करेंगे। 🙏";
    static final String HOLDING_ROMAN = "Aapka message mil gaya hai aur maine Arsh tak pahuncha diya hai. Woh jald hi aapse khud baat karenge. 🙏";
    static final String HOLDING_URDU = "آپ کا پیغام مل گیا ہے اور میں نے اسے Arsh تک پہنچا دیا ہے۔ وہ جلد ہی آپ سے خود رابطہ کریں گے۔ 🙏";
    static final Pattern ROMAN_HINDI = Pattern.compile("(?i)\\b(hai|hain|hoon|kya|kyu|kyun|kaise|kaisa|kaisi|nahi|nhi|nahin|haan|acha|accha|achha|theek|thik|tum|aap|mera|meri|mere|tera|teri|tumhara|aaj|abhi|kab|kaha|kahan|karo|raha|rahi|rahe|tha|thi|bhai|yaar|chalo|bolo|batao|bata|khana|liya|gaya|gayi|mein|mujhe|tujhe|hum|humko|kuch|sab|bahut|bohot|bht|jaldi|pyaar|pyar|shukriya|dhanyavad|suno|dekho|toh|bhi|ho|ka|ki|ke|ko|se|bilkul|yeh|woh|hoon|aapka|aapki|aapko|apka|swagat|zaroor|zarur|chahiye|karunga|karungi|karenge|deta|deti|pahuncha|pahunchaa|baat|sahayata|bataiyega|bataiye|mil|gayi|kar|karna|wala|wali|hamesha|madad|shubh|raat|din|sapne)\\b");
    static final Pattern EMOJI = Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F000}-\\x{1F2FF}\\x{2B50}\\x{2764}]");

    /** devanagari | urdu | roman_hindi | english | other: the sender's language and script, from their own words. */
    static String language(String text) {
        int dev = 0, ara = 0, lat = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x0900 && ch <= 0x097F) dev++;
            else if (ch >= 0x0600 && ch <= 0x06FF) ara++;
            else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) lat++;
            else if (Character.isLetter(ch)) other++;
        }
        if (dev > 0 && dev >= ara && dev * 2 >= lat) return "devanagari";
        if (ara > 0 && ara * 2 >= lat) return "urdu";
        if (other > lat) return "other";
        if (lat == 0) return "";
        java.util.regex.Matcher m = ROMAN_HINDI.matcher(text);
        int hits = 0;
        while (m.find()) hits++;
        int words = text.trim().split("\\s+").length;
        boolean strong = Pattern.compile("(?i)\\b(hai|hain|kya|kaise|nahi|nhi|haan|acha|accha|theek|thik|aap|tum|shukriya|dhanyavad|bhai|yaar|mujhe|kuch|bahut|bilkul|hoon|aapka|aapko|swagat|zaroor|chahiye|pahuncha|bataiyega|sahayata)\\b").matcher(text).find();
        return hits >= 2 || (strong && words <= 4) ? "roman_hindi" : "english";
    }

    static String languageRule(String lang) {
        switch (lang) {
            case "devanagari": return "The sender writes Hindi in Devanagari script. Reply ONLY in Hindi written in Devanagari script (Arsh and ULTRON may stay in Latin letters).";
            case "urdu": return "The sender writes Urdu in Urdu script. Reply ONLY in Urdu written in Urdu (Arabic) script (Arsh and ULTRON may stay in Latin letters).";
            case "roman_hindi": return "The sender writes Hindi/Hinglish in Roman (English) letters. Reply in natural Roman Hinglish exactly like them, never in Devanagari and never in pure English.";
            case "english": return "The sender writes in ENGLISH. Reply ONLY in plain English. Do NOT use a single Hindi or Hinglish word "
                + "(no hai, hain, main, yeh, bhi, kal, subah, milte, baat, deta, hoon, bilkul, ji, aap), even if earlier messages in the chat were Hinglish.";
            case "other": return "The sender writes in another language. Reply in that same language and script.";
            default: return "Reply in the language the sender used.";
        }
    }

    static boolean scriptMatches(String lang, String reply) {
        String r = language(reply.replaceAll("(?i)\\b(arsh|ultron|ai)\\b", ""));
        switch (lang) {
            case "devanagari": return r.equals("devanagari");
            case "urdu": return r.equals("urdu");
            case "roman_hindi": return r.equals("roman_hindi") || r.equals("english");   // Roman script kept (Hinglish varies)
            case "english": return r.equals("english");
            default: return true;
        }
    }

    static String langName(String lang) {
        switch (lang) {
            case "devanagari": return "Hindi (Devanagari script)";
            case "urdu": return "Urdu (Urdu script)";
            case "roman_hindi": return "Roman Hinglish";
            default: return "English";
        }
    }

    /** A simple, correct-language reply used only if the AI keeps answering in the wrong language. */
    static String safeReply(String lang) {
        switch (lang) {
            case "devanagari": return "आपका संदेश मिल गया, धन्यवाद! मैं Arsh को बता दूँगा। 😊";
            case "urdu": return "آپ کا پیغام مل گیا، شکریہ! میں Arsh کو بتا دوں گا۔ 😊";
            case "roman_hindi": return "Aapka message mil gaya, shukriya! Main Arsh ko bata dunga. 😊";
            default: return "Got your message, thank you! I'll let Arsh know. 😊";
        }
    }

    static String holding(String lang) {
        switch (lang) {
            case "devanagari": return HOLDING_DEV;
            case "urdu": return HOLDING_URDU;
            case "roman_hindi": return HOLDING_ROMAN;
            default: return HOLDING + " 🙏";
        }
    }

    static final String[] SOFT_EMOJI = {"😊", "✨", "🙂", "😄", "🌟", "🤗"};

    /** Makes sure a conversational reply carries at least one fitting emoji (unless emojis are off or the topic is serious). */
    static String ensureEmoji(Store s, String reply, String incoming, String chatKey, boolean serious) {
        if (emojis(s).equals("none") || EMOJI.matcher(reply).find()) return reply;
        String l = incoming.toLowerCase(Locale.ROOT);
        String e;
        if (serious) e = "🙏";
        else if (l.matches("(?s).*\\b(good ?night|gn|shubh ratri|so ja|sleep)\\b.*")) e = "🌙";
        else if (l.matches("(?s).*\\b(good ?morning|gm|suprabhat)\\b.*")) e = "☀️";
        else if (l.matches("(?s).*\\b(love|pyaar|pyar|miss you)\\b.*")) e = "❤️";
        else if (l.matches("(?s).*\\b(congrat|badhai|mubarak|passed|won|selected|birthday|bday)\\b.*")) e = "🎉";
        else if (l.matches("(?s).*\\b(haha|hehe|lol|lmao|😂|🤣)\\b.*") || l.contains("😂")) e = "😂";
        else if (l.matches("(?s).*\\b(cat|kitten|billi|dog|puppy|kutta|pet)\\b.*")) e = "🐾";
        else if (l.matches("(?s).*\\b(thanks|thank you|shukriya|dhanyavad|thx|ty)\\b.*")) e = "😊";
        else if (l.matches("(?s).*\\b(bye|take care|see you|tc)\\b.*")) e = "👋";
        else {
            String last = s.get("lastemoji:" + chatKey, "");
            e = SOFT_EMOJI[(int) (System.currentTimeMillis() / 1000 % SOFT_EMOJI.length)];
            if (e.equals(last)) e = SOFT_EMOJI[(java.util.Arrays.asList(SOFT_EMOJI).indexOf(e) + 1) % SOFT_EMOJI.length];
        }
        s.put("lastemoji:" + chatKey, e);
        return reply.trim() + " " + e;
    }

    static final ExecutorService pool = Executors.newSingleThreadExecutor();
    static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    static final Map<String, ScheduledFuture<?>> batchTimers = new HashMap<>();
    static final Map<String, Long> batchStart = new HashMap<>();
    static final Map<String, String> lastSent = new HashMap<>();   // chatKey -> last text ULTRON sent (to recognise it later)
    static final Map<String, NotifService.Note> liveNotes = new HashMap<>();   // chatKey -> newest notification with a reply button
    static Brain brain;
    static volatile boolean recovered = false;

    // =============================== settings ===============================
    /** off | manual (record only) | approve (draft, Arsh approves) | auto (routine replies go automatically). */
    public static String mode(Store s) {
        return s.get("auto_mode", "off");
    }

    public static boolean allApps(Store s) {
        return !"0".equals(s.get("auto_allapps", "1"));
    }

    public static boolean appOn(Store s, String pkg) {
        if (pkg.equals("com.arsh.ultron")) return false;
        if (allApps(s)) return true;
        for (String p : s.get("auto_apps", DEFAULT_APPS).split(",")) if (p.trim().equals(pkg)) return true;
        return false;
    }

    static int windowSec(Store s) {
        try {
            return Math.max(3, Math.min(15, Integer.parseInt(s.get("auto_window", "15"))));
        } catch (Exception e) {
            return 15;
        }
    }

    static int introMin(Store s) {
        try {
            return Math.max(5, Integer.parseInt(s.get("auto_intro_min", "5")));
        } catch (Exception e) {
            return 5;
        }
    }

    static int handoverMin(Store s) {
        try {
            return Math.max(5, Integer.parseInt(s.get("auto_handover_min", "30")));
        } catch (Exception e) {
            return 30;
        }
    }

    static String emojis(Store s) {
        return s.get("auto_emoji", "normal");   // none | light | normal
    }

    /** Short-message enforcement: every genuine conversational message gets a reply (Okay, Hmm, Thanks, Good night, emojis...). */
    public static boolean replyEvery(Store s) {
        return !"0".equals(s.get("auto_every", "1"));
    }

    /** Same-language enforcement: the reply must be in the sender's language and script. */
    public static boolean sameLang(Store s) {
        return !"0".equals(s.get("auto_samelang", "1"));
    }

    /** Per-contact rule: default | auto | approve | never. */
    static String rule(Store s, String chatKey) {
        return s.get("rule:" + chatKey, "default");
    }

    static boolean quietNow(Store s) {
        String a = s.get("quiet_start", ""), b = s.get("quiet_end", "");
        if (a.isEmpty() || b.isEmpty()) return false;
        try {
            Calendar c = Calendar.getInstance();
            int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            int st = Integer.parseInt(a.split(":")[0]) * 60 + Integer.parseInt(a.split(":")[1]);
            int en = Integer.parseInt(b.split(":")[0]) * 60 + Integer.parseInt(b.split(":")[1]);
            return st <= en ? (now >= st && now < en) : (now >= st || now < en);
        } catch (Exception e) {
            return false;
        }
    }

    public static JSONObject info(Store s) throws Exception {
        JSONArray apps = new JSONArray();
        for (String[] a : APPS) apps.put(new JSONObject().put("pkg", a[0]).put("name", a[1]).put("on", appOn(s, a[0])));
        return new JSONObject().put("mode", mode(s)).put("apps", apps).put("allApps", allApps(s))
            .put("groups", "1".equals(s.get("auto_groups", "0"))).put("style", s.get("auto_style", DEFAULT_STYLE))
            .put("every", replyEvery(s)).put("sameLang", sameLang(s)).put("window", windowSec(s)).put("introMin", introMin(s)).put("handoverMin", handoverMin(s))
            .put("emoji", emojis(s)).put("quietStart", s.get("quiet_start", "")).put("quietEnd", s.get("quiet_end", ""))
            .put("about", s.getSecret("about", "")).put("pending", Approvals.json())
            .put("contacts", contacts(s)).put("attention", attention(s)).put("queue", queueSummary(s));
    }

    public static void save(Context c, Store s, JSONObject in) {
        if (in.has("mode")) {
            String m = in.optString("mode");
            s.put("auto_mode", m.equals("auto") || m.equals("approve") || m.equals("manual") ? m : "off");
            s.activity("settings", "Automatic replies set to " + mode(s).toUpperCase(Locale.ROOT));
            Approvals.status(c, !mode(s).equals("off"), statusText(s));
        }
        if (in.has("groups")) s.put("auto_groups", in.optBoolean("groups") ? "1" : "0");
        if (in.has("every")) s.put("auto_every", in.optBoolean("every") ? "1" : "0");
        if (in.has("sameLang")) s.put("auto_samelang", in.optBoolean("sameLang") ? "1" : "0");
        if (in.has("allApps")) s.put("auto_allapps", in.optBoolean("allApps") ? "1" : "0");
        if (in.has("window")) s.put("auto_window", String.valueOf(Math.max(3, Math.min(15, in.optInt("window", 15)))));
        if (in.has("introMin")) s.put("auto_intro_min", String.valueOf(Math.max(5, in.optInt("introMin", 5))));
        if (in.has("handoverMin")) s.put("auto_handover_min", String.valueOf(Math.max(5, in.optInt("handoverMin", 30))));
        if (in.has("emoji")) s.put("auto_emoji", in.optString("emoji").matches("none|light|normal") ? in.optString("emoji") : "normal");
        if (in.has("quietStart")) s.put("quiet_start", in.optString("quietStart").matches("\\d{1,2}:\\d{2}") ? in.optString("quietStart") : "");
        if (in.has("quietEnd")) s.put("quiet_end", in.optString("quietEnd").matches("\\d{1,2}:\\d{2}") ? in.optString("quietEnd") : "");
        if (in.has("style")) s.put("auto_style", in.optString("style").trim().isEmpty() ? DEFAULT_STYLE : in.optString("style").trim());
        if (in.has("about")) s.putSecret("about", in.optString("about").trim());
        JSONArray apps = in.optJSONArray("apps");
        if (apps != null) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < apps.length(); i++) b.append(b.length() > 0 ? "," : "").append(apps.optString(i));
            s.put("auto_apps", b.toString());
        }
    }

    public static void refreshStatus(Context c) {
        Store s = new Store(c);
        Approvals.status(c, !mode(s).equals("off"), statusText(s));
    }

    static String statusText(Store s) {
        String m = mode(s);
        String r = Routines.active(s);
        if (!r.isEmpty()) return Routines.label(r) + " · " + (m.equals("auto") ? "answering for you" : m);
        return m.equals("auto") ? "Answering messages as your assistant. Personal ones wait for you."
            : m.equals("manual") ? "Recording messages for you. Not replying." : "Drafting replies for your approval.";
    }

    /** Emergency STOP: no more automatic actions, all waiting replies dropped. */
    public static String stop(Context c) {
        Agent.stop = true;                                                  // also ends an app-agent run
        Store s = new Store(c);
        s.put("auto_mode", "off");
        synchronized (batchTimers) {
            for (ScheduledFuture<?> f : batchTimers.values()) f.cancel(false);
            batchTimers.clear();
            batchStart.clear();
        }
        int n = Approvals.clear(c);
        Events.markAll(s, new String[]{"pending", "processing"}, "stopped");
        Approvals.status(c, false, "");
        s.activity("stop", "EMERGENCY STOP: automatic replies off" + (n > 0 ? ", " + n + " waiting replies cancelled" : ""));
        return "Stopped. Automatic replies are off" + (n > 0 ? " and " + n + " waiting replies were cancelled" : "") + ".";
    }

    // =============================== the event queue ===============================
    /** Encrypted, persistent record of every distinct incoming message and what happened to it. */
    static class Events {
        static JSONArray cache;

        static synchronized JSONArray all(Store s) {
            if (cache == null) {
                try {
                    cache = new JSONArray(s.getSecret("events", "[]"));
                } catch (Exception e) {
                    cache = new JSONArray();
                }
            }
            return cache;
        }

        static synchronized void save(Store s) {
            JSONArray a = all(s);
            if (a.length() > 600) {
                JSONArray keep = new JSONArray();
                for (int i = a.length() - 500; i < a.length(); i++) keep.put(a.opt(i));
                cache = keep;
            }
            s.putSecret("events", cache.toString());
        }

        static synchronized boolean has(Store s, String id) {
            JSONArray a = all(s);
            for (int i = a.length() - 1; i >= 0; i--) if (id.equals(a.optJSONObject(i).optString("id"))) return true;
            return false;
        }

        static synchronized void add(Store s, JSONObject e) {
            all(s).put(e);
            save(s);
        }

        static synchronized List<JSONObject> forChat(Store s, String chatKey, String... statuses) {
            List<JSONObject> out = new ArrayList<>();
            Set<String> want = new HashSet<>(java.util.Arrays.asList(statuses));
            JSONArray a = all(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && chatKey.equals(e.optString("chat")) && (want.isEmpty() || want.contains(e.optString("status")))) out.add(e);
            }
            return out;
        }

        static synchronized void mark(Store s, List<JSONObject> list, String status, String batch, String note) {
            for (JSONObject e : list) {
                try {
                    e.put("status", status).put("updated", System.currentTimeMillis());
                    if (batch != null) e.put("batch", batch);
                    if (note != null) e.put("note", note);
                } catch (Exception ignored) {
                }
            }
            save(s);
        }

        static synchronized void markAll(Store s, String[] from, String to) {
            Set<String> f = new HashSet<>(java.util.Arrays.asList(from));
            JSONArray a = all(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                try {
                    if (e != null && f.contains(e.optString("status"))) e.put("status", to);
                } catch (Exception ignored) {
                }
            }
            save(s);
        }

        /** True if this chat already has the same text (from the other person) within the time window, in any state. */
        /**
         * The same message delivered again in another form (an updated notification). Two messages that both carry
         * their own real timestamps more than 3 seconds apart are two genuine messages, even with identical text.
         */
        static synchronized boolean sameTextRecently(Store s, String chatKey, String text, long time, boolean realTime, long window) {
            String t = norm(text);
            JSONArray a = all(s);
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject e = a.optJSONObject(i);
                if (e == null || !chatKey.equals(e.optString("chat")) || "outgoing".equals(e.optString("kind"))) continue;
                if ("arsh".equals(e.optString("status")) || "ultron".equals(e.optString("status"))) continue;
                long et = e.optLong("time", e.optLong("received"));
                long dt = Math.abs(et - time);
                if (dt > window || !t.equals(norm(e.optString("text")))) continue;
                if (dt <= 3000 || !realTime || !e.optBoolean("rt")) return true;
            }
            return false;
        }

        /**
         * The same incoming message already handled under ANOTHER chat name of the same app
         * (apps sometimes label one conversation two ways). Returns that chat's name, or null.
         */
        static synchronized String elsewhere(Store s, String chatKey, String sender, String text, long time, String... statuses) {
            String t = norm(text);
            if (t.isEmpty()) return null;
            String pkg = chatKey.split("\\|", 2)[0];
            String who = chatKey.contains("|") ? chatKey.split("\\|", 2)[1] : chatKey;
            Set<String> want = new HashSet<>(java.util.Arrays.asList(statuses));
            JSONArray a = all(s);
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject e = a.optJSONObject(i);
                if (e == null || chatKey.equals(e.optString("chat")) || !e.optString("chat").startsWith(pkg + "|")) continue;
                if (!want.contains(e.optString("status")) || !t.equals(norm(e.optString("text")))) continue;
                long et = e.optLong("time", e.optLong("received"));
                long dt = Math.abs(et - time);
                if (dt > 3 * 60000L) continue;
                // only the SAME person (exact name) at the same moment: similar names are never treated as one person
                boolean samePerson = NotifService.exact(sender).equals(NotifService.exact(e.optString("sender")))
                    && !NotifService.exact(sender).isEmpty();
                if (samePerson && dt <= 3000) return e.optString("who");
            }
            return null;
        }

        /** Our reply showed up inside that chat's own notification: it really reached that conversation. */
        static synchronized void confirmDelivery(Store s, String chatKey, String text) {
            JSONArray a = all(s);
            boolean changed = false;
            for (int i = a.length() - 1; i >= 0 && i > a.length() - 200; i--) {
                JSONObject e = a.optJSONObject(i);
                if (e == null || !chatKey.equals(e.optString("chat")) || !"replied".equals(e.optString("status"))) continue;
                String r = e.optString("reply");
                if (!r.isEmpty() && (r.trim().equals(text.trim()) || r.contains(text.trim()) || text.contains(r.trim()))) {
                    try {
                        if (!"confirmed".equals(e.optString("delivery"))) {
                            e.put("delivery", "confirmed");
                            changed = true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            if (changed) save(s);
        }

        static synchronized boolean batchSent(Store s, String batch) {
            JSONArray a = all(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && batch.equals(e.optString("batch")) && "replied".equals(e.optString("status"))) return true;
            }
            return false;
        }

        static synchronized List<String> chatsWith(Store s, String... statuses) {
            Set<String> want = new HashSet<>(java.util.Arrays.asList(statuses));
            List<String> out = new ArrayList<>();
            JSONArray a = all(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && want.contains(e.optString("status")) && !out.contains(e.optString("chat"))) out.add(e.optString("chat"));
            }
            return out;
        }
    }

    static String sha(String t) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(t.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf(t.hashCode());
        }
    }

    // =============================== receiving ===============================
    /** One message inside a notification. */
    static class Msg {
        String sender, text, mime = "";
        long time;
        boolean fromMe, realTime;
        Uri uri;
    }

    /** Names Android/apps use for the phone owner's own messages. */
    static Set<String> selfNames(Bundle x) {
        Set<String> me = new HashSet<>(java.util.Arrays.asList("you", "me", "you:", "aap", "tum", "arsh"));
        try {
            CharSequence d = x.getCharSequence("android.selfDisplayName");
            if (d != null) me.add(d.toString().trim().toLowerCase(Locale.ROOT));
            Object user = x.get("android.messagingUser");
            if (user == null) user = x.get("android.messagingStyleUser");
            if (user instanceof Bundle) {
                CharSequence nm = ((Bundle) user).getCharSequence("name");
                if (nm != null) me.add(nm.toString().trim().toLowerCase(Locale.ROOT));
            } else if (user != null) {
                Object nm = user.getClass().getMethod("getName").invoke(user);
                if (nm != null) me.add(nm.toString().trim().toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignored) {
        }
        return me;
    }

    static List<Msg> messages(Notification n, long postTime, String title) {
        List<Msg> out = new ArrayList<>();
        Bundle x = n.extras;
        Set<String> me = selfNames(x);
        try {
            Parcelable[] msgs = x.getParcelableArray("android.messages");
            if (msgs != null && msgs.length > 0) {
                for (Parcelable p : msgs) {
                    if (!(p instanceof Bundle)) continue;
                    Bundle m = (Bundle) p;
                    CharSequence t = m.getCharSequence("text");
                    String mime = m.getString("type");
                    Object u = m.get("uri");
                    if (t == null && mime == null && u == null) continue;
                    Msg g = new Msg();
                    // a shared photo / reel / video with no text is still a message: it is never dropped
                    g.text = t != null && !t.toString().trim().isEmpty() ? t.toString().trim() : mediaLabel(mime);
                    g.mime = mime == null ? "" : mime;
                    if (u instanceof Uri) g.uri = (Uri) u;
                    g.realTime = m.containsKey("time");
                    g.time = m.getLong("time", postTime);
                    CharSequence who = m.getCharSequence("sender");
                    Object person = m.get("sender_person");
                    if (who == null && person != null) {
                        try {
                            Object nm = person.getClass().getMethod("getName").invoke(person);
                            if (nm != null) who = nm.toString();
                        } catch (Exception ignored) {
                        }
                    }
                    g.fromMe = who == null || me.contains(who.toString().trim().toLowerCase(Locale.ROOT));
                    g.sender = who == null ? "Arsh" : who.toString();
                    out.add(g);
                }
                return out;
            }
        } catch (Exception ignored) {
        }
        String text = NotifService.lastMessage(x);
        if (!text.isEmpty()) {
            Msg g = new Msg();
            g.text = text;
            g.time = postTime;
            g.sender = title;
            out.add(g);
        }
        return out;
    }

    static String mediaLabel(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return m.startsWith("image/") ? "📷 Photo" : m.startsWith("video/") ? "🎥 Video" : m.startsWith("audio/") ? "🎤 Voice message" : "📎 Shared content";
    }

    static final Pattern REEL = Pattern.compile("(?i)\\b(reels?|clip)\\b");
    static final Pattern POST = Pattern.compile("(?i)(sent (you )?a post|shared a post|\\bpost by\\b|sent .*'s post)");
    static final Pattern STORY = Pattern.compile("(?i)(story|stories)");
    static final Pattern PHOTO = Pattern.compile("(?i)(📷|\\bphotos?\\b|\\bimages?\\b|\\bpictures?\\b|sent an attachment)");
    static final Pattern VIDEO = Pattern.compile("(?i)(🎥|\\bvideos?\\b)");
    static final Pattern VOICE = Pattern.compile("(?i)(🎤|voice (message|note|clip)|\\baudio\\b)");
    static final Pattern STICKER = Pattern.compile("(?i)(\\bstickers?\\b|\\bgif\\b)");
    static final Pattern REACTION = Pattern.compile("(?i)(reacted|liked (a|your) message|loved (a|your) message)");
    static final Pattern LINK = Pattern.compile("(?i)(https?://|www\\.)");

    /** What a message is: text, reel, post, story, photo, video, voice, sticker, link, reaction, emoji. */
    static String contentType(String t) {
        String x = t == null ? "" : t;
        if (REACTION.matcher(x).find()) return "reaction";
        if (REEL.matcher(x).find() && (x.length() < 50 || x.toLowerCase(Locale.ROOT).matches("(?s).*\\b(sent|shared)\\b.*"))) return "reel";
        if (POST.matcher(x).find()) return "post";
        if (STORY.matcher(x).find() && x.length() < 120 && x.toLowerCase(Locale.ROOT).matches(".*(replied|reacted|mentioned|story).*")) return "story";
        if (VOICE.matcher(x).find() && x.length() < 60) return "voice";
        if (VIDEO.matcher(x).find() && x.length() < 60) return "video";
        if (PHOTO.matcher(x).find() && x.length() < 60) return "photo";
        if (STICKER.matcher(x).find() && x.length() < 40) return "sticker";
        if (LINK.matcher(x).find()) return "link";
        if (!x.trim().isEmpty() && x.replaceAll("[\\p{L}\\p{N}]", "").length() == x.length()) return "emoji";
        return "text";
    }

    static boolean isMedia(String type) {
        return !type.equals("text") && !type.equals("emoji") && !type.equals("reaction");
    }

    /** What kind of notification this is. Only "personal"/"group" may ever get an automatic reply. */
    static String classify(Notification n, String pkg, String title, String text) {
        String cat = n.category == null ? "" : n.category;
        String all = title + " " + text;
        if (OTP.matcher(all).find()) return "otp";
        if (SECURITY.matcher(all).find()) return "security";
        if (BANK.matcher(all).find() && !pkg.startsWith("com.whatsapp")) return "banking";
        if (cat.equals("missed_call") || (title + text).toLowerCase(Locale.ROOT).contains("missed call")) return "missed_call";
        if (cat.equals("call")) return "system";
        if (cat.equals("promo") || cat.equals("recommendation")) return "promotion";
        if (cat.equals("sys") || cat.equals("status") || cat.equals("progress") || cat.equals("service") || cat.equals("err")) return "system";
        if (DELIVERY.matcher(all).find() && !isMessenger(pkg)) return "delivery";
        if (PROMO.matcher(all).find() && !isMessenger(pkg)) return "promotion";
        if (EMERGENCY.matcher(text).find()) return "emergency";
        return "personal";
    }

    /**
     * A real group chat? Instagram, Telegram and SMS apps put a conversation title on ordinary one-to-one
     * chats too, so a title alone does not make a group: Android's own group flag does, or several different people writing.
     */
    static boolean isGroup(Notification n, String title) {
        Bundle x = n.extras;
        if (x.containsKey("android.isGroupConversation")) return x.getBoolean("android.isGroupConversation", false);
        return false;   // several different people without Android's group flag = a bundled notification: held, see receive()
    }

    static final Set<String> DIALERS = new HashSet<>(java.util.Arrays.asList("com.samsung.android.dialer", "com.android.server.telecom",
        "com.google.android.dialer", "com.android.phone", "com.android.dialer", "com.samsung.android.incallui"));

    static boolean isMessenger(String pkg) {
        for (String[] a : APPS) if (a[0].equals(pkg)) return true;
        return false;
    }

    /** Called for every notification Android shows (also when ULTRON's screen is closed). */
    public static void maybe(final Context c, final StatusBarNotification sbn) {
        pool.execute(new Runnable() {
            public void run() {
                try {
                    receive(c, sbn);
                } catch (Exception e) {
                    android.util.Log.e("ULTRON", "receive failed", e);
                }
            }
        });
    }

    static void receive(Context c, StatusBarNotification sbn) {
        Store s = new Store(c);
        if (!recovered) recover(c);
        String pkg = sbn.getPackageName();
        if (pkg.equals("com.arsh.ultron") || pkg.equals("android") || pkg.startsWith("com.android.systemui")) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        NotifService.Note note = NotifService.toNote(sbn);
        if (note == null) return;
        if (Owner.ours(note.text) || Owner.ours(note.title)) return;     // ULTRON's own report arriving on this phone
        if (Daily.findPhoneCheck(c, note)) return;                        // Arsh's secret "find my phone" word
        if (DIALERS.contains(pkg)) return;                                // SIM calls are handled by the call desk (Calls)
        if (Agenda.isEmail(pkg)) {                                        // emails: logged and summarised, never auto-answered
            Agenda.mail(c, note);
            return;
        }
        if (isMessenger(pkg) && Calls.fromNotification(c, sbn, note.app, note.title, note.text)) return;   // app calls
        String kind = classify(n, pkg, note.title, note.text);

        // things ULTRON never answers, but tells Arsh about
        if (kind.equals("missed_call") || kind.equals("emergency") || kind.equals("security")) {
            String id = sha(pkg + "|" + note.title + "|" + note.text + "|" + (sbn.getPostTime() / 60000));
            if (!Events.has(s, id)) {
                Events.add(s, event(id, note, note.title, note.text, sbn.getPostTime(), kind, "attention",
                    kind.equals("emergency") ? "Possible emergency: needs Arsh now" : kind.equals("missed_call") ? "Missed call" : "Security notification"));
                s.activity("attention", label(kind) + " from " + note.title + " (" + note.app + ")");
                Approvals.attention(c, kind, note.title, note.app, note.text, quietNow(s) && !kind.equals("emergency"));
            }
            if (!kind.equals("emergency")) return;
            if (Owner.sensitivity(s).equals("high")) Owner.emergency(c, note.title, note.app, note.text);
        }
        boolean noRoute = note.reply == null && !(isMessenger(pkg) && note.open != null);
        if (note.reply == null && !TapService.on() && !"1".equals(s.get("warned_tap", ""))) {
            s.put("warned_tap", "1");
            s.activity("attention", note.app + " has no Reply button in its notifications. Turn on ULTRON's screen helper "
                + "(Settings > Accessibility > ULTRON) so ULTRON can reply inside the app.");
        }
        if (kind.equals("banking") && Smart.on(s, "money_on", true)) Smart.money(s, note.text);
        if ((kind.equals("personal") || kind.equals("promotion")) && Smart.on(s, "scam_on", true)) {
            String why = Smart.scamReason(note.title, note.text, !Calls.category(s, note.title).equals("unknown"));
            if (why != null) {
                String id = sha(pkg + "|" + note.title + "|" + note.text);
                if (!Events.has(s, id)) Events.add(s, event(id, note, note.title, note.text, sbn.getPostTime(), "scam", "skipped",
                    "SCAM WARNING: " + why + ". Never answered."));
                Smart.warnScam(c, note.title, note.app, note.text, why);
                return;
            }
        }
        if (!kind.equals("personal") && !kind.equals("emergency")) {
            String id = sha(pkg + "|" + note.title + "|" + note.text);
            if (!Events.has(s, id)) {
                Events.add(s, event(id, note, note.title, note.text, sbn.getPostTime(), kind, "skipped", "Never auto-replied: " + label(kind)));
                s.activity("skipped", label(kind) + " from " + note.title + " (" + note.app + "): not answered by design");
            }
            return;
        }
        if (noRoute) {
            // a person's message in an app that offers no way to reply: recorded, never silently dropped
            String id = sha(pkg + "|" + note.title + "|" + note.text);
            if (!Events.has(s, id)) {
                Events.add(s, event(id, note, note.title, note.text, sbn.getPostTime(), kind, "skipped",
                    "NO_REPLY_POSSIBLE: " + note.app + " gives no way to reply to this notification"));
                s.activity("skipped", note.app + " message from " + note.title + ": the app offers no reply option (recorded)");
            }
            return;
        }
        Bundle x = n.extras;
        boolean group = isGroup(n, note.title);
        final String chatKey = pkg + "|" + note.title;
        if (!group && note.senders.size() > 1) {
            // one notification carrying messages from DIFFERENT people (a bundled inbox notification):
            // its reply button reaches only one of them, so nothing is sent automatically
            for (Msg m : messages(n, sbn.getPostTime(), note.title)) {
                if (m.fromMe) continue;
                String id = sha(pkg + "|" + m.sender + "|" + m.time + "|" + m.text);
                if (Events.has(s, id)) continue;
                Events.add(s, event(id, note, m.sender, m.text, m.time, kind, "attention",
                    "HELD: this notification mixes several people (" + note.senders + "). Not answered automatically so no reply can reach the wrong person."));
            }
            Owner.report(c, "high", "Reply held · " + note.app, note.app + " showed messages from several people in one notification ("
                + android.text.TextUtils.join(", ", note.senders) + "). ULTRON did not reply, so nobody gets a reply meant for someone else. Please answer them yourself.");
            return;
        }
        liveNotes.put(chatKey, note);

        List<Msg> msgs = messages(n, sbn.getPostTime(), note.title);
        int fresh = 0, dupes = 0;
        for (Msg m : msgs) {
            String id = sha(pkg + "|" + note.title + "|" + m.time + "|" + m.sender + "|" + m.text);
            if (Events.has(s, id) || (!m.fromMe && Events.sameTextRecently(s, chatKey, m.text, m.time, m.realTime, 5 * 60000L))) {
                dupes++;                                                  // duplicate delivery or the same message in another form
                continue;
            }
            if (!m.fromMe && m.time > 0 && m.time < lastReplyTime(s, chatKey) - 2000 && System.currentTimeMillis() - m.time > 20000) {
                Events.add(s, event(id, note, m.sender, m.text, m.time, "history", "history", "Older than ULTRON's last reply: already covered"));
                dupes++;
                continue;
            }
            String other = m.fromMe ? null : Events.elsewhere(s, chatKey, m.sender, m.text, m.time,
                "pending", "processing", "sending", "replied", "awaiting_approval", "skipped", "attention", "handover");
            if (other != null) {
                Events.add(s, event(id, note, m.sender, m.text, m.time, "duplicate", "duplicate",
                    "Same message already handled in '" + other + "' (shown here as '" + note.title + "'): answered once"));
                s.activity("duplicate", "Same message from " + note.title + " seen twice (also as '" + other + "'): answered once");
                dupes++;
                continue;
            }
            boolean ultronText = sentByUltron(s, chatKey, m.text);
            if (m.fromMe || ultronText) {
                String mine = lastSent.get(chatKey);
                boolean ours = ultronText || (mine != null && (mine.trim().equals(m.text) || mine.contains(m.text) || m.text.contains(mine.trim())));
                Events.add(s, event(id, note, "Arsh", m.text, m.time, "outgoing", ours ? "ultron" : "arsh", null));
                if (ours) Events.confirmDelivery(s, chatKey, m.text);
                else {
                    Smart.learnOwn(s, note.title, m.text);
                    Smart.archive(s, note.title, note.app, "Arsh", m.text);
                }
                if (!ours && System.currentTimeMillis() - m.time < 10 * 60000) handover(c, s, chatKey, note);
                continue;
            }
            if (System.currentTimeMillis() - m.time > 6 * 3600000L) {   // old history shown in the notification
                Events.add(s, event(id, note, m.sender, m.text, m.time, "history", "history", null));
                continue;
            }
            JSONObject e = event(id, note, m.sender, m.text, m.time, group ? "group" : kind, "pending", null);
            try {
                e.put("rt", m.realTime).put("ctype", contentType(m.text)).put("cid", note.chatId);
            } catch (Exception ignored) {
            }
            captureMedia(c, n, m, id);
            Events.add(s, e);
            fresh++;
            AutoReply.remember(s, chatKey, m.sender, m.text);
            Smart.archive(s, note.title, note.app, m.sender, m.text);
            if (!group) Smart.touch(s, note.title, note.app, m.text);
        }
        if (fresh == 0) {
            if (dupes > 0 && !msgs.isEmpty() && !msgs.get(msgs.size() - 1).fromMe)
                s.activity("duplicate", "Duplicate or updated notification from " + note.title + " (" + note.app + "): ignored, nothing new");
            return;
        }
        backlogCheck(c, s);
        String lang = language(msgs.get(msgs.size() - 1).text);
        if (!lang.isEmpty()) s.put("lang:" + chatKey, lang);
        s.activity("received", fresh + " new message" + (fresh == 1 ? "" : "s") + " from " + note.title + " on " + note.app);

        String m = mode(s), r = rule(s, chatKey);
        if (m.equals("off") || r.equals("never")) {
            Events.mark(s, Events.forChat(s, chatKey, "pending"), "skipped", null,
                m.equals("off") ? "NO_REPLY: automatic replies are OFF" : "NO_REPLY: this contact is set to never be answered");
            return;
        }
        if (Calls.blocked(Calls.category(s, note.title))) {
            Events.mark(s, Events.forChat(s, chatKey, "pending"), "skipped", null, "Restricted / unwanted contact: never answered automatically");
            return;
        }
        if (!appOn(s, pkg)) {
            Events.mark(s, Events.forChat(s, chatKey, "pending"), "skipped", null, "NO_REPLY: replies are switched off for " + note.app);
            return;
        }
        if (group && !"1".equals(s.get("auto_groups", "0"))) {
            Events.mark(s, Events.forChat(s, chatKey, "pending"), "skipped", null, "NO_REPLY: group chat (group replies are off)");
            return;
        }
        if (handoverUntil(s, chatKey) > System.currentTimeMillis()) {
            Events.mark(s, Events.forChat(s, chatKey, "pending"), "handover", null, "Arsh is handling this chat himself");
            return;
        }
        if (m.equals("manual")) {
            List<JSONObject> p = Events.forChat(s, chatKey, "pending");
            Events.mark(s, p, "attention", null, "Manual-only mode: recorded for Arsh");
            return;
        }
        if (kind.equals("emergency")) {
            // a possible emergency never waits for the 15-second window
            cancelBatch(chatKey);
            pool.execute(new Runnable() {
                public void run() {
                    process(c, chatKey, 0);
                }
            });
            return;
        }
        scheduleBatch(c, chatKey);
    }

    static JSONObject event(String id, NotifService.Note note, String sender, String text, long time, String kind, String status, String why) {
        JSONObject e = new JSONObject();
        try {
            e.put("id", id).put("chat", note.pkg + "|" + note.title).put("app", note.app).put("pkg", note.pkg).put("who", note.title)
                .put("sender", sender).put("text", text).put("time", time).put("kind", kind).put("status", status)
                .put("received", System.currentTimeMillis()).put("updated", System.currentTimeMillis());
            if (why != null) e.put("note", why);
        } catch (Exception ignored) {
        }
        return e;
    }

    static boolean selfName(String who) {
        String w = who == null ? "" : who.trim().toLowerCase(Locale.ROOT);
        return w.equals("you") || w.equals("me") || w.equals("arsh") || w.equals("you:");
    }

    static String label(String kind) {
        switch (kind) {
            case "otp": return "Code/OTP";
            case "banking": return "Banking alert";
            case "security": return "Security notification";
            case "promotion": return "Promotion";
            case "delivery": return "Delivery update";
            case "system": return "System notification";
            case "missed_call": return "Missed call";
            case "emergency": return "Possible emergency";
            default: return kind;
        }
    }

    static long lastReplyTime(Store s, String chatKey) {
        try {
            return Long.parseLong(s.get("lastreply:" + chatKey, "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean sentByUltron(Store s, String chatKey, String text) {
        String t = norm(text);
        if (t.isEmpty()) return false;
        if (t.contains(norm(INTRO)) || t.contains("ultron, arsh's ai assistant") || t.contains("passed it on to arsh")
            || t.contains("arsh tak pahuncha") || t.contains(norm(HOLDING_DEV).substring(0, 12)) || t.contains(norm(HOLDING_URDU).substring(0, 12))) return true;
        for (String h : s.get("sent_texts", "").split("\n")) if (!h.isEmpty() && h.equals(sha(t))) return true;
        for (JSONObject e : Events.forChat(s, chatKey, "replied", "sending")) if (t.equals(norm(e.optString("reply")))) return true;
        return false;
    }

    /** Two labels for the same person ("Arslan Airtel" / "Arslan Airtel (2 messages)" / "Arslan"). */
    static boolean similar(String a, String b) {
        String x = a == null ? "" : a.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        String y = b == null ? "" : b.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        if (x.length() < 3 || y.length() < 3) return false;
        return x.equals(y) || x.startsWith(y) || y.startsWith(x);
    }

    /** The person behind a chat, however the app labels it: app + letters of the name. */
    static String person(String chatKey) {
        String[] p = chatKey.split("\\|", 2);
        String n = p.length > 1 ? NotifService.chatName(p[1]) : "";
        return "p|" + p[0] + "|" + n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static String norm(String t) {
        return t == null ? "" : t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Remembers everything ULTRON sends (as fingerprints) so it can never answer itself. */
    static synchronized void rememberSent(Store s, String text) {
        String[] old = s.get("sent_texts", "").split("\n");
        StringBuilder b = new StringBuilder(sha(norm(text)));
        for (int i = 0; i < old.length && i < 199; i++) if (!old[i].isEmpty()) b.append("\n").append(old[i]);
        s.put("sent_texts", b.toString());
    }

    // =============================== Arsh takes over ===============================
    static long handoverUntil(Store s, String chatKey) {
        try {
            return Long.parseLong(s.get("handover:" + chatKey, "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    static void handover(Context c, Store s, String chatKey, NotifService.Note note) {
        long until = System.currentTimeMillis() + handoverMin(s) * 60000L;
        if (handoverUntil(s, chatKey) > System.currentTimeMillis()) return;
        s.put("handover:" + chatKey, String.valueOf(until));
        cancelBatch(chatKey);
        Events.mark(s, Events.forChat(s, chatKey, "pending"), "handover", null, "Arsh replied himself");
        s.activity("handover", "Arsh replied to " + note.title + " himself: ULTRON stays quiet in this chat for " + handoverMin(s) + " minutes");
    }

    public static void takeOver(Store s, String chatKey, int minutes) {
        s.put("handover:" + chatKey, String.valueOf(minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60000L));
        if (minutes > 0) cancelBatch(chatKey);
        s.activity("handover", minutes > 0 ? "Arsh took over " + chatKey.split("\\|", 2)[1] + " for " + minutes + " minutes"
            : "ULTRON may reply in " + chatKey.split("\\|", 2)[1] + " again");
    }

    // =============================== the 15-second window ===============================
    static void scheduleBatch(final Context c, final String chatKey) {
        Store s = new Store(c);
        synchronized (batchTimers) {
            if (batchTimers.containsKey(chatKey)) return;                // already collecting: never reset the timer
            long start = System.currentTimeMillis();
            if (start - lastReplyTime(s, chatKey) < 20000) {
                // ULTRON just answered this chat: give the app a moment to show that, so a late copy is recognised as a duplicate
                start = lastReplyTime(s, chatKey) + 20000 - windowSec(s) * 1000L;
            }
            batchStart.put(chatKey, start);
            batchTimers.put(chatKey, timer.schedule(new Runnable() {
                public void run() {
                    synchronized (batchTimers) {
                        batchTimers.remove(chatKey);
                        batchStart.remove(chatKey);
                    }
                    pool.execute(new Runnable() {
                        public void run() {
                            process(c, chatKey, 0);
                        }
                    });
                }
            }, Math.max(1000, start + windowSec(s) * 1000L - System.currentTimeMillis()), TimeUnit.MILLISECONDS));
        }
    }

    static void cancelBatch(String chatKey) {
        synchronized (batchTimers) {
            ScheduledFuture<?> f = batchTimers.remove(chatKey);
            if (f != null) f.cancel(false);
            batchStart.remove(chatKey);
        }
    }

    /** After a crash, restart or reconnect: finish whatever was left. Never sends twice. */
    public static void recover(Context c) {
        recovered = true;
        Store s = new Store(c);
        // a batch that was being sent when ULTRON stopped: check if the reply really went out
        for (String chat : Events.chatsWith(s, "sending")) {
            List<JSONObject> sending = Events.forChat(s, chat, "sending");
            String text = sending.isEmpty() ? "" : sending.get(0).optString("reply");
            if (seenInChat(chat, text)) Events.mark(s, sending, "replied", null, "Confirmed sent after restart");
            else Events.mark(s, sending, "attention", null, "ULTRON stopped while sending; not resent to avoid a duplicate. Please check this chat.");
        }
        for (String chat : Events.chatsWith(s, "processing")) Events.mark(s, Events.forChat(s, chat, "processing"), "pending", null, null);
        if (mode(s).equals("off") || mode(s).equals("manual")) return;
        for (String chat : Events.chatsWith(s, "pending")) scheduleBatch(c, chat);
    }

    /** True if the chat's notification now shows Arsh's side containing this text. */
    static boolean seenInChat(String chatKey, String text) {
        if (text == null || text.isEmpty()) return false;
        NotifService s = NotifService.instance;
        if (s == null) return false;
        try {
            for (StatusBarNotification sbn : s.getActiveNotifications()) {
                NotifService.Note n = NotifService.toNote(sbn);
                if (n == null || !(n.pkg + "|" + n.title).equals(chatKey)) continue;
                for (Msg m : messages(sbn.getNotification(), sbn.getPostTime(), n.title)) if (m.fromMe && text.trim().equals(m.text.trim())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // =============================== thinking and replying ===============================
    static void process(final Context c, final String chatKey, final int tries) {
        final Store s = new Store(c);
        if (mode(s).equals("off")) return;
        List<JSONObject> batch = Events.forChat(s, chatKey, "pending");
        if (batch.isEmpty()) return;
        if (handoverUntil(s, chatKey) > System.currentTimeMillis()) {
            Events.mark(s, batch, "handover", null, "Arsh is handling this chat himself");
            return;
        }
        List<JSONObject> real = new ArrayList<>();
        for (JSONObject e : batch) {
            if (sentByUltron(s, chatKey, e.optString("text")) || selfName(e.optString("sender"))) {
                try {
                    e.put("status", "ultron").put("note", "ULTRON's own message: never answered");
                } catch (Exception ignored) {
                }
            } else real.add(e);
        }
        Set<String> seenText = new HashSet<>();
        List<JSONObject> unique = new ArrayList<>();
        for (JSONObject e : real) {
            String other = Events.elsewhere(s, chatKey, e.optString("sender"), e.optString("text"), e.optLong("time"),
                "processing", "sending", "replied", "awaiting_approval");
            if (other != null) {
                try {
                    e.put("status", "duplicate").put("note", "Already answered in '" + other + "': answered once");
                } catch (Exception ignored) {
                }
                continue;
            }
            seenText.add(norm(e.optString("text")));
            unique.add(e);                                                // the same text sent twice is two messages, one reply covers both
        }
        if (unique.size() < batch.size()) Events.save(s);
        batch = unique;
        if (batch.isEmpty()) return;
        final String batchId = sha(chatKey + "|" + batch.get(0).optString("id") + "|" + batch.size());
        if (Events.batchSent(s, batchId)) return;                          // already answered: never twice
        Events.mark(s, batch, "processing", batchId, null);
        NotifService.Note note = refreshed(chatKey);
        if (note != null && note.reply == null) {
            if (!TapService.on()) {
                Events.mark(s, batch, "attention", batchId, note.app + " has no Reply button and the screen helper is off. Recorded for Arsh.");
                s.activity("failed", "Could not reply to " + note.title + " on " + note.app + ": turn on ULTRON in Settings > Accessibility");
                return;
            }
            if (locked(c) && !secureLock(c)) wake(c);              // swipe / no lock: wake the screen and open it
            if (locked(c)) {
                // a PIN/pattern/fingerprint lock is never bypassed: reply the moment Arsh unlocks the phone
                Events.mark(s, batch, "pending", batchId, "Waiting for the phone to be unlocked to reply inside " + note.app);
                s.put("wait_unlock", "1");
                if (tries < 72) {
                    timer.schedule(new Runnable() {
                        public void run() {
                            pool.execute(new Runnable() {
                                public void run() {
                                    process(c, chatKey, tries + 1);
                                }
                            });
                        }
                    }, 10, TimeUnit.MINUTES);
                }
                return;
            }
        }
        if (note == null) {
            Events.mark(s, batch, "attention", batchId, "The notification closed before ULTRON could reply. Recorded for Arsh.");
            s.activity("failed", "Could not reply to " + chatKey.split("\\|", 2)[1] + ": notification no longer available");
            return;
        }
        StringBuilder incoming = new StringBuilder();
        boolean onlyMedia = true, question = false, emergency = false, noNeed = true;
        Set<String> listed = new HashSet<>();
        for (JSONObject e : batch) {
            String t = e.optString("text");
            String type = e.optString("ctype", contentType(t));
            String seen = describeMedia(c, s, e);
            String line = (type.equals("text") ? "" : "[" + type + "] ") + t + (seen == null ? "" : seen);
            if (listed.add(norm(line))) incoming.append("- ").append(line).append("\n");
            if (!MEDIA.matcher(t).find()) onlyMedia = false;
            if (t.contains("?") || Pattern.compile("(?i)\\b(kya|kab|kaha|kahan|kyu|kyun|kaise|what|when|where|why|how|can you|could you|please)\\b").matcher(t).find()) question = true;
            if ("emergency".equals(e.optString("kind"))) emergency = true;
            if (!NO_NEED.matcher(t).find()) noNeed = false;
        }
        boolean group = "group".equals(batch.get(0).optString("kind"));
        s.activity("batch", "Answering " + batch.size() + " message" + (batch.size() == 1 ? "" : "s") + " from " + note.title + " on " + note.app + " together");

        try {
            if (brain == null) { brain = new Brain(s); brain.ctx = c.getApplicationContext(); }
            String lang = language(incoming.toString().replaceAll("(?m)^- ", ""));
            if (lang.isEmpty()) lang = s.get("lang:" + chatKey, "");
            JSONArray ctx = context(s, chatKey);
            StringBuilder hist = new StringBuilder();
            for (int i = 0; i < ctx.length(); i++) {
                JSONObject m = ctx.optJSONObject(i);
                if (m == null) continue;
                // ULTRON's own earlier replies in another language are left out, so they never pull this reply off-language
                if ("ULTRON".equals(m.optString("from")) && !scriptMatches(lang, m.optString("text").replace(INTRO, ""))) continue;
                hist.append(m.optString("from")).append(": ").append(m.optString("text")).append("\n");
            }
            String about = s.getSecret("about", "");
            String weather = "";
            if (Weather.ASKS.matcher(incoming).find() && Pattern.compile("(?i)\\b(weather|forecast|temperature|mausam|mosam|aqi|air quality|pollution|degree|rain|baarish|barish)\\b|मौसम|बारिश|موسم").matcher(incoming).find()) {
                java.util.List<String> places = Weather.cities(brain, incoming.toString());
                weather = places.isEmpty()
                    ? "- They asked about the weather without naming a place: ask which city (in their language). NEVER say or guess where Arsh is.\n"
                    : "- LIVE WEATHER DATA (answer their weather question with exactly these facts, never invent weather, never say where Arsh is):\n"
                    + Weather.factsFor(places, Weather.lastDays) + "\n";
                s.activity("weather", "Looked up live weather for " + note.title + (places.isEmpty() ? " (asked which city)" : ": " + places));
            }
            String emo = emojis(s);
            String prompt = "You are ULTRON, Arsh's personal AI Assistant. Someone sent messages to Arsh on " + note.app
                + ". You answer AS ULTRON on his behalf, never as Arsh, never pretending to be him.\n"
                + "Chat: " + note.title + (group ? " (a GROUP chat: reply only if a message is addressed to Arsh or clearly needs an answer)" : "") + "\n"
                + "Earlier conversation in THIS chat only (never mention other people's chats):\n" + (hist.length() == 0 ? "(none)\n" : hist)
                + "NEW MESSAGES (answer all of them together in ONE natural reply):\n" + incoming
                + "\nYour recent replies in this chat (do NOT reuse their greetings, sentence shapes, jokes, closing lines or emoji combinations):\n"
                + recentReplies(s, chatKey, 5, lang) + "\n"
                + "Style: " + s.get("auto_style", DEFAULT_STYLE) + "\n"
                + "Now: " + new java.text.SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH).format(new java.util.Date()) + " (local time)\n"
                + (Routines.status(s).isEmpty() ? "" : "Arsh's current status, set by Arsh himself (you MAY tell them exactly this): " + Routines.status(s) + "\n")
                + (about.isEmpty() ? "" : "Information Arsh approved for sharing: " + about + "\n")
                + (Smart.personContext(c, s, note.title).isEmpty() || group ? "" : "What ULTRON knows about " + note.title + " (use only if relevant, never recite it): "
                    + Smart.personContext(c, s, note.title) + "\n")
                + Smart.styleHints(s, note.title) + Learn.lessonText(s) + "\n"
                + "Rules:\n" + weather
                + "- Do NOT introduce yourself (the introduction is added automatically when needed).\n"
                + "- Write a fresh, original, context-aware reply that acknowledges EVERY message in the batch: answer questions directly, "
                + "congratulate good news, show empathy if they are upset, be playful when they joke. Understand slang, abbreviations and typos.\n"
                + "- LANGUAGE: " + languageRule(lang) + " The language of the NEW messages decides, even if earlier messages or your earlier "
                + "replies used another language. Never translate, never switch language unless they do.\n"
                + "- Short messages (okay, hmm, thanks, good night, good morning, bye, take care, yes, no, love you, an emoji) get a SHORT, warm, "
                + "natural reply of one line, never a paragraph and never unrelated information.\n"
                + (emo.equals("none") ? "- Do not use emojis.\n" : emo.equals("light")
                ? "- Use exactly one fitting emoji, different from your recent replies.\n"
                : "- Every reply MUST contain at least one fitting emoji (😂🤣 humour, 😊✨ friendly, ❤️🥰 affection, 🙏 respect, 🐱🐶 pets, "
                + "🎉 celebrations, 🌙 good night, ☀️ good morning), chosen for this message and different from the emojis in your recent replies; "
                + "never after every sentence, at most 3, and only a gentle 🙏 or 🤍 in serious or sad conversations.\n")
                + "- Never invent Arsh's location, activities, feelings, opinions, plans or availability. Never claim to be physically present "
                + "or to have experienced anything. Never share his private information. Never make promises or commitments for him.\n"
                + "- If they ask something only Arsh can decide or know (personal questions, plans, meeting, money, private details), "
                + "say politely that you will confirm with Arsh, and set needs_arsh to true.\n"
                + "- SHARED CONTENT ([reel], [post], [story], [photo], [video], [voice], [sticker], [link], [reaction]) is a genuine message and "
                + "MUST get a reply, together with the other messages. Use ONLY what you are given: the notification text, a caption or account "
                + "name, and 'ULTRON can see:' descriptions. Match its mood when that is clear (funny 😂, romantic ❤️, cute 🥰, sad 🥺, "
                + "motivational 🔥). If you were NOT told what it shows, never pretend you watched, saw or heard it: react warmly to them sharing "
                + "it and say Arsh will see it (for example: thanks for sharing the reel, I'll make sure Arsh sees it).\n"
                + "- Level: 1 = routine (greetings, small talk, acknowledging, passing a message on). 2 = personal, emotional, romantic, family, "
                + "invitations, plans, commitments, personal information, important decisions. 3 = money, payments, passwords, security, legal, confidential.\n"
                + (replyEvery(s)
                ? "- Every genuine conversational message from a person MUST get a reply, even a single word or emoji. Use action skip ONLY for "
                + "automated, promotional or system text that is not a person talking.\n"
                : "- action skip when no reply is useful: reactions, emojis alone, stickers, or a closing 'ok', 'thanks', 'good night' when a reply "
                + "would be repetitive. Never skip a real question.\n")
                + "- holding: a short, polite message IN THE SAME LANGUAGE AND SCRIPT saying you have passed the message to Arsh and he will reply "
                + "personally soon (used when level is 2 or 3), with 🙏.\n"
                + "- emergency: true ONLY for a genuine emergency (someone injured, medical emergency, accident, immediate danger or threat, "
                + "family urgently needing Arsh, or they say Arsh must be contacted immediately). Ordinary 'urgent' requests, jokes, films or "
                + "past events are NOT emergencies. emergency_summary: one line saying who needs what.\n"
                + "- plan: ONLY if a new message mentions a concrete date or time for something that involves Arsh (a call, meeting, visit, "
                + "deadline, payment, event), fill {\"type\": \"event\" (meeting/visit/appointment) or \"reminder\" (call back, pay, send, "
                + "do something), \"title\": \"short, e.g. Call Arslan back\", \"when\": \"yyyy-MM-ddTHH:mm\", \"minutes\": 60}. "
                + "Otherwise type is empty. Never promise it to them; Arsh decides.\n"
                + "Answer ONLY with JSON: {\"action\": \"reply\" or \"skip\", \"level\": 1, \"needs_arsh\": false, \"reply\": \"text\", "
                + "\"holding\": \"text\", \"reason\": \"few words\", \"emergency\": false, \"emergency_summary\": \"\", "
                + "\"plan\": {\"type\": \"\", \"title\": \"\", \"when\": \"\", \"minutes\": 60}, \"topic\": \"3-6 words: what this chat is about\", "
                + "\"birthday\": \"MM-DD only if the sender states their OWN birthday, else empty\"}";
            String raw = brain.quick(prompt, "You are ULTRON's message system. Output only JSON.");
            JSONObject j = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
            boolean realEmergency = j.optBoolean("emergency") && !Owner.sensitivity(s).equals("off");
            if (!group) {
                Smart.fact(s, note.title, "topic", j.optString("topic"));
                Smart.fact(s, note.title, "birthday", j.optString("birthday"));
            }
            JSONObject plan = j.optJSONObject("plan");
            if (plan != null && plan.optString("type").matches("event|reminder")) {
                Agenda.suggest(c, plan.optString("type"), plan.optString("title"), plan.optString("when"), plan.optInt("minutes", 60), note.title, note.app);
            }
            if (realEmergency) {
                String sum = j.optString("emergency_summary", "").trim();
                Owner.emergency(c, note.title, note.app, (sum.isEmpty() ? "" : sum + "\n") + "Messages: " + incoming.toString().trim());
            }
            String reply = clean(j.optString("reply"));
            boolean skip = "skip".equalsIgnoreCase(j.optString("action")) || reply.isEmpty();
            if (skip && (question || emergency || replyEvery(s))) {
                // a person wrote to Arsh: they get an answer, even for "okay" or a single emoji
                String again = brain.quick(prompt + "\nIMPORTANT: this is a person talking. You MUST reply (action reply), briefly.",
                    "You are ULTRON's message system. Output only JSON.");
                try {
                    JSONObject j2 = new JSONObject(again.substring(again.indexOf('{'), again.lastIndexOf('}') + 1));
                    if (!clean(j2.optString("reply")).isEmpty()) {
                        j = j2;
                        reply = clean(j2.optString("reply"));
                        skip = false;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!skip && sameLang(s) && !lang.isEmpty() && !scriptMatches(lang, reply)) {
                // wrong language or script: ask once more, strictly
                try {
                    String fix = brain.quick("Rewrite this reply so it says the same thing but follows this rule exactly: " + languageRule(lang)
                        + " Keep the emojis. Output only the rewritten reply.\n\nReply: " + reply, "You rewrite chat replies. Output only the reply.");
                    if (!clean(fix).isEmpty() && scriptMatches(lang, clean(fix))) reply = clean(fix);
                    s.activity("language", "Re-wrote a reply to " + note.title + " to match their language (" + lang.replace('_', ' ') + ")");
                } catch (Exception ignored) {
                }
                if (!scriptMatches(lang, reply)) {
                    // still the wrong language: translate it plainly, once more
                    try {
                        String fix = brain.quick("Translate this chat reply into " + langName(lang) + ". " + languageRule(lang)
                            + " Keep it short and keep the emojis. Output only the translation.\n\n" + reply,
                            "You translate chat replies. Output only the translation.");
                        if (!clean(fix).isEmpty() && scriptMatches(lang, clean(fix))) reply = clean(fix);
                    } catch (Exception ignored) {
                    }
                }
                if (!scriptMatches(lang, reply)) {
                    // never send a reply in the wrong language: a safe one in their language instead
                    reply = safeReply(lang);
                    s.activity("language", "Reply to " + note.title + " was in the wrong language twice: sent a simple " + langName(lang) + " reply instead");
                }
            }
            if (skip) {
                String why = j.optString("reason", "no reply needed");
                Events.mark(s, batch, (noNeed || onlyMedia) && !replyEvery(s) ? "skipped" : "attention", batchId, "No reply sent: " + why);
                s.activity("skipped", "No reply needed for " + note.title + " (" + why + ")" + (noNeed ? "" : ": recorded for Arsh"));
                return;
            }
            int level = Math.max(1, Math.min(3, j.optInt("level", 2)));
            if (MONEY.matcher(incoming).find()) level = 3;
            if (emergency) level = Math.max(level, 2);
            boolean needsArsh = j.optBoolean("needs_arsh") || level >= 2;
            reply = ensureEmoji(s, reply, incoming.toString(), chatKey, level >= 2 || emergency);
            String hold = clean(j.optString("holding"));
            if (hold.isEmpty() || (sameLang(s) && !lang.isEmpty() && !scriptMatches(lang, hold))) hold = holding(lang);
            hold = ensureEmoji(s, hold, "", chatKey, true);
            String reason = j.optString("reason", needsArsh ? "needs Arsh" : "routine");
            if (needsArsh && !realEmergency) {
                String cat = Calls.category(s, note.title);
                Owner.report(c, level == 3 || Calls.vip(cat) ? "high" : "normal", "Message · " + note.title + " (" + note.app + ")",
                    incoming.toString().trim() + "\nWhy it needs you: " + reason);
            }
            String r = rule(s, chatKey);
            boolean autoOk = (mode(s).equals("auto") || r.equals("auto")) && !r.equals("approve");
            if (!needsArsh && autoOk) {
                send(c, s, chatKey, batch, batchId, withIntro(s, chatKey, reply), "Auto-replied");
            } else if (level == 1 && autoOk) {
                // routine reply that promises to check with Arsh: send it, and tell Arsh
                send(c, s, chatKey, batch, batchId, withIntro(s, chatKey, reply), "Replied (Arsh to confirm)");
                Approvals.attention(c, "needs_arsh", note.title, note.app, incoming.toString().trim(), quietNow(s));
                s.activity("attention", note.title + " on " + note.app + " needs Arsh: " + reason);
            } else {
                // personal / sensitive: Arsh decides. In automatic mode the sender still gets a safe holding reply.
                if (autoOk) {
                    send(c, s, chatKey, batch, batchId, withIntro(s, chatKey, hold), "Holding reply");
                } else {
                    Events.mark(s, batch, "awaiting_approval", batchId, "Waiting for Arsh: " + reason);
                }
                Approvals.add(c, refreshedOr(chatKey, note), chatKey, incoming.toString().trim(), withIntro(s, chatKey, reply), level,
                    (level == 3 ? "restricted: " : "") + reason, quietNow(s) && !emergency);
            }
        } catch (Exception e) {
            if (tries < 3) {
                long wait = tries == 0 ? 20 : tries == 1 ? 60 : 180;
                Events.mark(s, batch, "pending", batchId, "AI busy, retrying");
                s.activity("retry", "AI brain busy for " + note.title + ", trying again in " + wait + " s");
                timer.schedule(new Runnable() {
                    public void run() {
                        pool.execute(new Runnable() {
                            public void run() {
                                process(c, chatKey, tries + 1);
                            }
                        });
                    }
                }, wait, TimeUnit.SECONDS);
                return;
            }
            if (mode(s).equals("auto")) {
                send(c, s, chatKey, Events.forChat(s, chatKey, "pending", "processing"), batchId,
                    withIntro(s, chatKey, holding(s.get("lang:" + chatKey, ""))), "AI offline, holding reply");
            } else {
                Events.mark(s, Events.forChat(s, chatKey, "pending", "processing"), "attention", batchId, "AI brains unavailable: recorded for Arsh");
            }
            s.activity("failed", "No AI answer for " + note.title + ": " + e.getMessage());
        }
    }

    static final Map<String, List<Long>> sentTimes = new HashMap<>();
    static final List<Long> allSent = new ArrayList<>();

    /** Safety brake: more than 4 replies in one chat within 3 minutes, or 15 overall within 5 minutes, means something is wrong. */
    static boolean loopBrake(Context c, Store s, String chatKey) {
        long now = System.currentTimeMillis();
        List<Long> t = sentTimes.get(chatKey);
        if (t == null) sentTimes.put(chatKey, t = new ArrayList<>());
        while (!t.isEmpty() && now - t.get(0) > 180000) t.remove(0);
        while (!allSent.isEmpty() && now - allSent.get(0) > 300000) allSent.remove(0);
        if (allSent.size() >= 15) {
            s.put("auto_mode", "manual");
            s.activity("stop", "SAFETY BRAKE: 15 replies in 5 minutes. Automatic replies switched to MANUAL. Check the message log.");
            Approvals.attention(c, "needs_arsh", "ULTRON safety brake", "ULTRON", "Too many replies in a short time. Automatic replies are now MANUAL until you switch them back.", false);
            return true;
        }
        if (t.size() >= 4) {
            s.put("handover:" + chatKey, String.valueOf(now + 30 * 60000L));
            s.activity("stop", "SAFETY BRAKE: 4 replies to " + chatKey.split("\\|", 2)[1] + " in 3 minutes. ULTRON stays quiet there for 30 minutes.");
            Approvals.attention(c, "needs_arsh", chatKey.split("\\|", 2)[1], "ULTRON", "ULTRON stopped replying in this chat for 30 minutes (too many replies in a short time).", false);
            return true;
        }
        return false;
    }

    /** Sends exactly once per batch, through the notification's own reply button. */
    static boolean send(Context c, Store s, String chatKey, List<JSONObject> batch, String batchId, String text, String what) {
        synchronized (AutoReply.class) {
            if (Events.batchSent(s, batchId)) return true;                 // idempotency: this batch is already answered
            if (loopBrake(c, s, chatKey)) {
                Events.mark(s, batch, "attention", batchId, "Safety brake: not sent (too many replies in a short time)");
                return false;
            }
            for (JSONObject e : batch) {
                try {
                    e.put("reply", text);
                } catch (Exception ignored) {
                }
            }
            NotifService.Note note = refreshed(chatKey);
            String doubt = verifyDestination(chatKey, batch, note);
            if (doubt != null) {
                Events.mark(s, batch, "attention", batchId, "HELD (not sent): " + doubt + ". Kept for Arsh so it cannot reach the wrong person.");
                s.activity("safety", "Reply to " + chatKey.split("\\|", 2)[1] + " NOT sent: " + doubt);
                Owner.report(c, "high", "Reply held · " + chatKey.split("\\|", 2)[1], "ULTRON did not send a reply because the destination could not be "
                    + "verified (" + doubt + "). Their message is kept in CONTROL > Needs you. Please reply yourself.");
                Approvals.attention(c, "needs_arsh", chatKey.split("\\|", 2)[1], note != null ? note.app : "", "Reply held: " + doubt, false);
                return false;
            }
            Events.mark(s, batch, "sending", batchId, null);
            boolean ok;
            String why = null;
            if (note != null && note.reply != null) ok = NotifService.sendReply(note, text);
            else if (note != null && note.open != null) {
                why = TapService.replyOnScreen(note.open, note.pkg, text, note.title);
                ok = why == null;
                if (wokeByUltron) {
                    wokeByUltron = false;
                    TapService.global(8);   // lock the screen again (as it was)
                }
                if (ok) s.activity("sent", "Replied inside " + note.app + " on screen (it has no Reply button)");
            } else ok = false;
            if (ok) {
                lastSent.put(chatKey, text);
                rememberSent(s, text);
                s.put("lastreply:" + chatKey, String.valueOf(System.currentTimeMillis()));
                sentTimes.get(chatKey).add(System.currentTimeMillis());
                allSent.add(System.currentTimeMillis());
                if (text.contains(INTRO)) {
                    s.put("intro:" + chatKey, String.valueOf(System.currentTimeMillis()));
                    s.put("intro:" + person(chatKey), String.valueOf(System.currentTimeMillis()));
                }
                Events.mark(s, batch, "replied", batchId, what);
                remember(s, chatKey, "ULTRON", text);
                s.activity("sent", what + " to " + (note != null ? note.title + " on " + note.app : chatKey) + ": " + text);
            } else {
                Events.mark(s, batch, "attention", batchId, "Could not send" + (why != null ? ": " + why : " (the notification closed)") + ". Recorded for Arsh.");
                s.activity("failed", "Could not reply to " + chatKey.split("\\|", 2)[1] + ": " + (why != null ? why : "the notification closed before sending"));
                Owner.report(c, "high", "Reply failed · " + chatKey.split("\\|", 2)[1], "ULTRON could not send its reply ("
                    + (why != null ? why : "the notification closed") + "). The message is kept in CONTROL > Needs you.");
            }
            return ok;
        }
    }

    /**
     * Last check before anything is sent: the reply must go out through THIS conversation's own notification.
     * Returns null when safe, otherwise why it is not.
     */
    static String verifyDestination(String chatKey, List<JSONObject> batch, NotifService.Note note) {
        if (note == null) return "the conversation's notification is gone";
        if (!(note.pkg + "|" + note.title).equals(chatKey)) return "the notification belongs to another chat";
        // two different live conversations showing the same name: ambiguous
        Set<String> ids = new HashSet<>(), people = new HashSet<>();
        int count = 0;
        for (NotifService.Note n : NotifService.notes()) {
            if (!(n.pkg + "|" + n.title).equals(chatKey) || (n.reply == null && n.open == null)) continue;
            count++;
            if (!n.chatId.isEmpty()) ids.add(n.chatId);
            people.addAll(n.senders);
        }
        if (ids.size() > 1) return "two different conversations are both called '" + note.title + "'";
        if (count > 1 && ids.isEmpty() && people.size() > 1) return "two different people appear under the name '" + note.title + "'";
        if (note.senders.size() > 1) return "the notification mixes several people";
        // every message in the batch must come from this conversation
        String title = NotifService.exact(note.title);
        for (JSONObject e : batch) {
            String cid = e.optString("cid");
            if (!cid.isEmpty() && !note.chatId.isEmpty() && !cid.equals(note.chatId)) return "a message in the batch came from a different conversation";
            if (!"group".equals(e.optString("kind"))) {
                String who = NotifService.exact(e.optString("sender"));
                if (!who.isEmpty() && !who.equals(title) && !note.senders.isEmpty() && !note.senders.contains(who))
                    return "the sender '" + e.optString("sender") + "' does not match this conversation";
            }
        }
        if (!note.senders.isEmpty() && !"group".equals(batch.get(0).optString("kind"))) {
            boolean any = false;
            for (JSONObject e : batch) if (note.senders.contains(NotifService.exact(e.optString("sender"))) || NotifService.exact(e.optString("sender")).equals(title)) any = true;
            if (!any) return "the conversation now shows a different person";
        }
        return null;
    }

    // ------------------------------ shared media ------------------------------
    static final Map<String, byte[]> media = new java.util.LinkedHashMap<String, byte[]>() {
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 12;
        }
    };

    /** Keeps (in memory only) a small copy of a photo shown in the notification, so the AI can look at it. */
    static void captureMedia(Context c, Notification n, Msg m, String id) {
        if (!isMedia(contentType(m.text)) && m.uri == null) return;
        android.graphics.Bitmap b = null;
        try {
            if (m.uri != null && m.mime.startsWith("image/")) {
                java.io.InputStream in = c.getContentResolver().openInputStream(m.uri);
                b = android.graphics.BitmapFactory.decodeStream(in);
                in.close();
            }
        } catch (Exception ignored) {
        }
        if (b == null) {
            try {
                Object pic = n.extras.get(Notification.EXTRA_PICTURE);
                if (pic instanceof android.graphics.Bitmap) b = (android.graphics.Bitmap) pic;
            } catch (Exception ignored) {
            }
        }
        if (b == null) return;
        try {
            float k = Math.min(1f, 768f / Math.max(b.getWidth(), b.getHeight()));
            android.graphics.Bitmap sm = android.graphics.Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)), Math.max(1, (int) (b.getHeight() * k)), true);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            sm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, o);
            synchronized (media) {
                media.put(id, o.toByteArray());
            }
        } catch (Exception ignored) {
        }
    }

    /** What ULTRON can actually see in shared media, or an honest note that it cannot. */
    static String describeMedia(Context c, Store s, JSONObject e) {
        String type = e.optString("ctype", "text");
        if (!isMedia(type)) return null;
        byte[] img;
        synchronized (media) {
            img = media.get(e.optString("id"));
        }
        if (img != null) {
            try {
                if (brain == null) { brain = new Brain(s); brain.ctx = c.getApplicationContext(); }
                String d = clean(brain.see(img, "Describe this image in one short sentence for a chat reply: what it shows and its mood "
                    + "(funny, cute, romantic, sad, motivational, beautiful...). Do not guess names of people."));
                if (!d.isEmpty()) {
                    e.put("media", "seen");
                    return " (ULTRON can see: " + d + ")";
                }
            } catch (Exception ignored) {
            }
        }
        try {
            e.put("media", "inaccessible");
        } catch (Exception ignored) {
        }
        s.activity("media", "CONTENT_INACCESSIBLE: a " + type + " from " + e.optString("who") + " could not be opened; ULTRON replies only to what the notification shows");
        return " (ULTRON cannot open this " + type + "; only the notification text above is known)";
    }

    /** Tells Arsh if messages pile up unanswered (more than 5 waiting for over 10 minutes). */
    static void backlogCheck(Context c, Store s) {
        JSONArray a = Events.all(s);
        long now = System.currentTimeMillis();
        int old = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && ("pending".equals(e.optString("status")) || "processing".equals(e.optString("status")))
                && now - e.optLong("received") > 10 * 60000L) old++;
        }
        if (old > 5 && !mode(s).equals("off") && !"1".equals(s.get("wait_unlock", ""))) {
            Owner.report(c, "high", "Messages waiting", old + " messages have been waiting more than 10 minutes without a reply. "
                + "Check internet, the AI keys, and CONTROL > Message log.");
        }
    }

    /** Called when Arsh approves a draft: the batch counts as answered. */
    public static void approvedSent(Store s, String chatKey, String text) {
        lastSent.put(chatKey, text);
        if (text.contains(INTRO)) s.put("intro:" + chatKey, String.valueOf(System.currentTimeMillis()));
        Events.mark(s, Events.forChat(s, chatKey, "awaiting_approval"), "replied", null, "Approved by Arsh");
        remember(s, chatKey, "ULTRON", text);
    }

    public static void rejected(Store s, String chatKey) {
        Events.mark(s, Events.forChat(s, chatKey, "awaiting_approval"), "completed", null, "Arsh rejected the draft");
    }

    /** The exact introduction, at most once per sender every five minutes (or the chosen interval). */
    static String withIntro(Store s, String chatKey, String reply) {
        String body = reply.trim().replaceAll("(?i)[^.!?]*\\b(i am|i'm|this is|it's|it is|main)\\s+ultron\\b[^.!?]*[.!?:]*\\s*", " ")
            .replaceAll("(?i)[^.!?]*\\bultron (here|speaking)\\b[^.!?]*[.!?:]*\\s*", " ")
            .replaceAll("(?i)[^.!?]*\\bcommunicating with you on his behalf\\b[^.!?]*[.!?]*\\s*", " ")
            .replaceAll("[ \\t]+", " ").trim();
        long last = 0;
        try {
            last = Math.max(Long.parseLong(s.get("intro:" + chatKey, "0")), Long.parseLong(s.get("intro:" + person(chatKey), "0")));
        } catch (Exception ignored) {
        }
        boolean introNow = System.currentTimeMillis() - last >= introMin(s) * 60000L;
        if (body.isEmpty()) body = "How may I help you?";
        return introNow ? INTRO + " " + body : body;
    }

    static String clean(String t) {
        if (t == null) return "";
        return t.replace("**", "").replace("__", "").replaceAll("^[\"']|[\"']$", "").trim();
    }

    static String recentReplies(Store s, String chatKey, int n) {
        return recentReplies(s, chatKey, n, "");
    }

    static String recentReplies(Store s, String chatKey, int n, String lang) {
        StringBuilder b = new StringBuilder();
        List<JSONObject> done = Events.forChat(s, chatKey, "replied");
        Set<String> seen = new HashSet<>();
        for (int i = done.size() - 1; i >= 0 && seen.size() < n; i--) {
            String r = done.get(i).optString("reply");
            if (!r.isEmpty() && (lang.isEmpty() || scriptMatches(lang, r.replace(INTRO, ""))) && seen.add(r)) b.append("- ").append(r).append("\n");
        }
        return b.length() == 0 ? "(none yet)" : b.toString();
    }

    /** The newest notification for this chat (its reply button changes after every message). */
    static NotifService.Note refreshed(String chatKey) {
        NotifService.Note openable = null;
        for (NotifService.Note n : NotifService.notes()) {
            if (!(n.pkg + "|" + n.title).equals(chatKey)) continue;
            if (n.reply != null) {
                liveNotes.put(chatKey, n);
                return n;
            }
            if (openable == null && n.open != null && isMessenger(n.pkg)) openable = n;
        }
        return openable;
    }

    static boolean secureLock(Context c) {
        try {
            android.app.KeyguardManager k = (android.app.KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            return k == null || k.isKeyguardSecure();
        } catch (Exception e) {
            return true;
        }
    }

    static volatile boolean wokeByUltron = false;

    /** Turns the screen on and opens a swipe-only lock screen (never a secure one). */
    static void wake(Context c) {
        try {
            android.os.PowerManager p = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            android.os.PowerManager.WakeLock w = p.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "ultron:reply");
            w.acquire(20000);
            android.content.Intent i = new android.content.Intent(c, WakeActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            for (int k = 0; k < 10 && locked(c); k++) Thread.sleep(400);
            wokeByUltron = !locked(c);
        } catch (Exception ignored) {
        }
    }

    /** Arsh unlocked the phone: answer everything that was waiting for it right away. */
    public static void unlocked(final Context c) {
        final Store s = new Store(c);
        if (!"1".equals(s.get("wait_unlock", ""))) return;
        s.put("wait_unlock", "");
        timer.schedule(new Runnable() {
            public void run() {
                pool.execute(new Runnable() {
                    public void run() {
                        for (String chat : Events.chatsWith(s, "pending")) process(c, chat, 0);
                    }
                });
            }
        }, 2, TimeUnit.SECONDS);
    }

    /** True when the phone is locked or the screen is off (ULTRON cannot reply on screen then). */
    static boolean locked(Context c) {
        try {
            android.app.KeyguardManager k = (android.app.KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            android.os.PowerManager p = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return (k != null && k.isKeyguardLocked()) || (p != null && !p.isInteractive());
        } catch (Exception e) {
            return false;
        }
    }

    static NotifService.Note refreshedOr(String chatKey, NotifService.Note fallback) {
        NotifService.Note n = refreshed(chatKey);
        return n != null ? n : fallback;
    }

    // =============================== per-contact memory ===============================
    static JSONArray context(Store s, String chatKey) {
        try {
            return new JSONArray(s.getSecret("ctx:" + chatKey, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static void remember(Store s, String chatKey, String from, String text) {
        try {
            JSONArray old = context(s, chatKey), now = new JSONArray();
            for (int i = Math.max(0, old.length() - 23); i < old.length(); i++) now.put(old.get(i));
            now.put(new JSONObject().put("from", from).put("text", text).put("t", System.currentTimeMillis()));
            s.putSecret("ctx:" + chatKey, now.toString());
        } catch (Exception ignored) {
        }
    }

    // =============================== dashboard data ===============================
    static JSONArray contacts(Store s) {
        JSONArray out = new JSONArray();
        Map<String, JSONObject> seen = new HashMap<>();
        JSONArray a = Events.all(s);
        for (int i = a.length() - 1; i >= 0 && seen.size() < 40; i--) {
            JSONObject e = a.optJSONObject(i);
            if (e == null || seen.containsKey(e.optString("chat")) || "history".equals(e.optString("status"))) continue;
            try {
                String chat = e.optString("chat");
                JSONObject c = new JSONObject().put("chat", chat).put("who", e.optString("who")).put("app", e.optString("app"))
                    .put("rule", rule(s, chat)).put("handover", handoverUntil(s, chat) > System.currentTimeMillis());
                seen.put(chat, c);
                out.put(c);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static JSONArray attention(Store s) {
        JSONArray out = new JSONArray(), a = Events.all(s);
        for (int i = a.length() - 1; i >= 0 && out.length() < 40; i--) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && "attention".equals(e.optString("status"))) out.put(e);
        }
        return out;
    }

    static JSONArray queueSummary(Store s) {
        JSONArray out = new JSONArray(), a = Events.all(s);
        for (int i = a.length() - 1; i >= 0 && out.length() < 60; i--) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && !"history".equals(e.optString("status")) && !"outgoing".equals(e.optString("kind"))) out.put(e);
        }
        return out;
    }

    public static void attentionDone(Store s, String id) {
        JSONArray a = Events.all(s);
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && (id.equals(e.optString("id")) || id.equals("all")) && "attention".equals(e.optString("status"))) l.add(e);
        }
        Events.mark(s, l, "completed", null, "Seen by Arsh");
    }

    public static void setRule(Store s, String chatKey, String r) {
        s.put("rule:" + chatKey, r.matches("default|auto|approve|never") ? r : "default");
        s.activity("settings", "Reply rule for " + chatKey.split("\\|", 2)[1] + ": " + r);
    }

    // =============================== voice commands ===============================
    /** "Reply to everyone who messaged me": processes every chat that has unanswered messages now. */
    public static String replyAll(Context c, Store s) {
        int n = 0;
        for (NotifService.Note note : NotifService.notes()) {
            if (note.reply == null) continue;
            String chat = note.pkg + "|" + note.title;
            if (Events.forChat(s, chat, "pending", "attention").isEmpty()) continue;
            Events.mark(s, Events.forChat(s, chat, "attention"), "pending", null, "Arsh asked to reply");
            cancelBatch(chat);
            final String key = chat;
            final Context cc = c;
            pool.execute(new Runnable() {
                public void run() {
                    process(cc, key, 0);
                }
            });
            n++;
        }
        return n == 0 ? "There are no unanswered messages I can reply to right now, Arsh."
            : "Replying to " + n + " chat" + (n == 1 ? "" : "s") + " now. Personal ones will wait for your approval.";
    }

    public static String summary(Store s) {
        try {
            JSONArray log = new JSONArray(s.getSecret("activity", "[]"));
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (int i = 0; i < log.length() && n < 4; i++) {
                JSONObject e = log.getJSONObject(i);
                if (!e.optString("kind").equals("sent")) continue;
                b.append(e.optString("text")).append(". ");
                n++;
            }
            int waiting = Approvals.list().size(), att = attention(s).length();
            String tail = (waiting > 0 ? waiting + " replies are waiting for your approval. " : "") + (att > 0 ? att + " messages need your attention." : "");
            return (n == 0 ? "I have not sent any replies yet, Arsh. " : b.toString()) + tail;
        } catch (Exception e) {
            return "I could not read my history, Arsh.";
        }
    }
}
