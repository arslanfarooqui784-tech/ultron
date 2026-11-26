package com.arsh.ultron;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replies waiting for Arsh: approve, edit or reject. Shown in ULTRON and as a phone notification. */
public class Approvals {
    public static class Item {
        public int id;
        public int level;
        public String app, who, got, reply, reason, chatKey;
        public long at;
        public NotifService.Note note;
        public boolean silent;
    }

    static final Map<Integer, Item> items = new LinkedHashMap<>();
    static int next = 1;
    static final String CHANNEL = "ultron_approvals";
    static final String STATUS_CHANNEL = "ultron_status";
    static final String ATTENTION_CHANNEL = "ultron_attention";
    static final String EMERGENCY_CHANNEL = "ultron_emergency";

    public static synchronized Item add(Context c, NotifService.Note note, String chatKey, String got, String reply, int level, String reason) {
        return add(c, note, chatKey, got, reply, level, reason, false);
    }

    public static synchronized Item add(Context c, NotifService.Note note, String chatKey, String got, String reply, int level, String reason, boolean silent) {
        // one waiting reply per chat: a newer message replaces the older draft
        for (Item old : new ArrayList<>(items.values())) {
            if (old.chatKey.equals(chatKey)) {
                items.remove(old.id);
                cancel(c, old.id);
            }
        }
        Item it = new Item();
        it.id = next++;
        it.level = level;
        it.app = note.app;
        it.who = note.title;
        it.got = got;
        it.reply = reply;
        it.reason = reason;
        it.chatKey = chatKey;
        it.at = System.currentTimeMillis();
        it.note = note;
        items.put(it.id, it);
        it.silent = silent;
        notifyArsh(c, it);
        new Store(c).activity("approval", "Waiting for approval: reply to " + it.who + " on " + it.app + " (" + reason + ")");
        return it;
    }

    public static synchronized List<Item> list() {
        return new ArrayList<>(items.values());
    }

    public static synchronized JSONArray json() {
        JSONArray a = new JSONArray();
        for (Item it : items.values()) {
            try {
                a.put(new JSONObject().put("id", it.id).put("level", it.level).put("app", it.app).put("who", it.who)
                    .put("got", it.got).put("reply", it.reply).put("reason", it.reason).put("at", it.at));
            } catch (Exception ignored) {
            }
        }
        return a;
    }

    /** Sends the (possibly edited) reply. Returns a short result for Arsh. */
    public static String approve(Context c, int id, String edited) {
        Item it;
        synchronized (Approvals.class) {
            it = items.remove(id);
        }
        cancel(c, id);
        if (it == null) return "That approval is no longer waiting.";
        String text = edited != null && !edited.trim().isEmpty() ? edited.trim() : it.reply;
        Store s = new Store(c);
        NotifService.Note fresh = AutoReply.refreshed(it.chatKey);
        if (NotifService.sendReply(fresh != null ? fresh : it.note, text)) {
            if (edited != null && !edited.trim().isEmpty()) Smart.learnEdit(s, it.who, it.reply, edited.trim());
            AutoReply.approvedSent(s, it.chatKey, text);
            AutoReply.rememberSent(s, text);
            s.activity("sent", "Approved reply to " + it.who + " on " + it.app + ": " + text);
            return "Sent to " + it.who + " on " + it.app + ".";
        }
        s.activity("failed", "Could not send approved reply to " + it.who + " (notification closed)");
        return "I could not send it: the " + it.app + " notification has closed. Open the chat and reply there.";
    }

    public static String reject(Context c, int id) {
        Item it;
        synchronized (Approvals.class) {
            it = items.remove(id);
        }
        cancel(c, id);
        if (it == null) return "That approval is no longer waiting.";
        Store st = new Store(c);
        Smart.learnReject(st, it.who, it.reply);
        AutoReply.rejected(st, it.chatKey);
        st.activity("rejected", "Rejected reply to " + it.who + " on " + it.app);
        return "Rejected. I did not reply to " + it.who + ".";
    }

    public static synchronized int clear(Context c) {
        int n = items.size();
        for (Integer id : new ArrayList<>(items.keySet())) cancel(c, id);
        items.clear();
        return n;
    }

    // ---------- phone notifications ----------
    static void channels(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            Class<?> ch = Class.forName("android.app.NotificationChannel");
            Object approvals = ch.getConstructor(String.class, CharSequence.class, int.class)
                .newInstance(CHANNEL, "ULTRON approvals", 4 /* IMPORTANCE_HIGH */);
            Object status = ch.getConstructor(String.class, CharSequence.class, int.class)
                .newInstance(STATUS_CHANNEL, "ULTRON status", 2 /* IMPORTANCE_LOW */);
            Object attention = ch.getConstructor(String.class, CharSequence.class, int.class)
                .newInstance(ATTENTION_CHANNEL, "ULTRON: needs you", 4 /* IMPORTANCE_HIGH */);
            NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, attention);
            NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, approvals);
            NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, status);
            Object emerg = ch.getConstructor(String.class, CharSequence.class, int.class)
                .newInstance(EMERGENCY_CHANNEL, "ULTRON: EMERGENCY", 4 /* IMPORTANCE_HIGH */);
            try {
                android.media.AudioAttributes aa = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM).build();
                ch.getMethod("setSound", android.net.Uri.class, android.media.AudioAttributes.class)
                    .invoke(emerg, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM), aa);
                ch.getMethod("enableVibration", boolean.class).invoke(emerg, true);
                ch.getMethod("setBypassDnd", boolean.class).invoke(emerg, true);
            } catch (Exception ignored) {
            }
            NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, emerg);
        } catch (Exception ignored) {
        }
    }

    static Notification.Builder builder(Context c, String channel) {
        channels(c);
        try {
            return Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, channel);
        } catch (Exception e) {
            return new Notification.Builder(c);
        }
    }

    static PendingIntent action(Context c, String what, int id) {
        Intent i = new Intent(c, ApprovalReceiver.class).setAction("com.arsh.ultron." + what).putExtra("id", id);
        return PendingIntent.getBroadcast(c, id * 10 + what.length(), i, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* IMMUTABLE */);
    }

    static void notifyArsh(Context c, Item it) {
        try {
            Intent open = new Intent(c, MainActivity.class).putExtra("page", "control.html").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent tap = PendingIntent.getActivity(c, it.id * 10, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
            String title = (it.level >= 3 ? "RESTRICTED · " : "Approve? · ") + it.who + " (" + it.app + ")";
            Notification.Builder b = builder(c, it.silent ? STATUS_CHANNEL : CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText("ULTRON: " + it.reply)
                .setStyle(new Notification.BigTextStyle().bigText("They said: " + it.got + "\n\nULTRON wants to reply:\n" + it.reply
                    + "\n\nWhy approval: " + it.reason))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setColor(0xFFFF1728);
            if (it.level < 3) b.addAction(new Notification.Action.Builder(0, "APPROVE", action(c, "APPROVE", it.id)).build());
            b.addAction(new Notification.Action.Builder(0, "EDIT", tap).build());
            b.addAction(new Notification.Action.Builder(0, "REJECT", action(c, "REJECT", it.id)).build());
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(1000 + it.id, b.build());
        } catch (Exception ignored) {
        }
    }

    static int attentionId = 5000;

    /** Tells Arsh about something ULTRON must not answer itself (missed call, emergency, security, a question it could not answer). */
    public static void attention(Context c, String kind, String who, String app, String text, boolean silent) {
        try {
            Intent open = new Intent(c, MainActivity.class).putExtra("page", "control.html").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String head = kind.equals("emergency") ? "POSSIBLE EMERGENCY · " : kind.equals("missed_call") ? "Missed call · " : kind.equals("security") ? "Security · " : "Needs you · ";
            Notification n = builder(c, silent ? STATUS_CHANNEL : ATTENTION_CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(head + who + " (" + app + ")").setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text + "\n\nULTRON did not reply to this automatically."))
                .setContentIntent(PendingIntent.getActivity(c, attentionId, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000))
                .setAutoCancel(true).setColor(0xFFFF1728).build();
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(attentionId++, n);
        } catch (Exception ignored) {
        }
    }

    static void cancel(Context c, int id) {
        try {
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(1000 + id);
        } catch (Exception ignored) {
        }
    }

    /** Small notification showing ULTRON is watching (or stopped). */
    public static void status(Context c, boolean on, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (!on) {
                nm.cancel(999);
                return;
            }
            Intent open = new Intent(c, MainActivity.class).putExtra("page", "control.html").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Notification n = builder(c, STATUS_CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("ULTRON is active")
                .setContentText(text).setOngoing(true).setColor(0xFFFF1728)
                .setContentIntent(PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000))
                .addAction(new Notification.Action.Builder(0, "STOP", action(c, "STOP", 0)).build())
                .build();
            nm.notify(999, n);
        } catch (Exception ignored) {
        }
    }
}
