package com.arsh.ultron;

import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Advanced ULTRON: day planner, life timeline, known places (and travel detection), theft alerts,
 * the study brain (your PDFs and notes), and files / clipboard shared with the PC.
 */
public class Advanced {
    // =========================================================== day planner ======
    public static String planDay(Context c, boolean next) {
        Store s = new Store(c);
        StringBuilder f = new StringBuilder();
        f.append("Now: ").append(new SimpleDateFormat("EEEE h:mm a", Locale.ENGLISH).format(new Date())).append("\n");
        long now = System.currentTimeMillis();
        Calendar end = Calendar.getInstance();
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        f.append("Calendar: ").append(Agenda.describeEvents(Agenda.events(c, now, end.getTimeInMillis()))).append("\n");
        JSONArray r = Agenda.upcomingReminders(s, end.getTimeInMillis());
        f.append("Reminders: ");
        for (int i = 0; i < r.length(); i++) f.append(new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(r.optJSONObject(i).optLong("when")))).append(" ").append(r.optJSONObject(i).optString("what")).append("; ");
        f.append("\n");
        int waiting = 0;
        StringBuilder who = new StringBuilder();
        JSONArray a = AutoReply.Events.all(s);
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && "personal".equals(e.optString("kind")) && ("attention".equals(e.optString("status")) || "awaiting_approval".equals(e.optString("status")))) {
                waiting++;
                if (who.indexOf(e.optString("who")) < 0) who.append(e.optString("who")).append(", ");
            }
        }
        f.append("People waiting for a reply: ").append(waiting).append(who.length() > 0 ? " (" + who + ")" : "").append("\n");
        for (String l : s.get("lists", "").split(",")) if (!l.isEmpty()) f.append(Daily.readList(s, l)).append("\n");
        try {
            f.append("Weather: ").append(Weather.facts(Weather.home(s), 1)).append("\n");
        } catch (Exception ignored) {
        }
        JSONArray q = Smart.list(s, "quiz_log");
        if (q.length() > 0) f.append("Last study quiz: ").append(q.optJSONObject(q.length() - 1).optString("topic")).append(" ")
            .append(q.optJSONObject(q.length() - 1).optInt("score")).append("/").append(q.optJSONObject(q.length() - 1).optInt("of")).append("\n");
        f.append("Routine: ").append(Routines.active(s).isEmpty() ? "none" : Routines.active(s)).append("\n");
        try {
            return AutoReply.clean(new Brain(s).quick((next
                ? "Tell Arsh the ONE best thing to do next and why, in 1 to 2 spoken sentences."
                : "Plan the rest of Arsh's day as a short spoken plan (4 to 7 sentences, time-ordered): fixed events and reminders first, then replies he owes, "
                + "errands from his lists, study time, breaks, and a weather tip. Realistic, kind, no lists, no emojis.")
                + " Use ONLY these facts:\n" + f, "You are ULTRON, Arsh's planner."));
        } catch (Exception e) {
            return "Here's what's on today: " + f.toString().replace("\n", ". ");
        }
    }

    // =========================================================== places ======
    public static JSONArray places(Store s) {
        return Smart.list(s, "places");
    }

    public static String namePlace(Context c, String name) {
        Store s = new Store(c);
        Location l = fix(c);
        if (l == null) return "I couldn't get your location. Turn on Location and allow ULTRON to use it, Arsh.";
        try {
            JSONArray a = places(s), keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!a.optJSONObject(i).optString("name").equalsIgnoreCase(name)) keep.put(a.opt(i));
            keep.put(new JSONObject().put("name", name.toLowerCase(Locale.ROOT)).put("lat", l.getLatitude()).put("lon", l.getLongitude()));
            s.putSecret("places", keep.toString());
            s.activity("places", "Saved this place as " + name);
            schedulePlaces(c);
            return "Saved this spot as your " + name + ". " + placeHint(name);
        } catch (Exception e) {
            return "I couldn't save the place.";
        }
    }

    static String placeHint(String name) {
        if (name.matches("(?i)college|school|class|coaching|library|university")) return "When you arrive here I'll switch on Study mode, and turn it off when you leave.";
        if (name.matches("(?i)office|work")) return "When you arrive here I'll switch on Meeting mode if you have a meeting.";
        if (name.matches("(?i)home|house|ghar")) return "When you get home I'll end Driving mode.";
        return "I'll note when you're here in your timeline.";
    }

    static Location fix(Context c) {
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
            if (best != null && System.currentTimeMillis() - best.getTime() < 10 * 60000L) return best;
            String link = Daily.locationLink(c);                       // asks for a fresh fix
            if (link.isEmpty()) return best;
            String[] p = link.substring(link.indexOf("q=") + 2).split(",");
            Location l = new Location("ultron");
            l.setLatitude(Double.parseDouble(p[0]));
            l.setLongitude(Double.parseDouble(p[1]));
            l.setTime(System.currentTimeMillis());
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    public static void schedulePlaces(Context c) {
        // location smarts are switched off (Arsh chose not to have them)
        if (true) return;
        Store s = new Store(c);
        if (!Smart.on(s, "places_on", false) || places(s).length() == 0) return;
        Agenda.at(c, System.currentTimeMillis() + 15 * 60000L, Agenda.alarmIntent(c, "PLACES", "tick"));
    }

    /** Every 15 minutes: where is Arsh? Arriving / leaving known places, travelling fast. */
    public static void placesTick(final Context c) {
        schedulePlaces(c);
        AutoReply.pool.execute(new Runnable() {
            public void run() {
                Store s = new Store(c);
                Location l = fix(c);
                if (l == null) return;
                String here = "";
                JSONArray a = places(s);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject p = a.optJSONObject(i);
                    float[] d = new float[1];
                    Location.distanceBetween(l.getLatitude(), l.getLongitude(), p.optDouble("lat"), p.optDouble("lon"), d);
                    if (d[0] < 180) here = p.optString("name");
                }
                String was = s.get("place_now", "");
                // speed from the previous fix
                try {
                    String[] prev = s.get("place_fix", "").split(",");
                    if (prev.length == 3) {
                        float[] d = new float[1];
                        Location.distanceBetween(Double.parseDouble(prev[0]), Double.parseDouble(prev[1]), l.getLatitude(), l.getLongitude(), d);
                        double hours = (l.getTime() - Long.parseLong(prev[2])) / 3600000.0;
                        double kmh = hours > 0.05 ? d[0] / 1000.0 / hours : 0;
                        if (kmh > 25 && Routines.active(s).isEmpty() && Smart.once(s, "travel", 2 * 3600000L)) {
                            Smart.notify(c, 6950, "🚗 Looks like you're travelling", "Start Driving mode? ULTRON will read messages aloud and tell people you're on the road.",
                                PendingIntent.getBroadcast(c, 6951, new Intent(c, AgendaReceiver.class).setAction("com.arsh.ultron.ROUTINE_START").putExtra("id", "driving"),
                                    PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
                        }
                    }
                } catch (Exception ignored) {
                }
                s.put("place_fix", l.getLatitude() + "," + l.getLongitude() + "," + l.getTime());
                if (here.equals(was)) return;
                s.put("place_now", here);
                if (!was.isEmpty()) {
                    visit(s, was, "left");
                    if (was.matches("college|school|class|coaching|library|university") && Routines.active(s).equals("study") && "1".equals(s.get("place_routine", "")))
                        Routines.stop(c, "left " + was);
                }
                if (!here.isEmpty()) {
                    visit(s, here, "arrived");
                    if (here.matches("home|house|ghar") && Routines.active(s).equals("driving")) Routines.stop(c, "arrived home");
                    if (here.matches("college|school|class|coaching|library|university") && Routines.active(s).isEmpty()) {
                        Routines.start(c, "study", "arrived at " + here);
                        s.put("place_routine", "1");
                    }
                }
            }
        });
    }

    static void visit(Store s, String place, String what) {
        try {
            Smart.push(s, "visits", new JSONObject().put("p", place).put("w", what).put("at", System.currentTimeMillis()), 600);
            s.activity("places", (what.equals("arrived") ? "Arrived at " : "Left ") + place);
        } catch (Exception ignored) {
        }
    }

    // =========================================================== life timeline ======
    public static String timeline(Context c, Brain brain, String question) {
        Store s = new Store(c);
        long day;
        try {
            String raw = brain.quick("Today is " + new SimpleDateFormat("EEEE yyyy-MM-dd", Locale.ENGLISH).format(new Date()) + ". Which date does this ask about: \""
                + question + "\"? Answer ONLY yyyy-MM-dd.", "Output only the date.");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(raw);
            day = m.find() ? new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(m.group()).getTime() : startOfDay(-1);
        } catch (Exception e) {
            day = startOfDay(-1);
        }
        long end = day + 86400000L;
        SimpleDateFormat t = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        StringBuilder f = new StringBuilder();
        JSONArray v = Smart.list(s, "visits");
        for (int i = 0; i < v.length(); i++) { JSONObject x = v.optJSONObject(i); if (x.optLong("at") >= day && x.optLong("at") < end) f.append(t.format(new Date(x.optLong("at")))).append(" ").append(x.optString("w")).append(" ").append(x.optString("p")).append("\n"); }
        JSONArray cl = Calls.log(s);
        for (int i = 0; i < cl.length(); i++) { JSONObject x = cl.optJSONObject(i); if (x.optLong("at") >= day && x.optLong("at") < end && !"ringing".equals(x.optString("status"))) f.append(t.format(new Date(x.optLong("at")))).append(" call ").append(x.optString("status")).append(" with ").append(x.optString("who")).append(" (").append(x.optString("app")).append(")\n"); }
        JSONArray ar = Smart.list(s, "chat_archive");
        java.util.Map<String, Integer> talked = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ar.length(); i++) { JSONObject x = ar.optJSONObject(i); if (x.optLong("at") >= day && x.optLong("at") < end) { String k = x.optString("w") + " (" + x.optString("a") + ")"; talked.put(k, (talked.containsKey(k) ? talked.get(k) : 0) + 1); } }
        if (!talked.isEmpty()) f.append("Chats: ").append(talked.toString()).append("\n");
        f.append("Calendar: ").append(Agenda.describeEvents(Agenda.events(c, day, end))).append("\n");
        JSONArray n = Smart.list(s, "notes");
        for (int i = 0; i < n.length(); i++) if (n.optJSONObject(i).optLong("at") >= day && n.optJSONObject(i).optLong("at") < end) f.append("Note: ").append(n.optJSONObject(i).optString("t")).append("\n");
        JSONArray mo = Smart.list(s, "money");
        double spent = 0;
        for (int i = 0; i < mo.length(); i++) if (mo.optJSONObject(i).optLong("at") >= day && mo.optJSONObject(i).optLong("at") < end && "debit".equals(mo.optJSONObject(i).optString("type"))) spent += mo.optJSONObject(i).optDouble("amt");
        if (spent > 0) f.append("Spent: ₹").append(Math.round(spent)).append("\n");
        JSONArray sl = Smart.list(s, "sleep_log");
        for (int i = 0; i < sl.length(); i++) if (sl.optJSONObject(i).optLong("to") >= day && sl.optJSONObject(i).optLong("to") < end) f.append("Woke up (sleep mode off) at ").append(t.format(new Date(sl.optJSONObject(i).optLong("to")))).append("\n");
        String label = new SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH).format(new Date(day));
        if (f.length() < 30) return "I don't have much recorded for " + label + ", Arsh. I remember days from when ULTRON started tracking them.";
        try {
            return AutoReply.clean(brain.quick("Tell Arsh the story of his " + label + " in 3 to 6 spoken sentences, in time order, from ONLY these records:\n" + f
                + "\nAnswer his question: \"" + question + "\". Don't invent anything.", "You are ULTRON, telling Arsh about his day."));
        } catch (Exception e) {
            return label + ": " + f.toString().replace("\n", ". ");
        }
    }

    static long startOfDay(int addDays) {
        Calendar k = Calendar.getInstance();
        k.add(Calendar.DAY_OF_MONTH, addDays);
        k.set(Calendar.HOUR_OF_DAY, 0);
        k.set(Calendar.MINUTE, 0);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    // =========================================================== theft alert ======
    /** Which SIM(s) are in the phone right now (carrier names; no protected identifiers). */
    static String simPrint(Context c) {
        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            StringBuilder b = new StringBuilder(tm.getSimState() + ":" + tm.getSimOperator() + ":" + tm.getSimOperatorName());
            try {
                android.telephony.SubscriptionManager sm = (android.telephony.SubscriptionManager) c.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                java.util.List<android.telephony.SubscriptionInfo> l = sm.getActiveSubscriptionInfoList();
                if (l != null) for (android.telephony.SubscriptionInfo i : l) b.append("|").append(i.getSimSlotIndex()).append(":").append(i.getCarrierName()).append(":").append(i.getNumber());
            } catch (Exception ignored) {
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Called on start-up and when the SIM changes: a different SIM -> alert to Arsh's trusted number with the location. */
    public static void simCheck(final Context c) {
        final Store s = new Store(c);
        if (!Smart.on(s, "theft_on", true)) return;
        final String now = simPrint(c), was = s.get("sim_print", "");
        if (now.isEmpty() || now.startsWith("1:")) return;                  // no SIM / still loading
        if (was.isEmpty()) { s.put("sim_print", now); return; }
        if (was.equals(now)) return;
        s.put("sim_print", now);
        AutoReply.pool.execute(new Runnable() {
            public void run() {
                String link = Daily.locationLink(c);
                String msg = Owner.HEAD + "⚠️ SIM CHANGED on Arsh's phone. SIM now: " + now.replaceAll("^\\d+:", "").replace("|", " / ")
                    + (link.isEmpty() ? "" : ". Location: " + link) + ". If this wasn't you, the phone may be stolen.";
                if (!Owner.samePhone(s)) Owner.sms(c, s, msg);
                Smart.notify(c, 6960, "SIM changed", "ULTRON noticed a different SIM and alerted your trusted number.", null);
                s.activity("security", "SIM change detected: alert sent to the owner number");
            }
        });
    }

    /** Phone switching off while locked: last-second SMS with the location (if enabled). */
    public static void shuttingDown(Context c) {
        Store s = new Store(c);
        if (!Smart.on(s, "theft_power", false) || !AutoReply.locked(c) || Owner.samePhone(s)) return;
        String link = "";
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null) link = String.format(Locale.ROOT, " Last location: https://maps.google.com/?q=%.6f,%.6f", l.getLatitude(), l.getLongitude());
        } catch (Exception ignored) {
        }
        Owner.sms(c, s, Owner.HEAD + "Arsh's phone is being switched off while locked." + link);
    }

    // =========================================================== study brain ======
    public static JSONArray docs(Store s) {
        return Smart.list(s, "docs");
    }

    /** Reads a PDF / image / text file Arsh picked, turns it into text with the AI, and keeps it for questions. */
    public static String importDoc(Context c, Uri uri) {
        Store s = new Store(c);
        try {
            String name = "document";
            Cursor cur = c.getContentResolver().query(uri, new String[]{"_display_name"}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) name = cur.getString(0);
                } finally {
                    cur.close();
                }
            }
            String mime = c.getContentResolver().getType(uri);
            InputStream in = c.getContentResolver().openInputStream(uri);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 15 * 1024 * 1024) throw new Exception("file is bigger than 15 MB");
            }
            in.close();
            String text;
            if (mime != null && mime.startsWith("text")) text = b.toString("UTF-8");
            else text = new Brain(s).readDocument(b.toByteArray(), mime == null ? "application/pdf" : mime,
                "Transcribe ALL the text of this study material faithfully (headings, definitions, formulas, examples). Keep chapter / topic headings. "
                    + "For diagrams, describe them in one line. No commentary.");
            text = text.trim();
            if (text.length() < 50) return "I couldn't read any text from " + name + ".";
            String id = AutoReply.sha(name + text.length());
            File d = new File(c.getFilesDir(), "docs");
            d.mkdirs();
            s.putSecret("doc:" + id, Memory.clip(text, 150000));
            Smart.push(s, "docs", new JSONObject().put("id", id).put("name", name).put("chars", text.length()).put("at", System.currentTimeMillis()), 30);
            s.activity("study", "Added to the study brain: " + name + " (" + text.length() + " characters)");
            return "I've read " + name + ". Ask me anything from it, Arsh: explain a topic, make questions, or quiz you.";
        } catch (Exception e) {
            return "I couldn't read that file: " + e.getMessage() + ". It needs a Gemini or Claude key.";
        }
    }

    /** The parts of Arsh's documents that best match the question. */
    static String relevant(Store s, String question, int maxChars) {
        JSONArray d = docs(s);
        String[] words = question.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").split("\\s+");
        java.util.List<Object[]> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < d.length(); i++) {
            String text = s.getSecret("doc:" + d.optJSONObject(i).optString("id"), "");
            String name = d.optJSONObject(i).optString("name");
            for (int p = 0; p < text.length(); p += 1500) {
                String ch = text.substring(p, Math.min(text.length(), p + 1800));
                String low = ch.toLowerCase(Locale.ROOT);
                int score = 0;
                for (String w : words) if (w.length() > 3 && low.contains(w)) score += 2;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)chapter\\s*(\\d+)").matcher(question);
                if (m.find() && low.matches("(?s).*chapter\\s*" + m.group(1) + "\\b.*")) score += 10;
                chunks.add(new Object[]{score, "[" + name + "] " + ch});
            }
        }
        java.util.Collections.sort(chunks, new java.util.Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) { return (Integer) b[0] - (Integer) a[0]; }
        });
        StringBuilder out = new StringBuilder();
        for (Object[] ch : chunks) {
            if (out.length() > maxChars) break;
            out.append(ch[1]).append("\n---\n");
        }
        return out.toString();
    }

    public static String askDocs(Context c, Brain brain, String question) {
        Store s = new Store(c);
        if (docs(s).length() == 0) return "Your study brain is empty, Arsh. Open ULTRON+ and tap ADD NOTES OR PDF.";
        String ctx = relevant(s, question, 9000);
        try {
            return AutoReply.clean(brain.quick("Arsh's study material:\n" + ctx + "\n\nArsh asks: \"" + question + "\". Answer like a great teacher, using his "
                + "material first (say if something is not in it). Clear, step by step, spoken style; if he asks for questions, give numbered questions "
                + "with answers at the end.", "You are ULTRON, Arsh's tutor."));
        } catch (Exception e) {
            return "I couldn't answer from your notes right now.";
        }
    }

    // =========================================================== files + clipboard with the PC ======
    /** Uploads a file (from the share menu or "send my last screenshot to my PC") to the paired PC. */
    public static String sendToPc(Context c, Uri uri, String name) {
        Store s = new Store(c);
        if (!PcLink.paired(s)) return "Pair with your PC first (ULTRON+ › Phone + PC).";
        try {
            long size = -1;
            Cursor cur = c.getContentResolver().query(uri, new String[]{"_size"}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst() && !cur.isNull(0)) size = cur.getLong(0);
                } finally {
                    cur.close();
                }
            }
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (size <= 0) {                                                    // unknown size: read it first (up to 100 MB)
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] bb = new byte[65536];
                int k;
                while ((k = in.read(bb)) > 0) {
                    b.write(bb, 0, k);
                    if (b.size() > 100 * 1024 * 1024) throw new Exception("file bigger than 100 MB");
                }
                in.close();
                in = new java.io.ByteArrayInputStream(b.toByteArray());
                size = b.size();
            }
            HttpURLConnection h = (HttpURLConnection) new URL("http://" + PcLink.addr(s) + "/phone/file").openConnection();
            h.setConnectTimeout(5000);
            h.setReadTimeout(120000);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setFixedLengthStreamingMode(size);
            h.setRequestProperty("X-Ultron-Phone", s.getSecret("pc_token", ""));
            h.setRequestProperty("X-Name", Uri.encode(name == null ? "file" : name));
            h.setRequestProperty("Content-Type", "application/octet-stream");
            OutputStream o = h.getOutputStream();
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                total += n;
                if (total > 500L * 1024 * 1024) throw new Exception("file bigger than 500 MB");
            }
            o.close();
            in.close();
            int code = h.getResponseCode();
            if (code != 200) return "The PC didn't accept the file (error " + code + "). Update the PC ULTRON.";
            s.activity("pc", "Sent " + name + " to the PC");
            return "Sent " + name + " to your PC (Downloads › ULTRON from phone).";
        } catch (Exception e) {
            return "I couldn't send it to the PC: " + e.getMessage();
        }
    }

    /** Newest photo / screenshot / download on the phone: {uri, name}. */
    public static Object[] latest(Context c, String kind) {
        try {
            Uri base = kind.equals("download") ? Uri.parse("content://media/external/downloads") : android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String sel = kind.equals("screenshot") ? "relative_path LIKE ?" : null;
            String[] args = kind.equals("screenshot") ? new String[]{"%Screenshot%"} : null;
            Cursor cur = c.getContentResolver().query(base, new String[]{"_id", "_display_name"}, sel, args, "date_added DESC");
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) return new Object[]{android.content.ContentUris.withAppendedId(base, cur.getLong(0)), cur.getString(1)};
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** A file the PC sent: saved in Downloads/ULTRON and announced. */
    public static String receiveFromPc(Context c, Store s, String path, String name) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("http://" + PcLink.addr(s) + path).openConnection();
            h.setRequestProperty("X-Ultron-Phone", s.getSecret("pc_token", ""));
            h.setConnectTimeout(5000);
            h.setReadTimeout(120000);
            InputStream in = h.getInputStream();
            ContentValues v = new ContentValues();
            v.put("_display_name", name);
            v.put("relative_path", "Download/ULTRON");
            Uri u = c.getContentResolver().insert(Uri.parse("content://media/external/downloads"), v);
            OutputStream o = c.getContentResolver().openOutputStream(u);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            o.close();
            in.close();
            Intent open = new Intent(Intent.ACTION_VIEW).setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            Smart.notify(c, 6970, "📥 File from your PC", name + " is in Downloads › ULTRON. Tap to open.",
                PendingIntent.getActivity(c, 6970, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
            s.activity("pc", "Received " + name + " from the PC");
            return "saved";
        } catch (Exception e) {
            return "failed: " + e.getMessage();
        }
    }

    public static String clipToPc(Context c, String text) {
        Store s = new Store(c);
        if (!PcLink.paired(s)) return "Pair with your PC first (ULTRON+ › Phone + PC).";
        if (text == null || text.isEmpty()) return "Your clipboard is empty, Arsh.";
        try {
            PcLink.post(s, "/phone/clip", new JSONObject().put("text", text), 10);
            return "Copied to your PC's clipboard. Press Ctrl+V on the PC.";
        } catch (Exception e) {
            return "I couldn't reach the PC: " + e.getMessage();
        }
    }

    // =========================================================== settings ======
    public static JSONObject info(Store s) throws Exception {
        return new JSONObject().put("docs", docs(s)).put("places", places(s)).put("placesOn", false)
            .put("theft", Smart.on(s, "theft_on", true)).put("theftPower", Smart.on(s, "theft_power", false)).put("convo", Smart.on(s, "convo_auto", false))
            .put("voiceLang", s.get("voice_lang", "auto")).put("lockedApps", s.get("locked_apps", "")).put("intruders", Smart.list(s, "intruders"));
    }

    public static void save(Context c, JSONObject in) {
        Store s = new Store(c);
        String[][] m = {{"placesOn", "places_on"}, {"theft", "theft_on"}, {"theftPower", "theft_power"}};
        for (String[] x : m) if (in.has(x[0])) s.put(x[1], in.optBoolean(x[0]) ? "1" : "0");
        if (in.has("voiceLang") && in.optString("voiceLang").matches("auto|en|hi")) s.put("voice_lang", in.optString("voiceLang"));
        if (in.has("lockedApps")) s.put("locked_apps", in.optString("lockedApps").replaceAll("[^a-zA-Z0-9._,]", ""));
        if (in.has("forgetPlace")) Smart.list(s, "places");
        try {
            if (in.has("forgetPlace")) Learn.remove(s, "places", in.optInt("forgetPlace"));
            if (in.has("forgetDoc")) {
                JSONObject d = docs(s).optJSONObject(in.optInt("forgetDoc"));
                if (d != null) s.putSecret("doc:" + d.optString("id"), "");
                Learn.remove(s, "docs", in.optInt("forgetDoc"));
            }
        } catch (Exception ignored) {
        }
        schedulePlaces(c);
    }
}
