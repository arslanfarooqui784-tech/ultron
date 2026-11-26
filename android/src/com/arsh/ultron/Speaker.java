package com.arsh.ultron;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Speaks out loud when the app is closed (calls, reminders, messages).
 * Always ULTRON's own deep voice. If that voice can't be reached (no internet),
 * ULTRON shows the words as a notification instead of using another voice.
 */
public class Speaker {
    static TextToSpeech tts;
    static boolean ready = false;
    static final List<String> waiting = new ArrayList<>();
    static final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    static Thread worker;
    static MediaPlayer player;

    public static synchronized void say(Context c, final String text) {
        if (text == null || text.trim().isEmpty()) return;
        final Context app = c.getApplicationContext();
        queue.offer(text);
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(new Runnable() {
                public void run() {
                    while (true) {
                        String t;
                        try {
                            t = queue.poll(60, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (t == null) return;
                        // only ULTRON's own voice, never silent: keep retrying until his voice comes through
                        speakPatiently(app, t);
                    }
                }
            }, "ultron-speaker");
            worker.start();
        }
    }

    /** Tries quickly a few times, then keeps trying (up to 15 minutes) until the internet is back. */
    static void speakPatiently(Context c, String text) {
        long[] waits = {0, 1500, 3000, 5000};
        for (long w : waits) {
            if (w > 0) try { Thread.sleep(w); } catch (InterruptedException e) { return; }
            if (ultronVoice(c, text)) return;
        }
        Smart.notify(c, 6950, "ULTRON will say this when his voice is back", text, null);
        long end = System.currentTimeMillis() + 15 * 60 * 1000;
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(20000); } catch (InterruptedException e) { return; }
            if (ultronVoice(c, text)) {
                try {
                    ((android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(6950);
                } catch (Exception ignored) { }
                return;
            }
        }
    }

    /** Saves ULTRON's voice for common phrases so they play instantly, even offline. */
    public static void warmUp(final Context c) {
        new Thread(new Runnable() {
            public void run() {
                String[] common = {"I am listening, Arsh. Say a command.", "Okay Arsh, I'm all ears. Just talk to me, no need to tap. Say bye when you're done.",
                    "Talk to you later, Arsh.", "Good morning, Arsh.", "Good night, Arsh.", "Done, Arsh.", "Okay, Arsh.",
                    "Sorry Arsh, I couldn't do that.", "Sorry Arsh, I didn't catch that. Please say it again.", "Reminder, Arsh."};
                try {
                    Store s = new Store(c);
                    String key = s.fishKey();
                    if (key.isEmpty()) return;
                    Brain b = new Brain(s);
                    b.ctx = c;
                    for (String t : common) {
                        if (Learn.cachedVoice(c, t) == null) b.speakCached(t, key);
                    }
                } catch (Exception ignored) { }
            }
        }, "ultron-voice-warmup").start();
    }

    /** ULTRON's voice: cached phrases play instantly, new ones come from the voice service. */
    static boolean ultronVoice(Context c, String text) {
        try {
            Store s = new Store(c);
            String key = s.fishKey();
            byte[] mp3 = Learn.cachedVoice(c, text);
            if (mp3 == null) {
                if (key.isEmpty()) return false;
                Brain b = new Brain(s);
                b.ctx = c;
                mp3 = b.speakCached(text, key);
            }
            if (mp3 == null || mp3.length < 500) return false;
            File f = new File(c.getCacheDir(), "speak_now.mp3");
            FileOutputStream o = new FileOutputStream(f);
            o.write(mp3);
            o.close();
            return play(f);
        } catch (Exception e) {
            return false;
        }
    }

    /** Plays the file and waits until it finishes (so announcements don't overlap). */
    static boolean play(File f) {
        final Object done = new Object();
        final boolean[] finished = {false};
        try {
            synchronized (Speaker.class) {
                if (player != null) {
                    try { player.release(); } catch (Exception ignored) { }
                }
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(16) // USAGE_ASSISTANT (API 26)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                player.setDataSource(f.getAbsolutePath());
                player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    public void onCompletion(MediaPlayer mp) {
                        synchronized (done) { finished[0] = true; done.notifyAll(); }
                    }
                });
                player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        synchronized (done) { finished[0] = true; done.notifyAll(); }
                        return true;
                    }
                });
                player.prepare();
                player.start();
            }
            synchronized (done) {
                long end = System.currentTimeMillis() + 30000;
                while (!finished[0] && System.currentTimeMillis() < end) done.wait(500);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Backup: the phone's built-in voice. */
    static synchronized void phoneVoice(Context c, final String text) {
        if (tts == null) {
            waiting.add(text);
            tts = new TextToSpeech(c, new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    synchronized (Speaker.class) {
                        ready = status == TextToSpeech.SUCCESS;
                        if (ready) {
                            tts.setLanguage(new Locale("en", "IN"));
                            for (String w : waiting) tts.speak(w, TextToSpeech.QUEUE_ADD, null, "u" + w.hashCode());
                        }
                        waiting.clear();
                    }
                }
            });
            return;
        }
        if (ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + text.hashCode());
        else waiting.add(text);
    }

    public static synchronized void stop() {
        queue.clear();
        if (tts != null) tts.stop();
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
        }
    }
}
