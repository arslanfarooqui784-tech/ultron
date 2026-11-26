package com.arsh.ultron;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everyday helpers: names like "mummy", lists, notes, scheduled and group messages, location,
 * find-my-phone, news and cricket, currency, the wake-up routine and auto-silent from the calendar.
 */
public class Daily {
    // ================================================================ relationship names ======
    static final String[] RELATIONS = {"mummy", "mum", "mom", "mother", "ammi", "maa", "papa", "dad", "father", "abbu", "pappa", "bhai", "brother",
        "bro", "sister", "sis", "didi", "behen", "wife", "husband", "bestie", "best friend", "boss", "nana", "nani", "dada", "dadi", "chacha",
        "chachi", "mama", "mami", "uncle", "aunty", "aunt", "cousin", "girlfriend", "boyfriend", "son", "daughter"};

    static String rel(String w) {
        return w.toLowerCase(Locale.ROOT).replaceAll("^(my|meri|mere|mera)\\s+", "").replaceAll("[^\\p{L} ]", "").trim();
    }

    public static String alias(Store s, String word) {
        return s.get("alias:" + rel(word), "");
    }

    public static String setAlias(Store s, String word, String contactName) {
        s.put("alias:" + rel(word), contactName.trim());
        s.activity("people", "\"" + rel(word) + "\" means " + contactName.trim());
        return "Got it. When you say " + rel(word) + ", I'll use " + contactName.trim() + ".";
    }

    public static boolean isRelation(String w) {
        String r = rel(w);
        for (String x : RELATIONS) if (x.equals(r)) return true;
        return false;
    }

    // ================================================================ lists ======
    static JSONArray items(Store s, String list) {
        try { return new JSONArray(s.getSecret("list:" + list, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    public static String listName(String raw) {
        String l = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("\\b(my|the|list)\\b", "").trim();
        return l.isEmpty() ? "shopping" : l.replaceAll("[^a-z0-9 ]", "").trim();
    }

    public static String addItems(Store s, String list, String what) {
        JSONArray a = items(s, list);
        List<String> added = new ArrayList<>();
        for (String part : what.split("\\s*(?:,|\\band\\b|\\baur\\b|&)\\s*")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            boolean dup = false;
            for (int i = 0; i < a.length(); i++) if (a.optString(i).equalsIgnoreCase(p)) dup = true;
            if (!dup) { a.put(p); added.add(p); }
        }
        s.putSecret("list:" + list, a.toString());
        registerList(s, list);
        return added.isEmpty() ? "Already on your " + list + " list." : "Added " + join(added) + " to your " + list + " list. It has " + a.length() + " item" + (a.length() == 1 ? "" : "s") + ".";
    }

    public static String removeItem(Store s, String list, String what) {
        JSONArray a = items(s, list), keep = new JSONArray();
        String gone = null;
        for (int i = 0; i < a.length(); i++) {
            if (gone == null && a.optString(i).toLowerCase(Locale.ROOT).contains(what.toLowerCase(Locale.ROOT).trim())) gone = a.optString(i);
            else keep.put(a.optString(i));
        }
        s.putSecret("list:" + list, keep.toString());
        return gone == null ? "I don't see " + what + " on your " + list + " list." : "Removed " + gone + " from your " + list + " list.";
    }

    public static String readList(Store s, String list) {
        JSONArray a = items(s, list);
        if (a.length() == 0) return "Your " + list + " list is empty, Arsh.";
        List<String> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
        return "Your " + list + " list: " + join(l) + ".";
    }

    public static String listText(Store s, String list) {
        JSONArray a = items(s, list);
        StringBuilder b = new StringBuilder(list.substring(0, 1).toUpperCase(Locale.ROOT) + list.substring(1) + " list:");
        for (int i = 0; i < a.length(); i++) b.append("\n• ").append(a.optString(i));
        return b.toString();
    }

    public static String clearList(Store s, String list) {
        s.putSecret("list:" + list, "[]");
        return "Cleared your " + list + " list.";
    }

    static void registerList(Store s, String list) {
        String all = s.get("lists", "");
        if (!("," + all + ",").contains("," + list + ",")) s.put("lists", all.isEmpty() ? list : all + "," + list);
    }

    static String join(List<String> l) {
        if (l.size() <= 1) return l.isEmpty() ? "" : l.get(0);
        return android.text.TextUtils.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1);
    }

    // ================================================================ notes ======
    public static String note(Store s, String text) {
        try {
            Smart.push(s, "notes", new JSONObject().put("t", Memory.clip(text.trim(), 400)).put("at", System.currentTimeMillis()), 300);
        } catch (Exception ignored) {
        }
        s.activity("note", text);
        return "Noted: " + text;
    }

    public static String askNotes(Brain brain, Store s, String question) {
        JSONArray a = Smart.list(s, "notes");
        if (a.length() == 0) return "You have no notes yet, Arsh. Say: note, followed by what to remember.";
        StringBuilder b = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("d MMM h:mm a", Locale.ENGLISH);
        for (int i = Math.max(0, a.length() - 60); i < a.length(); i++) b.append(f.format(new Date(a.optJSONObject(i).optLong("at")))).append(": ").append(a.optJSONObject(i).optString("t")).append("\n");
        try {
            return AutoReply.clean(brain.quick("Arsh's notes (date: note):\n" + b + "\nArsh asks: \"" + question + "\". Answer in 1-3 spoken sentences using "
                + "ONLY these notes (newest first matters most). If no note answers it, say so.", "You are ULTRON. Never invent."));
        } catch (Exception e) {
            return "Your latest note: " + a.optJSONObject(a.length() - 1).optString("t");
        }
    }

    public static String readNotes(Store s) {
        JSONArray a = Smart.list(s, "notes");
        if (a.length() == 0) return "You have no notes yet, Arsh.";
        StringBuilder b = new StringBuilder("Your latest notes: ");
        for (int i = a.length() - 1, n = 0; i >= 0 && n < 5; i--, n++) b.append(n > 0 ? "; " : "").append(a.optJSONObject(i).optString("t"));
        return b + ".";
    }

    // ================================================================ location ======
    /** Best recent location as a Google Maps link, or "" (needs location permission). */
    public static String locationLink(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (Exception ignored) {
                }
            }
            if (best == null || System.currentTimeMillis() - best.getTime() > 30 * 60000L) {
                final Location[] got = {null};
                final Object lock = new Object();
                android.location.LocationListener ll = new android.location.LocationListener() {
                    public void onLocationChanged(Location l) { synchronized (lock) { got[0] = l; lock.notifyAll(); } }
                    public void onStatusChanged(String p, int s, android.os.Bundle b) {}
                    public void onProviderEnabled(String p) {}
                    public void onProviderDisabled(String p) {}
                };
                String prov = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
                lm.requestSingleUpdate(prov, ll, android.os.Looper.getMainLooper());
                synchronized (lock) {
                    if (got[0] == null) lock.wait(15000);
                }
                lm.removeUpdates(ll);
                if (got[0] != null) best = got[0];
            }
            if (best == null) return "";
            return String.format(Locale.ROOT, "https://maps.google.com/?q=%.6f,%.6f", best.getLatitude(), best.getLongitude());
        } catch (SecurityException e) {
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================ find my phone ======
    static MediaPlayer ringer;

    /** A text with Arsh's secret word: ring loudly (even on silent) and answer with the phone's location. */
    public static boolean findPhoneCheck(Context c, NotifService.Note note) {
        Store s = new Store(c);
        String word = s.get("find_word", "").trim();
        if (word.length() < 4 || note.text == null) return false;
        if (!note.text.trim().equalsIgnoreCase(word) && !note.text.trim().equalsIgnoreCase("ultron " + word)) return false;
        String k = "find:" + AutoReply.sha(note.pkg + note.title + note.text + (System.currentTimeMillis() / 120000));
        if (!s.get(k, "").isEmpty()) return true;
        s.put(k, "1");
        s.activity("security", "FIND MY PHONE triggered by " + note.title + " (" + note.app + ")");
        ring(c, 60);
        final NotifService.Note n = note;
        final Context ctx = c.getApplicationContext();
        AutoReply.pool.execute(new Runnable() {
            public void run() {
                String link = locationLink(ctx);
                int batt = -1;
                try {
                    Intent b = ctx.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    batt = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100));
                } catch (Exception ignored) {
                }
                String msg = "ULTRON: Arsh's phone is ringing now." + (link.isEmpty() ? " Location is not available (turn on location)." : " Location: " + link)
                    + (batt >= 0 ? " Battery " + batt + "%." : "");
                boolean sent = n.reply != null && NotifService.sendReply(n, msg);
                Store st = new Store(ctx);
                AutoReply.rememberSent(st, msg);
                if (!sent) Owner.sms(ctx, st, Owner.HEAD + msg);
                st.activity("security", "Find my phone: rang and sent " + (link.isEmpty() ? "battery only" : "the location"));
            }
        });
        return true;
    }

    public static void ring(Context c, int seconds) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            try {
                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            } catch (Exception ignored) {
            }
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            if (ringer != null) ringer.release();
            ringer = new MediaPlayer();
            ringer.setAudioStreamType(AudioManager.STREAM_ALARM);
            ringer.setDataSource(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            ringer.setLooping(true);
            ringer.prepare();
            ringer.start();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() { stopRing(); }
            }, seconds * 1000L);
            Smart.notify(c, 6600, "📍 Find my phone", "ULTRON is ringing because someone sent your secret word. Tap to stop.",
                PendingIntent.getBroadcast(c, 6601, new Intent(c, AgendaReceiver.class).setAction("com.arsh.ultron.STOPRING"), PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
        } catch (Exception ignored) {
        }
    }

    public static void stopRing() {
        try {
            if (ringer != null) {
                ringer.stop();
                ringer.release();
            }
        } catch (Exception ignored) {
        }
        ringer = null;
    }

    // ================================================================ scheduled messages ======
    /** Saves a message to be sent later (Arsh already approved the exact text, person and time). */
    public static String schedule(Context c, String who, String number, String app, String text, long when) {
        Store s = new Store(c);
        String id = UUID.randomUUID().toString().substring(0, 8);
        try {
            s.putSecret("sched:" + id, new JSONObject().put("who", who).put("number", number).put("app", app).put("text", text).put("when", when).toString());
            String all = s.get("sched_ids", "");
            s.put("sched_ids", all.isEmpty() ? id : all + "," + id);
        } catch (Exception ignored) {
        }
        Agenda.at(c, when, Agenda.alarmIntent(c, "SENDMSG", id));
        s.activity("scheduled", "Message to " + who + " (" + app + ") scheduled for " + Agenda.NICE.format(new Date(when)) + ": " + text);
        return "Scheduled. I'll send \"" + text + "\" to " + who + " on " + (app.equals("whatsapp") ? "WhatsApp" : "SMS") + " at " + Agenda.NICE.format(new Date(when)) + ".";
    }

    public static void sendScheduled(final Context c, final String id) {
        AutoReply.pool.execute(new Runnable() {
            public void run() {
                Store s = new Store(c);
                JSONObject j;
                try {
                    j = new JSONObject(s.getSecret("sched:" + id, "{}"));
                } catch (Exception e) {
                    return;
                }
                if (!j.has("text")) return;
                String who = j.optString("who"), text = j.optString("text"), num = j.optString("number");
                boolean ok;
                String how;
                if (j.optString("app").equals("whatsapp") && !AutoReply.locked(c) && TapService.on()) {
                    Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + waNumber(num) + "?text=" + Uri.encode(text)))
                        .setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ok = TapService.sendOnScreen(open, "com.whatsapp", text, who) == null;
                    how = "WhatsApp";
                } else if (j.optString("app").equals("whatsapp")) {
                    // phone locked: WhatsApp needs the screen, so send by SMS instead only if Arsh allowed a fallback; otherwise remind him
                    Smart.notify(c, 6700 + Math.abs(id.hashCode() % 100), "⏰ Scheduled WhatsApp to " + who,
                        "Unlock your phone and tap here to send: \"" + text + "\"",
                        PendingIntent.getActivity(c, 6700, new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + waNumber(num) + "?text=" + Uri.encode(text)))
                            .setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
                    s.activity("scheduled", "Scheduled WhatsApp to " + who + " is ready: phone was locked, Arsh was asked to tap send");
                    s.putSecret("sched:" + id, "{}");
                    return;
                } else {
                    ok = Owner.smsTo(c, s, num, text);
                    how = "SMS";
                }
                s.putSecret("sched:" + id, "{}");
                AutoReply.rememberSent(s, text);
                s.activity(ok ? "sent" : "failed", (ok ? "Scheduled " + how + " sent to " : "Scheduled message FAILED to ") + who + ": " + text);
                if (!ok) Smart.notify(c, 6800, "Scheduled message not sent", "To " + who + ": " + text, null);
            }
        });
    }

    static String waNumber(String n) {
        String num = n.replaceAll("[^0-9+]", "");
        if (num.startsWith("0")) num = num.substring(1);
        if (!num.startsWith("+")) num = (num.length() == 10 ? "91" : "") + num;
        return num.replace("+", "");
    }

    // ================================================================ contacts ======
    public static String saveContact(Context c, String name, String number) {
        try {
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build());
            c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            new Store(c).activity("contacts", "Saved contact " + name + " " + number);
            return "Saved " + name + " (" + number + ") in your contacts.";
        } catch (SecurityException e) {
            return "I need permission to edit contacts first. Allow it in Setup, Arsh.";
        } catch (Exception e) {
            return "I couldn't save the contact: " + e.getMessage();
        }
    }

    // ================================================================ calendar auto-silent ======
    static final Pattern BUSY = Pattern.compile("(?i)\\b(meeting|class|lecture|interview|exam|test|call|appointment|session|seminar|webinar|presentation|zoom|teams|meet)\\b");

    /** Puts Meeting mode on at the start of matching calendar events in the next 40 minutes, and off at the end. */
    public static void calendarSilent(Context c) {
        Store s = new Store(c);
        if (!Smart.on(s, "cal_silent", true)) return;
        JSONArray ev = Agenda.events(c, System.currentTimeMillis() - 60000, System.currentTimeMillis() + 40 * 60000L);
        for (int i = 0; i < ev.length(); i++) {
            JSONObject e = ev.optJSONObject(i);
            if (e.optBoolean("allDay") || !BUSY.matcher(e.optString("title")).find()) continue;
            String k = "calsil:" + AutoReply.sha(e.optString("title") + e.optLong("begin"));
            if (!s.get(k, "").isEmpty()) continue;
            s.put(k, "1");
            Agenda.at(c, Math.max(System.currentTimeMillis() + 5000, e.optLong("begin")), Agenda.alarmIntent(c, "CALSIL_ON", e.optString("title")));
            Agenda.at(c, e.optLong("end"), Agenda.alarmIntent(c, "CALSIL_OFF", e.optString("title")));
            s.activity("routine", "Meeting mode will turn on for \"" + e.optString("title") + "\" from " + Agenda.NICE.format(new Date(e.optLong("begin"))));
        }
    }

    public static void calSilentOn(Context c, String title) {
        Store s = new Store(c);
        if (!Routines.active(s).isEmpty()) return;                         // never override a routine Arsh started
        Routines.start(c, "meeting", "calendar: " + title);
        s.put("calsil_active", "1");
    }

    public static void calSilentOff(Context c) {
        Store s = new Store(c);
        if ("1".equals(s.get("calsil_active", "")) && Routines.active(s).equals("meeting")) Routines.stop(c, "calendar event ended");
        s.put("calsil_active", "");
    }

    // ================================================================ news, cricket, currency ======
    static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(12000);
        h.setRequestProperty("User-Agent", "Mozilla/5.0 ULTRON");
        h.setInstanceFollowRedirects(true);
        InputStream in = h.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    static List<String> rssTitles(String xml, int max) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<item>.*?<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", Pattern.DOTALL).matcher(xml);
        while (m.find() && out.size() < max) {
            String t = m.group(1).replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Top headlines (Google News, India) — optionally on a topic. */
    public static List<String> headlines(String topic, int max) throws Exception {
        String url = topic == null || topic.trim().isEmpty() ? "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"
            : "https://news.google.com/rss/search?hl=en-IN&gl=IN&ceid=IN:en&q=" + java.net.URLEncoder.encode(topic.trim(), "UTF-8");
        return rssTitles(get(url), max);
    }

    public static String cricket() {
        try {
            List<String> live = rssTitles(get("https://static.cricinfo.com/rss/livescores.xml"), 8);
            if (!live.isEmpty()) return "Live and recent cricket: " + android.text.TextUtils.join("; ", live) + ".";
        } catch (Exception ignored) {
        }
        try {
            List<String> h = headlines("cricket score", 4);
            if (!h.isEmpty()) return "Latest cricket news: " + android.text.TextUtils.join("; ", h) + ".";
        } catch (Exception ignored) {
        }
        return "I couldn't get cricket scores right now, Arsh.";
    }

    static final String[][] CUR = {{"dollar", "USD"}, {"usd", "USD"}, {"rupee", "INR"}, {"inr", "INR"}, {"euro", "EUR"}, {"eur", "EUR"}, {"pound", "GBP"},
        {"gbp", "GBP"}, {"dirham", "AED"}, {"aed", "AED"}, {"riyal", "SAR"}, {"sar", "SAR"}, {"yen", "JPY"}, {"taka", "BDT"}, {"canadian dollar", "CAD"},
        {"australian dollar", "AUD"}, {"singapore dollar", "SGD"}, {"dinar", "KWD"}};

    static String code(String w) {
        String x = w.toLowerCase(Locale.ROOT).trim();
        for (String[] c : CUR) if (x.startsWith(c[0])) return c[1];
        return x.length() == 3 ? x.toUpperCase(Locale.ROOT) : "";
    }

    public static String convert(double amount, String from, String to) {
        String f = code(from), t = code(to);
        if (f.isEmpty() || t.isEmpty()) return "";
        try {
            JSONObject r = new JSONObject(get("https://open.er-api.com/v6/latest/" + f)).getJSONObject("rates");
            if (!r.has(t)) return "";
            double v = amount * r.getDouble(t);
            return String.format(Locale.ENGLISH, "%,.2f %s is about %,.2f %s (live rate).", amount, f, v, t);
        } catch (Exception e) {
            return "I couldn't get live exchange rates right now.";
        }
    }

    // ================================================================ wake-up routine ======
    public static void scheduleWake(Context c) {
        Store s = new Store(c);
        PendingIntent p = Agenda.alarmIntent(c, "WAKEUP", "daily");
        String t = s.get("wake_time", "");
        if (!t.matches("\\d{1,2}:\\d{2}")) {
            Agenda.cancel(c, p);
            return;
        }
        Agenda.at(c, Agenda.nextTime(t), p);
    }

    /** Alarm sound until Arsh taps; then ULTRON says good morning with the weather and the briefing. */
    public static void fireWake(Context c) {
        Store s = new Store(c);
        if (!s.get("wake_daily", "1").equals("1")) s.put("wake_time", "");
        scheduleWake(c);
        try {
            Intent open = new Intent(c, MainActivity.class).putExtra("say", "wakeup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent tap = PendingIntent.getActivity(c, 9200, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
            Notification.Builder b = Approvals.builder(c, Approvals.EMERGENCY_CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("☀️ Good morning, Arsh").setContentText("Tap to stop the alarm and hear your morning briefing.")
                .setCategory(Notification.CATEGORY_ALARM).setPriority(Notification.PRIORITY_MAX).setAutoCancel(true)
                .setContentIntent(tap).setFullScreenIntent(tap, true).setColor(0xFFFF1728)
                .addAction(new Notification.Action.Builder(0, "I'M UP ▶", tap).build());
            Notification n = b.build();
            n.flags |= Notification.FLAG_INSISTENT;                          // keeps ringing until tapped
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(9200, n);
        } catch (Exception ignored) {
        }
        s.activity("routine", "Wake-up alarm rang");
        if (Routines.active(s).equals("sleep")) Routines.stop(c, "wake-up alarm");
    }

    public static String wakeText(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(9200);
        return Agenda.briefing(c, true);
    }

    // ================================================================ settings ======
    public static JSONObject info(Store s) throws Exception {
        JSONObject al = new JSONObject();
        for (String r : RELATIONS) if (!s.get("alias:" + r, "").isEmpty()) al.put(r, s.get("alias:" + r, ""));
        JSONArray lists = new JSONArray();
        for (String l : s.get("lists", "").split(",")) if (!l.isEmpty()) lists.put(new JSONObject().put("name", l).put("items", items(s, l)));
        return new JSONObject().put("announce", Smart.on(s, "announce_calls", true)).put("findWord", s.get("find_word", "").isEmpty() ? "" : "set")
            .put("calSilent", Smart.on(s, "cal_silent", true)).put("news", Smart.on(s, "news_brief", true))
            .put("wakeTime", s.get("wake_time", "")).put("wakeDaily", s.get("wake_daily", "1").equals("1")).put("aliases", al).put("lists", lists)
            .put("notes", Smart.list(s, "notes").length());
    }

    public static void save(Context c, JSONObject in) {
        Store s = new Store(c);
        if (in.has("announce")) s.put("announce_calls", in.optBoolean("announce") ? "1" : "0");
        if (in.has("calSilent")) s.put("cal_silent", in.optBoolean("calSilent") ? "1" : "0");
        if (in.has("news")) s.put("news_brief", in.optBoolean("news") ? "1" : "0");
        if (in.has("findWord")) {
            String w = in.optString("findWord").trim();
            if (w.isEmpty() || w.length() >= 4) {
                s.put("find_word", w);
                s.activity("security", w.isEmpty() ? "Find my phone switched off" : "Find my phone secret word set");
            }
        }
        if (in.has("wakeTime")) s.put("wake_time", in.optString("wakeTime").matches("\\d{1,2}:\\d{2}") ? in.optString("wakeTime") : "");
        if (in.has("wakeDaily")) s.put("wake_daily", in.optBoolean("wakeDaily") ? "1" : "0");
        scheduleWake(c);
    }
}
