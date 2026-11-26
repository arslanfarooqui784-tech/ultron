package com.arsh.ultron;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * ULTRON's agenda: real reminders (alarms that fire even after a restart), the phone calendar,
 * reminder / meeting suggestions spotted in chats, the email log and the daily briefing.
 */
public class Agenda {
    static final SimpleDateFormat NICE = new SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH);
    static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT);

    // ===================================================== alarms (one helper for everything timed) =====
    static PendingIntent alarmIntent(Context c, String action, String id) {
        Intent i = new Intent(c, AgendaReceiver.class).setAction("com.arsh.ultron." + action).putExtra("id", id);
        return PendingIntent.getBroadcast(c, (action + id).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
    }

    static void at(Context c, long when, PendingIntent p) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);       // exact alarms not allowed: within a few minutes
        }
    }

    static void cancel(Context c, PendingIntent p) {
        ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(p);
    }

    /** After a restart or an update: put every reminder, the briefing and the routine schedule back. */
    public static void rescheduleAll(Context c) {
        Store s = new Store(c);
        JSONArray r = reminders(s);
        long now = System.currentTimeMillis();
        for (int i = 0; i < r.length(); i++) {
            JSONObject x = r.optJSONObject(i);
            if (x != null && !x.optBoolean("done")) at(c, Math.max(now + 5000, x.optLong("when")), alarmIntent(c, "REMIND", x.optString("id")));
        }
        scheduleBriefing(c);
        Routines.schedule(c);
        Smart.scheduleNudges(c);
        Smart.scheduleWeekly(c);
        Daily.scheduleWake(c);
        Learn.scheduleReflect(c);
        Updater.schedule(c);
        for (String id : s.get("sched_ids", "").split(",")) {
            if (id.isEmpty()) continue;
            try {
                JSONObject j = new JSONObject(s.getSecret("sched:" + id, "{}"));
                if (j.has("when")) at(c, Math.max(now + 5000, j.optLong("when")), alarmIntent(c, "SENDMSG", id));
            } catch (Exception ignored) {
            }
        }
    }

    // ===================================================== reminders =====
    static JSONArray reminders(Store s) {
        try { return new JSONArray(s.getSecret("reminders", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    public static synchronized String addReminder(Context c, String what, long when, String from) {
        return addReminder(c, what, when, from, false);
    }

    public static synchronized String addReminder(Context c, String what, long when, String from, boolean daily) {
        Store s = new Store(c);
        if (when < System.currentTimeMillis() - 60000) return "That time has already passed, Arsh.";
        try {
            JSONArray a = reminders(s), keep = new JSONArray();
            long old = System.currentTimeMillis() - 7 * 86400000L;
            for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).optLong("when") > old || a.getJSONObject(i).optBoolean("daily")) keep.put(a.get(i));
            String id = UUID.randomUUID().toString().substring(0, 8);
            keep.put(new JSONObject().put("id", id).put("what", what).put("when", when).put("from", from == null ? "" : from).put("daily", daily));
            s.putSecret("reminders", keep.toString());
            at(c, when, alarmIntent(c, "REMIND", id));
            s.activity("reminder", "Reminder set for " + NICE.format(new Date(when)) + ": " + what);
            return (daily ? "Daily reminder set, starting " : "Reminder set for ") + NICE.format(new Date(when)) + ": " + what + ".";
        } catch (Exception e) {
            return "I could not save that reminder.";
        }
    }

    static synchronized JSONObject takeReminder(Store s, String id, boolean done) {
        try {
            JSONArray a = reminders(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                if (id.equals(x.optString("id"))) {
                    if (done) x.put("done", true);
                    s.putSecret("reminders", a.toString());
                    return x;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static void fireReminder(Context c, String id) {
        Store s = new Store(c);
        JSONObject x = takeReminder(s, id, true);
        if (x == null) return;
        String what = x.optString("what");
        if (x.optBoolean("daily")) {
            // repeat tomorrow at the same time
            try {
                JSONArray a = reminders(s);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject y = a.getJSONObject(i);
                    if (id.equals(y.optString("id"))) {
                        long next = y.optLong("when");
                        while (next <= System.currentTimeMillis() + 60000) next += 86400000L;
                        y.put("done", false).put("when", next);
                        at(c, next, alarmIntent(c, "REMIND", id));
                    }
                }
                s.putSecret("reminders", a.toString());
            } catch (Exception ignored) {
            }
        }
        int nid = 7000 + Math.abs(id.hashCode() % 1000);
        try {
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Notification.Builder b = Approvals.builder(c, Approvals.ATTENTION_CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("⏰ Reminder").setContentText(what).setStyle(new Notification.BigTextStyle().bigText(what
                    + (x.optString("from").isEmpty() ? "" : "\n(from " + x.optString("from") + ")")))
                .setColor(0xFFFF1728).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(PendingIntent.getActivity(c, nid, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000))
                .addAction(new Notification.Action.Builder(0, "SNOOZE 10 MIN", alarmIntent(c, "SNOOZE", id)).build());
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(nid, b.build());
        } catch (Exception ignored) {
        }
        if (Routines.active(s).equals("driving")) Speaker.say(c, "Reminder, Arsh: " + what);
        s.activity("reminder", "Reminder: " + what);
    }

    static void snooze(Context c, String id) {
        Store s = new Store(c);
        JSONObject x = takeReminder(s, id, false);
        if (x == null) return;
        try {
            JSONArray a = reminders(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject y = a.getJSONObject(i);
                if (id.equals(y.optString("id"))) y.put("done", false).put("when", System.currentTimeMillis() + 600000L);
            }
            s.putSecret("reminders", a.toString());
        } catch (Exception ignored) {
        }
        at(c, System.currentTimeMillis() + 600000L, alarmIntent(c, "REMIND", id));
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(7000 + Math.abs(id.hashCode() % 1000));
    }

    static JSONArray upcomingReminders(Store s, long until) {
        JSONArray out = new JSONArray(), a = reminders(s);
        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && !x.optBoolean("done") && x.optLong("when") >= now - 60000 && x.optLong("when") <= until) out.put(x);
        }
        return out;
    }

    // ===================================================== calendar =====
    /** Events between from and to: [{title, begin, end, where}] (needs calendar permission). */
    public static JSONArray events(Context c, long from, long to) {
        JSONArray out = new JSONArray();
        try {
            Uri.Builder u = CalendarContract.Instances.CONTENT_URI.buildUpon();
            android.content.ContentUris.appendId(u, from);
            android.content.ContentUris.appendId(u, to);
            Cursor cur = c.getContentResolver().query(u.build(), new String[]{CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY}, null, null, CalendarContract.Instances.BEGIN + " ASC");
            if (cur != null) {
                try {
                    while (cur.moveToNext() && out.length() < 30)
                        out.put(new JSONObject().put("title", cur.getString(0)).put("begin", cur.getLong(1)).put("end", cur.getLong(2))
                            .put("where", cur.getString(3) == null ? "" : cur.getString(3)).put("allDay", cur.getInt(4) == 1));
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static long writableCalendar(Context c) {
        try {
            Cursor cur = c.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, new String[]{CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.IS_PRIMARY},
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=" + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                    + " AND " + CalendarContract.Calendars.VISIBLE + "=1", null, null);
            long best = -1;
            int bestScore = -1;
            if (cur != null) {
                try {
                    while (cur.moveToNext()) {
                        int score = ("com.google".equals(cur.getString(1)) ? 2 : 0) + (cur.getInt(2) == 1 ? 1 : 0);
                        if (score > bestScore) { best = cur.getLong(0); bestScore = score; }
                    }
                } finally {
                    cur.close();
                }
            }
            return best;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String addEvent(Context c, String title, long begin, long minutes, String note) {
        long cal = writableCalendar(c);
        if (cal < 0) return "I could not find a calendar I can write to. Allow ULTRON's calendar permission in Setup, Arsh.";
        try {
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, cal);
            v.put(CalendarContract.Events.TITLE, title);
            v.put(CalendarContract.Events.DTSTART, begin);
            v.put(CalendarContract.Events.DTEND, begin + Math.max(15, minutes) * 60000L);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().getID());
            if (note != null && !note.isEmpty()) v.put(CalendarContract.Events.DESCRIPTION, note);
            v.put(CalendarContract.Events.HAS_ALARM, 1);
            Uri ev = c.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, v);
            if (ev == null) return "The calendar did not accept the event.";
            ContentValues r = new ContentValues();
            r.put(CalendarContract.Reminders.EVENT_ID, Long.parseLong(ev.getLastPathSegment()));
            r.put(CalendarContract.Reminders.MINUTES, 15);
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            c.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, r);
            new Store(c).activity("calendar", "Added to calendar: " + title + " · " + NICE.format(new Date(begin)));
            return "Added to your calendar: " + title + ", " + NICE.format(new Date(begin)) + ".";
        } catch (SecurityException e) {
            return "I need the calendar permission first. Open Setup and allow Calendar, Arsh.";
        } catch (Exception e) {
            return "I could not add that to the calendar: " + e.getMessage();
        }
    }

    public static String describeEvents(JSONArray ev) {
        if (ev.length() == 0) return "nothing on the calendar";
        StringBuilder b = new StringBuilder();
        SimpleDateFormat t = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        for (int i = 0; i < ev.length(); i++) {
            JSONObject e = ev.optJSONObject(i);
            b.append(i > 0 ? "; " : "").append(e.optBoolean("allDay") ? "all day" : t.format(new Date(e.optLong("begin"))))
                .append(" ").append(e.optString("title")).append(e.optString("where").isEmpty() ? "" : " at " + e.optString("where"));
        }
        return b.toString();
    }

    // ===================================================== suggestions spotted in chats =====
    /**
     * A chat mentioned a time for something involving Arsh ("call me tomorrow at 5", "meeting on Monday 11 am").
     * Nothing is added by itself: Arsh gets a notification with ADD / NO.
     */
    public static void suggest(Context c, String type, String title, String when, int minutes, String who, String app) {
        long t;
        try {
            t = ISO.parse(when).getTime();
        } catch (Exception e) {
            return;
        }
        if (t < System.currentTimeMillis() + 60000 || t > System.currentTimeMillis() + 120L * 86400000L || title.trim().isEmpty()) return;
        Store s = new Store(c);
        String key = "sugg:" + AutoReply.sha(title.toLowerCase(Locale.ROOT) + "|" + t);
        if (!s.get(key, "").isEmpty()) return;
        String id = UUID.randomUUID().toString().substring(0, 8);
        try {
            s.putSecret("suggest:" + id, new JSONObject().put("type", type.equals("event") ? "event" : "reminder").put("title", title)
                .put("when", t).put("minutes", minutes <= 0 ? 60 : minutes).put("who", who).put("app", app).toString());
        } catch (Exception ignored) {
        }
        s.put(key, id);
        int nid = 8000 + Math.abs(id.hashCode() % 1000);
        String what = (type.equals("event") ? "📅 Add to calendar? " : "⏰ Set a reminder? ") + title;
        try {
            Notification.Builder b = Approvals.builder(c, Approvals.ATTENTION_CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(what).setContentText(NICE.format(new Date(t)) + " · from " + who + " (" + app + ")")
                .setColor(0xFFFF1728).setAutoCancel(true)
                .addAction(new Notification.Action.Builder(0, "ADD", alarmIntent(c, "SUGGEST_ADD", id)).build())
                .addAction(new Notification.Action.Builder(0, "NO", alarmIntent(c, "SUGGEST_NO", id)).build());
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(nid, b.build());
        } catch (Exception ignored) {
        }
        s.activity("suggest", "Suggested " + (type.equals("event") ? "calendar event" : "reminder") + " from " + who + ": " + title + " · " + NICE.format(new Date(t)));
    }

    static String acceptSuggestion(Context c, String id, boolean yes) {
        Store s = new Store(c);
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(8000 + Math.abs(id.hashCode() % 1000));
        try {
            JSONObject j = new JSONObject(s.getSecret("suggest:" + id, "{}"));
            s.putSecret("suggest:" + id, "{}");
            if (!yes || !j.has("title")) return "Okay, not added.";
            String note = "Spotted by ULTRON in " + j.optString("who") + "'s " + j.optString("app") + " chat";
            if (j.optString("type").equals("event")) return addEvent(c, j.optString("title"), j.optLong("when"), j.optInt("minutes", 60), note);
            return addReminder(c, j.optString("title"), j.optLong("when"), j.optString("who"));
        } catch (Exception e) {
            return "That suggestion has expired.";
        }
    }

    // ===================================================== email (read, summarise, never auto-reply) =====
    static final java.util.Set<String> EMAIL = new java.util.HashSet<>(java.util.Arrays.asList("com.google.android.gm",
        "com.samsung.android.email.provider", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
        "ch.protonmail.android", "com.google.android.gm.lite"));
    static final java.util.regex.Pattern IMPORTANT = java.util.regex.Pattern.compile(
        "(?i)(urgent|asap|interview|offer letter|invoice|payment (due|pending)|deadline|important|action required|exam|result|admit card|hall ticket)");

    public static boolean isEmail(String pkg) {
        return EMAIL.contains(pkg);
    }

    static JSONArray mails(Store s) {
        try { return new JSONArray(s.getSecret("mails", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** An email notification: logged for the briefing and "read my emails". Emails are never answered automatically. */
    public static synchronized void mail(Context c, NotifService.Note n) {
        if (n.title.isEmpty() || n.title.equalsIgnoreCase(n.app) || n.text.matches("(?i)^\\d+ new (messages|emails)$")) return;
        Store s = new Store(c);
        String id = AutoReply.sha(n.pkg + "|" + n.title + "|" + n.text);
        JSONArray a = mails(s);
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optJSONObject(i).optString("id"))) return;
        boolean important = IMPORTANT.matcher(n.title + " " + n.text).find() || Calls.vip(Calls.category(s, n.title));
        try {
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, a.length() - 99); i < a.length(); i++) keep.put(a.get(i));
            keep.put(new JSONObject().put("id", id).put("from", n.title).put("text", n.text).put("app", n.app).put("at", System.currentTimeMillis())
                .put("important", important));
            s.putSecret("mails", keep.toString());
        } catch (Exception ignored) {
        }
        s.activity("email", "Email from " + n.title + (important ? " (looks important)" : "") + ": " + n.text);
        if (important) Owner.report(c, "normal", "Email · " + n.title, n.text);
    }

    public static JSONArray mailsSince(Store s, long since) {
        JSONArray out = new JSONArray(), a = mails(s);
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optLong("at") >= since) out.put(a.optJSONObject(i));
        return out;
    }

    // ===================================================== daily briefing =====
    public static boolean briefingOn(Store s) { return !"0".equals(s.get("brief_on", "1")); }
    public static String briefingTime(Store s) { return s.get("brief_time", "08:00"); }

    public static void scheduleBriefing(Context c) {
        Store s = new Store(c);
        PendingIntent p = alarmIntent(c, "BRIEF", "daily");
        if (!briefingOn(s)) {
            cancel(c, p);
            return;
        }
        at(c, nextTime(briefingTime(s)), p);
    }

    /** Next occurrence of HH:MM (today if still ahead, else tomorrow). */
    static long nextTime(String hhmm) {
        Calendar k = Calendar.getInstance();
        int h = 8, m = 0;
        try {
            h = Integer.parseInt(hhmm.split(":")[0]);
            m = Integer.parseInt(hhmm.split(":")[1]);
        } catch (Exception ignored) {
        }
        k.set(Calendar.HOUR_OF_DAY, h);
        k.set(Calendar.MINUTE, m);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        if (k.getTimeInMillis() <= System.currentTimeMillis() + 30000) k.add(Calendar.DAY_OF_MONTH, 1);
        return k.getTimeInMillis();
    }

    /** What happened and what's ahead, as plain facts (the AI turns it into speech). */
    static String facts(Context c, Store s, long since) {
        StringBuilder f = new StringBuilder();
        // messages by person
        Map<String, Integer> count = new TreeMap<>();
        Map<String, String> last = new TreeMap<>();
        int needs = 0;
        JSONArray a = AutoReply.Events.all(s);
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null || e.optLong("received") < since) continue;
            String st = e.optString("status"), k = e.optString("kind");
            if (k.equals("outgoing") || st.equals("history") || st.equals("ultron") || st.equals("arsh") || st.equals("duplicate")) continue;
            if (!(k.equals("personal") || k.equals("group") || k.equals("emergency"))) continue;
            String who = e.optString("who") + " (" + e.optString("app") + ")";
            count.put(who, (count.containsKey(who) ? count.get(who) : 0) + 1);
            last.put(who, e.optString("text"));
            if (st.equals("attention") || st.equals("awaiting_approval")) needs++;
        }
        f.append("Messages: ");
        if (count.isEmpty()) f.append("none.\n");
        else {
            int n = 0;
            for (Map.Entry<String, Integer> x : count.entrySet()) {
                if (n++ >= 10) break;
                f.append(x.getKey()).append(" sent ").append(x.getValue()).append(", last: \"").append(Memory.clip(last.get(x.getKey()), 80)).append("\"; ");
            }
            f.append("\n");
        }
        f.append("Waiting for Arsh personally: ").append(needs).append(" message(s).\n");
        // calls
        JSONArray cl = Calls.log(s);
        StringBuilder missed = new StringBuilder();
        for (int i = 0; i < cl.length(); i++) {
            JSONObject e = cl.optJSONObject(i);
            if (e != null && e.optLong("at") >= since && (e.optString("status").equals("missed") || e.optString("status").equals("declined")))
                missed.append(e.optString("who")).append(" (").append(e.optString("app")).append("), ");
        }
        f.append("Missed calls: ").append(missed.length() == 0 ? "none" : missed).append("\n");
        // email
        JSONArray m = mailsSince(s, since);
        f.append("Emails: ").append(m.length());
        for (int i = 0; i < Math.min(6, m.length()); i++) {
            JSONObject e = m.optJSONObject(i);
            f.append(i == 0 ? " - " : "; ").append(e.optString("from")).append(": ").append(Memory.clip(e.optString("text"), 60)).append(e.optBoolean("important") ? " (important)" : "");
        }
        f.append("\n");
        // today
        long now = System.currentTimeMillis(), end = now + 18 * 3600000L;
        f.append("Calendar today: ").append(describeEvents(events(c, now, end))).append("\n");
        JSONArray r = upcomingReminders(s, end);
        f.append("Reminders today: ");
        for (int i = 0; i < r.length(); i++) f.append(i > 0 ? "; " : "").append(new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(r.optJSONObject(i).optLong("when")))).append(" ").append(r.optJSONObject(i).optString("what"));
        if (r.length() == 0) f.append("none");
        try {
            f.append("\nWeather (").append(Weather.home(s)).append("): ").append(Weather.facts(Weather.home(s), 1));
        } catch (Exception ignored) {
        }
        if (Smart.on(s, "news_brief", true)) {
            try {
                java.util.List<String> h = Daily.headlines(null, 3);
                if (!h.isEmpty()) f.append("\nTop news: ").append(android.text.TextUtils.join("; ", h));
            } catch (Exception ignored) {
            }
        }
        String dyk = Learn.didYouKnow(s);
        if (!dyk.isEmpty()) f.append("\nSomething ULTRON learned for you (one line, optional): ").append(dyk);
        if (!s.get("sleep_last", "").isEmpty()) f.append("\n").append(s.get("sleep_last", ""));
        f.append("\nMoney yesterday: ").append(Smart.spending(s, System.currentTimeMillis() - 24 * 3600000L, "in the last 24 hours"));
        f.append("\nActive routine: ").append(Routines.active(s).isEmpty() ? "none" : Routines.active(s)).append("\n");
        return f.toString();
    }

    /** Builds the briefing text (AI-written, with a plain fallback). */
    public static String briefing(Context c, boolean morning) {
        Store s = new Store(c);
        long since;
        try {
            since = Long.parseLong(s.get("brief_last", "0"));
        } catch (Exception e) {
            since = 0;
        }
        if (since <= 0 || System.currentTimeMillis() - since > 36 * 3600000L) since = System.currentTimeMillis() - 24 * 3600000L;
        String facts = facts(c, s, since);
        String text;
        try {
            Brain b = new Brain(s);
            text = b.quick("You are ULTRON, Arsh's AI assistant. Write his " + (morning ? "morning " : "") + "briefing to be SPOKEN aloud, "
                + "60 to 120 words, warm and efficient, starting with '" + greeting() + ", Arsh.'. Cover: the weather, who messaged and what they want (group "
                + "small things), missed calls, important emails, what is on today, reminders, top news in one line, and what needs him personally. Do not invent "
                + "anything that is not in the facts. No lists, no emojis, no markdown.\n\nFACTS (since " + NICE.format(new Date(since)) + "):\n" + facts,
                "You write spoken briefings. Output only the briefing.");
            text = AutoReply.clean(text);
            if (text.length() < 20) throw new Exception("short");
        } catch (Exception e) {
            text = greeting() + ", Arsh. Here is your update. " + facts.replace("\n", " ");
        }
        s.putSecret("brief_text", text);
        s.put("brief_last", String.valueOf(System.currentTimeMillis()));
        s.activity("briefing", "Briefing prepared");
        return text;
    }

    static String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
    }

    static void fireBriefing(final Context c) {
        scheduleBriefing(c);                                               // tomorrow's
        AutoReply.pool.execute(new Runnable() {
            public void run() {
                String text = briefing(c, true);
                try {
                    Intent listen = new Intent(c, MainActivity.class).putExtra("say", "briefing").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    Notification.Builder b = Approvals.builder(c, Approvals.ATTENTION_CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("☀️ ULTRON briefing").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                        .setColor(0xFFFF1728).setAutoCancel(true)
                        .setContentIntent(PendingIntent.getActivity(c, 9001, listen, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000))
                        .addAction(new Notification.Action.Builder(0, "▶ LISTEN", PendingIntent.getActivity(c, 9002, listen,
                            PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000)).build());
                    ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(9000, b.build());
                } catch (Exception ignored) {
                }
                if (Routines.readAloud(new Store(c))) Speaker.say(c, text);
            }
        });
    }

    public static JSONObject info(Context c) throws Exception {
        Store s = new Store(c);
        JSONArray r = upcomingReminders(s, System.currentTimeMillis() + 30L * 86400000L);
        return new JSONObject().put("briefOn", briefingOn(s)).put("briefTime", briefingTime(s)).put("reminders", r)
            .put("today", events(c, System.currentTimeMillis(), System.currentTimeMillis() + 24 * 3600000L))
            .put("mails", mailsSince(s, System.currentTimeMillis() - 48 * 3600000L)).put("lastBrief", s.getSecret("brief_text", ""))
            .put("homeCity", Weather.home(s));
    }

    public static void save(Context c, JSONObject in) {
        Store s = new Store(c);
        if (in.has("briefOn")) s.put("brief_on", in.optBoolean("briefOn") ? "1" : "0");
        if (in.has("briefTime") && in.optString("briefTime").matches("\\d{1,2}:\\d{2}")) s.put("brief_time", in.optString("briefTime"));
        if (in.has("homeCity") && in.optString("homeCity").trim().matches("[\\p{L} .'-]{2,40}")) s.put("home_city", in.optString("homeCity").trim());
        scheduleBriefing(c);
    }

    /** Parses a spoken time with the AI ("tomorrow at 5", "next Monday 11 am", "in 20 minutes"). Returns epoch ms or -1. */
    public static long parseWhen(Store s, String phrase) {
        String low = phrase.toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bin (\\d+) (second|minute|min|hour|hr|day)s?\\b").matcher(low);
        if (m.find()) {
            long n = Long.parseLong(m.group(1));
            String u = m.group(2);
            return System.currentTimeMillis() + n * (u.startsWith("s") ? 1000L : u.startsWith("m") ? 60000L : u.startsWith("h") ? 3600000L : 86400000L);
        }
        try {
            String now = new SimpleDateFormat("EEEE yyyy-MM-dd'T'HH:mm", Locale.ENGLISH).format(new Date());
            String raw = new Brain(s).quick("Now is " + now + " (local time). Convert this to a local date-time: \"" + phrase
                + "\". If no time of day is given, use 09:00. Answer ONLY with yyyy-MM-ddTHH:mm or NONE.", "Output only the date-time.");
            java.util.regex.Matcher d = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}").matcher(raw);
            if (d.find()) return ISO.parse(d.group()).getTime();
        } catch (Exception ignored) {
        }
        return -1;
    }
}
