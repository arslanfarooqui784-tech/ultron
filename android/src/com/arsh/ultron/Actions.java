package com.arsh.ultron;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ULTRON's action system on the phone. Every message, call or reply is confirmed by Arsh first. */
public class Actions {
    public static class Result {
        public String reply, label = "PHONE", nav = "";

        Result(String r) {
            reply = r;
        }

        Result nav(String page) {
            nav = page;
            return this;
        }
    }

    private final MainActivity a;
    private final Brain brain;
    private List<NotifService.Note> lastNotes = new ArrayList<>();
    private Map<String, ResolveInfo> apps;
    private long appsAt;

    static final Map<String, String> ALIAS = new HashMap<>();

    static {
        String[][] al = {{"insta", "instagram"}, {"yt", "youtube"}, {"you tube", "youtube"}, {"snap", "snapchat"},
            {"snap chat", "snapchat"}, {"whats app", "whatsapp"}, {"watsapp", "whatsapp"}, {"whatsap", "whatsapp"},
            {"what's app", "whatsapp"}, {"fb", "facebook"}, {"mail", "gmail"}, {"email", "gmail"}, {"e mail", "gmail"},
            {"emails", "gmail"}, {"inbox", "gmail"}, {"tele gram", "telegram"}, {"pw", "physics wallah"},
            {"calculator", "calculator"}, {"dialer", "phone"}, {"messages", "messages"}, {"sms", "messages"},
            {"files", "my files"}, {"notes", "samsung notes"}, {"store", "play store"}, {"photos", "gallery"}};
        for (String[] p : al) ALIAS.put(p[0], p[1]);
    }

    JSONObject quiz;
    String translateTo;

    /** True while running the steps of a plan Arsh already approved as a whole (each step was shown to him). */
    boolean preApproved = false;
    boolean inPlan = false;

    boolean confirm(String title, String body) {
        return preApproved || a.confirm(title, body);
    }

    public Actions(MainActivity a, Brain brain) {
        this.a = a;
        this.brain = brain;
    }

    static String tidy(String t) {
        t = t.toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "").trim();
        t = t.replaceAll("^(hey |ok |okay |hi )?(ultron[, ]+)?(please |can you |could you |would you |kindly |just )?", "").trim();
        t = t.replaceAll("\\s+(for me|please|now|right now|on my phone|on phone|in my phone)$", "").trim();
        return t;
    }

    static Matcher m(String regex, String text) {
        Matcher x = Pattern.compile(regex).matcher(text);
        return x.find() ? x : null;
    }

    static boolean has(String regex, String text) {
        return Pattern.compile(regex).matcher(text).find();
    }

    // ================= main entry =================
    public Result handle(String question) {
        String low = tidy(question);
        Matcher x;
        if (quiz != null && !has("^(stop|end|quit|cancel)( the)? quiz$", low)) return quizAnswer(low);
        if (has("^(stop|end|quit|cancel)( the)? quiz$", low) && quiz != null) {
            String r = "Quiz stopped. You got " + quiz.optInt("score") + " of " + quiz.optInt("i") + ".";
            quiz = null;
            return new Result(r);
        }
        if (translateTo != null) {
            if (has("^(stop|end|cancel|exit)( the)? (translat\\w*|translator)( mode)?$", low)) {
                translateTo = null;
                return new Result("Translator off.");
            }
            try {
                Result r = new Result(AutoReply.clean(brain.quick("Translate into " + translateTo + ". Keep the meaning and tone, natural spoken "
                    + translateTo + (translateTo.equalsIgnoreCase("hindi") ? " in Devanagari script" : translateTo.equalsIgnoreCase("urdu") ? " in Urdu script" : "")
                    + ". Output only the translation.\n\n" + question, "You are a translator. Output only the translation.")));
                r.label = "TRANSLATE";
                return r;
            } catch (Exception e) {
                return new Result("I couldn't translate that right now.");
            }
        }
        // control the PC from the phone
        if ((x = m("^(.+?)\\s+(?:on|in) (?:my |the )?(?:pc|computer|laptop|desktop)$", low)) != null && PcLink.paired(a.store)) {
            if (has("\\b(shut ?down|restart|reboot|log ?off|sign out|delete|format|kill|close all)\\b", low)
                && !confirm("Do this on your PC?", question)) return new Result("Okay, I won't.");
            Result r = new Result(PcLink.ask(a, x.group(1)));
            r.label = "PC";
            return r;
        }
        String learned = inPlan ? null : Learn.rewrite(a.store, low);       // a phrase ULTRON learned from Arsh
        if (learned != null && !learned.equals(low)) {
            a.store.activity("learn", "Understood \"" + low + "\" as \"" + learned + "\" (learned shortcut)");
            low = learned;
            question = learned;
        }
        if ((x = m("^(?:learn about|learn more about|teach yourself about|research about|research|study about|read about) (.+)$", low)) != null) {
            String f = Learn.learnAbout(a.store, x.group(1), true);
            return new Result(f == null ? "I couldn't learn about that right now, Arsh." : "Learned about " + x.group(1) + ". " + f.split("\n")[0] + " I'll use this from now on.");
        }
        if (has("^what (have|did) you (learn|learned|learnt)( today| recently| from me)?\\??$|^what do you know about me now", low)) {
            JSONArray l = Learn.lessons(a.store), k = Learn.knowledge(a.store), sc = Learn.shortcuts(a.store);
            StringBuilder b = new StringBuilder(a.store.get("learn_summary", "").isEmpty() ? "" : a.store.get("learn_summary", "") + ". ");
            if (l.length() > 0) b.append("About your style: ").append(l.optJSONObject(l.length() - 1).optString("t")).append(" ");
            b.append("I know ").append(l.length()).append(" lessons, ").append(sc.length()).append(" of your own phrases and ").append(k.length()).append(" topics.");
            return new Result(b.toString().trim());
        }
        if (has("^(improve|upgrade|update|review) yourself( now)?$|^self[- ]?(review|learn)( now)?$", low)) return new Result(Learn.reflect(a));
        if (has("^(check for |any )?(app )?updates?\\??$|^update (ultron|the app|app)$", low)) {
            Updater.check(a, true);
            return new Result("Checking your PC for a newer ULTRON. If there is one, you'll get a notification to install it.");
        }
        Result adv = advanced(question, low);
        if (adv != null) return adv;
        Result dr = daily(question, low);
        if (dr != null) return dr;
        if (!inPlan && multiStep(low)) {
            Result r = plan(question);
            if (r != null) return r;
        }

        // ULTRON's own screens
        if (has("^(open |show )?(my )?(api )?keys$|^(ai|api) keys$|^(open |show )?ultron settings$|^change (my )?keys$", low))
            return new Result("Opening my keys page, Arsh.").nav("keys.html");
        if (has("^(open |show |run )?(the )?setup$|^(open |give )?(ultron )?permissions$|^setup ultron$", low))
            return new Result("Opening setup, Arsh.").nav("setup.html");
        if (has("^(lock|lock yourself|lock ultron|lock the app)$", low)) {
            a.lock();
            return new Result("Locked.");
        }
        if ((x = m("^(?:use|switch to|change to)\\s+(?:the\\s+)?(gpt|chatgpt|astra|gpt 6|claude|gemini|google|grok|groq|openrouter|open router|auto|automatic)(?:\\s+brain)?$", low)) != null) {
            String w = x.group(1);
            String b = w.contains("gpt") || w.equals("astra") ? "chatgpt" : w.equals("google") ? "gemini" : w.startsWith("open") ? "openrouter"
                : w.startsWith("auto") ? "auto" : w;
            if (!b.equals("auto") && !a.store.keys().containsKey(b)) return sys("I don't have a " + Brain.NAMES.get(b) + " key yet, Arsh. Say: open keys.");
            brain.chosen = b;
            return sys(b.equals("auto") ? "Automatic mode. I will use my fastest brains." : Brain.NAMES.get(b) + " brain activated.");
        }
        if (has("^(which|what) (brain|ai|model)", low)) {
            return sys("I am a multi-brain system, Arsh. Right now I am on " + (brain.chosen.equals("auto") ? "automatic mode" : Brain.NAMES.get(brain.chosen)) + ".");
        }

        // memory
        if (has("^(remember|save|note|yaad rakh\\w*)( this| our| the)? (conversation|chat|discussion|talk)$|^remember this$", low))
            return sys(a.memory.rememberConversation());
        if ((x = m("^(?:please )?(?:remember|note down|save in memory|yaad rakhna|keep in mind)(?: that|:)?\\s+(.+)$", low)) != null
            && !has("^(remember|recall) (what|when|where|how|if|our|we|the last|last|everything)", low)) {
            String fact = question.replaceAll("(?i)^.*?\\b(remember|note down|save in memory|yaad rakhna|keep in mind)(\\s+that|:)?\\s+", "").trim();
            JSONObject saved = a.memory.add(guessCategory(fact), fact, "told by Arsh", 1.0, false);
            return sys(saved != null ? "Got it, Arsh. I will remember that." : "I did not save that. I never store passwords, codes or OTPs.");
        }
        if ((x = m("^(?:forget|delete|remove)(?: the)?(?: memory)?(?: about| that)?\\s+(.+)$", low)) != null && !has("\\b(file|photo|app|contact)\\b", low)) {
            if (Memory.words(x.group(1)).isEmpty()) return sys("What should I forget, Arsh? Say for example: forget my old phone number.");
            org.json.JSONArray hits = a.memory.search(x.group(1), null, 5);
            if (hits.length() == 0) return sys("I have no memory about " + x.group(1) + ", Arsh.");
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < hits.length(); i++) list.append("- ").append(hits.optJSONObject(i).optString("text")).append("\n");
            if (!confirm("Forget these memories?", list.toString())) return sys("Kept them.");
            for (int i = 0; i < hits.length(); i++) a.memory.delete(hits.optJSONObject(i).optString("id"));
            return sys("Forgotten.");
        }
        if (has("^(pause|stop) (your )?(memory|remembering|learning)", low)) {
            a.store.put("mem_paused", "1");
            return sys("Memory paused. I will not save new conversations or facts until you say resume memory.");
        }
        if (has("^(resume|start|turn on) (your )?(memory|remembering|learning)", low)) {
            a.store.put("mem_paused", "0");
            return sys("Memory is on again, Arsh.");
        }
        if (has("^(open |show )?(my |your )?(memory|memories|memory dashboard|understanding arsh)( dashboard| profile)?$|^what do you know about me", low)) {
            org.json.JSONArray all = a.memory.search("", null, 400);
            return new Result("I have " + all.length() + " memories about you, Arsh. Opening your memory dashboard.").nav("memory.html");
        }

        // EMERGENCY STOP and automatic replies
        if (has("^(stop|stop everything|emergency stop|stop all|ultron stop|abort|cancel everything)$", low)
            || has("\\bstop (all )?(the )?(automatic|automated|auto)\\b", low)
            || (has("\\b(auto ?reply|auto ?replies|automatic repl(y|ies)|automated repl(y|ies))\\b", low) && has("\\b(off|stop|disable|band|bandh)\\b", low))) {
            return sys(AutoReply.stop(a));
        }
        if (has("\\b(auto ?reply|auto ?replies|automatic repl(y|ies)|automated repl(y|ies)|auto ?respond|reply automatically|auto mode)\\b", low)) {
            if (has("\\b(on|start|enable|activate|chalu)\\b", low)) {
                if (!NotifService.connected()) return new Result("I need notification access first, Arsh. Opening setup.").nav("setup.html");
                try {
                    AutoReply.save(a, a.store, new JSONObject().put("mode", has("\\b(approv|ask|draft)", low) ? "approve" : "auto"));
                } catch (Exception ignored) {
                }
                return sys("Automatic replies are on, Arsh. I will answer routine messages as your assistant, and personal ones will wait for your approval.");
            }
            if (has("\\b(what|show|log|history|sent|did you)\\b", low)) return new Result(AutoReply.summary(a.store));
            return new Result("Opening the control center, Arsh.").nav("control.html");
        }
        if (has("\\b(manual|manual only|manual mode|record only)\\b", low) && has("\\b(mode|on|switch|only)\\b", low)) {
            try {
                AutoReply.save(a, a.store, new JSONObject().put("mode", "manual"));
            } catch (Exception ignored) {
            }
            return sys("Manual-only mode. I will record every message for you and not reply to anyone.");
        }
        if (has("^what did you (reply|send|say)|^(show|read) (the )?(reply|replies|activity) (log|history)", low)) return new Result(AutoReply.summary(a.store));
        if (has("\\b(approvals?|pending|waiting) ?(repl(y|ies)|messages)?\\b", low) && has("\\b(show|open|any|what|read|check|list)\\b", low)) {
            java.util.List<Approvals.Item> items = Approvals.list();
            if (items.isEmpty()) return new Result("Nothing is waiting for your approval, Arsh.");
            StringBuilder b = new StringBuilder(items.size() + " repl" + (items.size() == 1 ? "y is" : "ies are") + " waiting. ");
            for (int i = 0; i < Math.min(3, items.size()); i++) {
                Approvals.Item it = items.get(i);
                b.append("To ").append(it.who).append(" on ").append(it.app).append(": ").append(it.reply).append(". ");
            }
            return new Result(b.toString() + "Opening approvals.").nav("control.html");
        }
        if (has("^(reply|respond|answer) to (everyone|everybody|all)( who (messaged|texted) me)?|^reply (to )?all( messages)?$", low)) {
            if (!NotifService.connected()) return new Result("I need notification access first, Arsh. Opening setup.").nav("setup.html");
            return new Result(AutoReply.replyAll(a, a.store));
        }
        if (has("^(open |show )?(the )?(control( center| centre)?|dashboard|activity( history)?|history)$", low))
            return new Result("Opening the control center.").nav("control.html");

        // alarms, timers, reminders
        if ((x = m("\\b(?:set |wake me up |set an )?alarm (?:for |at )?(\\d{1,2})(?:[:. ](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?", low)) != null
            || (x = m("^wake me (?:up )?at (\\d{1,2})(?:[:. ](\\d{2}))?\\s*(am|pm)?", low)) != null) {
            int h = Integer.parseInt(x.group(1)), mi = x.group(2) == null ? 0 : Integer.parseInt(x.group(2));
            String ap = x.group(3) == null ? "" : x.group(3).replace(".", "");
            if (ap.equals("pm") && h < 12) h += 12;
            if (ap.equals("am") && h == 12) h = 0;
            start(new Intent(android.provider.AlarmClock.ACTION_SET_ALARM).putExtra(android.provider.AlarmClock.EXTRA_HOUR, h)
                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, mi).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "ULTRON alarm")
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true));
            return new Result("Alarm set for " + String.format(Locale.ENGLISH, "%d:%02d", h == 0 ? 12 : (h > 12 ? h - 12 : h), mi) + (h >= 12 ? " PM" : " AM") + ", Arsh.");
        }
        if ((x = m("\\b(?:set (?:a )?)?timer (?:for )?(\\d+)\\s*(second|sec|minute|min|hour|hr)s?\\b", low)) != null
            || (x = m("^(\\d+)\\s*(second|sec|minute|min|hour|hr)s? timer$", low)) != null) {
            int n = Integer.parseInt(x.group(1));
            String u = x.group(2);
            int secs = u.startsWith("h") ? n * 3600 : u.startsWith("m") ? n * 60 : n;
            start(new Intent(android.provider.AlarmClock.ACTION_SET_TIMER).putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "ULTRON timer").putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true));
            return new Result("Timer set for " + n + " " + u + (n == 1 ? "" : "s") + ".");
        }
        if ((x = m("^(?:remind me|set (?:a )?reminder)(?: to| for| about)?\\s+(.+)$", low)) != null) {
            String body = x.group(1).trim(), what = body, phrase = "in 1 hour";
            Matcher lead = Pattern.compile("^((?:tomorrow|today|tonight|in|at|on|next|this)\\b.*?)\\s+to\\s+(.+)$").matcher(body);
            Matcher tail = Pattern.compile("^(.+?)\\s+((?:at|on|by|tomorrow|today|tonight|in|next|this|after)\\b.*)$").matcher(body);
            if (lead.find()) { phrase = lead.group(1); what = lead.group(2); }
            else if (tail.find()) { what = tail.group(1); phrase = tail.group(2); }
            boolean daily = has("\\b(every ?day|daily|every night|every morning|everyday)\\b", body);
            what = what.replaceAll("\\s*\\b(every ?day|daily|every night|every morning|everyday)\\b\\s*", " ").trim();
            phrase = phrase.replaceAll("\\b(every ?day|daily|everyday)\\b", "today").replaceAll("every night", "tonight").replaceAll("every morning", "tomorrow morning");
            long when = Agenda.parseWhen(a.store, phrase);
            if (when < 0) return new Result("When should I remind you, Arsh? Say for example: remind me to call mom tomorrow at 6 pm.");
            if (daily) while (when < System.currentTimeMillis()) when += 86400000L;
            return new Result(Agenda.addReminder(a, what.replaceAll("^to\\s+", ""), when, "", daily));
        }
        if (has("^(what are |show |list |any )?(my )?reminders( today| for today)?\\??$", low)) {
            JSONArray r = Agenda.upcomingReminders(a.store, System.currentTimeMillis() + 7L * 86400000L);
            if (r.length() == 0) return new Result("You have no reminders coming up, Arsh.");
            StringBuilder b = new StringBuilder("Your reminders: ");
            for (int i = 0; i < r.length(); i++) b.append(i > 0 ? "; " : "").append(Agenda.NICE.format(new java.util.Date(r.optJSONObject(i).optLong("when")))).append(", ").append(r.optJSONObject(i).optString("what"));
            return new Result(b + ".");
        }

        // weather and forecast (live, any city)
        if (Weather.ASKS.matcher(low).find() && has("\\b(weather|forecast|temperature|temp|mausam|mosam|aqi|air quality|pollution|degrees?)\\b|\\b(will it|is it|going to) (rain|be hot|be cold|snow)|\\brain(ing)? (today|tomorrow|now)|मौसम", low)) {
            java.util.List<String> cities = Weather.cities(brain, question);
            if (cities.isEmpty()) cities.add(Weather.home(a.store));
            String facts = Weather.factsFor(cities, Weather.lastDays);
            try {
                Result r = new Result(AutoReply.clean(brain.quick("Arsh asked: \"" + question + "\"\nLIVE WEATHER DATA:\n" + facts
                    + "\n\nAnswer him in 2 to 5 natural spoken sentences using ONLY this data (temperatures, sky, rain chance, air quality). "
                    + "If several cities, compare briefly. Add one practical tip if useful (umbrella, water, mask). No lists, no emojis.", "You are ULTRON. Be brief and exact.")));
                r.label = "WEATHER";
                return r;
            } catch (Exception e) {
                return new Result(facts);
            }
        }

        // chat memory search
        if ((x = m("^(?:what|when|where|did|how much|which)\\b.*\\b(?:say|said|tell|told|ask|asked|mention|mentioned|send|sent|talk|talked|message|messaged)\\b.*$", low)) != null
            && !has("\\b(weather|forecast|calendar|reminder)\\b", low)) {
            Result r = new Result(Smart.search(brain, a.store, question));
            r.label = "MEMORY";
            return r;
        }
        if ((x = m("^(?:search|find)(?: in)? (?:my )?(?:chats|messages|conversations)(?: for| about)?\\s+(.+)$", low)) != null) {
            Result r = new Result(Smart.search(brain, a.store, "What was said about " + x.group(1) + "?"));
            r.label = "MEMORY";
            return r;
        }
        // people
        if ((x = m("^(?:tell me about|who is|what do you know about|info on)\\s+(.+?)\\??$", low)) != null && !Smart.person(a.store, x.group(1)).optString("name").isEmpty()) {
            String who = Smart.person(a.store, x.group(1)).optString("name");
            return new Result(who + ": " + Smart.personContext(a, a.store, who).replace("; ", ". ") + ". Last message: \"" + Smart.person(a.store, who).optString("lastText") + "\"");
        }
        if ((x = m("^(?:mark|set|add|put)\\s+(.+?)\\s+(?:as|in|to)\\s+(?:my\\s+)?(family|close|close friend|important|professional|business|unwanted|restricted)(?: contact| contacts)?$", low)) != null) {
            String cat = x.group(2).startsWith("close") ? "close" : x.group(2);
            Calls.setCategory(a.store, x.group(1), cat);
            return new Result(x.group(1) + " is now in " + cat + ".");
        }
        // money
        if (has("\\b(how much|what) (did i|have i) (spend|spent)|\\bmy (spending|expenses)\\b|\\bspending (this|last|today)|\\bexpenses (this|today)", low)) {
            boolean month = has("\\bmonth\\b", low), today = has("\\btoday\\b", low);
            long since = today ? System.currentTimeMillis() - 24 * 3600000L : System.currentTimeMillis() - (month ? 30L : 7L) * 86400000L;
            return new Result(Smart.spending(a.store, since, today ? "today" : month ? "in the last 30 days" : "in the last 7 days"));
        }
        // study quiz
        if ((x = m("^(?:quiz|test|question) me (?:on|about|in)\\s+(.+)$", low)) != null || (x = m("^(?:start|give me) (?:a )?quiz (?:on|about)\\s+(.+)$", low)) != null)
            return quizStart(x.group(1));
        // translator
        if ((x = m("^translate (?:this )?(?:in)?to (english|hindi|urdu|arabic|french|spanish|german|bengali|punjabi|marathi|tamil|telugu|gujarati)\\s*[:,-]?\\s+(.+)$", low)) != null) {
            try {
                Result r = new Result(AutoReply.clean(brain.quick("Translate into " + x.group(1) + (x.group(1).equals("hindi") ? " (Devanagari)" : "")
                    + ". Output only the translation.\n\n" + x.group(2), "Output only the translation.")));
                r.label = "TRANSLATE";
                return r;
            } catch (Exception e) {
                return new Result("I couldn't translate that right now.");
            }
        }
        if ((x = m("^(?:start |turn on )?(?:translator|translate|translation)(?: mode)?(?: from \\w+)?(?: in)? ?to (english|hindi|urdu|arabic|french|spanish|german|bengali|punjabi|marathi|tamil|telugu|gujarati)(?: mode)?$", low)) != null) {
            translateTo = x.group(1).substring(0, 1).toUpperCase(Locale.ROOT) + x.group(1).substring(1);
            return new Result("Translator on. Everything you say now, I'll say in " + translateTo + ". Say stop translating to finish.");
        }
        // screenshots
        if (has("\\bscreenshot\\b", low) && has("\\b(read|what|whats|explain|summari[sz]e|check|tell|total|solve|answer|translate|in my|last|latest)\\b", low))
            return screenshot(question);

        // briefing
        if (has("^(my |the |give me (my |the )?|start (my |the )?)?(daily |morning |evening )?(briefing|brief|update|summary)$|^good morning( ultron)?$|^what did i miss|^what('s| is) (new|happening)( today)?$", low)) {
            Result r = new Result(Agenda.briefing(a, false));
            r.label = "BRIEFING";
            return r;
        }

        // routines
        if ((x = m("^(?:start |turn on |activate |enable |switch on |go to |begin )?(sleep|sleeping|driving|drive|car|meeting|study|studying|focus)(?: mode| routine)?(?: on)?$", low)) != null
            || (x = m("^i(?:'m| am) (driving|going to sleep|sleeping|in a meeting|studying|going to study)$", low)) != null) {
            String g = x.group(1);
            String n = g.startsWith("driv") || g.equals("car") ? "driving" : g.contains("sleep") ? "sleep" : g.contains("meeting") ? "meeting" : "study";
            return new Result(Routines.start(a, n, "voice"));
        }
        if (has("^(stop|end|turn off|disable|exit|cancel)( the)?( (sleep|driving|meeting|study|focus))?( mode| routine)$|^(sleep|driving|meeting|study|focus) mode off$|^i('m| am) (back|awake|home|free)$", low))
            return new Result(Routines.stop(a, "voice"));
        if ((x = m("^(?:set )?my status (?:to |is |as )(.+)$", low)) != null) {
            a.store.put("status_custom", Memory.clip(x.group(1).trim(), 200));
            a.store.activity("routine", "Status set: " + x.group(1));
            return new Result("Status set. If people message you, I'll tell them: " + x.group(1));
        }
        if (has("^(clear|remove|reset) my status$", low)) {
            a.store.put("status_custom", "");
            return new Result("Status cleared.");
        }

        // calendar
        if ((x = m("^(?:what(?:'s| is) on |show |check |read )?(?:my )?(?:calendar|schedule|agenda|plans|meetings)(?: for)?\\s*(today|tomorrow)?\\??$", low)) != null
            || (x = m("^what (?:do i have|have i got)(?: on| planned)?\\s*(today|tomorrow)?\\??$", low)) != null) {
            boolean tom = "tomorrow".equals(x.group(1));
            java.util.Calendar k = java.util.Calendar.getInstance();
            if (tom) k.add(java.util.Calendar.DAY_OF_MONTH, 1);
            k.set(java.util.Calendar.HOUR_OF_DAY, tom ? 0 : k.get(java.util.Calendar.HOUR_OF_DAY));
            long from = tom ? k.getTimeInMillis() : System.currentTimeMillis();
            k.set(java.util.Calendar.HOUR_OF_DAY, 23);
            k.set(java.util.Calendar.MINUTE, 59);
            if (!a.granted("android.permission.READ_CALENDAR")) {
                a.askPermissions();
                return new Result("Allow ULTRON to read your calendar first, Arsh. I just asked Android for it.");
            }
            return new Result((tom ? "Tomorrow" : "Today") + ": " + Agenda.describeEvents(Agenda.events(a, from, k.getTimeInMillis())) + ".");
        }
        if ((x = m("^(?:add|create|schedule|put|book|set up)(?: a| an)? (?:meeting|event|appointment|calendar event|plan)\\s+(.+)$", low)) != null
            || (x = m("^(?:add|put) (.+?) (?:to|in|on) (?:my )?calendar$", low)) != null) {
            try {
                String now = new java.text.SimpleDateFormat("EEEE yyyy-MM-dd'T'HH:mm", Locale.ENGLISH).format(new java.util.Date());
                String raw = brain.quick("Now is " + now + ". Arsh wants a calendar event: \"" + x.group(1) + "\". Answer ONLY with JSON "
                    + "{\"title\": \"short title\", \"when\": \"yyyy-MM-ddTHH:mm\", \"minutes\": 60}. If no time of day, use 10:00.", "Output only JSON.");
                JSONObject j = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
                long when = Agenda.ISO.parse(j.getString("when")).getTime();
                String title = j.optString("title", x.group(1));
                if (!confirm("Add to calendar?", title + "\n" + Agenda.NICE.format(new java.util.Date(when)))) return new Result("Not added.");
                return new Result(Agenda.addEvent(a, title, when, j.optInt("minutes", 60), "Added by ULTRON"));
            } catch (Exception e) {
                return new Result("I could not work out the date and time, Arsh. Say for example: add meeting with Arslan tomorrow at 5 pm.");
            }
        }

        // email (read and draft; ULTRON never sends emails by itself)
        if (has("\\b(read|check|any|show|summari[sz]e)\\b.*\\b(e-?mails?|mails?|inbox|gmail)\\b|^(new )?e-?mails\\??$", low)) {
            JSONArray m = Agenda.mailsSince(a.store, System.currentTimeMillis() - 24 * 3600000L);
            if (m.length() == 0) return new Result("No new emails in the last 24 hours, Arsh" + (NotifService.connected() ? "." : ". Note: notification access is off."));
            StringBuilder list = new StringBuilder();
            for (int i = Math.max(0, m.length() - 12); i < m.length(); i++)
                list.append(m.optJSONObject(i).optString("from")).append(": ").append(m.optJSONObject(i).optString("text")).append(m.optJSONObject(i).optBoolean("important") ? " (important)" : "").append("\n");
            try {
                return new Result(brain.quick("Arsh's recent emails (from: subject/preview):\n" + list + "\nTell him briefly, in 2 to 5 spoken sentences, "
                    + "who wrote and what matters, important ones first. Do not invent anything.", "You are ULTRON. Be brief."));
            } catch (Exception e) {
                return new Result("You have " + m.length() + " emails. " + list.toString().replace("\n", ". "));
            }
        }
        if ((x = m("^(?:reply|respond|answer)(?: to)?(?: the)? e-?mail from (.+?) (?:saying|with|that) (.+)$", low)) != null) {
            String who = x.group(1), text = x.group(2);
            for (NotifService.Note n : NotifService.notes()) {
                if (!Agenda.isEmail(n.pkg) || !NotifService.exact(n.title).contains(NotifService.exact(who))) continue;
                if (n.reply != null) {
                    if (!confirm("Reply to " + n.title + "'s email?", text)) return new Result("Not sent.");
                    return new Result(NotifService.sendReply(n, text) ? "Reply sent to " + n.title + "." : "The email app did not accept the reply.");
                }
                copy(text);
                try {
                    if (n.open != null) n.open.send();
                } catch (Exception ignored) {
                }
                return new Result("I opened " + n.title + "'s email and copied your reply. Tap Reply, paste, and send, Arsh.");
            }
            return new Result("I don't see an email from " + who + " in your notifications right now, Arsh.");
        }

        // WhatsApp message to a contact (approved, then sent by the screen helper)
        if ((x = m("^(?:send |write )?(?:a )?(?:whatsapp|whats app|wa)(?: message)?(?: to)?\\s+(.+?)\\s+(?:saying|that|with|message)\\s+(.+)$", low)) != null
            || (x = m("^(?:message|text) (.+?) on whatsapp (?:saying|that) (.+)$", low)) != null) {
            return whatsapp(x.group(1), x.group(2));
        }

        // phone + PC
        if (has("^(sync|synchroni[sz]e)( with)?( my)?( the)? (pc|computer|laptop)$|^sync now$", low)) {
            if (!PcLink.paired(a.store)) return new Result("I'm not paired with your PC yet, Arsh. Opening ULTRON+ so you can pair.").nav("plus.html");
            try {
                return new Result(PcLink.sync(a));
            } catch (Exception e) {
                return new Result("I couldn't reach your PC: " + e.getMessage());
            }
        }
        if (has("^(open |show )?(ultron ?plus|ultron\\+|features|routines|briefing settings|pc link)$", low))
            return new Result("Opening ULTRON+.").nav("plus.html");

        // sound modes
        if (has("\\b(silent|vibrate|vibration|normal|ring|ringer|sound) mode\\b|^(silent|vibrate|unmute) (the )?phone$|^(activate|turn on) silent", low)) {
            AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
            boolean normal = has("\\b(normal|ring|ringer|sound|off silent|unmute)\\b", low) && !has("\\bsilent mode on\\b", low);
            boolean vibrate = has("\\bvibrat", low);
            try {
                if (normal) am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                else if (vibrate) am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                else am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                a.store.activity("action", "Sound mode: " + (normal ? "normal" : vibrate ? "vibrate" : "silent"));
                return new Result(normal ? "Sound is back on." : vibrate ? "Vibrate mode on." : "Silent mode on.");
            } catch (SecurityException e) {
                am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                start(new Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS"));
                return new Result("I set vibrate. For full silent, allow Do Not Disturb access for ULTRON on this screen, Arsh.");
            }
        }

        // files
        if ((x = m("^(?:find|open|show|search|where is)(?: me)?(?: my| the)?\\s+(.+?)\\s*(?:file|files|document|documents)?$", low)) != null
            && has("\\b(pdf|file|files|document|doc|photo|image|picture|video|download|downloaded|apk|excel|word|ppt|zip|song)\\b", low)) {
            return findFile(low);
        }

        // email
        if ((x = m("^(?:send|write|draft|compose)(?: an?)? (?:email|mail|e-mail) to (\\S+@\\S+|.+?)(?: (?:saying|about|that) (.+))?$", low)) != null) {
            return email(x.group(1), x.group(2), question);
        }

        // calls
        if (has("^(answer|pick up|pickup|receive|accept)( the| this)?( phone)? call$|^answer( it)?$", low)) return answerCall();
        if (has("^(end|cut|hang up|reject|decline|disconnect)( the| this)?( phone)? call$|^hang up$", low)) return endCall();
        if ((x = m("^(?:call|dial|phone|ring)\\s+(.+)$", low)) != null) return call(x.group(1));

        // SMS
        if ((x = m("^(?:send (?:an? )?(?:sms|text|message)|sms|text)\\s+(?:to\\s+)?(.+?)\\s+(?:saying|that|says|message)\\s+(.+)$", low)) != null)
            return sms(x.group(1), x.group(2));
        if ((x = m("^(?:send (?:an? )?sms|sms|text)\\s+(?:to\\s+)?([+\\d][\\d\\s-]{5,})\\s+(.+)$", low)) != null)
            return sms(x.group(1), x.group(2));
        if (has("\\b(read|check|show|any)\\b.*\\b(sms|text messages)\\b", low)) return readSms();

        // notifications and replies
        if (has("\\b(reply|replied|respond|answer|tell her|tell him|message back|send her|send him|write back)\\b", low)
            && !has("\\bcall\\b", low)) return reply(question, low);
        if (has("\\b(notification|notifications|who (messaged|texted|called) me|any (new )?messages|unread|my messages)\\b", low)
            || has("\\b(check|read)\\b.*\\b(whatsapp|instagram|telegram|snapchat|gmail)\\b", low)) return readNotes(low);

        // music and media
        if (has("^(pause|stop)( the)?( music| song| video)?$|^(resume|play|continue)( the)?( music| song)?$|^play pause$", low))
            return media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Done.");
        if (has("^(next|skip)( song| track| video)?$|^skip (this|the) song$", low)) return media(KeyEvent.KEYCODE_MEDIA_NEXT, "Next track.");
        if (has("^(previous|last|go back a)( song| track)?$", low)) return media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous track.");
        if (has("^(volume up|increase (the )?volume|louder|turn (it )?up( the volume)?)$", low)) return volume(AudioManager.ADJUST_RAISE, "Volume up.");
        if (has("^(volume down|decrease (the )?volume|lower (the )?volume|quieter|turn (it )?down( the volume)?)$", low)) return volume(AudioManager.ADJUST_LOWER, "Volume down.");
        if (has("^(mute|silence)( the)?( phone| volume)?$", low)) return volume(AudioManager.ADJUST_MUTE, "Muted.");
        if (has("^unmute", low)) return volume(AudioManager.ADJUST_UNMUTE, "Unmuted.");
        if ((x = m("^(?:open youtube and |go to youtube and |on youtube )?(?:play|put on|start playing|listen to)\\s+(.+?)(?:\\s+(?:on|in|from) (youtube|spotify|youtube music))?$", low)) != null)
            return play(x.group(1), x.group(2));
        if ((x = m("^(?:search|find|look up)\\s+(?:for\\s+)?(.+?)\\s+(?:on|in)\\s+youtube$", low)) != null
            || (x = m("^youtube\\s+(?:search\\s+)?(.+)$", low)) != null) {
            openApp("https://www.youtube.com/results?search_query=" + enc(x.group(1)), "com.google.android.youtube");
            return new Result("Here are YouTube results for " + x.group(1) + ".");
        }
        if ((x = m("^(?:google|search(?:\\s+google)?(?:\\s+for)?|look up|search the web for|browse for)\\s+(.+?)(?:\\s+on google)?$", low)) != null) {
            view("https://www.google.com/search?q=" + enc(x.group(1)), null);
            return new Result("Searching Google for " + x.group(1) + ".");
        }
        if ((x = m("^(?:navigate to|directions to|take me to|route to)\\s+(.+)$", low)) != null) {
            view("google.navigation:q=" + enc(x.group(1)), "com.google.android.apps.maps");
            return new Result("Starting directions to " + x.group(1) + ".");
        }

        // phone controls
        if (has("\\b(flash ?light|torch)\\b", low)) return torch(!has("\\b(off|stop|close|band)\\b", low));
        if (has("^(what('s| is) (my )?)?battery( level| percentage)?$|^how much battery", low)) {
            int lvl = ((BatteryManager) a.getSystemService(Context.BATTERY_SERVICE)).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return new Result("Your battery is at " + lvl + " percent, Arsh.");
        }
        if (has("^(go )?back$|^go back$", low)) return accessibility(AccessibilityService.GLOBAL_ACTION_BACK, "Going back.");
        if (has("^(go )?home( screen)?$|^go to (the )?home( screen)?$", low)) return accessibility(AccessibilityService.GLOBAL_ACTION_HOME, "Home.");
        if (has("^(show )?recent( apps)?$|^recents$", low)) return accessibility(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps.");
        if (has("^(open |show |pull down )?(the )?notification (panel|shade|bar)$", low)) return accessibility(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications.");
        if (has("^(open )?quick settings$", low)) return accessibility(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings.");
        if (has("^(take (a )?)?screenshot$", low)) return accessibility(9 /* GLOBAL_ACTION_TAKE_SCREENSHOT */, "Screenshot taken.");
        if (has("^scroll( down)?$", low)) return scroll(true);
        if (has("^scroll up$", low)) return scroll(false);
        if ((x = m("^(?:tap|click|press|touch|select)\\s+(?:on\\s+)?(?:the\\s+)?(.+?)(?:\\s+button)?$", low)) != null) return tap(x.group(1));
        if ((x = m("^(?:type|write|enter)\\s+(.+)$", low)) != null && !has("^(type|write) (a|an) ", low)) return typeText(question.replaceAll("(?i)^.*?\\b(type|write|enter)\\s+", ""));
        if (has("^(read|what('s| is)) (the |this )?screen$|^what('s| is) on (the |my )?screen", low)) return readScreen();

        // dual WhatsApp
        if (has("\\b(dual|second|2nd|other|clone|cloned|duplicate)\\b", low) && low.replace(" ", "").contains("whatsapp")) return dualWhatsApp();

        // WhatsApp to a number with a message (opens the chat with the text ready)
        if ((x = m("^(?:whatsapp|message on whatsapp|send whatsapp(?: message)?(?: to)?)\\s+([+\\d][\\d\\s-]{5,})\\s*(.*)$", low)) != null) {
            String n = number(x.group(1)).replace("+", "");
            view("https://wa.me/" + n + (x.group(2).isEmpty() ? "" : "?text=" + enc(x.group(2))), "com.whatsapp");
            return new Result("WhatsApp chat ready. Check it and tap send, Arsh.");
        }

        // open apps
        boolean openish = has("\\b(open|opan|oppen|opn|sopan|upon|oben|hopen|launch|start|show|go to|kholo|khol|chalao|chalu)\\b", low);
        String[] words = low.split("\\s+");
        if (openish || words.length <= 2) {
            Result r = openByName(low, openish);
            if (r != null) return r;
        }
        return null;
    }

    Result sys(String r) {
        Result x = new Result(r);
        x.label = "SYSTEM";
        return x;
    }

    // ================= apps =================
    synchronized Map<String, ResolveInfo> apps() {
        if (apps == null || System.currentTimeMillis() - appsAt > 60000) {
            apps = new HashMap<>();
            PackageManager pm = a.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                String label = String.valueOf(r.loadLabel(pm)).toLowerCase(Locale.ROOT).trim();
                if (!apps.containsKey(label)) apps.put(label, r);
            }
            appsAt = System.currentTimeMillis();
        }
        return apps;
    }

    Result openByName(String low, boolean openish) {
        String t = low.replaceAll("^.*?\\b(open|opan|oppen|opn|sopan|upon|oben|hopen|launch|start|show|go to|kholo|khol|chalao|chalu)\\b\\s*", "")
            .replaceAll("^(the|my|me)\\s+", "").replaceAll("\\s+(app|application|website|site|page)$", "").trim();
        if (!openish) t = low;
        if (t.isEmpty()) return null;
        for (Map.Entry<String, String> e : ALIAS.entrySet()) if (t.equals(e.getKey())) t = e.getValue();
        // special screens
        switch (t) {
            case "camera": start(new Intent("android.media.action.STILL_IMAGE_CAMERA")); return new Result("Opening camera.");
            case "settings": start(new Intent(Settings.ACTION_SETTINGS)); return new Result("Opening settings.");
            case "wifi": case "wi-fi": start(new Intent(Settings.ACTION_WIFI_SETTINGS)); return new Result("Opening Wi-Fi settings.");
            case "bluetooth": start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return new Result("Opening Bluetooth settings.");
            case "phone": start(new Intent(Intent.ACTION_DIAL)); return new Result("Opening your dialer.");
            default:
        }
        Map<String, ResolveInfo> all = apps();
        ResolveInfo hit = all.get(t);
        if (hit == null) {
            for (Map.Entry<String, ResolveInfo> e : all.entrySet()) if (e.getKey().startsWith(t) && t.length() >= 3) { hit = e.getValue(); break; }
        }
        if (hit == null) {
            // the app name may be inside a longer sentence ("open my whatsapp and check messages")
            String bestKey = null;
            for (String k : all.keySet()) {
                if (k.length() >= 3 && Pattern.compile("\\b" + Pattern.quote(k) + "\\b").matcher(low).find()
                    && (bestKey == null || k.length() > bestKey.length())) bestKey = k;
            }
            for (Map.Entry<String, String> e : ALIAS.entrySet()) {
                if (bestKey == null && Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b").matcher(low).find() && all.containsKey(e.getValue()))
                    bestKey = e.getValue();
            }
            if (bestKey != null) hit = all.get(bestKey);
        }
        if (hit == null) {
            return openish ? new Result("I could not find an app called " + t + " on your phone, Arsh.") : null;
        }
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(new ComponentName(hit.activityInfo.packageName, hit.activityInfo.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        start(i);
        String label = String.valueOf(hit.loadLabel(a.getPackageManager()));
        String extra = has("\\b(message|messages|reply|chat)\\b", low) ? " Say read my notifications to hear new messages." : "";
        return new Result("Opening " + label + ", Arsh." + extra);
    }

    Result dualWhatsApp() {
        try {
            UserManager um = (UserManager) a.getSystemService(Context.USER_SERVICE);
            LauncherApps la = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            UserHandle me = android.os.Process.myUserHandle();
            for (UserHandle u : um.getUserProfiles()) {
                if (u.equals(me)) continue;
                List<LauncherActivityInfo> list = la.getActivityList("com.whatsapp", u);
                if (list != null && !list.isEmpty()) {
                    la.startMainActivity(list.get(0).getComponentName(), u, null, null);
                    return new Result("Opening your dual WhatsApp, Arsh.");
                }
            }
        } catch (Exception ignored) {
        }
        // Samsung keeps Dual Messenger separate: ask Android, which shows the "which WhatsApp?" picker
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send"));
            start(Intent.createChooser(i, "Which WhatsApp?"));
            return new Result("Pick the dual WhatsApp, Arsh.");
        } catch (Exception e) {
            return new Result("I could not reach your dual WhatsApp, Arsh.");
        }
    }

    void start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        a.runOnUiThread(new Runnable() { public void run() {
            try {
                a.startActivity(i);
            } catch (Exception e) {
                a.log("COULD NOT OPEN · " + e.getMessage());
            }
        }});
    }

    void view(String url, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (pkg != null && installed(pkg)) i.setPackage(pkg);
        start(i);
    }

    void openApp(String url, String pkg) {
        view(url, pkg);
    }

    boolean installed(String pkg) {
        try {
            a.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ================= music =================
    Result play(String q, String where) {
        if (has("^(music|a song|some music|songs|something)$", q)) q = "trending music songs";
        if (where != null && where.equals("spotify")) {
            view("spotify:search:" + q, "com.spotify.music");
            return new Result("Opening " + q + " on Spotify.");
        }
        final String query = q;
        String url = "https://www.youtube.com/results?search_query=" + enc(query);
        try {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", "Mozilla/5.0");
            h.put("Accept-Language", "en-US,en;q=0.9");
            String html = Brain.http(url, h, null, 10);
            Matcher v = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(html);
            if (v.find()) url = "https://www.youtube.com/watch?v=" + v.group(1);
        } catch (Exception ignored) {
        }
        boolean music = where != null && where.equals("youtube music");
        view(music ? url.replace("www.youtube.com", "music.youtube.com") : url,
            music ? "com.google.android.apps.youtube.music" : "com.google.android.youtube");
        return new Result("Playing " + query + (music ? " on YouTube Music" : " on YouTube") + ", Arsh.");
    }

    Result media(int key, String reply) {
        AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        return new Result(reply);
    }

    Result volume(int dir, String reply) {
        AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        int times = dir == AudioManager.ADJUST_RAISE || dir == AudioManager.ADJUST_LOWER ? 3 : 1;
        for (int i = 0; i < times; i++) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
        return new Result(reply);
    }

    Result torch(boolean on) {
        try {
            CameraManager cm = (CameraManager) a.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    cm.setTorchMode(id, on);
                    return new Result(on ? "Flashlight on." : "Flashlight off.");
                }
            }
        } catch (Exception e) {
            return new Result("I could not switch the flashlight, Arsh.");
        }
        return new Result("This phone has no flashlight I can use.");
    }

    // ================= contacts, calls, SMS =================
    static String number(String text) {
        String d = text.replaceAll("[^\\d+]", "");
        if (d.replace("+", "").length() < 6) return "";
        if (!d.startsWith("+") && d.length() == 10) d = "+91" + d;
        return d;
    }

    /** Returns {name, number} for a spoken name or number, or null. */
    String[] contact(String who) {
        String al = Daily.alias(a.store, who);
        if (!al.isEmpty()) who = al;
        String n = number(who);
        if (!n.isEmpty()) return new String[]{n, n};
        if (!a.granted("android.permission.READ_CONTACTS")) {
            a.askPermissions();
            return null;
        }
        String clean = who.replaceAll("^(my |to )", "").trim();
        String[] best = null;
        try (Cursor c = a.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", new String[]{"%" + clean + "%"}, null)) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(0), num = c.getString(1);
                if (best == null || name.equalsIgnoreCase(clean) || name.length() < best[0].length()) best = new String[]{name, num};
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    void copy(final String text) {
        a.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ULTRON", text));
                } catch (Exception ignored) {
                }
            }
        });
    }

    Result whatsapp(String who, String text) {
        String[] c = contact(who);
        if (c == null) return notFound(who);
        String num = c[1].replaceAll("[^0-9+]", "");
        if (num.startsWith("0")) num = num.substring(1);
        if (!num.startsWith("+")) num = (num.length() == 10 ? "91" : "") + num;
        num = num.replace("+", "");
        if (!confirm("Send WhatsApp to " + c[0] + "?", text)) return new Result("Message cancelled.");
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + num + "?text=" + Uri.encode(text))).setPackage("com.whatsapp")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!TapService.on()) {
            start(open);
            return new Result("WhatsApp is open with your message to " + c[0] + ". Tap send, Arsh.");
        }
        String why = TapService.sendOnScreen(open, "com.whatsapp", text, c[0]);
        if (why == null) {
            AutoReply.rememberSent(a.store, text);
            a.store.activity("sent", "WhatsApp to " + c[0] + " (approved by Arsh): " + text);
            return new Result("Sent to " + c[0] + " on WhatsApp.");
        }
        start(open);
        return new Result("I opened the chat with " + c[0] + " but did not send (" + why + "). Check it and tap send, Arsh.");
    }

    static final Pattern VERB = Pattern.compile("\\b(call|dial|send|text|message|whatsapp|sms|remind|set|open|play|turn|start|stop|add|reply|tell|email|search|navigate|alarm|timer|mode|routine|sync|read|check)\\b");

    /** "send Arslan the address and then call him" -> more than one action joined together. */
    static boolean multiStep(String low) {
        if (!Pattern.compile("\\b(and then|then|after that|and also|also)\\b|,\\s*(and\\s+)?(call|send|set|open|remind|text|message|play|start|add)\\b|\\band (call|send|set|open|remind|text|message|play|start|add|turn)\\b").matcher(low).find())
            return false;
        Matcher v = VERB.matcher(low);
        int n = 0;
        while (v.find()) n++;
        return n >= 2;
    }

    /** Breaks a request into simple ULTRON commands, shows them to Arsh once, then runs them in order. */
    Result plan(String question) {
        JSONArray steps;
        try {
            String raw = brain.quick("Arsh said: \"" + question + "\"\nSplit it into separate simple phone commands, in order, written exactly in these forms "
                + "(fill in names/text/times): 'call NAME' | 'send sms to NAME saying TEXT' | 'send whatsapp to NAME saying TEXT' | 'remind me to TASK at TIME' | "
                + "'set alarm for TIME' | 'set timer for N minutes' | 'open APP' | 'play SONG on youtube' | 'navigate to PLACE' | 'add meeting TITLE on DATE at TIME' | "
                + "'sleep mode' | 'driving mode' | 'meeting mode' | 'study mode' | 'stop routine' | 'silent mode' | 'vibrate mode' | 'turn on flashlight' | "
                + "'search google for X' | 'read my emails' | 'briefing' | 'what is on my calendar today'. Keep message text exactly as Arsh said it, in his "
                + "language, and resolve 'him/her' to the name he used. Answer ONLY with JSON {\"steps\": [\"...\"]}. If it is really one command, return one step.",
                "Output only JSON.");
            steps = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)).getJSONArray("steps");
        } catch (Exception e) {
            return null;
        }
        if (steps.length() < 2) return null;
        if (steps.length() > 8) return new Result("That's more than 8 steps, Arsh. Please split it into smaller requests.");
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < steps.length(); i++) list.append(i + 1).append(". ").append(steps.optString(i)).append("\n");
        if (!a.confirm("Do these " + steps.length() + " steps?", list.toString().trim())) return new Result("Okay, I won't do any of them.");
        StringBuilder out = new StringBuilder();
        inPlan = true;
        preApproved = true;
        a.store.activity("plan", "Multi-step plan approved: " + list.toString().replace("\n", " "));
        try {
            for (int i = 0; i < steps.length(); i++) {
                Result r;
                try {
                    r = handle(steps.optString(i));
                } catch (Exception e) {
                    r = new Result("step failed: " + e.getMessage());
                }
                String said = r == null ? "I couldn't do that one." : r.reply;
                out.append("Step ").append(i + 1).append(": ").append(said).append(" ");
                if (r != null && !r.nav.isEmpty()) a.store.activity("plan", "Step " + (i + 1) + " wanted to open a page; skipped inside a plan");
                try {
                    Thread.sleep(1500);                                   // let each app open before the next step
                } catch (InterruptedException ignored) {
                }
            }
        } finally {
            inPlan = false;
            preApproved = false;
        }
        return new Result(out.toString().trim());
    }

    // ---------------- study quiz ----------------
    Result quizStart(String topic) {
        try {
            String raw = brain.quick("Make a 5-question multiple-choice quiz for a student on: " + topic + ". Mix easy and medium questions. "
                + "Answer ONLY with JSON {\"qs\": [{\"q\": \"question\", \"o\": [\"A\", \"B\", \"C\", \"D\"], \"a\": 0, \"why\": \"one-line explanation\"}]} "
                + "where a is the index (0-3) of the correct option. Facts must be correct.", "Output only JSON.");
            JSONArray qs = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)).getJSONArray("qs");
            if (qs.length() == 0) throw new Exception("empty");
            quiz = new JSONObject().put("topic", topic).put("qs", qs).put("i", 0).put("score", 0);
            Result r = new Result("Quiz on " + topic + ", " + qs.length() + " questions. Answer A, B, C or D. " + askQ());
            r.label = "QUIZ";
            return r;
        } catch (Exception e) {
            quiz = null;
            return new Result("I couldn't make a quiz on that right now, Arsh.");
        }
    }

    String askQ() {
        JSONObject q = quiz.optJSONArray("qs").optJSONObject(quiz.optInt("i"));
        JSONArray o = q.optJSONArray("o");
        StringBuilder b = new StringBuilder("Question " + (quiz.optInt("i") + 1) + ": " + q.optString("q"));
        for (int k = 0; k < o.length(); k++) b.append(" ").append((char) ('A' + k)).append(") ").append(o.optString(k)).append(".");
        return b.toString();
    }

    Result quizAnswer(String low) {
        try {
            JSONArray qs = quiz.getJSONArray("qs");
            JSONObject q = qs.getJSONObject(quiz.optInt("i"));
            JSONArray o = q.getJSONArray("o");
            int pick = -1;
            Matcher x = Pattern.compile("^(?:option |answer |it'?s |the answer is )?\\(?([abcd1-4])\\)?\\b").matcher(low.replaceAll("^(a|ay|hey)$", "a").replaceAll("^(bee|be)$", "b").replaceAll("^(see|sea)$", "c").replaceAll("^(dee)$", "d"));
            if (x.find()) {
                char ch = x.group(1).charAt(0);
                pick = ch >= 'a' ? ch - 'a' : ch - '1';
            }
            for (int k = 0; pick < 0 && k < o.length(); k++) if (low.contains(o.optString(k).toLowerCase(Locale.ROOT)) && o.optString(k).length() > 1) pick = k;
            if (pick < 0) return new Result("Say A, B, C or D, Arsh. " + askQ());
            int right = q.optInt("a");
            boolean ok = pick == right;
            if (ok) quiz.put("score", quiz.optInt("score") + 1);
            String say = (ok ? "Correct! " : "Not quite. The answer is " + (char) ('A' + right) + ", " + o.optString(right) + ". ") + q.optString("why") + " ";
            quiz.put("i", quiz.optInt("i") + 1);
            if (quiz.optInt("i") >= qs.length()) {
                int sc = quiz.optInt("score");
                Smart.push(a.store, "quiz_log", new JSONObject().put("topic", quiz.optString("topic")).put("score", sc).put("of", qs.length()).put("at", System.currentTimeMillis()), 100);
                a.store.activity("study", "Quiz on " + quiz.optString("topic") + ": " + sc + "/" + qs.length());
                quiz = null;
                Result r = new Result(say + "Quiz done! You scored " + sc + " out of " + qs.length() + (sc == qs.length() ? ". Perfect, Arsh!" : sc >= 3 ? ". Well done." : ". Let's revise this topic again soon."));
                r.label = "QUIZ";
                return r;
            }
            Result r = new Result(say + askQ());
            r.label = "QUIZ";
            return r;
        } catch (Exception e) {
            quiz = null;
            return new Result("The quiz got mixed up, Arsh. Say quiz me on a topic to start again.");
        }
    }

    // ---------------- screenshots ----------------
    Result screenshot(String question) {
        if (!a.granted("android.permission.READ_MEDIA_IMAGES") && !a.granted("android.permission.READ_EXTERNAL_STORAGE")) {
            a.askPermissions();
            return new Result("Allow ULTRON to see your photos first, Arsh. I just asked Android for it.");
        }
        try {
            Cursor c = a.getContentResolver().query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DATA, "relative_path"},
                "(relative_path LIKE ? OR _data LIKE ?)", new String[]{"%Screenshot%", "%Screenshot%"},
                android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC");
            long id = -1;
            if (c != null) {
                try {
                    if (c.moveToFirst()) id = c.getLong(0);
                } finally {
                    c.close();
                }
            }
            if (id < 0) return new Result("I couldn't find a screenshot on your phone, Arsh.");
            Uri u = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            android.graphics.BitmapFactory.Options op = new android.graphics.BitmapFactory.Options();
            op.inSampleSize = 2;
            java.io.InputStream in = a.getContentResolver().openInputStream(u);
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in, null, op);
            in.close();
            float k = Math.min(1f, 1280f / Math.max(b.getWidth(), b.getHeight()));
            b = android.graphics.Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)), Math.max(1, (int) (b.getHeight() * k)), true);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, o);
            String ans = brain.see(o.toByteArray(), "Arsh asks about his latest screenshot: \"" + question + "\". Read the screenshot carefully and answer his "
                + "question in 1 to 5 spoken sentences. If he just wants it read, summarise what it shows and any important numbers, dates or names. "
                + "Never read out passwords, OTPs or card numbers.");
            Result r = new Result(AutoReply.clean(ans));
            r.label = "VISION";
            return r;
        } catch (Exception e) {
            return new Result("I couldn't read the screenshot: " + e.getMessage() + ". It needs a Gemini, Claude or ChatGPT key.");
        }
    }

    static String names(JSONArray a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length(); i++) b.append(i > 0 ? ", " : "").append(a.optString(i));
        return b.toString();
    }

    Result notFound(String who) {
        if (Daily.isRelation(who))
            return new Result("Who is your " + Daily.rel(who) + ", Arsh? Tell me once, for example: my " + Daily.rel(who) + " is Ayesha Khan (the name in your contacts).");
        return new Result("I could not find " + who + " in your contacts, Arsh.");
    }

    /** Sends on WhatsApp if Arsh chats with this person there, otherwise by SMS. */
    Result smartSend(String who, String text) {
        String[] c = contact(who);
        if (c == null) return notFound(who);
        JSONObject p = Smart.person(a.store, c[0]);
        if (p.optJSONArray("apps") == null) p = Smart.person(a.store, who);
        JSONArray apps = p.optJSONArray("apps");
        boolean wa = false;
        for (int i = 0; apps != null && i < apps.length(); i++) if (apps.optString(i).toLowerCase(Locale.ROOT).contains("whatsapp")) wa = true;
        return wa ? whatsapp(c[0], text) : sms(c[0], text);
    }

    static final Pattern REL_WORDS = Pattern.compile("(mummy|mum|mom|mother|ammi|maa|papa|dad|father|abbu|pappa|bhai|brother|bro|sister|sis|didi|behen|wife|husband|bestie|best friend|boss|nana|nani|dada|dadi|chacha|chachi|mama|mami|uncle|aunty|aunt|cousin|girlfriend|boyfriend|son|daughter)");

    Result daily(String question, String low) {
        Matcher x;
        // relationship names
        if ((x = m("^(?:my )?" + REL_WORDS.pattern() + " is (?:named |called )?(.+)$", low)) != null || (x = m("^(.+?) is my " + REL_WORDS.pattern() + "$", low)) != null) {
            boolean relFirst = REL_WORDS.matcher(x.group(1)).matches();
            String relw = relFirst ? x.group(1) : x.group(2), name = relFirst ? x.group(2) : x.group(1);
            String[] c = contact(name);
            if (c == null) return new Result("I couldn't find " + name + " in your contacts, Arsh. Say the name exactly as it is saved.");
            return new Result(Daily.setAlias(a.store, relw, c[0]));
        }
        // call back / call history / decline with a text
        if (has("^(call back|callback|return)( the| my)?( last)?( missed)?( call)?$|^call (back )?(the |my )?last (missed )?(caller|call)( back)?$", low)) {
            JSONArray cl = Calls.log(a.store);
            for (int i = cl.length() - 1; i >= 0; i--) {
                JSONObject e = cl.optJSONObject(i);
                if (!("missed".equals(e.optString("status")) || "declined".equals(e.optString("status")))) continue;
                if (!e.optString("number").isEmpty()) return call(e.optString("number"));
                return new Result("Your last missed call was from " + e.optString("who") + " on " + e.optString("app") + ". Open " + e.optString("app") + " to call back, Arsh.");
            }
            return new Result("I don't see any missed calls, Arsh.");
        }
        if (has("^(who|kisne) (called|call kiya)( me)?( today)?|^(my |show |any )?missed calls( today)?$|^call (history|log)$", low)) {
            JSONArray cl = Calls.log(a.store);
            List<String> l = new ArrayList<>();
            long since = System.currentTimeMillis() - 24 * 3600000L;
            for (int i = cl.length() - 1; i >= 0 && l.size() < 10; i--) {
                JSONObject e = cl.optJSONObject(i);
                if (e.optLong("at") < since || "ringing".equals(e.optString("status"))) continue;
                l.add(e.optString("who") + " (" + e.optString("app") + ", " + e.optString("status") + ", "
                    + new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date(e.optLong("at"))) + ")");
            }
            return new Result(l.isEmpty() ? "No calls in the last 24 hours, Arsh." : "Calls in the last 24 hours: " + android.text.TextUtils.join("; ", l) + ".");
        }
        if (has("^(i'?m busy,? )?(decline|reject|cut)( the| this)?( call)? and (text|message|sms)|^busy,? send sorry$|^send sorry$|^(i'?m )?busy,? (text|message) (them|him|her)$", low))
            return new Result(Calls.declineAndText(a));

        // lists
        if ((x = m("^(?:add|put)\\s+(.+?)\\s+(?:to|in|on)\\s+(?:my |the )?([a-z ]*?)\\s*list$", low)) != null)
            return new Result(Daily.addItems(a.store, Daily.listName(x.group(2)), x.group(1)));
        if ((x = m("^(?:remove|delete|cross off|tick off)\\s+(.+?)\\s+from\\s+(?:my |the )?([a-z ]*?)\\s*list$", low)) != null)
            return new Result(Daily.removeItem(a.store, Daily.listName(x.group(2)), x.group(1)));
        if ((x = m("^(?:what(?:'s| is) (?:on|in) |read |show |check )(?:my |the )?([a-z ]*?)\\s*list\\??$", low)) != null)
            return new Result(Daily.readList(a.store, Daily.listName(x.group(1))));
        if ((x = m("^(?:clear|empty|delete)\\s+(?:my |the )?([a-z ]*?)\\s*list$", low)) != null) {
            if (!confirm("Clear the " + Daily.listName(x.group(1)) + " list?", Daily.readList(a.store, Daily.listName(x.group(1))))) return new Result("Kept it.");
            return new Result(Daily.clearList(a.store, Daily.listName(x.group(1))));
        }
        if ((x = m("^(?:send|share)\\s+(?:my |the )?([a-z ]*?)\\s*list\\s+(?:to|with)\\s+(.+)$", low)) != null)
            return smartSend(x.group(2), Daily.listText(a.store, Daily.listName(x.group(1))));

        // notes
        if ((x = m("^(?:note|take a note|make a note|write down|note down|jot down)[:,]?\\s+(?:that\\s+)?(.+)$", low)) != null)
            return new Result(Daily.note(a.store, question.replaceAll("(?i)^.*?(note|write down|note down|jot down)[:,]?\\s+(that\\s+)?", "")));
        if (has("^(read|show|what are) my notes$|^my notes$", low)) return new Result(Daily.readNotes(a.store));
        if (has("^where did i (park|keep|put|leave)|^what did i (note|write)|^(check|search) my notes", low)) return new Result(Daily.askNotes(brain, a.store, question));

        // contacts by voice
        if ((x = m("^(?:what(?:'s| is)|tell me|give me)\\s+(.+?)(?:'s| ka| ki)? (?:phone |mobile |contact )?number\\??$", low)) != null) {
            String[] c = contact(x.group(1));
            if (c == null) return notFound(x.group(1));
            return new Result(c[0] + "'s number is " + c[1] + ".");
        }
        if ((x = m("^(?:send|share)\\s+(.+?)(?:'s)? (?:number|contact)\\s+(?:to|with)\\s+(.+)$", low)) != null) {
            String[] c = contact(x.group(1));
            if (c == null) return notFound(x.group(1));
            return smartSend(x.group(2), c[0] + ": " + c[1]);
        }
        if ((x = m("^save\\s+(this number|that number|the last (?:caller|number)|[+\\d][\\d\\s-]{6,})\\s+as\\s+(.+)$", low)) != null) {
            String num = x.group(1).matches("[+\\d][\\d\\s-]+") ? x.group(1).replaceAll("[\\s-]", "") : "";
            if (num.isEmpty()) {
                JSONArray cl = Calls.log(a.store);
                for (int i = cl.length() - 1; i >= 0 && num.isEmpty(); i--) num = cl.optJSONObject(i).optString("number");
            }
            if (num.isEmpty()) return new Result("Which number should I save, Arsh? Say: save 98765 43210 as Rahul.");
            String name = x.group(2).trim();
            name = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
            if (!confirm("Save contact?", name + "\n" + num)) return new Result("Not saved.");
            return new Result(Daily.saveContact(a, name, num));
        }

        // location
        if ((x = m("^(?:send|share)\\s+(?:my )?(?:current |live )?location\\s+(?:to|with)\\s+(.+)$", low)) != null) {
            if (!a.granted("android.permission.ACCESS_FINE_LOCATION") && !a.granted("android.permission.ACCESS_COARSE_LOCATION")) {
                a.askPermissions();
                return new Result("Allow ULTRON to use your location first, Arsh. I just asked Android for it.");
            }
            String link = Daily.locationLink(a);
            if (link.isEmpty()) return new Result("I couldn't get your location. Turn on Location in quick settings and try again, Arsh.");
            return smartSend(x.group(1), "📍 My location: " + link);
        }

        // chat summary
        if ((x = m("^(?:summari[sz]e|summary of|sum up)\\s+(?:my |the )?(?:chat|conversation|messages|group)\\s+(?:with|from|in|of)\\s+(.+?)(\\s+today|\\s+yesterday|\\s+this week)?$", low)) != null) {
            String who = NotifService.exact(x.group(1));
            long since = System.currentTimeMillis() - (" this week".equals(x.group(2)) ? 7 * 86400000L : " yesterday".equals(x.group(2)) ? 2 * 86400000L : 86400000L);
            JSONArray ar = Smart.list(a.store, "chat_archive");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < ar.length(); i++) {
                JSONObject m = ar.optJSONObject(i);
                if (m.optLong("at") >= since && NotifService.exact(m.optString("w")).contains(who)) b.append(m.optString("f")).append(": ").append(m.optString("t")).append("\n");
            }
            if (b.length() == 0) return new Result("I don't have messages from " + x.group(1) + " in that time, Arsh.");
            try {
                return new Result(AutoReply.clean(brain.quick("Summarise this chat for Arsh in 2 to 5 spoken sentences: what was discussed, any plans, "
                    + "questions for him and anything he must do. Use only the messages.\n\n" + b, "You are ULTRON. Be brief and exact.")));
            } catch (Exception e) {
                return new Result("I couldn't summarise right now.");
            }
        }

        // group messages
        if ((x = m("^(?:create|make) (?:a )?group (?:called |named )?([a-z ]+?) with (.+)$", low)) != null) {
            JSONArray g = new JSONArray();
            for (String n : x.group(2).split("\\s*(?:,|\\band\\b|&)\\s*")) {
                String[] c = contact(n);
                if (c != null) g.put(c[0]);
            }
            a.store.put("grp:" + x.group(1).trim(), g.toString());
            return new Result("Group " + x.group(1).trim() + " has " + g.length() + " people: " + names(g) + ".");
        }
        if ((x = m("^(?:tell|message|text|send(?: a message)? to|inform)\\s+(?:all|everyone in|everybody in|the whole)\\s+(?:my |the )?(family|close friends?|friends|important|professional|business|[a-z]+)(?: group| contacts| people)?\\s*(?:that|saying|:|,)?\\s+(.+)$", low)) != null) {
            String g = x.group(1).startsWith("close") ? "close" : x.group(1).trim();
            JSONArray names;
            try { names = new JSONArray(a.store.get("grp:" + g, "[]")); } catch (Exception e) { names = new JSONArray(); }
            if (names.length() == 0) return new Result("You have nobody in " + g + " yet, Arsh. Say: mark Arslan as " + g + ", or: create group " + g + " with A, B and C.");
            if (!a.confirm("Send to " + names.length() + " people in " + g + "?", names(names) + "\n\n" + x.group(2))) return new Result("Not sent.");
            preApproved = true;
            StringBuilder out = new StringBuilder();
            try {
                for (int i = 0; i < names.length(); i++) {
                    Result r = smartSend(names.optString(i), x.group(2));
                    out.append(names.optString(i)).append(": ").append(r.reply).append(" ");
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                }
            } finally {
                preApproved = false;
            }
            return new Result(out.toString().trim());
        }

        // scheduled messages ("send happy birthday to Arslan at 12 midnight")
        if (has("^(send|text|message|whatsapp|wish|tell)\\b", low) && has("\\b(at \\d|at midnight|at noon|midnight|tomorrow|tonight|in \\d+ (min|minute|hour)s?|on (mon|tue|wed|thu|fri|sat|sun))", low)) {
            try {
                String now = new java.text.SimpleDateFormat("EEEE yyyy-MM-dd'T'HH:mm", Locale.ENGLISH).format(new java.util.Date());
                String raw = brain.quick("Now is " + now + ". Arsh said: \"" + question + "\". He wants a message sent LATER. Answer ONLY with JSON "
                    + "{\"who\": \"person\", \"text\": \"the exact message to send, in his words and language\", \"when\": \"yyyy-MM-ddTHH:mm\", "
                    + "\"app\": \"whatsapp|sms|auto\"}. 12 midnight means 00:00 of the next day. If it is not a later message, answer {\"who\": \"\"}.", "Output only JSON.");
                JSONObject j = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
                if (!j.optString("who").isEmpty() && !j.optString("text").isEmpty()) {
                    long when = Agenda.ISO.parse(j.getString("when")).getTime();
                    if (when > System.currentTimeMillis() + 60000) {
                        String[] c = contact(j.getString("who"));
                        if (c == null) return notFound(j.getString("who"));
                        String app = j.optString("app", "auto");
                        if (app.equals("auto")) {
                            JSONArray apps = Smart.person(a.store, c[0]).optJSONArray("apps");
                            app = apps != null && apps.toString().toLowerCase(Locale.ROOT).contains("whatsapp") ? "whatsapp" : "sms";
                        }
                        if (!confirm("Send later to " + c[0] + " (" + (app.equals("whatsapp") ? "WhatsApp" : "SMS") + ")?",
                            Agenda.NICE.format(new java.util.Date(when)) + "\n\n" + j.getString("text"))) return new Result("Not scheduled.");
                        return new Result(Daily.schedule(a, c[0], c[1], app, j.getString("text"), when));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // tell X that Y -> WhatsApp or SMS, whichever Arsh uses with them
        if ((x = m("^(?:tell|message|msg|inform|let)\\s+(.+?)\\s+(?:know\\s+)?(?:that|saying|ki|:)\\s+(.+)$", low)) != null && !has("\\b(on whatsapp|on sms|by sms|on instagram)\\b", low))
            return smartSend(x.group(1), question.replaceAll("(?i)^.*?\\s(?:that|saying|ki|:)\\s+", ""));
        // second WhatsApp
        if ((x = m("^(?:send |write )?(?:a )?(?:whatsapp|message)(?: message)? (?:to )?(.+?) (?:from|on|using) (?:my )?(?:second|dual|other|clone|2nd) whatsapp (?:saying|that) (.+)$", low)) != null) {
            String[] c = contact(x.group(1));
            if (c == null) return notFound(x.group(1));
            if (!confirm("Send from your second WhatsApp to " + c[0] + "?", x.group(2))) return new Result("Not sent.");
            start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + Daily.waNumber(c[1]) + "?text=" + Uri.encode(x.group(2)))));
            return new Result("Choose the WhatsApp with the dual badge, Arsh. Your message to " + c[0] + " is already typed: just tap send.");
        }

        // news, cricket, money helpers
        if ((x = m("^(?:the |today'?s |latest |top )?(?:news|headlines)(?: today)?(?: (?:about|on|for) (.+))?$|^what(?:'s| is) the (?:latest )?news(?: (?:about|on) (.+))?\\??$", low)) != null) {
            String topic = x.group(1) != null ? x.group(1) : x.group(2);
            try {
                List<String> h = Daily.headlines(topic, 6);
                if (h.isEmpty()) return new Result("I couldn't find news on that right now.");
                return new Result(AutoReply.clean(brain.quick("Headlines" + (topic == null ? "" : " about " + topic) + ":\n- " + android.text.TextUtils.join("\n- ", h)
                    + "\nTell Arsh the top news in 3 to 5 short spoken sentences, using only these headlines.", "You are ULTRON, a news reader.")));
            } catch (Exception e) {
                return new Result("I couldn't reach the news right now, Arsh.");
            }
        }
        if (has("\\b(cricket|ipl|match score|live score|test match|odi|t20)\\b", low) && !has("\\b(play|youtube|watch)\\b", low)) return new Result(Daily.cricket());
        if ((x = m("^(?:convert |how much is |what is )?([\\d,.]+)\\s*([a-z ]+?)\\s+(?:to|in|into)\\s+([a-z ]+?)\\??$", low)) != null && !Daily.code(x.group(2)).isEmpty() && !Daily.code(x.group(3)).isEmpty())
            return new Result(Daily.convert(Double.parseDouble(x.group(1).replace(",", "")), x.group(2), x.group(3)));
        if ((x = m("^(?:what(?:'s| is) the |today'?s )?(dollar|euro|pound|dirham|riyal|yen|dinar)(?: rate| price| to rupee| in rupees)(?: today)?\\??$", low)) != null)
            return new Result(Daily.convert(1, x.group(1), "rupee"));
        if ((x = m("^split\\s+(?:rs\\.?|₹|inr)?\\s*([\\d,.]+)\\s*(?:rupees|rs)?\\s+(?:between|among|by|in)\\s+(\\d+)(?: people| persons| friends)?$", low)) != null) {
            double amt = Double.parseDouble(x.group(1).replace(",", ""));
            int n = Math.max(1, Integer.parseInt(x.group(2)));
            return new Result(String.format(Locale.ENGLISH, "₹%,.0f split between %d is ₹%,.2f each.", amt, n, amt / n));
        }

        // wake-up routine
        if ((x = m("^wake me(?: up)?(?: at| by)?\\s+(.+?)(\\s+every ?day|\\s+daily)?(?:\\s+with (?:the |my )?(?:briefing|news|weather))?$", low)) != null) {
            long when = Agenda.parseWhen(a.store, x.group(1).matches(".*\\d.*") && !x.group(1).contains("tomorrow") ? "at " + x.group(1) : x.group(1));
            if (when < 0) return new Result("What time should I wake you, Arsh?");
            String hhmm = new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(new java.util.Date(when));
            a.store.put("wake_time", hhmm);
            a.store.put("wake_daily", x.group(2) != null ? "1" : "0");
            Daily.scheduleWake(a);
            return new Result("I'll wake you at " + new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date(when))
                + (x.group(2) != null ? " every day" : "") + " with an alarm, then your weather and briefing. Tap the alarm to hear it.");
        }
        if (has("^(cancel|stop|turn off|remove)( my| the)? wake ?up( alarm| routine)?$", low)) {
            a.store.put("wake_time", "");
            Daily.scheduleWake(a);
            return new Result("Wake-up alarm cancelled.");
        }
        return null;
    }

    static final Pattern AGENT_APPS = Pattern.compile("(uber|ola|rapido|swiggy|zomato|blinkit|zepto|instamart|bigbasket|amazon|flipkart|myntra|meesho|nykaa|ajio|bookmyshow|makemytrip|irctc|redbus|paytm|phonepe|google pay|jiomart|dominos|domino's)");

    Result advanced(String question, String low) {
        Matcher x;
        // camera vision
        if ((x = m("^(what is this|what's this|whats this|look at this|scan this|read this|solve this|what am i looking at|identify this|translate this|check this|what does this say|kya hai ye|ye kya hai)\\b(.*)$", low)) != null) {
            if (!a.granted("android.permission.CAMERA")) {
                a.askPermissions();
                return new Result("Allow ULTRON to use the camera first, Arsh. I just asked Android for it.");
            }
            byte[] img = a.capturePhoto();
            if (img == null) return new Result("No photo taken, Arsh.");
            try {
                Result r = new Result(AutoReply.clean(brain.see(img, "Arsh pointed his camera and asks: \"" + question + "\". Answer helpfully in 1 to 5 spoken sentences: "
                    + "identify it, read any text, solve it step by step if it's a question, translate if asked. If unsure, say so. Never read out card numbers or codes.")));
                r.label = "VISION";
                return r;
            } catch (Exception e) {
                return new Result("I couldn't look at it: " + e.getMessage() + ". Vision needs a Gemini, Claude or ChatGPT key.");
            }
        }
        // day planner
        if (has("^(plan my day|make (a |my )?plan for today|what('s| is) my plan( for today)?|organi[sz]e my day)$", low)) return new Result(Advanced.planDay(a, false));
        if (has("^what should i do (next|now)\\??$|^what('s| is) next\\??$|^what now\\??$", low)) return new Result(Advanced.planDay(a, true));
        // life timeline
        if (has("^what did i do\\b|^where (was|did) i (go|been)?.*\\b(yesterday|today|last|on)\\b|^(tell me about|how was) my (day|yesterday|last \\w+)|^my day (yesterday|on)", low)) {
            Result r = new Result(Advanced.timeline(a, brain, question));
            r.label = "TIMELINE";
            return r;
        }
        // study brain
        if (has("\\b(from|in|using) my (notes|pdf|pdfs|book|books|study material|documents?)\\b|^explain chapter \\d+|^(make|create|give me) \\d* ?questions\\b.*\\b(notes|chapter|pdf)", low)) {
            if (has("^quiz me\\b", low)) {
                String ctx = Advanced.relevant(a.store, question, 3000);
                if (ctx.isEmpty()) return new Result("Your study brain is empty, Arsh. Add notes in ULTRON+.");
                return quizStart("this study material (ask only about it):\n" + ctx);
            }
            Result r = new Result(Advanced.askDocs(a, brain, question));
            r.label = "STUDY";
            return r;
        }
        if (has("^(add|upload|import) (my )?(notes|pdf|book|study material|document)", low)) return new Result("Opening ULTRON+, tap ADD NOTES OR PDF.").nav("plus.html");
        // files + clipboard with the PC
        if ((x = m("^send (?:my |the )?(?:last |latest |recent )?(photo|picture|pic|screenshot|download|downloaded file|file) to (?:my |the )?(?:pc|computer|laptop)$", low)) != null) {
            String kind = x.group(1).startsWith("screen") ? "screenshot" : x.group(1).startsWith("down") || x.group(1).equals("file") ? "download" : "photo";
            Object[] f = Advanced.latest(a, kind);
            if (f == null) return new Result("I couldn't find a recent " + kind + ", Arsh.");
            return new Result(Advanced.sendToPc(a, (Uri) f[0], (String) f[1]));
        }
        if (has("^(send|copy|share) (my |the |this )?clipboard to (my |the )?(pc|computer|laptop)$|^copy (this|that) to (my )?(pc|computer)$", low)) {
            final String[] clip = {""};
            final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
            a.runOnUiThread(new Runnable() { public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm.hasPrimaryClip()) clip[0] = String.valueOf(cm.getPrimaryClip().getItemAt(0).coerceToText(a));
                } catch (Exception ignored) {
                }
                l.countDown();
            }});
            try { l.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return new Result(Advanced.clipToPc(a, clip[0]));
        }
        // app agent
        Matcher ag = AGENT_APPS.matcher(low);
        if ((has("^(book|order|buy|get me|find|search|add|check|track|reorder)\\b", low) && ag.find()) || (x = m("^(?:agent|use the app|do it in the app)[:,]?\\s+(.+)$", low)) != null) {
            String appName = ag.find(0) ? ag.group(1) : "";
            String pkg = appName.isEmpty() ? null : Agent.findApp(a, appName);
            if (!appName.isEmpty() && pkg == null) return new Result(appName + " isn't installed on your phone, Arsh.");
            if (!TapService.on()) return new Result("Turn on ULTRON's screen helper first: Settings › Accessibility › ULTRON.");
            if (!a.confirm("Let ULTRON use " + (appName.isEmpty() ? "your apps" : appName) + "?", question + "\n\nULTRON will tap through the app for you and STOP before anything that pays, orders, books or sends. Say STOP or tap STOP in the notification to end it."))
                return new Result("Okay, I won't.");
            Result r = new Result(Agent.run(a, brain, question, pkg));
            r.label = "AGENT";
            return r;
        }
        return null;
    }

    Result call(String who) {
        String[] c = contact(who);
        if (c == null) return notFound(who);
        if (!confirm("Call " + c[0] + "?", c[1])) return new Result("Call cancelled.");
        if (!a.granted("android.permission.CALL_PHONE")) {
            start(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c[1])));
            return new Result("Tap the call button to call " + c[0] + ".");
        }
        start(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + c[1])));
        return new Result("Calling " + c[0] + ".");
    }

    Result answerCall() {
        try {
            TelecomManager tm = (TelecomManager) a.getSystemService(Context.TELECOM_SERVICE);
            TelecomManager.class.getMethod("acceptRingingCall").invoke(tm);
            return new Result("Answering.");
        } catch (Exception e) {
            a.askPermissions();
            return new Result("I could not answer the call. Allow phone permission for me in setup, Arsh.");
        }
    }

    Result endCall() {
        try {
            TelecomManager tm = (TelecomManager) a.getSystemService(Context.TELECOM_SERVICE);
            Object ok = TelecomManager.class.getMethod("endCall").invoke(tm);
            return new Result(Boolean.TRUE.equals(ok) ? "Call ended." : "There is no call to end.");
        } catch (Exception e) {
            a.askPermissions();
            return new Result("I could not end the call. Allow phone permission for me in setup, Arsh.");
        }
    }

    Result sms(String who, String text) {
        String[] c = contact(who);
        if (c == null) return notFound(who);
        if (!confirm("Send SMS to " + c[0] + "?", text)) return new Result("Message cancelled.");
        if (!a.granted("android.permission.SEND_SMS")) {
            start(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + c[1])).putExtra("sms_body", text));
            return new Result("Tap send to message " + c[0] + ".");
        }
        try {
            SmsManager sm = SmsManager.getDefault();
            sm.sendMultipartTextMessage(c[1], null, sm.divideMessage(text), null, null);
            return new Result("Message sent to " + c[0] + ".");
        } catch (Exception e) {
            return new Result("The SMS did not send, Arsh.");
        }
    }

    Result readSms() {
        if (!a.granted("android.permission.READ_SMS")) {
            a.askPermissions();
            return new Result("Allow SMS permission for me first, Arsh, then ask again.");
        }
        StringBuilder s = new StringBuilder();
        int n = 0;
        try (Cursor c = a.getContentResolver().query(Uri.parse("content://sms/inbox"), new String[]{"address", "body"}, null, null, "date DESC")) {
            while (c != null && c.moveToNext() && n < 5) {
                String from = c.getString(0);
                String[] who = contactName(from);
                s.append("From ").append(who != null ? who[0] : from).append(": ").append(c.getString(1)).append("\n");
                n++;
            }
        } catch (Exception e) {
            return new Result("I could not read your messages, Arsh.");
        }
        if (n == 0) return new Result("You have no text messages, Arsh.");
        return new Result(summarise(s.toString(), "text messages"));
    }

    String[] contactName(String number) {
        try (Cursor c = a.getContentResolver().query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return new String[]{c.getString(0)};
        } catch (Exception ignored) {
        }
        return null;
    }

    // ================= notifications =================
    Result readNotes(String low) {
        if (!NotifService.connected()) return new Result("I need notification access first, Arsh. Say: open setup.");
        List<NotifService.Note> notes = NotifService.notes();
        lastNotes = notes;
        Matcher w = Pattern.compile("\\b(whatsapp|instagram|telegram|snapchat|gmail|email|facebook|linkedin|messages|youtube)\\b").matcher(low);
        List<NotifService.Note> pick = new ArrayList<>();
        String want = w.find() ? (w.group(1).equals("email") ? "gmail" : w.group(1)) : null;
        for (NotifService.Note n : notes) if (want == null || n.app.toLowerCase(Locale.ROOT).contains(want)) pick.add(n);
        if (pick.isEmpty()) return new Result("You have no new " + (want == null ? "" : want + " ") + "notifications, Arsh.");
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(12, pick.size()); i++) {
            NotifService.Note n = pick.get(i);
            s.append(n.app).append(" - ").append(n.title).append(": ").append(n.text).append("\n");
        }
        return new Result(summarise(s.toString(), "notifications"));
    }

    String summarise(String list, String kind) {
        try {
            return brain.quick("Here are Arsh's phone " + kind + ":\n" + list
                    + "\nTell him briefly, in 2 to 5 spoken sentences, who wrote and what they want. Group by app. Do not invent anything.",
                "You are ULTRON, Arsh's assistant. Plain spoken English, no symbols.");
        } catch (Exception e) {
            String[] lines = list.split("\n");
            StringBuilder s = new StringBuilder("Here are your " + kind + ". ");
            for (int i = 0; i < Math.min(5, lines.length); i++) s.append(lines[i]).append(". ");
            return s.toString();
        }
    }

    Result reply(String question, String low) {
        if (!NotifService.connected()) return new Result("I need notification access to reply, Arsh. Say: open setup.");
        List<NotifService.Note> notes = NotifService.notes();
        if (!notes.isEmpty()) lastNotes = notes;
        StringBuilder list = new StringBuilder();
        for (NotifService.Note n : lastNotes) list.append(n.app).append(" | ").append(n.title).append(" | ").append(n.text).append("\n");
        String who, msg, app;
        try {
            String raw = brain.quick("Arsh said (speech to text, may have mistakes): " + question + "\n\nRecent phone notifications (app | chat | text):\n"
                    + (list.length() == 0 ? "none" : list) + "\nWork out who he wants to reply to (use the chat name exactly as in the list), "
                    + "which app, and the exact reply text he wants to send, written in first person as Arsh, cleaned up, in the same language he used. "
                    + "Do not add anything he did not say. Answer ONLY with JSON: {\"who\": \"chat name\", \"app\": \"WhatsApp\", \"message\": \"...\"}. "
                    + "If there is no reply text, use an empty message.",
                "You output only JSON.");
            JSONObject j = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
            who = j.optString("who").trim();
            msg = j.optString("message").trim();
            app = j.optString("app", "WhatsApp").trim();
        } catch (Exception e) {
            return new Result("I could not understand the reply, Arsh. Say: reply to Farooqi, yes I had my lunch.");
        }
        if (msg.isEmpty()) return new Result("What should I reply to " + (who.isEmpty() ? "them" : who) + ", Arsh?");
        NotifService.Note target = null;
        for (NotifService.Note n : lastNotes) {
            if (n.reply == null) continue;
            boolean nameOk = !who.isEmpty() && (n.title.toLowerCase(Locale.ROOT).contains(who.toLowerCase(Locale.ROOT))
                || who.toLowerCase(Locale.ROOT).contains(n.title.toLowerCase(Locale.ROOT)));
            boolean appOk = app.isEmpty() || n.app.toLowerCase(Locale.ROOT).contains(app.toLowerCase(Locale.ROOT).split(" ")[0]);
            if (nameOk && appOk) { target = n; break; }
        }
        if (target != null) {
            if (!confirm("Reply to " + target.title + " on " + target.app + "?", msg)) return new Result("Reply cancelled.");
            return new Result(NotifService.sendReply(target, msg) ? "Sent to " + target.title + " on " + target.app + ", Arsh."
                : "The reply did not go through, Arsh. The notification may have closed.");
        }
        // no notification to reply to: copy the text and open the app
        final String clip = msg;
        a.runOnUiThread(new Runnable() { public void run() {
            ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ULTRON reply", clip));
        }});
        String target2 = app.toLowerCase(Locale.ROOT).contains("insta") ? "instagram" : app.toLowerCase(Locale.ROOT).contains("tele") ? "telegram" : "whatsapp";
        openByName("open " + target2, true);
        return new Result("I could not find a message from " + (who.isEmpty() ? "them" : who) + " to reply to, so I copied your reply and opened "
            + target2 + ". Open the chat, paste and send, Arsh.");
    }

    // ================= accessibility =================
    Result needTap() {
        return new Result("I need the ULTRON control permission for that, Arsh. Say: open setup.");
    }

    Result accessibility(int action, String reply) {
        if (!TapService.on()) return needTap();
        return new Result(TapService.global(action) ? reply : "That did not work, Arsh.");
    }

    Result scroll(boolean down) {
        if (!TapService.on()) return needTap();
        return new Result(TapService.scroll(down) ? (down ? "Scrolled down." : "Scrolled up.") : "There is nothing to scroll here.");
    }

    Result tap(String what) {
        if (!TapService.on()) return needTap();
        String hit = TapService.tap(what);
        return new Result(hit != null ? "Tapped " + hit + "." : "I could not find " + what + " on the screen, Arsh.");
    }

    Result typeText(String text) {
        if (!TapService.on()) return needTap();
        return new Result(TapService.type(text) ? "Typed it." : "I could not find a text box on the screen, Arsh.");
    }

    Result readScreen() {
        if (!TapService.on()) return needTap();
        String t = TapService.screenText();
        if (t == null || t.isEmpty()) return new Result("I can't read this screen, Arsh.");
        try {
            return new Result(brain.quick("This is the text on Arsh's phone screen:\n" + t + "\nTell him briefly what is on it, in 2 or 3 spoken sentences.",
                "You are ULTRON. Plain spoken English."));
        } catch (Exception e) {
            return new Result(t.length() > 300 ? t.substring(0, 300) : t);
        }
    }

    static String guessCategory(String f) {
        String l = f.toLowerCase(Locale.ROOT);
        if (has("\\b(always|never|from now on|whenever|every time)\\b", l) && has("\\b(you|ultron|reply|replies|answer|call me|tell me)\\b", l)) return "instructions";
        if (has("\\b(prefer|like|love|hate|favourite|favorite|don't like|dislike)\\b", l)) return "preferences";
        if (has("\\b(my )?(mother|father|mom|dad|brother|sister|wife|husband|friend|boss|uncle|aunt|cousin|son|daughter|birthday)\\b", l)) return "people";
        if (has("\\b(project|ultron|app|build|launch)\\b", l)) return "projects";
        if (has("\\b(decided|decision|we agreed|final)\\b", l)) return "decisions";
        if (has("\\b(plan|goal|want to|will|future|dream)\\b", l)) return "goals";
        if (has("\\b(work|office|job|client|salon|business|meeting)\\b", l)) return "work";
        if (has("\\b(every day|daily|morning|night|routine|usually)\\b", l)) return "routines";
        if (has("\\b(todo|to do|task|need to|have to|must)\\b", l)) return "tasks";
        return "personal";
    }

    // ================= files =================
    Result findFile(String low) {
        if (!allFiles()) {
            start(new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:" + a.getPackageName())));
            return new Result("Allow ULTRON to see your files on this screen, Arsh, then ask again.");
        }
        String[] stop = {"find", "open", "show", "search", "where", "is", "me", "my", "the", "a", "file", "files", "document", "documents",
            "i", "downloaded", "download", "downloads", "yesterday", "today", "last", "week", "recent", "latest", "that", "which", "from", "in"};
        java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList(stop));
        java.util.List<String> words = new ArrayList<>();
        String ext = null;
        for (String w : low.split("[^a-z0-9]+")) {
            if (w.isEmpty() || skip.contains(w)) continue;
            if (w.matches("pdf|apk|zip|mp3|mp4|jpg|png|docx?|xlsx?|pptx?|txt")) { ext = w; continue; }
            if (w.matches("photo|photos|image|images|picture|pictures")) { ext = "jpg"; continue; }
            if (w.matches("video|videos")) { ext = "mp4"; continue; }
            if (w.matches("word")) { ext = "doc"; continue; }
            if (w.matches("excel")) { ext = "xls"; continue; }
            if (w.matches("song|songs|audio")) { ext = "mp3"; continue; }
            words.add(w);
        }
        long since = 0, now = System.currentTimeMillis();
        if (low.contains("yesterday")) since = now - 2L * 86400000;
        else if (low.contains("today")) since = now - 86400000;
        else if (low.contains("week")) since = now - 8L * 86400000;
        java.io.File root = android.os.Environment.getExternalStorageDirectory();
        java.io.File[] places = {new java.io.File(root, "Download"), new java.io.File(root, "Documents"), new java.io.File(root, "DCIM"),
            new java.io.File(root, "Pictures"), new java.io.File(root, "Movies"), new java.io.File(root, "Music"),
            new java.io.File(root, "Android/media/com.whatsapp/WhatsApp/Media"), root};
        java.io.File best = null;
        int scanned = 0;
        java.util.ArrayDeque<java.io.File> q = new java.util.ArrayDeque<>(java.util.Arrays.asList(places));
        java.util.Set<String> done = new java.util.HashSet<>();
        while (!q.isEmpty() && scanned < 20000) {
            java.io.File d = q.poll();
            if (d == null || !done.add(d.getAbsolutePath())) continue;
            java.io.File[] list = d.listFiles();
            if (list == null) continue;
            for (java.io.File f : list) {
                scanned++;
                if (f.isDirectory()) {
                    if (!f.getName().startsWith(".") && !f.getAbsolutePath().contains("/Android/data")) q.add(f);
                    continue;
                }
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (ext != null && !n.endsWith("." + ext) && !(ext.equals("jpg") && (n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")))
                    && !(ext.equals("doc") && n.endsWith(".docx")) && !(ext.equals("xls") && n.endsWith(".xlsx"))) continue;
                if (since > 0 && f.lastModified() < since) continue;
                boolean ok = true;
                for (String w : words) if (!n.contains(w)) { ok = false; break; }
                if (!ok) continue;
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
        }
        if (best == null) return new Result("I could not find that file, Arsh.");
        a.store.activity("action", "Found file " + best.getAbsolutePath());
        openFile(best);
        return new Result("Opening " + best.getName() + ".");
    }

    boolean allFiles() {
        try {
            return (Boolean) android.os.Environment.class.getMethod("isExternalStorageManager").invoke(null);
        } catch (Exception e) {
            return a.granted("android.permission.READ_EXTERNAL_STORAGE");
        }
    }

    void openFile(java.io.File f) {
        try {
            Uri uri = null;
            try (Cursor c = a.getContentResolver().query(android.provider.MediaStore.Files.getContentUri("external"),
                new String[]{android.provider.MediaStore.Files.FileColumns._ID}, android.provider.MediaStore.Files.FileColumns.DATA + "=?",
                new String[]{f.getAbsolutePath()}, null)) {
                if (c != null && c.moveToFirst()) uri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Files.getContentUri("external"), c.getLong(0));
            }
            if (uri == null) {
                start(new Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"
                    + Uri.encode(f.getAbsolutePath().replaceFirst("^/storage/emulated/0/", "")))));
                return;
            }
            String name = f.getName().toLowerCase(Locale.ROOT);
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
            String type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            start(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type == null ? "*/*" : type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            a.log("open file failed " + e);
        }
    }

    // ================= email =================
    Result email(String who, String about, String question) {
        String address = who.contains("@") ? who.replaceAll("[^A-Za-z0-9@._+-]", "") : "";
        if (address.isEmpty()) {
            try (Cursor c = a.getContentResolver().query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Email.ADDRESS}, ContactsContract.CommonDataKinds.Email.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + who.trim() + "%"}, null)) {
                if (c != null && c.moveToFirst()) address = c.getString(0);
            } catch (Exception ignored) {
            }
        }
        String subject = "", body = about == null ? "" : about;
        try {
            String raw = brain.quick("Arsh asked: " + question + "\nWrite the email for him to send himself (it goes out from his own account, "
                + "so write in first person as Arsh). Keep it short and polite. Answer ONLY with JSON: {\"subject\": \"...\", \"body\": \"...\"}",
                "You write emails. Output only JSON.");
            JSONObject j = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
            subject = j.optString("subject");
            body = j.optString("body");
        } catch (Exception ignored) {
        }
        start(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + address)).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body));
        a.store.activity("action", "Drafted email to " + (address.isEmpty() ? who : address) + ": " + subject);
        return new Result("Your email to " + (address.isEmpty() ? who : address) + " is ready. Check it and tap send, Arsh."
            + (address.isEmpty() ? " I could not find their email address, so add it." : ""));
    }
}
