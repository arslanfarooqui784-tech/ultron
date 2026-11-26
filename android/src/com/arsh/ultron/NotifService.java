package com.arsh.ultron;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/** Reads notifications (WhatsApp, Instagram, Telegram, SMS...) and can reply through their own Reply button. */
public class NotifService extends NotificationListenerService {
    static NotifService instance;

    public static class Note {
        public String pkg, app, title, text;
        public long when;
        public Notification.Action reply;
        public String raw = "", chatId = "";
        /** Exact names of the people (not Arsh) whose messages this notification shows. */
        public java.util.Set<String> senders = new java.util.HashSet<>();
        public android.app.PendingIntent open;   // opens this chat (used when the app has no Reply button)
    }

    /**
     * The conversation this notification belongs to.
     *
     * Root cause of the Instagram wrong-chat reply (v4.5-v5.0): ULTRON trusted an app's hidden notification
     * id (shortcut id) as a per-chat id and named the chat after the FIRST person ever seen with that id.
     * Instagram can reuse one id for different conversations, so person B's messages were filed under
     * person A's chat, mixed into A's context, and a reply could go out through the other person's notification.
     *
     * Now: an id is only trusted while every notification carrying it shows the SAME person. The moment it shows
     * a different person, the id is marked shared and never used again; every notification is then identified by
     * its own exact chat name. Different people are never merged, however similar their names are.
     */
    static String canonical(StatusBarNotification sbn, Notification n, String pkg, String title, Note note) {
        String id = "";
        try {
            Object sc = Notification.class.getMethod("getShortcutId").invoke(n);
            if (sc != null) id = sc.toString();
        } catch (Exception ignored) {
        }
        if (id.isEmpty() && sbn.getTag() != null && sbn.getTag().contains("@")) id = sbn.getTag();
        if (id.isEmpty() || id.toLowerCase(java.util.Locale.ROOT).contains("summary")) return title;
        String person = exact(title);
        if (person.isEmpty() || title.equalsIgnoreCase(label(pkg))) return title;
        String key = "cid2:" + pkg + "|" + sbn.getUser().hashCode() + "|" + id;
        android.content.Context c = instance;
        if (c == null) return title;
        synchronized (NotifService.class) {
            Store s = new Store(c);
            String known = s.get(key, "");
            if (known.equals("SHARED")) return title;                      // this id is not one conversation: never trust it
            if (known.isEmpty()) {
                s.put(key, title);
                note.chatId = id;
                return title;
            }
            if (exact(known).equals(person)) {                             // same person, maybe a "(2 messages)" variant
                note.chatId = id;
                return known;
            }
            s.put(key, "SHARED");
            s.activity("safety", note.app + " used one notification id for two different chats ('" + known + "' and '" + title
                + "'). ULTRON now keeps them strictly separate.");
            return title;
        }
    }

    /** A chat name reduced to its letters and digits, after removing "(2 messages)"-style counters. Exact comparison only. */
    public static String exact(String title) {
        return chatName(title == null ? "" : title).toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    android.content.BroadcastReceiver unlock, theft;

    @Override
    public void onListenerConnected() {
        instance = this;
        Calls.watch(this);
        Advanced.simCheck(this);
        if (theft == null) {
            theft = new android.content.BroadcastReceiver() {
                public void onReceive(android.content.Context c, Intent i) {
                    if (Intent.ACTION_SHUTDOWN.equals(i.getAction())) Advanced.shuttingDown(c.getApplicationContext());
                    else Advanced.simCheck(c.getApplicationContext());
                }
            };
            try {
                android.content.IntentFilter f = new android.content.IntentFilter("android.intent.action.SIM_STATE_CHANGED");
                f.addAction(Intent.ACTION_SHUTDOWN);
                registerReceiver(theft, f);
            } catch (Exception ignored) {
            }
        }
        if (unlock == null) {
            unlock = new android.content.BroadcastReceiver() {
                public void onReceive(android.content.Context c, Intent i) {
                    AutoReply.unlocked(c.getApplicationContext());
                    Owner.flushWhatsApp(c.getApplicationContext());
                }
            };
            try {
                registerReceiver(unlock, new android.content.IntentFilter(Intent.ACTION_USER_PRESENT));
            } catch (Exception ignored) {
            }
        }
        final NotifService me = this;
        AutoReply.pool.execute(new Runnable() {
            public void run() {
                AutoReply.recover(me);   // finish anything left after a crash, restart or reconnect (never sends twice)
            }
        });
    }

    public void onListenerDisconnected() {
        try {
            Owner.report(getApplicationContext(), "high", "ULTRON lost notification access",
                "Android disconnected ULTRON's notification reader, so new messages cannot be seen right now. ULTRON asked Android to reconnect. "
                + "If this repeats: Settings > Notifications > Device & app notifications > ULTRON: switch it off and on.");
        } catch (Exception ignored) {
        }
        if (instance == this) instance = null;
        rebind(this);   // ask Android to reconnect ULTRON's notification reader straight away
    }

    /** Asks Android to (re)connect the notification reader. */
    public static void rebind(android.content.Context c) {
        try {
            NotificationListenerService.class.getMethod("requestRebind", android.content.ComponentName.class)
                .invoke(null, new android.content.ComponentName(c, NotifService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        try {
            if (unlock != null) unregisterReceiver(unlock);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        instance = this;
        AutoReply.maybe(this, sbn);
    }

    public static boolean connected() {
        return instance != null;
    }

    static String label(String pkg) {
        switch (pkg) {
            case "com.whatsapp": return "WhatsApp";
            case "com.whatsapp.w4b": return "WhatsApp Business";
            case "com.instagram.android": return "Instagram";
            case "org.telegram.messenger": return "Telegram";
            case "com.snapchat.android": return "Snapchat";
            case "com.google.android.gm": return "Gmail";
            case "com.samsung.android.messaging":
            case "com.google.android.apps.messaging": return "Messages";
            case "com.facebook.orca": return "Messenger";
            case "com.facebook.katana": return "Facebook";
            case "com.linkedin.android": return "LinkedIn";
            case "com.google.android.youtube": return "YouTube";
            default:
                String[] p = pkg.split("\\.");
                String s = p[p.length - 1];
                return s.substring(0, 1).toUpperCase() + s.substring(1);
        }
    }

    /** Current notifications from apps (newest first). */
    public static List<Note> notes() {
        List<Note> out = new ArrayList<>();
        NotifService s = instance;
        if (s == null) return out;
        StatusBarNotification[] all;
        try {
            all = s.getActiveNotifications();
        } catch (Exception e) {
            return out;
        }
        if (all == null) return out;
        for (StatusBarNotification sbn : all) {
            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && n.actions == null) continue;
            Note note = toNote(sbn);
            if (note != null) out.add(note);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Note>() { public int compare(Note a, Note b) { return Long.compare(b.when, a.when); } });
        return out;
    }

    /** One notification as ULTRON sees it, or null for system or empty ones. */
    static Note toNote(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg.equals("com.arsh.ultron") || pkg.equals("android") || pkg.startsWith("com.android.systemui")
            || pkg.startsWith("com.samsung.android.app.smartcapture")) return null;
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        if (x == null) return null;
        Note note = new Note();
        note.pkg = pkg;
        note.app = label(pkg);
        CharSequence convo = x.getCharSequence("android.conversationTitle");
        note.title = chatName(convo != null ? convo.toString() : str(x.getCharSequence(Notification.EXTRA_TITLE)));
        note.raw = note.title;
        note.title = canonical(sbn, n, pkg, note.title, note);
        note.text = lastMessage(x);
        note.when = sbn.getPostTime();
        note.open = n.contentIntent;
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                RemoteInput[] ri = a.getRemoteInputs();
                if (ri != null && ri.length > 0) { note.reply = a; break; }
            }
        }
        if (note.title.isEmpty() && note.text.isEmpty()) return null;
        try {
            for (AutoReply.Msg m : AutoReply.messages(n, sbn.getPostTime(), note.title))
                if (!m.fromMe && m.sender != null && !exact(m.sender).isEmpty()) note.senders.add(exact(m.sender));
        } catch (Exception ignored) {
        }
        return note;
    }

    /**
     * The conversation's real name. Apps change the notification title as messages pile up
     * ("Arslan Airtel (2 messages)", "Arslan Airtel (3)", "Arslan Airtel · 4"): that must stay ONE chat.
     */
    static String chatName(String title) {
        String t = title == null ? "" : title.trim();
        for (int i = 0; i < 3; i++) {
            t = t.replaceAll("(?i)\\s*[\\(\\[]\\s*\\d+\\s*(new\\s+)?(messages?|msgs?|unread|notifications?)?\\s*[\\)\\]]\\s*$", "")
                .replaceAll("(?i)\\s*[·•:-]\\s*\\d+\\s*(new\\s+)?(messages?)?\\s*$", "")
                .replaceAll("(?i)^\\d+\\s+new\\s+messages?\\s+from\\s+", "")
                .replaceAll("[\\u200e\\u200f\\u202a-\\u202e]", "")
                .trim();
        }
        return t;
    }

    static String str(CharSequence c) {
        return c == null ? "" : c.toString().trim();
    }

    static String lastMessage(Bundle x) {
        try {
            Parcelable[] msgs = x.getParcelableArray("android.messages");
            if (msgs != null && msgs.length > 0) {
                StringBuilder s = new StringBuilder();
                int start = Math.max(0, msgs.length - 3);
                for (int i = start; i < msgs.length; i++) {
                    if (msgs[i] instanceof Bundle) {
                        Bundle m = (Bundle) msgs[i];
                        CharSequence t = m.getCharSequence("text");
                        if (t != null) s.append(s.length() > 0 ? " / " : "").append(t);
                    }
                }
                if (s.length() > 0) return s.toString();
            }
        } catch (Exception ignored) {
        }
        CharSequence big = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (big != null && big.length() > 0) return big.toString().trim();
        return str(x.getCharSequence(Notification.EXTRA_TEXT));
    }

    /** Sends a reply through the notification's own Reply button (like replying from the notification shade). */
    public static boolean sendReply(Note note, String message) {
        NotifService s = instance;
        if (s == null || note == null || note.reply == null) return false;
        try {
            RemoteInput[] inputs = note.reply.getRemoteInputs();
            Intent fill = new Intent();
            Bundle res = new Bundle();
            for (RemoteInput r : inputs) res.putCharSequence(r.getResultKey(), message);
            RemoteInput.addResultsToIntent(inputs, fill, res);
            PendingIntent pi = note.reply.actionIntent;
            pi.send(s, 0, fill);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
