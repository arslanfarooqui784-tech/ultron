package com.arsh.ultron;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    static final String HOME = "https://ultron.app/";
    static final String[] PERMS = {"android.permission.RECORD_AUDIO", "android.permission.CALL_PHONE",
        "android.permission.ANSWER_PHONE_CALLS", "android.permission.READ_PHONE_STATE", "android.permission.SEND_SMS",
        "android.permission.READ_SMS", "android.permission.READ_CONTACTS", "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_CALL_LOG", "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR",
        "android.permission.READ_MEDIA_IMAGES", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.WRITE_CONTACTS", "android.permission.CAMERA"};

    Store store;
    Brain brain;
    Actions actions;
    Memory memory;
    WebView web;
    final ExecutorService pool = Executors.newCachedThreadPool();
    final Handler ui = new Handler(Looper.getMainLooper());
    volatile boolean unlocked = false;
    long hiddenAt = 0;
    int fails = 0;
    long lockedUntil = 0;
    SpeechRecognizer recognizer;
    String listenId;
    TextToSpeech tts;
    boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(this);
        brain = new Brain(store);
        Speaker.warmUp(this);
        NotifService.rebind(this);
        memory = new Memory(store, brain);
        brain.memory = memory;
        brain.ctx = this;
        try {
            Agenda.rescheduleAll(this);
            PcLink.start(this);
            Widget.refresh(this);
            Updater.check(this, false);
        } catch (Exception ignored) {
        }
        actions = new Actions(this, brain);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF030405);
        getWindow().setNavigationBarColor(0xFF030405);

        web = new WebView(this);
        web.setBackgroundColor(0xFF030405);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "UltronNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            // Android 7+ calls this (newer than the build library, so no @Override)
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("ultron.app".equals(u.getHost())) return false;
                try {
                    Intent i = u.getScheme() != null && u.getScheme().equals("intent")
                        ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, u);
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!"ultron.app".equals(u.getHost())) return null;
                String path = u.getPath() == null ? "/" : u.getPath();
                if (path.matches("/intruder/intruder_\\d+\\.jpg") && unlocked) {
                    try {
                        return new WebResourceResponse("image/jpeg", null, new java.io.FileInputStream(new java.io.File(new java.io.File(getFilesDir(), "intruders"), path.substring(10))));
                    } catch (Exception e) {
                        return null;
                    }
                }
                return serve(path);
            }
        });
        setContentView(web);
        String page = getIntent() != null ? getIntent().getStringExtra("page") : null;
        web.loadUrl(page != null ? HOME + page : HOME);
        special(getIntent());
        shared(getIntent());

        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() { public void onInit(int status) {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("en", "IN"));
                ttsReady = true;
            }
        }});
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
                js("window.__ttsEvent && __ttsEvent(" + JSONObject.quote(id) + ",'start')");
            }

            @Override
            public void onDone(String id) {
                js("window.__ttsEvent && __ttsEvent(" + JSONObject.quote(id) + ",'end')");
            }

            @Override
            public void onError(String id) {
                onDone(id);
            }
        });
    }

    // ---------- pages from inside the app ----------
    WebResourceResponse serve(String path) {
        try {
            String asset;
            String type = "text/html";
            if (path.equals("/") || path.equals("/index.html")) asset = unlocked ? "index.html" : "lock.html";
            else if (path.equals("/keys.html") || path.equals("/setup.html") || path.equals("/autoreply.html") || path.equals("/control.html") || path.equals("/calls.html") || path.equals("/plus.html") || path.equals("/memory.html")) asset = unlocked ? path.substring(1) : "lock.html";
            else if (path.startsWith("/face/") && path.endsWith(".png")) {
                asset = "face/" + path.substring(6).replaceAll("[^a-z0-9.-]", "");
                type = "image/png";
            } else if (path.equals("/icon.png")) {
                asset = "icon.png";
                type = "image/png";
            } else {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", new HashMap<String, String>(),
                    new ByteArrayInputStream(new byte[0]));
            }
            InputStream in = getAssets().open(asset);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            return new WebResourceResponse(type, "utf-8", 200, "OK", h, in);
        } catch (Exception e) {
            return null;
        }
    }

    void js(String code) {
        ui.post(new Runnable() { public void run() { web.evaluateJavascript(code, null); }});
    }

    void reply(String id, int status, JSONObject body) {
        js("window.__nativeDone && __nativeDone(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(body.toString()) + ")");
    }

    String bioId;

    boolean deviceSecure() {
        try {
            return ((android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE)).isKeyguardSecure();
        } catch (Exception e) {
            return false;
        }
    }

    boolean bioAvailable() {
        return store.hasPassword() && !"0".equals(store.get("bio_on", "1")) && deviceSecure();
    }

    void bioDone(boolean ok) {
        String id = bioId;
        bioId = null;
        if (id == null) return;
        try {
            if (ok) {
                unlocked = true;
                fails = 0;
                askPermissions();
                store.activity("security", "Unlocked with fingerprint / phone lock");
                reply(id, 200, new JSONObject().put("ok", true));
            } else reply(id, 403, new JSONObject().put("error", "Not confirmed"));
        } catch (Exception ignored) {
        }
    }

    final java.util.concurrent.atomic.AtomicReference<CountDownLatch> camWait = new java.util.concurrent.atomic.AtomicReference<>();
    volatile boolean camOk = false;
    String docId;

    /** Opens the camera; returns the photo as JPEG bytes (scaled), or null if Arsh cancelled. Blocks (call off the UI thread). */
    public byte[] capturePhoto() {
        final CountDownLatch done = new CountDownLatch(1);
        camWait.set(done);
        camOk = false;
        ui.post(new Runnable() { public void run() {
            try {
                Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, Uri.parse("content://" + CamProvider.AUTHORITY + "/look.jpg"))
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, 4343);
            } catch (Exception e) {
                done.countDown();
            }
        }});
        try {
            done.await(180, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        if (!camOk) return null;
        try {
            android.graphics.BitmapFactory.Options op = new android.graphics.BitmapFactory.Options();
            op.inSampleSize = 2;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(CamProvider.file(this).getAbsolutePath(), op);
            float k = Math.min(1f, 1400f / Math.max(b.getWidth(), b.getHeight()));
            b = android.graphics.Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)), Math.max(1, (int) (b.getHeight() * k)), true);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, o);
            CamProvider.file(this).delete();
            return o.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == 4242) bioDone(result == RESULT_OK);
        if (req == 4343) {
            camOk = result == RESULT_OK;
            CountDownLatch l = camWait.getAndSet(null);
            if (l != null) l.countDown();
        }
        if (req == 4444) {
            final String id = docId;
            docId = null;
            if (result != RESULT_OK || data == null || data.getData() == null) {
                if (id != null) try { reply(id, 200, new JSONObject().put("result", "No file chosen.")); } catch (Exception ignored) { }
                return;
            }
            final Uri u = data.getData();
            pool.execute(new Runnable() { public void run() {
                String r = Advanced.importDoc(MainActivity.this, u);
                if (id != null) try { reply(id, 200, new JSONObject().put("result", r)); } catch (Exception ignored) { }
            }});
        }
    }

    /** Something shared to ULTRON from another app (a file, photo or text). */
    void shared(final Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        final Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        final String text = i.getStringExtra(Intent.EXTRA_TEXT);
        i.setAction(Intent.ACTION_MAIN);
        ui.postDelayed(new Runnable() { public void run() {
            if (!unlocked) { ui.postDelayed(this, 1500); return; }
            final String[] opts = u != null ? new String[]{"Send to my PC", "Add to my study brain", "Ask ULTRON about it"}
                : new String[]{"Copy to my PC's clipboard", "Ask ULTRON about it"};
            new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_Material_Dialog_Alert).setTitle("ULTRON")
                .setItems(opts, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, final int w) {
                        pool.execute(new Runnable() { public void run() {
                            String r;
                            if (u != null && w == 0) {
                                String name = "file";
                                try {
                                    android.database.Cursor c = getContentResolver().query(u, new String[]{"_display_name"}, null, null, null);
                                    if (c != null) { if (c.moveToFirst()) name = c.getString(0); c.close(); }
                                } catch (Exception ignored) {
                                }
                                r = Advanced.sendToPc(MainActivity.this, u, name);
                            } else if (u != null && w == 1) r = Advanced.importDoc(MainActivity.this, u);
                            else if (u == null && w == 0) r = Advanced.clipToPc(MainActivity.this, text);
                            else {
                                try {
                                    if (u != null) {
                                        java.io.InputStream in = getContentResolver().openInputStream(u);
                                        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                                        byte[] buf = new byte[16384];
                                        int n;
                                        while ((n = in.read(buf)) > 0 && b.size() < 15 * 1024 * 1024) b.write(buf, 0, n);
                                        in.close();
                                        String mime = getContentResolver().getType(u);
                                        r = AutoReply.clean(brain.readDocument(b.toByteArray(), mime == null ? "image/jpeg" : mime,
                                            "Arsh shared this with ULTRON. Explain what it is and the important points in 2 to 5 spoken sentences."));
                                    } else r = brain.think("Arsh shared this text: " + text + "\nExplain or summarise it briefly.")[0];
                                } catch (Exception e) {
                                    r = "I couldn't read it: " + e.getMessage();
                                }
                            }
                            js("window.ultronSay && ultronSay(" + JSONObject.quote(r) + ")");
                        }});
                    }
                }).show();
        }}, 1200);
    }

    /** Things other screens ask for when opening ULTRON: speak the briefing, or start listening (side key / assistant). */
    void special(final Intent i) {
        if (i == null) return;
        final boolean assist = Intent.ACTION_ASSIST.equals(i.getAction()) || "android.intent.action.VOICE_COMMAND".equals(i.getAction()) || i.getBooleanExtra("listen", false);
        final String say = i.getStringExtra("say");
        if (!assist && say == null) return;
        i.removeExtra("say");
        i.removeExtra("listen");
        ui.postDelayed(new Runnable() { public void run() {
            if (!unlocked) {
                ui.postDelayed(this, 1500);                               // wait until Arsh unlocks ULTRON
                return;
            }
            if ("wakeup".equals(say)) {
                pool.execute(new Runnable() { public void run() {
                    String t = Daily.wakeText(MainActivity.this);
                    js("window.ultronSay && ultronSay(" + JSONObject.quote(t) + ")");
                }});
            } else if ("briefing".equals(say)) {
                String t = store.getSecret("brief_text", "");
                if (!t.isEmpty()) js("window.ultronSay && ultronSay(" + JSONObject.quote(t) + ")");
            } else if (assist) js("window.ultronMicStart && ultronMicStart()");
        }}, 2200);
    }

    public void log(String text) {
        android.util.Log.i("ULTRON", text);
    }

    public void lock() {
        unlocked = false;
        ui.post(new Runnable() { public void run() { web.loadUrl(HOME); }});
    }

    // ---------- permissions ----------
    public boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    public void askPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : PERMS) if (!granted(p)) need.add(p);
        if (!need.isEmpty()) ui.post(new Runnable() { public void run() { requestPermissions(need.toArray(new String[0]), 7); }});
    }

    /** Shows YES / NO on the phone. Only YES lets the action happen. */
    public boolean confirm(String title, String message) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean yes = new AtomicBoolean(false);
        ui.post(new Runnable() { public void run() {
            try {
                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(title).setMessage(message).setCancelable(true)
                    .setPositiveButton("YES", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) { yes.set(true); done.countDown(); } })
                    .setNegativeButton("NO", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) { done.countDown(); } })
                    .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                        public void onCancel(android.content.DialogInterface d) { done.countDown(); } })
                    .show();
            } catch (Exception e) {
                done.countDown();
            }
        }});
        try {
            done.await(90, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return yes.get();
    }

    boolean notifAllowed() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(this, NotifService.class).flattenToString());
    }

    boolean tapAllowed() {
        String flat = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return TapService.on() || (flat != null && flat.contains(getPackageName() + "/"));
    }

    // ---------- the bridge the page talks to ----------
    class Bridge {
        @JavascriptInterface
        public void call(String route, String id, String body) {
            pool.execute(new Runnable() { public void run() {
                try {
                    JSONObject in = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
                    handle(route, id, in);
                } catch (Exception e) {
                    try {
                        reply(id, 500, new JSONObject().put("error", String.valueOf(e.getMessage())));
                    } catch (Exception ignored) {
                    }
                }
            }});
        }

        @JavascriptInterface
        public void listen(String id, String lang) {
            ui.post(new Runnable() { public void run() { startListening(id, lang); }});
        }

        @JavascriptInterface
        public void listenQuiet(String id, String lang) {
            ui.post(new Runnable() { public void run() {
                quiet(true);
                startListening(id, lang);
                ui.postDelayed(new Runnable() { public void run() { quiet(false); }}, 900);
            }});
        }

        @JavascriptInterface
        public boolean wakeOn() {
            return !"0".equals(store.get("wake_on", "1"));
        }

        @JavascriptInterface
        public void stopListen() {
            ui.post(new Runnable() { public void run() {
                if (recognizer != null) recognizer.cancel();
                endListen("end", "");
            }});
        }

        @JavascriptInterface
        public void tts(String id, String text, float pitch, float rate) {
            if (!ttsReady) {
                js("window.__ttsEvent && __ttsEvent(" + JSONObject.quote(id) + ",'end')");
                return;
            }
            tts.setPitch(Math.max(0.6f, Math.min(1.2f, pitch + 0.2f)));
            tts.setSpeechRate(Math.max(0.7f, Math.min(1.3f, rate)));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        }

        @JavascriptInterface
        public void ttsStop() {
            if (tts != null) tts.stop();
        }

        @JavascriptInterface
        public void openSetting(String which) {
            ui.post(new Runnable() { public void run() {
                try {
                    switch (which) {
                        case "notif":
                            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                            break;
                        case "access":
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            break;
                        case "perms":
                            askPermissions();
                            break;
                        case "appinfo":
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                            break;
                        case "battery":
                            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                            break;
                        case "files":
                            startActivity(new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:" + getPackageName())));
                            break;
                        case "dnd":
                            startActivity(new Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS"));
                            break;
                        case "home":
                            web.loadUrl(HOME);
                            break;
                        default:
                    }
                } catch (Exception ignored) {
                }
            }});
        }
    }

    void handle(String route, String id, JSONObject in) throws Exception {
        switch (route) {
            case "/login": {
                if (System.currentTimeMillis() < lockedUntil) {
                    reply(id, 429, new JSONObject().put("error", "Too many tries. Wait 30 seconds."));
                    return;
                }
                String pw = in.optString("password");
                if (!store.hasPassword()) {
                    if (pw.length() < 6 || !pw.equals(in.optString("password2"))) {
                        reply(id, 400, new JSONObject().put("error", "Passwords must match and be at least 6 characters"));
                        return;
                    }
                    store.setPassword(pw);
                    unlocked = true;
                    askPermissions();
                    reply(id, 200, new JSONObject().put("ok", true));
                    return;
                }
                if (store.checkPassword(pw)) {
                    fails = 0;
                    unlocked = true;
                    askPermissions();
                    reply(id, 200, new JSONObject().put("ok", true));
                } else {
                    Thread.sleep(800);
                    if (fails + 1 == 2 && granted("android.permission.CAMERA")) LockGate.intruder(this, "wrong ULTRON password (2 times)");
                    if (++fails >= 5) {
                        fails = 0;
                        lockedUntil = System.currentTimeMillis() + 30000;
                    }
                    reply(id, 403, new JSONObject().put("error", "Access denied"));
                }
                return;
            }
            case "/lockinfo":
                reply(id, 200, new JSONObject().put("setup", !store.hasPassword()).put("bio", bioAvailable()));
                return;
            case "/biounlock":
                if (!bioAvailable()) {
                    reply(id, 400, new JSONObject().put("error", "Fingerprint unlock is off or the phone has no screen lock"));
                    return;
                }
                bioId = id;
                ui.post(new Runnable() { public void run() {
                    try {
                        android.app.KeyguardManager k = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                        Intent i = k.createConfirmDeviceCredentialIntent("Unlock ULTRON", "Use your fingerprint or phone PIN");
                        if (i == null) throw new Exception("no lock");
                        startActivityForResult(i, 4242);
                    } catch (Exception e) {
                        bioDone(false);
                    }
                }});
                return;
            default:
        }
        if (!unlocked) {
            reply(id, 401, new JSONObject().put("error", "locked"));
            return;
        }
        switch (route) {
            case "/logout":
                unlocked = false;
                reply(id, 200, new JSONObject().put("ok", true));
                return;
            case "/health":
                reply(id, 200, new JSONObject().put("ok", true).put("key", !store.fishKey().isEmpty()));
                return;
            case "/speak": {
                String key = store.fishKey();
                if (key.isEmpty()) {
                    reply(id, 503, new JSONObject().put("error", "phone voice"));
                    return;
                }
                byte[] mp3 = brain.speakCached(in.optString("text"), key);
                reply(id, 200, new JSONObject().put("b64", Base64.encodeToString(mp3, Base64.NO_WRAP)));
                return;
            }
            case "/ask": {
                String q = in.optString("text").trim();
                if (q.isEmpty()) {
                    reply(id, 400, new JSONObject().put("error", "no question"));
                    return;
                }
                Actions.Result r = null;
                try {
                    r = actions.handle(q);
                } catch (Exception e) {
                    r = new Actions.Result("That action failed, Arsh: " + e.getMessage());
                }
                store.activity("command", q);
                Learn.log(store, q, r != null ? r.reply : "(answered by the AI brain)", r != null ? r.label : "BRAIN");
                if (r != null) {
                    if (!r.label.equals("SYSTEM")) store.activity("action", r.reply);
                    brain.remember(q, r.reply);
                    reply(id, 200, new JSONObject().put("reply", r.reply).put("brain", r.label).put("open", "").put("show", "")
                        .put("nav", r.nav));
                    memory.logTurn(q, r.reply, r.label);
                    final String nav = r.nav;
                    if (!nav.isEmpty()) ui.postDelayed(new Runnable() { public void run() { web.loadUrl(HOME + nav); }}, 2500);
                    return;
                }
                try {
                    String[] got = brain.think(q);
                    memory.logTurn(q, got[0], got[1]);
                    memory.learn(q, got[0]);
                    reply(id, 200, new JSONObject().put("reply", got[0]).put("brain", got[1]).put("open", "").put("show", ""));
                } catch (Exception e) {
                    reply(id, 502, new JSONObject().put("error", String.valueOf(e.getMessage())));
                }
                return;
            }
            case "/keys":
                if (in.has("keys")) store.setKeysText(in.optString("keys"));
                if (in.has("fish") && !in.optString("fish").trim().isEmpty()) store.setFishKey(in.optString("fish"));
                if (in.optBoolean("clearFish")) store.setFishKey("");
                // fall through to show what is connected
            case "/keysinfo": {
                Map<String, String> k = store.keys();
                StringBuilder names = new StringBuilder();
                for (String b : Brain.ORDER) {
                    if (k.containsKey(b)) names.append(names.length() > 0 ? ", " : "").append(Brain.NAMES.get(b))
                        .append(k.get(b).startsWith("OR:") ? " (via OpenRouter)" : "");
                }
                reply(id, 200, new JSONObject().put("brains", names.toString()).put("voice", !store.fishKey().isEmpty()));
                return;
            }
            case "/autosave":
                AutoReply.save(this, store, in);
                // fall through to show the settings
            case "/autoinfo":
            case "/control": {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                android.net.NetworkInfo net = cm.getActiveNetworkInfo();
                int battery = ((android.os.BatteryManager) getSystemService(BATTERY_SERVICE)).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                org.json.JSONArray notes = new org.json.JSONArray();
                java.util.Map<String, Boolean> seenApps = new java.util.LinkedHashMap<>();
                for (NotifService.Note n : NotifService.notes()) {
                    if (notes.length() < 25) notes.put(new JSONObject().put("app", n.app).put("who", n.title).put("text", n.text)
                        .put("at", n.when).put("canReply", n.reply != null));
                    Boolean had = seenApps.get(n.app);
                    seenApps.put(n.app, (had != null && had) || n.reply != null);
                }
                org.json.JSONArray apps = new org.json.JSONArray();
                for (java.util.Map.Entry<String, Boolean> e : seenApps.entrySet())
                    apps.put(new JSONObject().put("name", e.getKey()).put("reply", e.getValue()));
                Map<String, String> k = store.keys();
                StringBuilder brains = new StringBuilder();
                for (String b : Brain.ORDER) if (k.containsKey(b) && !k.get(b).startsWith("OR:")) brains.append(brains.length() > 0 ? ", " : "").append(Brain.NAMES.get(b));
                reply(id, 200, AutoReply.info(store).put("notif", notifAllowed()).put("access", tapAllowed())
                    .put("battery", battery).put("online", net != null && net.isConnected())
                    .put("brains", brains.toString()).put("notes", notes).put("apps", apps)
                    .put("autoApps", AutoReply.info(store).getJSONArray("apps"))
                    .put("activity", new org.json.JSONArray(store.getSecret("activity", "[]"))));
                return;
            }
            case "/callsave":
                Calls.save(store, in);
                Owner.save(store, in);
                // fall through
            case "/callinfo":
                reply(id, 200, new JSONObject().put("calls", Calls.info(store)).put("owner", Owner.info(store))
                    .put("callLog", granted("android.permission.READ_CALL_LOG")).put("phone", granted("android.permission.READ_PHONE_STATE"))
                    .put("sms", granted("android.permission.SEND_SMS")).put("dial", granted("android.permission.CALL_PHONE"))
                    .put("access", tapAllowed()).put("cats", new org.json.JSONArray(java.util.Arrays.asList(Calls.CATS))));
                if (!granted("android.permission.READ_CALL_LOG") || !granted("android.permission.READ_PHONE_STATE")) askPermissions();
                return;
            case "/setcategory":
                Calls.setCategory(store, in.optString("who"), in.optString("cat"));
                reply(id, 200, new JSONObject().put("ok", true));
                return;
            case "/ownertest":
                Owner.report(this, "high", "TEST REPORT", "This is a test from ULTRON. If you can read this, owner reports reach you. 🤖");
                reply(id, 200, new JSONObject().put("ok", true).put("result", "Test report sent. Check your SMS / WhatsApp and the activity log for confirmation."));
                return;
            case "/ownerdigest":
                Owner.sendDigest(this);
                reply(id, 200, new JSONObject().put("ok", true));
                return;
            case "/contactrule":
                AutoReply.setRule(store, in.optString("chat"), in.optString("rule"));
                reply(id, 200, new JSONObject().put("ok", true));
                return;
            case "/takeover":
                AutoReply.takeOver(store, in.optString("chat"), in.optInt("minutes", 0));
                reply(id, 200, new JSONObject().put("ok", true));
                return;
            case "/attentiondone":
                AutoReply.attentionDone(store, in.optString("id"));
                reply(id, 200, new JSONObject().put("ok", true));
                return;
            case "/approve":
                reply(id, 200, new JSONObject().put("result", Approvals.approve(this, in.optInt("id"), in.optString("text", null))));
                return;
            case "/reject":
                reply(id, 200, new JSONObject().put("result", Approvals.reject(this, in.optInt("id"))));
                return;
            case "/stop":
                reply(id, 200, new JSONObject().put("result", AutoReply.stop(this)));
                return;
            case "/memory": {
                JSONObject out = new JSONObject()
                    .put("items", memory.search(in.optString("q"), in.optString("category"), 300))
                    .put("log", memory.searchLog(in.optString("logq"), 200))
                    .put("profile", memory.profile())
                    .put("paused", memory.paused())
                    .put("off", store.get("mem_off", ""))
                    .put("categories", new org.json.JSONArray(java.util.Arrays.asList(Memory.CATEGORIES)));
                reply(id, 200, out);
                return;
            }
            case "/memadd": {
                JSONObject m = memory.add(in.optString("category"), in.optString("text"), "added by Arsh", 1.0, false);
                reply(id, 200, new JSONObject().put("ok", m != null).put("error", m == null ? "Not saved (empty, a code or password, or that category is off)" : ""));
                return;
            }
            case "/memedit":
                reply(id, 200, new JSONObject().put("ok", memory.edit(in.optString("id"), in.optString("text"), in.optString("category"))));
                return;
            case "/memdel":
                reply(id, 200, new JSONObject().put("ok", memory.delete(in.optString("id"))));
                return;
            case "/memsettings":
                if (in.has("paused")) {
                    store.put("mem_paused", in.optBoolean("paused") ? "1" : "0");
                    store.activity("memory", in.optBoolean("paused") ? "Memory collection paused" : "Memory collection resumed");
                }
                if (in.has("off")) store.put("mem_off", in.optString("off"));
                reply(id, 200, new JSONObject().put("ok", true));
                return;
            case "/docpick":
                docId = id;
                ui.post(new Runnable() { public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*", "text/plain"});
                    startActivityForResult(i, 4444);
                }});
                return;
            case "/plusinfo":
                reply(id, 200, new JSONObject().put("agenda", Agenda.info(this)).put("routines", Routines.info(store))
                    .put("pc", PcLink.info(store)).put("wake", !"0".equals(store.get("wake_on", "1"))).put("bio", !"0".equals(store.get("bio_on", "1")))
                    .put("bioPossible", deviceSecure()).put("calendar", granted("android.permission.READ_CALENDAR")).put("smart", Smart.info(store)).put("daily", Daily.info(store)).put("learn", Learn.info(store)).put("adv", Advanced.info(store)));
                if (!granted("android.permission.READ_CALENDAR")) askPermissions();
                return;
            case "/plussave":
                Agenda.save(this, in);
                Smart.save(this, in);
                Daily.save(this, in);
                Learn.save(store, in);
                Advanced.save(this, in);
                if (in.optBoolean("learnNow")) {
                    reply(id, 200, new JSONObject().put("result", Learn.reflect(this)));
                    return;
                }
                if (in.optBoolean("updateNow")) {
                    Updater.check(this, true);
                    reply(id, 200, new JSONObject().put("result", "Checking your PC for a newer ULTRON..."));
                    return;
                }
                if (in.has("wake")) store.put("wake_on", in.optBoolean("wake") ? "1" : "0");
                if (in.has("bio")) store.put("bio_on", in.optBoolean("bio") ? "1" : "0");
                if (in.has("pcOn")) {
                    store.put("pc_on", in.optBoolean("pcOn") ? "1" : "0");
                    if (in.optBoolean("pcOn")) PcLink.start(this);
                }
                if (in.has("pcAddr") && in.optString("pcAddr").trim().matches("[0-9a-zA-Z.\\-]+(:\\d+)?")) store.put("pc_addr", in.optString("pcAddr").trim());
                if (in.has("routine")) Routines.save(this, in.getJSONObject("routine"));
                reply(id, 200, new JSONObject().put("ok", true));
                return;
            case "/routine":
                reply(id, 200, new JSONObject().put("result", in.optString("name").isEmpty() ? Routines.stop(this, "button") : Routines.start(this, in.optString("name"), "button")));
                return;
            case "/briefing":
                reply(id, 200, new JSONObject().put("text", Agenda.briefing(this, false)));
                ui.post(new Runnable() { public void run() { web.loadUrl(HOME); }});
                special(new Intent().putExtra("say", "briefing"));
                return;
            case "/pcpair":
                reply(id, 200, new JSONObject().put("result", PcLink.pair(this, in.optString("addr"), in.optString("code"))));
                return;
            case "/pcunpair":
                PcLink.unpair(this);
                reply(id, 200, new JSONObject().put("result", "Unpaired from the PC."));
                return;
            case "/pcsync": {
                String r;
                try {
                    r = PcLink.sync(this);
                } catch (Exception e) {
                    r = "Could not reach the PC: " + e.getMessage();
                }
                reply(id, 200, new JSONObject().put("result", r));
                return;
            }
            case "/memsummary":
                reply(id, 200, new JSONObject().put("summary", memory.buildSummary()));
                return;
            case "/memwipe":
                if (confirm("Delete " + (in.optBoolean("memories") && in.optBoolean("conversations") ? "ALL memories and conversations"
                    : in.optBoolean("memories") ? "ALL memories" : "ALL conversation records") + "?", "This cannot be undone.")) {
                    memory.wipe(in.optBoolean("memories"), in.optBoolean("conversations"));
                    reply(id, 200, new JSONObject().put("ok", true));
                } else {
                    reply(id, 200, new JSONObject().put("ok", false));
                }
                return;
            case "/memexport": {
                final String data = memory.export().toString(2);
                store.activity("memory", "Exported the memory backup");
                ui.post(new Runnable() { public void run() {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("application/json")
                        .putExtra(Intent.EXTRA_SUBJECT, "ULTRON memory backup").putExtra(Intent.EXTRA_TEXT, data);
                    startActivity(Intent.createChooser(send, "Save ULTRON memory backup"));
                }});
                reply(id, 200, new JSONObject().put("ok", true));
                return;
            }
            case "/memimport":
                reply(id, 200, new JSONObject().put("count", memory.importJson(in.optString("text"))));
                return;
            case "/clearhistory":
                store.putSecret("activity", "[]");
                reply(id, 200, new JSONObject().put("ok", true));
                return;
            case "/setupinfo": {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                JSONObject perms = new JSONObject();
                for (String p : PERMS) perms.put(p.substring(p.lastIndexOf('.') + 1), granted(p));
                reply(id, 200, new JSONObject().put("notif", notifAllowed()).put("access", tapAllowed())
                    .put("battery", pm.isIgnoringBatteryOptimizations(getPackageName())).put("perms", perms)
                    .put("brains", !store.keys().isEmpty()));
                return;
            }
            default:
                reply(id, 404, new JSONObject().put("error", "unknown"));
        }
    }

    // ---------- microphone ----------
    boolean muted = false;

    /** Silences the recogniser's start beep while ULTRON waits for "Hey ULTRON". */
    void quiet(boolean on) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (on == muted) return;
            int dir = on ? android.media.AudioManager.ADJUST_MUTE : android.media.AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, 0);
            am.adjustStreamVolume(android.media.AudioManager.STREAM_SYSTEM, dir, 0);
            muted = on;
        } catch (Exception ignored) {
        }
    }

    void startListening(String id, String lang) {
        if (!granted("android.permission.RECORD_AUDIO")) {
            askPermissions();
            listenId = id;
            endListen("error", "not-allowed");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listenId = id;
            endListen("error", "no-speech-service");
            return;
        }
        if (recognizer != null) recognizer.destroy();
        listenId = id;
        if (tts != null) tts.stop();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) {}
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float v) {}
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() {}
            public void onPartialResults(Bundle b) {}
            public void onEvent(int t, Bundle b) {}

            public void onError(int e) {
                String why = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "no-speech"
                    : e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "not-allowed" : e == SpeechRecognizer.ERROR_NETWORK ? "network" : "error-" + e;
                endListen("error", why);
            }

            public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = r != null && !r.isEmpty() ? r.get(0) : "";
                if (text.isEmpty()) endListen("error", "no-speech");
                else endListen("result", text);
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        String vl = store.get("voice_lang", "auto");
        if (vl.equals("hi")) lang = "hi-IN";
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang == null || lang.isEmpty() ? "en-IN" : lang);
        if (vl.equals("auto")) {
            // English and Hindi both understood (Hinglish too); ignored by recognisers that can't do it
            i.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", new String[]{"hi-IN"});
            i.putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true);
            i.putStringArrayListExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES", new java.util.ArrayList<String>(java.util.Arrays.asList("en-IN", "hi-IN")));
        }
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizer.startListening(i);
    }

    void endListen(String type, String text) {
        String id = listenId;
        if (id == null) return;
        if (!type.equals("end")) js("window.__srEvent && __srEvent(" + JSONObject.quote(id) + "," + JSONObject.quote(type) + "," + JSONObject.quote(text) + ")");
        js("window.__srEvent && __srEvent(" + JSONObject.quote(id) + ",'end','')");
        listenId = null;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String page = i.getStringExtra("page");
        if (page != null && page.matches("[a-z]+\\.html")) web.loadUrl(HOME + page);
        else if (Intent.ACTION_ASSIST.equals(i.getAction()) || i.getStringExtra("say") != null) {
            String u = web.getUrl();
            if (u == null || !(u.equals(HOME) || u.endsWith("index.html"))) web.loadUrl(HOME);
        }
        special(i);
        shared(i);
    }

    // ---------- lifecycle ----------
    @Override
    protected void onPause() {
        super.onPause();
        quiet(false);
        js("window.__ultronPause && __ultronPause()");
        hiddenAt = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!NotifService.connected()) NotifService.rebind(this);
        if (unlocked && hiddenAt > 0 && System.currentTimeMillis() - hiddenAt > 15 * 60 * 1000) lock();
        js("window.__ultronResume && __ultronResume()");
    }

    @Override
    public void onBackPressed() {
        String url = web.getUrl();
        if (url != null && (url.endsWith("keys.html") || url.endsWith("setup.html") || url.endsWith("autoreply.html") || url.endsWith("control.html") || url.endsWith("calls.html") || url.endsWith("plus.html") || url.endsWith("memory.html"))) web.loadUrl(HOME);
        else moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        pool.shutdownNow();
        super.onDestroy();
    }
}
