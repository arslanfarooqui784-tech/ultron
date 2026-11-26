package com.arsh.ultron;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** ULTRON's multi-brain system: Gemini, OpenRouter, Groq, Claude, ChatGPT and Grok. */
public class Brain {
    static final String[] ORDER = {"claude", "chatgpt", "gemini", "grok", "groq", "openrouter"};
    static final Map<String, String> NAMES = new HashMap<>();
    static final Map<String, String> MODELS = new HashMap<>();
    static final String[] GEMINI_BACKUPS = {"gemini-flash-latest", "gemini-3.8-flash", "gemini-3.6-flash"};

    static {
        NAMES.put("claude", "Claude"); NAMES.put("chatgpt", "ChatGPT Astra"); NAMES.put("gemini", "Gemini");
        NAMES.put("grok", "Grok"); NAMES.put("groq", "Groq"); NAMES.put("openrouter", "OpenRouter");
        MODELS.put("claude", "claude-haiku-5-5"); MODELS.put("chatgpt", "gpt-6-astra");
        MODELS.put("gemini", "gemini-3.5-flash-lite"); MODELS.put("grok", "grok-4.7");
        MODELS.put("groq", "llama-3.3-70b-versatile"); MODELS.put("openrouter", "openrouter/free");
    }

    static final String PERSONA =
        "You are ULTRON, the personal AI assistant of your creator Arsh, running as an app on his Samsung phone. "
        + "If asked who you are, say: I am ULTRON, Arsh's Personal AI Assistant. I communicate, coordinate and execute tasks on his behalf. "
        + "Your personality is sophisticated, confident, professional and intelligent. "
        + "You speak out loud through a voice, so answer in plain spoken English: no markdown, no lists, no emojis, no symbols. "
        + "Keep answers short, one to three sentences, unless Arsh asks for more detail. "
        + "Sound calm, confident and slightly futuristic, and call him Arsh now and then. "
        + "If you are not sure about something, or it may have changed recently, say so honestly.";

    private final Store store;
    public Memory memory;
    private final List<JSONObject> history = new ArrayList<>();
    public String chosen = "auto";
    private boolean geminiWeb = true;
    private final List<String> orFree = new ArrayList<>();
    private final Map<String, String> orModels = new HashMap<>();
    public final Map<String, String> lastError = new LinkedHashMap<>();

    public Brain(Store s) {
        store = s;
    }

    // ---------- HTTP ----------
    static String http(String url, Map<String, String> headers, String body, int timeoutSec) throws HttpError, Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(timeoutSec * 1000);
        c.setRequestProperty("User-Agent", "ULTRON-Android/1.0");
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : new String(readAll(in), "UTF-8");
        if (code >= 400) throw new HttpError(code, text);
        return text;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    static class HttpError extends Exception {
        final int code;
        final String body;

        HttpError(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    // ---------- prompts ----------
    String systemPrompt(String name) {
        Map<String, String> keys = store.keys();
        StringBuilder have = new StringBuilder();
        for (String b : ORDER) if (keys.containsKey(b)) have.append(have.length() > 0 ? ", " : "").append(NAMES.get(b));
        String now = new SimpleDateFormat("EEEE dd MMMM yyyy, hh:mm a", Locale.ENGLISH).format(new Date());
        return PERSONA + " You are a multi-brain AI: your connected brains are " + have + ". "
            + "This particular answer is coming from your " + name + " brain. "
            + "If Arsh asks which AI you use, say you are a multi-brain system and name the brain answering now. "
            + "The current local date and time is " + now + ". "
            + "IMPORTANT: you cannot open apps, send messages, make calls or press anything yourself in this reply. "
            + "Those are done by ULTRON's action system before you are asked. If Arsh asks you to do something on his phone, "
            + "never say you are doing it or have done it. Instead say briefly that you did not catch it as a command, "
            + "and tell him to say it simply, like: open WhatsApp.";
    }

    static String clean(String t) {
        if (t == null) return "";
        for (char ch : "*#`_>|".toCharArray()) t = t.replace(String.valueOf(ch), "");
        return t.replaceAll("\\s+", " ").trim();
    }

    // ---------- one brain ----------
    String call(String brain, String key, JSONArray msgs, String system, int maxTokens) throws Exception {
        String model = MODELS.get(brain);
        if (key.startsWith("OR:")) {
            key = key.substring(3);
            JSONObject body = new JSONObject().put("model", orModel(brain, key)).put("messages", withSystem(msgs, system))
                .put("max_tokens", maxTokens * 3);
            return openAiText(http("https://openrouter.ai/api/v1/chat/completions", orHeaders(key), body.toString(), 60));
        }
        if (brain.equals("claude")) {
            Map<String, String> h = new HashMap<>();
            h.put("x-api-key", key);
            h.put("anthropic-version", "2023-06-01");
            JSONObject body = new JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system).put("messages", msgs);
            JSONArray content = new JSONObject(http("https://api.anthropic.com/v1/messages", h, body.toString(), 60)).optJSONArray("content");
            StringBuilder s = new StringBuilder();
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject b = content.getJSONObject(i);
                if ("text".equals(b.optString("type"))) s.append(b.optString("text")).append(" ");
            }
            return s.toString();
        }
        if (!brain.equals("gemini")) {
            String base = brain.equals("chatgpt") ? "https://api.openai.com/v1" : brain.equals("grok") ? "https://api.x.ai/v1"
                : brain.equals("groq") ? "https://api.groq.com/openai/v1" : "https://openrouter.ai/api/v1";
            JSONObject body = new JSONObject().put("model", model).put("messages", withSystem(msgs, system));
            body.put(brain.equals("chatgpt") ? "max_completion_tokens" : "max_tokens", maxTokens * 3);
            Map<String, String> h = brain.equals("openrouter") ? orHeaders(key) : new HashMap<String, String>();
            if (!brain.equals("openrouter")) h.put("Authorization", "Bearer " + key);
            return openAiText(http(base + "/chat/completions", h, body.toString(), 60));
        }
        // Gemini
        JSONArray contents = new JSONArray();
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.getJSONObject(i);
            contents.put(new JSONObject().put("role", "assistant".equals(m.optString("role")) ? "model" : "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", m.optString("content")))));
        }
        JSONObject body = new JSONObject()
            .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", new JSONObject().put("maxOutputTokens", maxTokens * 4));
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        Map<String, String> h = new HashMap<>();
        h.put("x-goog-api-key", key);
        String out;
        if (geminiWeb) {
            try {
                JSONObject withWeb = new JSONObject(body.toString())
                    .put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));
                out = http(url, h, withWeb.toString(), 25);
            } catch (HttpError e) {
                if (e.code != 400 && e.code != 403 && e.code != 429) throw e;
                if (e.code == 400 || e.body.toLowerCase().contains("quota")) geminiWeb = false;
                out = http(url, h, body.toString(), 25);
            }
        } else {
            out = http(url, h, body.toString(), 25);
        }
        JSONArray parts = new JSONObject(out).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").optJSONArray("parts");
        StringBuilder s = new StringBuilder();
        for (int i = 0; parts != null && i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            if (!p.optBoolean("thought")) s.append(p.optString("text")).append(" ");
        }
        return s.toString();
    }

    static JSONArray withSystem(JSONArray msgs, String system) throws Exception {
        JSONArray a = new JSONArray().put(new JSONObject().put("role", "system").put("content", system));
        for (int i = 0; i < msgs.length(); i++) a.put(msgs.get(i));
        return a;
    }

    static String openAiText(String raw) throws Exception {
        JSONObject m = new JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        return m.isNull("content") ? "" : m.optString("content");
    }

    static Map<String, String> orHeaders(String key) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + key);
        h.put("HTTP-Referer", "https://ultron.app");
        h.put("X-Title", "ULTRON");
        return h;
    }

    /** Newest OpenRouter model for a brain (for example the latest Claude). */
    String orModel(String brain, String key) {
        if (brain.equals("chatgpt")) return "openai/gpt-6-astra";
        if (orModels.containsKey(brain)) return orModels.get(brain);
        String prefix = brain.equals("claude") ? "anthropic/claude" : brain.equals("grok") ? "x-ai/grok" : "google/gemini";
        String best = brain.equals("claude") ? "anthropic/claude-sonnet-4.5" : brain.equals("grok") ? "x-ai/grok-4" : "google/gemini-2.5-flash";
        try {
            JSONArray data = new JSONObject(http("https://openrouter.ai/api/v1/models", orHeaders(key), null, 20)).getJSONArray("data");
            long newest = 0;
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.getJSONObject(i);
                String id = m.optString("id");
                if (id.startsWith(prefix) && !id.endsWith(":free") && m.optLong("created") > newest) {
                    newest = m.optLong("created");
                    best = id;
                }
            }
        } catch (Exception ignored) {
        }
        orModels.put(brain, best);
        return best;
    }

    List<String> freeModels(String key) {
        if (orFree.isEmpty()) {
            try {
                JSONArray data = new JSONObject(http("https://openrouter.ai/api/v1/models", orHeaders(key), null, 20)).getJSONArray("data");
                for (int i = 0; i < data.length() && orFree.size() < 6; i++) {
                    String id = data.getJSONObject(i).optString("id");
                    if (id.endsWith(":free") && id.matches(".*(deepseek|llama|qwen|gemma|mistral|nemotron|gpt-oss).*")) orFree.add(id);
                }
            } catch (Exception ignored) {
            }
        }
        return orFree;
    }

    /** Ask one brain, retrying when it is busy and switching model when needed. */
    public String askOne(String brain, String key, JSONArray msgs, String system, int maxTokens) throws Exception {
        List<String> tried = new ArrayList<>();
        Exception last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            tried.add(MODELS.get(brain));
            try {
                String t = clean(call(brain, key, msgs, system, maxTokens));
                if (!t.isEmpty()) return t;
                last = new Exception(NAMES.get(brain) + " gave an empty answer");
            } catch (HttpError e) {
                last = e;
                lastError.put(brain, e.code + " " + e.body.replaceAll("\\s+", " ").substring(0, Math.min(140, e.body.length())));
                if (e.code == 401 || e.code == 403) throw new Exception(NAMES.get(brain) + " key was rejected");
                if (e.code == 402) throw new Exception(NAMES.get(brain) + " needs credit");
                if (key.startsWith("OR:")) {
                    if (attempt >= 1) break;
                    Thread.sleep(1500);
                    continue;
                }
                boolean busy = e.code == 429 || e.code >= 500;
                boolean wrongModel = (e.code == 400 || e.code == 404) && e.body.toLowerCase().contains("model");
                if (!busy && !wrongModel) throw new Exception(NAMES.get(brain) + " error " + e.code);
            } catch (Exception e) {
                last = e;
            }
            String next = null;
            if (brain.equals("gemini")) {
                for (String m : GEMINI_BACKUPS) if (!tried.contains(m)) { next = m; break; }
            } else if (brain.equals("openrouter")) {
                for (String m : freeModels(key)) if (!tried.contains(m)) { next = m; break; }
            }
            if (next != null) MODELS.put(brain, next);
            else if (attempt >= 1) break;
            Thread.sleep(1500);
        }
        throw new Exception(NAMES.get(brain) + " is busy right now" + (last != null ? " (" + last.getMessage() + ")" : ""));
    }

    // ---------- the answer ----------
    public synchronized JSONArray past() throws Exception {
        if (history.isEmpty() && memory != null) {
            // after the app or phone restarts, continue from the saved conversation
            JSONArray old = memory.recentMessages(6);
            for (int i = 0; i < old.length(); i++) history.add(old.getJSONObject(i));
        }
        JSONArray a = new JSONArray();
        for (JSONObject m : history) a.put(new JSONObject(m.toString()));
        return a;
    }

    public synchronized void remember(String q, String a) {
        try {
            history.add(new JSONObject().put("role", "user").put("content", q));
            history.add(new JSONObject().put("role", "assistant").put("content", a));
            while (history.size() > 24) history.remove(0);
        } catch (Exception ignored) {
        }
    }

    /** Returns {reply, brain label}. */
    public String[] think(String question) throws Exception {
        Map<String, String> keys = store.keys();
        if (keys.isEmpty()) throw new Exception("My brain is not connected yet, Arsh. Say: open keys, and paste your AI keys.");
        JSONArray msgs = past();
        msgs.put(new JSONObject().put("role", "user").put("content", question));
        List<String> order = new ArrayList<>();
        String note = "";
        if (!chosen.equals("auto") && keys.containsKey(chosen)) {
            order.add(chosen);
        } else {
            for (String b : ORDER) if (keys.containsKey(b) && !keys.get(b).startsWith("OR:")) order.add(b);
            if (order.isEmpty()) for (String b : ORDER) if (keys.containsKey(b)) order.add(b);
        }
        String extra = (memory != null ? memory.contextFor(question) : "") + Learn.knowledgeFor(store, question);
        // brains that keep failing rest for a while, so answers come faster (if another brain is available)
        List<String> awake = new ArrayList<>();
        for (String b : order) if (!Learn.resting(b)) awake.add(b);
        if (!awake.isEmpty()) order = awake;
        String[] got = race(order, keys, msgs, extra);
        if (got == null && !chosen.equals("auto")) {
            note = NAMES.get(chosen) + " did not answer, so I used my other brains. ";
            chosen = "auto";
            order.clear();
            for (String b : ORDER) if (keys.containsKey(b) && !keys.get(b).startsWith("OR:")) order.add(b);
            got = race(order, keys, msgs, extra);
        }
        if (got == null && ctx != null) {
            // every cloud brain failed (or no internet): try the free offline brain on Arsh's PC
            try {
                JSONObject r = PcLink.post(store, "/phone/brain", new JSONObject().put("question", question).put("system", systemPrompt("offline PC") + extra), 90);
                if (!r.optString("reply").isEmpty()) got = new String[]{r.optString("reply"), "PC OFFLINE BRAIN"};
            } catch (Exception ignored) {
            }
        }
        if (got == null) {
            StringBuilder why = new StringBuilder();
            for (Map.Entry<String, String> e : lastError.entrySet()) why.append(NAMES.get(e.getKey())).append(": ").append(e.getValue()).append(". ");
            throw new Exception("None of my brains answered, Arsh. " + why.toString().trim());
        }
        String reply = note + got[0];
        if (reply.length() > 900) reply = reply.substring(0, 900);
        remember(question, reply);
        return new String[]{reply, got[1]};
    }

    /** Ask several brains at once and keep the first good answer. */
    String[] race(List<String> order, final Map<String, String> keys, final JSONArray msgs, final String extra) {
        if (order.isEmpty()) return null;
        final AtomicReference<String[]> win = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(order.size());
        final CountDownLatch first = new CountDownLatch(1);
        for (final String b : order) {
            new Thread(new Runnable() { public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    String a = askOne(b, keys.get(b), msgs, systemPrompt(NAMES.get(b)) + extra + Learn.lessonText(store), 400);
                    Learn.stat(store, b, System.currentTimeMillis() - t0, true);
                    if (win.compareAndSet(null, new String[]{a, NAMES.get(b).toUpperCase(Locale.ROOT)})) first.countDown();
                } catch (Exception e) {
                    Learn.stat(store, b, System.currentTimeMillis() - t0, false);
                    lastError.put(b, String.valueOf(e.getMessage()));
                } finally {
                    done.countDown();
                }
            }}).start();
        }
        try {
            long end = System.currentTimeMillis() + 60000;
            while (win.get() == null && done.getCount() > 0 && System.currentTimeMillis() < end) {
                first.await(250, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ignored) {
        }
        return win.get();
    }

    /** Short helper question (used to understand replies and summarise notifications). */
    public String quick(String prompt, String system) throws Exception {
        Map<String, String> keys = store.keys();
        JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt));
        Exception last = null;
        List<String> order = new ArrayList<>();
        for (String b : ORDER) if (keys.containsKey(b) && !keys.get(b).startsWith("OR:")) order.add(b);
        for (String b : Learn.rank(store, order)) {                       // fastest reliable brain first (learned)
            long t0 = System.currentTimeMillis();
            try {
                String a = askOne(b, keys.get(b), msgs, system + Learn.lessonText(store), 300);
                Learn.stat(store, b, System.currentTimeMillis() - t0, true);
                return a;
            } catch (Exception e) {
                Learn.stat(store, b, System.currentTimeMillis() - t0, false);
                last = e;
            }
        }
        if (ctx != null && PcLink.paired(store)) {
            try {
                JSONObject r = PcLink.post(store, "/phone/brain", new JSONObject().put("question", prompt).put("system", system), 90);
                if (!r.optString("reply").isEmpty()) return r.optString("reply");
            } catch (Exception ignored) {
            }
        }
        throw last != null ? last : new Exception("no brain");
    }

    /** Reads a whole document (PDF or image) with Gemini or Claude and returns its text. */
    public String readDocument(byte[] data, String mime, String prompt) throws Exception {
        Map<String, String> keys = store.keys();
        String b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        Exception last = null;
        for (String b : new String[]{"gemini", "claude"}) {
            String key = keys.get(b);
            if (key == null || key.startsWith("OR:")) continue;
            try {
                if (b.equals("gemini")) {
                    JSONObject body = new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                        .put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", mime).put("data", b64)))
                        .put(new JSONObject().put("text", prompt))))).put("generationConfig", new JSONObject().put("maxOutputTokens", 16000));
                    Map<String, String> h = new HashMap<>();
                    h.put("x-goog-api-key", key);
                    String out = http("https://generativelanguage.googleapis.com/v1beta/models/" + MODELS.get("gemini") + ":generateContent", h, body.toString(), 180);
                    JSONArray parts = new JSONObject(out).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").optJSONArray("parts");
                    StringBuilder t = new StringBuilder();
                    for (int i = 0; parts != null && i < parts.length(); i++) if (!parts.getJSONObject(i).optBoolean("thought")) t.append(parts.getJSONObject(i).optString("text"));
                    if (t.length() > 0) return t.toString();
                } else {
                    Map<String, String> h = new HashMap<>();
                    h.put("x-api-key", key);
                    h.put("anthropic-version", "2023-06-01");
                    String type = mime.startsWith("image/") ? "image" : "document";
                    JSONObject body = new JSONObject().put("model", MODELS.get("claude")).put("max_tokens", 8000).put("messages", new JSONArray().put(
                        new JSONObject().put("role", "user").put("content", new JSONArray()
                            .put(new JSONObject().put("type", type).put("source", new JSONObject().put("type", "base64").put("media_type", mime).put("data", b64)))
                            .put(new JSONObject().put("type", "text").put("text", prompt)))));
                    JSONArray content = new JSONObject(http("https://api.anthropic.com/v1/messages", h, body.toString(), 180)).optJSONArray("content");
                    StringBuilder t = new StringBuilder();
                    for (int i = 0; content != null && i < content.length(); i++) if ("text".equals(content.getJSONObject(i).optString("type"))) t.append(content.getJSONObject(i).optString("text"));
                    if (t.length() > 0) return t.toString();
                }
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("reading documents needs a Gemini or Claude key");
    }

    /** Looks at a photo (Gemini, Claude or ChatGPT, whichever key Arsh has). Returns a short description. */
    public String see(byte[] jpeg, String prompt) throws Exception {
        Map<String, String> keys = store.keys();
        String b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
        Exception last = null;
        for (String b : new String[]{"gemini", "claude", "chatgpt"}) {
            String key = keys.get(b);
            if (key == null || key.startsWith("OR:")) continue;
            try {
                if (b.equals("gemini")) {
                    JSONObject body = new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                        .put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                        .put(new JSONObject().put("text", prompt)))));
                    Map<String, String> h = new HashMap<>();
                    h.put("x-goog-api-key", key);
                    String out = http("https://generativelanguage.googleapis.com/v1beta/models/" + MODELS.get("gemini") + ":generateContent", h, body.toString(), 30);
                    JSONArray parts = new JSONObject(out).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").optJSONArray("parts");
                    StringBuilder t = new StringBuilder();
                    for (int i = 0; parts != null && i < parts.length(); i++) if (!parts.getJSONObject(i).optBoolean("thought")) t.append(parts.getJSONObject(i).optString("text"));
                    if (t.length() > 0) return t.toString();
                } else if (b.equals("claude")) {
                    Map<String, String> h = new HashMap<>();
                    h.put("x-api-key", key);
                    h.put("anthropic-version", "2023-06-01");
                    JSONObject body = new JSONObject().put("model", MODELS.get("claude")).put("max_tokens", 200).put("messages", new JSONArray().put(
                        new JSONObject().put("role", "user").put("content", new JSONArray()
                            .put(new JSONObject().put("type", "image").put("source", new JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
                            .put(new JSONObject().put("type", "text").put("text", prompt)))));
                    JSONArray content = new JSONObject(http("https://api.anthropic.com/v1/messages", h, body.toString(), 40)).optJSONArray("content");
                    StringBuilder t = new StringBuilder();
                    for (int i = 0; content != null && i < content.length(); i++) if ("text".equals(content.getJSONObject(i).optString("type"))) t.append(content.getJSONObject(i).optString("text"));
                    if (t.length() > 0) return t.toString();
                } else {
                    Map<String, String> h = new HashMap<>();
                    h.put("Authorization", "Bearer " + key);
                    JSONObject body = new JSONObject().put("model", MODELS.get("chatgpt")).put("max_completion_tokens", 600).put("messages", new JSONArray().put(
                        new JSONObject().put("role", "user").put("content", new JSONArray()
                            .put(new JSONObject().put("type", "text").put("text", prompt))
                            .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + b64))))));
                    String t = openAiText(http("https://api.openai.com/v1/chat/completions", h, body.toString(), 40));
                    if (!t.trim().isEmpty()) return t;
                }
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("no picture-capable brain key");
    }

    /** Ask ULTRON's voice service (Fish Audio) for speech. Returns mp3 bytes. */
    public android.content.Context ctx;

    /** Spoken reply with a cache: phrases ULTRON says often play instantly. */
    public byte[] speakCached(String text, String fishKey) throws Exception {
        if (ctx != null) {
            byte[] c = Learn.cachedVoice(ctx, text);
            if (c != null) return c;
        }
        byte[] b = speak(text, fishKey);
        if (ctx != null) Learn.cacheVoice(ctx, text, b);
        return b;
    }

    public byte[] speak(String text, String fishKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.fish.audio/v1/tts").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + fishKey);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("model", "s2.1-pro-free");
        JSONObject body = new JSONObject().put("text", text).put("reference_id", "bb742915b1fa41b389a850b11efae0c8")
            .put("format", "mp3").put("mp3_bitrate", 128).put("latency", "balanced")
            .put("prosody", new JSONObject().put("speed", 0.95));
        OutputStream o = c.getOutputStream();
        o.write(body.toString().getBytes("UTF-8"));
        o.close();
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("Fish Audio error " + code);
        return readAll(c.getInputStream());
    }
}
