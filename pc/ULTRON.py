#!/usr/bin/env python3
"""
ULTRON - start here (password protected).
Double-click this file to start ULTRON.
First run: create your password in this window. Only you can unlock ULTRON.
Forgot it? Delete ultron_lock.json in this folder and start again to set a new one.
Brains: put your AI keys (Claude, ChatGPT, Gemini, Grok) in ai_keys.txt in this folder.
"""
import getpass
import hashlib
import hmac
import json
import os
import datetime
import secrets
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ultron_voice_server as core

# ON_PHONE: this copy runs inside Termux on Arsh's phone (no Windows, no PC).
ON_PHONE = sys.platform != "win32" or os.environ.get("ULTRON_ON_PHONE") == "1"
LOCK = os.path.join(core.HERE, "ultron_lock.json")
SESSIONS = set()
FAILS = {"n": 0, "until": 0.0}


def hash_pw(pw, salt):
    return hashlib.pbkdf2_hmac("sha256", pw.encode("utf-8"), salt, 200000).hex()


def setup():
    if os.path.exists(LOCK):
        return
    print("=== ULTRON first-time setup: create your password ===")
    print("(Nothing shows while you type. That is normal.)")
    while True:
        p1 = getpass.getpass("New password (at least 6 characters): ")
        p2 = getpass.getpass("Type it again: ")
        if len(p1) >= 6 and p1 == p2:
            break
        print("They did not match, or it was too short. Try again.")
    salt = secrets.token_bytes(16)
    with open(LOCK, "w", encoding="utf-8") as f:
        json.dump({"salt": salt.hex(), "hash": hash_pw(p1, salt)}, f)
    print("Password saved. Only you can open ULTRON now.\n")


def check(pw):
    with open(LOCK, "r", encoding="utf-8") as f:
        d = json.load(f)
    return hmac.compare_digest(hash_pw(pw, bytes.fromhex(d["salt"])), d["hash"])


# ---------------- ULTRON's brains: Claude, ChatGPT, Gemini, Grok, Groq, OpenRouter ----------------
# Paste any API keys into ai_keys.txt in this folder (one per line, any order).
# ULTRON recognises them by how they start:
#   sk-ant-...  Claude (Anthropic)     sk-...  ChatGPT (OpenAI, uses GPT-6 Astra)
#   AIza... or AQ.  Gemini (Google)    xai-... Grok (xAI)
#   gsk_...     Groq (free, Llama/Qwen)   sk-or-... OpenRouter (free, many AIs)
# Say "use chatgpt" / "use grok" / "use gemini" / "use claude" / "use auto" to switch.
# Say "ask all ..." to ask every connected AI and get one combined answer.
# One OpenRouter key (sk-or-...) also unlocks ChatGPT, Claude, Grok (add credit on openrouter.ai);
# those paid brains answer only when chosen ("use claude") or in "ask all".
HISTORY = []
HISTORY_LOCK = threading.Lock()
STATE = {"brain": "auto"}
ORDER = ["claude", "chatgpt", "gemini", "grok", "groq", "openrouter"]
NAMES = {"claude": "Claude", "chatgpt": "ChatGPT Astra", "gemini": "Gemini", "grok": "Grok", "groq": "Groq", "openrouter": "OpenRouter"}
OPENAI_STYLE = {"chatgpt": "https://api.openai.com/v1", "grok": "https://api.x.ai/v1",
                "groq": "https://api.groq.com/openai/v1", "openrouter": "https://openrouter.ai/api/v1"}
GEMINI_BACKUPS = ["gemini-flash-latest", "gemini-3.8-flash", "gemini-3.6-flash"]
MODELS = {
    "claude": os.environ.get("ULTRON_CLAUDE_MODEL", "claude-haiku-5-5"),
    "chatgpt": os.environ.get("ULTRON_CHATGPT_MODEL", "gpt-6-astra"),
    "gemini": os.environ.get("ULTRON_GEMINI_MODEL", "gemini-3.5-flash-lite"),
    "grok": os.environ.get("ULTRON_GROK_MODEL", "grok-4.7"),
    "groq": os.environ.get("ULTRON_GROQ_MODEL", "llama-3.3-70b-versatile"),
    "openrouter": os.environ.get("ULTRON_OPENROUTER_MODEL", "openrouter/free"),
}
PERSONA = (
    "You are ULTRON, the personal AI assistant of your creator Arsh. "
    "You speak out loud through a voice, so answer in plain spoken English: "
    "no markdown, no lists, no emojis, no symbols. Keep answers short, "
    "one to three sentences, unless Arsh asks for more detail. "
    "Sound calm, confident and slightly futuristic, and call him Arsh now and then. "
    "If you are not sure about something, or it may have changed recently, say so honestly. "
    "You have an action system: when Arsh says open a website or app, play a song, or search Google or YouTube, "
    "it is done for you automatically, so never say you cannot open or play things. "
    "You can also open any installed app or any of Arsh's files by name, control music (pause, next, volume) and close apps. "
    "For news, prices, weather or anything recent, use your web search when it is available."
)


def load_keys():
    keys = {}
    env = {"claude": "ANTHROPIC_API_KEY", "chatgpt": "OPENAI_API_KEY", "gemini": "GEMINI_API_KEY", "grok": "XAI_API_KEY",
           "groq": "GROQ_API_KEY", "openrouter": "OPENROUTER_API_KEY"}
    for b, name in env.items():
        if os.environ.get(name, "").strip():
            keys[b] = os.environ[name].strip()
    home = os.path.expanduser("~")
    words = []
    for folder in (core.HERE, os.path.join(home, "Documents"), os.path.join(home, "OneDrive", "Documents")):
        for fname in ("ai_keys.txt", "claude_api_key.txt", "openai_api_key.txt", "gemini_api_key.txt", "grok_api_key.txt"):
            path = os.path.join(folder, fname)
            if os.path.exists(path):
                with open(path, "r", encoding="utf-8-sig") as f:
                    words += f.read().replace(",", " ").replace("=", " ").replace(":", " ").split()
    for w in words:
        w = w.strip().strip('"').strip("'")
        if w.startswith("sk-ant-"):
            keys.setdefault("claude", w)
        elif w.startswith("sk-or-"):
            keys.setdefault("openrouter", w)
        elif w.startswith("gsk_"):
            keys.setdefault("groq", w)
        elif w.startswith("xai-"):
            keys.setdefault("grok", w)
        elif w.startswith("AIza") or w.startswith("AQ."):
            keys.setdefault("gemini", w)
        elif w.startswith("sk-") and len(w) > 30:
            keys.setdefault("chatgpt", w)
    # One OpenRouter key can also stand in for ChatGPT, Claude, Grok and Gemini.
    if "openrouter" in keys:
        for b in ("chatgpt", "claude", "grok", "gemini"):
            keys.setdefault(b, "OR:" + keys["openrouter"])
    return keys


OR_PICK = {"claude": ("anthropic/", "sonnet"), "chatgpt": ("openai/", "gpt-"),
           "grok": ("x-ai/", "grok"), "gemini": ("google/", "flash")}
OR_MODELS = {}
WEB = {}
LAST_ERR = {}
OR_INFO = {}
FREE_CACHE = []


def free_models(key):
    """A few OpenRouter free models to rotate through when the free router is busy."""
    if not FREE_CACHE:
        try:
            data = http_json("https://openrouter.ai/api/v1/models", {"Authorization": "Bearer " + key})["data"]
            ids = [m["id"] for m in data if m["id"].endswith(":free")]
            good = [i for i in ids if any(x in i for x in ("deepseek", "llama", "qwen", "gemma", "mistral", "nemotron", "gpt-oss"))]
            FREE_CACHE.extend((good or ids)[:6])
        except Exception:
            pass
    return FREE_CACHE
OR_PREFER = {"chatgpt": "openai/gpt-6-astra"}


def or_model(brain, key):
    """Newest OpenRouter model for this brain (e.g. the latest Claude Sonnet)."""
    if brain in OR_MODELS:
        return OR_MODELS[brain]
    prefix, want = OR_PICK[brain]
    data = http_json("https://openrouter.ai/api/v1/models", {"Authorization": "Bearer " + key})["data"]
    ids = [m["id"] for m in data]
    if OR_PREFER.get(brain) in ids:
        OR_MODELS[brain] = OR_PREFER[brain]
        print("  > " + NAMES[brain] + " via OpenRouter: " + OR_MODELS[brain])
        return OR_MODELS[brain]
    bad = (":free", "image", "audio", "embed", "tts", "realtime", "search", "codex", "research", "-pro", "vision", "nano", "cyber", "oss", "gemma", "lite")
    cands = [m for m in data if m["id"].startswith(prefix) and want in m["id"] and not any(x in m["id"] for x in bad)]
    cands.sort(key=lambda m: m.get("created", 0), reverse=True)
    if not cands:
        raise RuntimeError(NAMES[brain] + " is not available on OpenRouter")
    OR_MODELS[brain] = cands[0]["id"]
    print("  > " + NAMES[brain] + " via OpenRouter: " + OR_MODELS[brain])
    return OR_MODELS[brain]


def brain_key():
    return bool(load_keys())


def http_json(url, headers, body=None, timeout=45):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if data else "GET",
                                 headers=dict(headers, **{"content-type": "application/json"}))
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def discover(brain, key):
    """Ask the provider which models exist and pick a sensible chat model."""
    try:
        if brain == "openrouter":
            data = http_json("https://openrouter.ai/api/v1/models", {"Authorization": "Bearer " + key})["data"]
            ids = [m["id"] for m in data if m["id"].endswith(":free")]
            best = [i for i in ids if any(x in i for x in ("deepseek", "llama", "qwen", "nemotron"))]
            return (best or ids or [None])[0]
        if brain == "groq":
            ids = [m["id"] for m in http_json("https://api.groq.com/openai/v1/models", {"Authorization": "Bearer " + key})["data"]]
            best = [i for i in ids if "versatile" in i or "gpt-oss-120b" in i or "qwen" in i]
            return (best or [i for i in ids if "whisper" not in i and "guard" not in i] or [None])[0]
        if brain == "grok":
            ids = [m["id"] for m in http_json("https://api.x.ai/v1/models", {"Authorization": "Bearer " + key})["data"]]
            ids = [i for i in ids if i.startswith("grok") and not any(x in i for x in ("image", "imagine", "vision", "code", "build", "tts", "voice"))]
        elif brain == "chatgpt":
            ids = [m["id"] for m in http_json("https://api.openai.com/v1/models", {"Authorization": "Bearer " + key})["data"]]
            ids = [i for i in ids if i.startswith("gpt-") and not any(x in i for x in ("audio", "realtime", "image", "transcribe", "tts", "search", "codex", "cyber"))]
        elif brain == "gemini":
            data = http_json("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", {"x-goog-api-key": key})
            ids = [m["name"].split("/")[-1] for m in data.get("models", []) if "generateContent" in m.get("supportedGenerationMethods", [])]
            ids = [i for i in ids if "flash" in i and not any(x in i for x in ("image", "tts", "live", "audio", "preview", "exp"))]
        else:
            ids = [m["id"] for m in http_json("https://api.anthropic.com/v1/models", {"x-api-key": key, "anthropic-version": "2023-06-01"})["data"]]
            ids = [i for i in ids if "haiku" in i] or ids
        return sorted(ids)[-1] if ids else None
    except Exception:
        return None


def call_brain(brain, key, msgs, system, max_tokens=400):
    model = MODELS[brain]
    if key.startswith("OR:"):
        key = key[3:]
        body = {"model": or_model(brain, key), "messages": [{"role": "system", "content": system}] + msgs, "max_tokens": max_tokens * 3}
        data = http_json("https://openrouter.ai/api/v1/chat/completions",
                         {"Authorization": "Bearer " + key, "HTTP-Referer": "http://127.0.0.1:8765", "X-Title": "ULTRON"}, body, timeout=60)
        return data["choices"][0]["message"].get("content") or ""
    if brain == "claude":
        data = http_json("https://api.anthropic.com/v1/messages",
                         {"x-api-key": key, "anthropic-version": "2023-06-01"},
                         {"model": model, "max_tokens": max_tokens, "system": system, "messages": msgs})
        return " ".join(b.get("text", "") for b in data.get("content", []) if b.get("type") == "text")
    if brain in OPENAI_STYLE:
        body = {"model": model, "messages": [{"role": "system", "content": system}] + msgs}
        body["max_completion_tokens" if brain == "chatgpt" else "max_tokens"] = max_tokens * 3
        headers = {"Authorization": "Bearer " + key}
        if brain == "openrouter":
            headers.update({"HTTP-Referer": "http://127.0.0.1:8765", "X-Title": "ULTRON"})
        data = http_json(OPENAI_STYLE[brain] + "/chat/completions", headers, body, timeout=60)
        return data["choices"][0]["message"].get("content") or ""
    contents = [{"role": "model" if m["role"] == "assistant" else "user", "parts": [{"text": m["content"]}]} for m in msgs]
    body = {"systemInstruction": {"parts": [{"text": system}]}, "contents": contents,
            "generationConfig": {"maxOutputTokens": max_tokens * 4}}
    url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
    if WEB.get("gemini", True):
        try:
            data = http_json(url, {"x-goog-api-key": key}, dict(body, tools=[{"google_search": {}}]), timeout=25)
        except urllib.error.HTTPError as e:
            if e.code not in (400, 403, 429):
                raise
            detail = e.read().decode("utf-8", "replace")[:200]
            if e.code == 400 or "quota" in detail.lower():
                WEB["gemini"] = False
            print("  > Gemini web search refused (" + str(e.code) + ": " + " ".join(detail.split())[:120] + "); answering without it")
            data = http_json(url, {"x-goog-api-key": key}, body, timeout=25)
    else:
        data = http_json(url, {"x-goog-api-key": key}, body, timeout=25)
    parts = data.get("candidates", [{}])[0].get("content", {}).get("parts", [])
    return " ".join(p.get("text", "") for p in parts if not p.get("thought"))


def ask_one(brain, key, msgs, system, max_tokens=400):
    """Call one brain; retry when busy, switch model if the name is wrong or overloaded."""
    tried = set()
    for attempt in range(4):
        tried.add(MODELS[brain])
        try:
            return call_brain(brain, key, msgs, system, max_tokens)
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:300]
            code = e.code
            if code in (401, 403):
                raise RuntimeError(NAMES[brain] + " error " + str(code) + " (key rejected)")
            if code == 402:
                raise RuntimeError(NAMES[brain] + " error 402 (no credit" + (" on OpenRouter)" if key.startswith("OR:") else ")"))
            wrong_model = code in (400, 404) and "model" in detail.lower()
            if key.startswith("OR:"):
                OR_MODELS.pop(brain, None)
                if wrong_model and attempt < 1:
                    continue
                raise RuntimeError(NAMES[brain] + " error " + str(code) + (" (add OpenRouter credit)" if code in (402, 429) else ""))
            busy = code in (429, 500, 502, 503, 504, 529)
            if not (wrong_model or busy):
                raise RuntimeError(NAMES[brain] + " error " + str(code) + " (" + " ".join(detail.split())[:100] + ")")
            LAST_ERR[brain] = str(code) + " " + " ".join(detail.split())[:150]
            print("  ~ " + NAMES[brain] + " " + str(code) + (" busy" if busy else " model problem") + ", trying again")
        except Exception as e:
            if "timed out" not in str(e) and not isinstance(e, (TimeoutError, urllib.error.URLError)):
                raise
            print("  ~ " + NAMES[brain] + " slow or unreachable, trying again")
            code, wrong_model, busy = 0, False, True
        # pick another model for the next attempt when one is available
        nxt = None
        if key.startswith("OR:"):
            if attempt >= 1:
                break
            time.sleep(1.5)
            continue
        if brain == "gemini":
            nxt = next((m for m in GEMINI_BACKUPS if m not in tried), None)
        if brain == "openrouter":
            nxt = next((m for m in free_models(key) if m not in tried), None)
        if nxt is None and (wrong_model or attempt >= 1):
            found = discover(brain, key)
            if found and found not in tried:
                nxt = found
        if nxt:
            print("  > " + NAMES[brain] + " now using model " + nxt)
            MODELS[brain] = nxt
        elif attempt >= 1:
            break
        time.sleep(1.5)
    raise RuntimeError(NAMES[brain] + " is busy right now" + (" (" + LAST_ERR[brain] + ")" if brain in LAST_ERR else ""))


def clean(text):
    for ch in "*#`_>|":
        text = text.replace(ch, "")
    return " ".join(text.split()).strip()


def system_prompt(name):
    now = datetime.datetime.now().strftime("%A %d %B %Y, %I:%M %p")
    keys = load_keys()
    have = ", ".join(NAMES[b] for b in ORDER if b in keys)
    mems = shared_memories()[-30:]
    memtext = (" Things Arsh asked ULTRON to remember (shared with his phone): " + "; ".join(m["text"] for m in mems) + ".") if mems else ""
    return (PERSONA + memtext + " You are a multi-brain AI: your connected brains are " + have + ". "
            "This particular answer is coming from your " + name + " brain. "
            "If Arsh asks which AI you use, say you are a multi-brain system and name the brain answering now. "
            "Arsh can switch brains by saying use astra, use claude, use grok, use gemini, use auto, or ask all. "
            "The current local date and time is " + now + ". "
            "IMPORTANT: you cannot open apps, send messages, make calls or press anything yourself in this reply. "
            "Those are done by ULTRON's action system before you are asked. If Arsh asks you to open an app, play something, "
            "send or read a message, or control his phone or PC, never say you are doing it or have done it. "
            "Instead say briefly that you did not catch it as a command, and tell him to say it simply, like: open WhatsApp.")


def remember(question, reply):
    with HISTORY_LOCK:
        HISTORY.append({"role": "user", "content": question})
        HISTORY.append({"role": "assistant", "content": reply})
        del HISTORY[:-24]


def command(q):
    """Brain-control phrases. Returns (reply, brain_label) or None."""
    low = q.lower().strip(" .!?")
    keys = load_keys()
    for b, words in {"chatgpt": ("chatgpt", "chat gpt", "gpt", "openai", "open ai", "astra", "gpt 6", "gpt-6", "gpt 6 astra", "gpt-6 astra"), "grok": ("grok", "grock"),
                     "groq": ("groq", "llama"), "openrouter": ("openrouter", "open router", "deepseek"),
                     "gemini": ("gemini", "google"), "claude": ("claude", "anthropic", "cloud"), "auto": ("auto", "automatic", "all brains", "best")}.items():
        for w in words:
            for lead in ("use ", "switch to ", "change to ", "change brain to ", "brain ", "activate "):
                if low == lead + w or low == lead + w + " brain" or low == lead + w + " mode":
                    if b != "auto" and b not in keys:
                        return ("I don't have a " + NAMES[b] + " key yet, Arsh. Add it to ai_keys.txt and I will connect.", "SYSTEM")
                    STATE["brain"] = b
                    return (("Switching to automatic mode. I will use the best connected brain." if b == "auto"
                             else NAMES[b] + " brain activated."), "SYSTEM")
    m = re.match(r"^(?:please )?(?:remember|note down|keep in mind|yaad rakhna)(?: that|:)?\s+(.{3,})$", low)
    if m:
        n = add_shared([{"text": m.group(1), "category": "personal"}], "pc")
        return ("Remembered, Arsh. Your phone will know it too after the next sync." if n else "I already knew that, Arsh."), "MEMORY"
    if not ON_PHONE and sys.platform == "win32":
        if re.match(r"^(cancel|stop|abort) (the )?(shut ?down|restart)( of)?( (my|the) (pc|computer|laptop))?$", low):
            os.system("shutdown /a")
            return "Shutdown cancelled, Arsh.", "PC"
        if re.match(r"^(shut ?down|turn off|switch off|power off)( my| the)?( pc| computer| laptop| system)?$", low):
            os.system("shutdown /s /t 30")
            return "Shutting down your PC in 30 seconds, Arsh. Say cancel shutdown to stop it.", "PC"
        if re.match(r"^(restart|reboot)( my| the)?( pc| computer| laptop| system)?$", low):
            os.system("shutdown /r /t 30")
            return "Restarting your PC in 30 seconds, Arsh. Say cancel restart to stop it.", "PC"
        if re.match(r"^(lock)( my| the)?( pc| computer| laptop| screen| system)$", low):
            os.system("rundll32.exe user32.dll,LockWorkStation")
            return "PC locked.", "PC"
        if re.match(r"^(sleep|put (my |the )?(pc|computer|laptop) to sleep)( my| the)?( pc| computer| laptop)?$", low):
            os.system("rundll32.exe powrprof.dll,SetSuspendState 0,1,0")
            return "Putting your PC to sleep.", "PC"
    m = re.match(r"^(?:send|share|transfer)\s+(.+?)\s+to (?:my )?(?:phone|mobile)$", low)
    if m and not ON_PHONE:
        what = m.group(1)
        if re.search(r"\bclipboard\b", what):
            text = pc_clipboard_get()
            if not text:
                return "Your PC clipboard is empty, Arsh.", "PHONE"
            res = helper_job({"type": "clipboard", "text": text}, wait=40)
            return ("Copied to your phone's clipboard." if res and res.get("ok") else "Your phone isn't connected right now, Arsh."), "PHONE"
        path = find_pc_file(what)
        if not path:
            return "I couldn't find that file on your PC, Arsh. Try: send the latest download to my phone.", "PHONE"
        if os.path.getsize(path) > 300 * 1024 * 1024:
            return "That file is bigger than 300 MB, Arsh.", "PHONE"
        k = secrets.token_hex(12)
        OUTBOX[k] = path
        res = helper_job({"type": "file", "path": "/phone/fileget?k=" + k, "name": os.path.basename(path)}, wait=150)
        OUTBOX.pop(k, None)
        if res and res.get("ok"):
            return "Sent " + os.path.basename(path) + " to your phone (Downloads, ULTRON).", "PHONE"
        return ("Your phone isn't connected right now, Arsh." if not res else "The phone couldn't save it: " + str(res.get("data"))), "PHONE"
    if WEATHER_WORDS.search(low):
        return weather_answer(q), "WEATHER"
    if re.search(r"\b(phone status|status of my phone|what('s| is) (happening|new) on my phone|anything on my phone|my phone update)\b", low):
        return phone_status_text(), "PHONE"
    if low in ("test brains", "check brains", "diagnose", "test your brains", "check your brains", "brain test"):
        threading.Thread(target=self_check, daemon=True).start()
        return ("Running a brain check now, Arsh. The results are saved in the ULTRON log.", "SYSTEM")
    if low in ("which brain", "which brain are you using", "brain status", "what brains do you have", "which ai are you using", "which ai",
               "which brains", "what brain", "list brains", "show brains", "which brain are you connected", "which brains are connected"):
        have = [NAMES[b] + (" via OpenRouter" if keys[b].startswith("OR:") else "") for b in ORDER if b in keys]
        mode = "automatic" if STATE["brain"] == "auto" else NAMES[STATE["brain"]]
        return (("Brain mode is " + mode + ". Connected brains: " + ", ".join(have) + ".") if have
                else "No AI brains are connected yet. Add keys to ai_keys.txt.", "SYSTEM")
    return None


BRAIN_WORDS = [("chatgpt", ("gpt 6 astra", "gpt-6 astra", "gpt6 astra", "astra", "asrea", "astr", "gpt 6", "gpt-6", "gpt6", "chatgpt", "chat gpt", "gpt", "openai", "open ai")),
               ("claude", ("claude", "anthropic", "anthropadic", "cloud")),
               ("grok", ("grok", "grock")), ("groq", ("groq", "llama")),
               ("openrouter", ("openrouter", "open router", "deepseek")),
               ("gemini", ("gemini", "google")), ("auto", ("auto", "automatic", "all brains", "best"))]


def switch_phrase(question):
    """'use gpt astra brain and tell me X' -> ('chatgpt', 'tell me X'); returns None if no switch."""
    low = question.lower().strip(" .!?")
    for lead in ("please use ", "use ", "switch to ", "change to ", "change brain to ", "activate ", "with "):
        if low.startswith(lead):
            rest = low[len(lead):]
            for b, words in BRAIN_WORDS:
                for w in words:
                    if rest.startswith(w):
                        tail = rest[len(w):].strip()
                        extra = ("astra", "asrea", "astr", "6", "gpt", "brain", "mode", "model", "ai", "sonnet", "opus", "flash")
                        words_left = tail.split(" ")
                        while words_left and words_left[0].strip(",:-") in extra:
                            words_left.pop(0)
                        tail = " ".join(words_left).strip()
                        for joiner in ("and ", "to ", "then ", ",", "-", ":"):
                            if tail.startswith(joiner):
                                tail = tail[len(joiner):].strip()
                        return b, question[len(question) - len(tail):].strip() if tail else ""
    return None


# ---------------- ULTRON's actions: browser, YouTube, Google, Windows apps ----------------
import re
import urllib.parse
import webbrowser
_wb_open = webbrowser.open
webbrowser.open = lambda url, *a, **k: _wb_open(url.replace("0.0.0.0", "127.0.0.1"), *a, **k)
if ON_PHONE:
    def _phone_open(url, *a, **k):
        import subprocess
        try:
            subprocess.Popen(["termux-open-url", url.replace("0.0.0.0", "127.0.0.1")])
            return True
        except Exception:
            return False
    webbrowser.open = _phone_open

SITES = {
    "youtube": "https://www.youtube.com", "google": "https://www.google.com", "gmail": "https://mail.google.com",
    "instagram": "https://www.instagram.com", "insta": "https://www.instagram.com", "facebook": "https://www.facebook.com",
    "whatsapp": "https://web.whatsapp.com", "linkedin": "https://www.linkedin.com", "twitter": "https://x.com", "x": "https://x.com",
    "chatgpt": "https://chatgpt.com", "claude": "https://claude.ai", "gemini": "https://gemini.google.com",
    "spotify": "https://open.spotify.com", "netflix": "https://www.netflix.com", "amazon": "https://www.amazon.in",
    "flipkart": "https://www.flipkart.com", "github": "https://github.com", "maps": "https://maps.google.com",
    "google maps": "https://maps.google.com", "drive": "https://drive.google.com", "google drive": "https://drive.google.com",
    "news": "https://news.google.com", "wikipedia": "https://www.wikipedia.org", "physics wallah": "https://www.pw.live",
    "pw": "https://www.pw.live", "openrouter": "https://openrouter.ai",
}
APPS = {
    "notepad": "notepad.exe", "calculator": "calc.exe", "calc": "calc.exe", "paint": "mspaint.exe",
    "file explorer": "explorer.exe", "explorer": "explorer.exe", "files": "explorer.exe", "my files": "explorer.exe",
    "settings": "ms-settings:", "camera": "microsoft.windows.camera:", "clock": "ms-clock:", "alarm": "ms-clock:",
    "calendar": "outlookcal:", "mail": "outlookmail:", "store": "ms-windows-store:", "microsoft store": "ms-windows-store:",
    "photos": "ms-photos:", "word": "winword.exe", "excel": "excel.exe", "powerpoint": "powerpnt.exe",
    "chrome": "chrome.exe", "google chrome": "chrome.exe", "edge": "msedge.exe", "task manager": "taskmgr.exe",
    "snipping tool": "ms-screenclip:", "documents": os.path.join(os.path.expanduser("~"), "Documents"),
    "downloads": os.path.join(os.path.expanduser("~"), "Downloads"),
}


def open_url(url):
    webbrowser.open(url, new=2)


def open_app(target):
    try:
        os.startfile(target)
        return True
    except Exception:
        try:
            import subprocess
            subprocess.Popen(["cmd", "/c", "start", "", target], shell=False, creationflags=0x08000000)
            return True
        except Exception:
            return False


def youtube_first(query):
    """Find the first YouTube video for a search, so it starts playing straight away."""
    url = "https://www.youtube.com/results?search_query=" + urllib.parse.quote_plus(query)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Accept-Language": "en-US,en;q=0.9"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            html = resp.read().decode("utf-8", "replace")
        m = re.search(r'"videoId":"([A-Za-z0-9_-]{11})"', html)
        if m:
            return "https://www.youtube.com/watch?v=" + m.group(1) + "&autoplay=1"
    except Exception:
        pass
    return url


def tidy(text):
    return text.strip(" .!?,").strip()


# ---- full PC access: installed apps, your files, media keys ----
HOME = os.path.expanduser("~")
USER_DIRS = [os.path.join(HOME, d) for d in ("Desktop", "Documents", "Downloads", "Music", "Videos", "Pictures")] + \
            [os.path.join(HOME, "OneDrive", d) for d in ("Desktop", "Documents", "Pictures", "Music", "Videos")]
APP_INDEX = {}
INDEX_STATE = {"ready": False}


def build_app_index():
    """Collect every installed app (Start menu shortcuts + Store apps) so ULTRON can open anything by name."""
    found = {}
    roots = [os.path.join(os.environ.get("APPDATA", ""), "Microsoft", "Windows", "Start Menu", "Programs"),
             os.path.join(os.environ.get("PROGRAMDATA", ""), "Microsoft", "Windows", "Start Menu", "Programs"),
             os.path.join(HOME, "Desktop"), os.path.join(HOME, "OneDrive", "Desktop")]
    for root in roots:
        for dirpath, _, files in os.walk(root):
            for f in files:
                if f.lower().endswith((".lnk", ".url", ".appref-ms")):
                    name = os.path.splitext(f)[0].lower()
                    if "uninstall" not in name:
                        found.setdefault(name, os.path.join(dirpath, f))
    try:
        import subprocess
        out = subprocess.run(["powershell", "-NoProfile", "-Command", "Get-StartApps | ConvertTo-Json -Compress"],
                             capture_output=True, text=True, timeout=40, creationflags=0x08000000).stdout
        apps = json.loads(out or "[]")
        for a in (apps if isinstance(apps, list) else [apps]):
            name, appid = str(a.get("Name", "")).lower(), str(a.get("AppID", ""))
            if name and appid and "uninstall" not in name:
                found.setdefault(name, "shell:AppsFolder\\" + appid)
    except Exception:
        pass
    APP_INDEX.clear()
    APP_INDEX.update(found)
    INDEX_STATE["ready"] = True
    print("  > app index ready: " + str(len(APP_INDEX)) + " apps")


def best_match(name, choices):
    import difflib
    name = name.lower().strip()
    if name in choices:
        return name
    starts = [c for c in choices if c.startswith(name)]
    if starts:
        return min(starts, key=len)
    words = [c for c in choices if (" " + name) in (" " + c)]
    if words:
        return min(words, key=len)
    close = difflib.get_close_matches(name, list(choices), n=1, cutoff=0.8)
    return close[0] if close else None


def find_app(name):
    if not INDEX_STATE["ready"]:
        build_app_index()
    hit = best_match(name, APP_INDEX)
    return (hit, APP_INDEX[hit]) if hit else (None, None)


def launch(target):
    if target.startswith("shell:AppsFolder"):
        import subprocess
        subprocess.Popen(["explorer.exe", target])
        return True
    return open_app(target)


MEDIA_EXT = (".mp3", ".m4a", ".wav", ".flac", ".aac", ".ogg", ".mp4", ".mkv", ".avi", ".mov", ".webm")


def find_file(name, want_media=False, folders_too=True):
    """Look through your Desktop, Documents, Downloads, Music, Videos and Pictures for a file or folder."""
    name = name.lower().strip()
    best = None
    for root in USER_DIRS:
        if not os.path.isdir(root):
            continue
        base_depth = root.count(os.sep)
        for dirpath, dirs, files in os.walk(root):
            if dirpath.count(os.sep) - base_depth >= 4:
                dirs[:] = []
            dirs[:] = [d for d in dirs if not d.startswith((".", "__")) and d.lower() not in ("node_modules", "appdata")]
            entries = ([(d, True) for d in dirs] if folders_too and not want_media else []) + [(f, False) for f in files]
            for entry, is_dir in entries:
                stem = os.path.splitext(entry)[0].lower() if not is_dir else entry.lower()
                if want_media and not entry.lower().endswith(MEDIA_EXT):
                    continue
                if name == stem:
                    return os.path.join(dirpath, entry)
                if all(w in stem for w in name.split()) and (best is None or len(stem) < best[0]):
                    best = (len(stem), os.path.join(dirpath, entry))
    return best[1] if best else None


def media_key(code, times=1):
    import ctypes
    for _ in range(times):
        ctypes.windll.user32.keybd_event(code, 0, 0, 0)
        ctypes.windll.user32.keybd_event(code, 0, 2, 0)


MEDIA = {
    ("pause", "pause music", "pause the music", "pause video", "stop music", "stop the music", "stop song", "resume", "resume music",
     "continue music", "play pause", "unpause"): (0xB3, 1, "Done, Arsh."),
    ("next", "next song", "next track", "skip", "skip song", "skip this song"): (0xB0, 1, "Next track."),
    ("previous", "previous song", "previous track", "last song", "go back a song"): (0xB1, 1, "Previous track."),
    ("volume up", "increase volume", "louder", "turn it up", "turn up the volume"): (0xAF, 5, "Volume up."),
    ("volume down", "decrease volume", "lower volume", "quieter", "turn it down", "turn down the volume"): (0xAE, 5, "Volume down."),
    ("mute", "unmute", "mute volume", "silence"): (0xAD, 1, "Done."),
}
PROTECTED = ("explorer", "python", "pythonw", "svchost", "winlogon", "csrss", "system", "dwm", "lsass", "services", "claude")


def close_app(name):
    import subprocess
    exe = APPS.get(name, "")
    if not exe.endswith(".exe"):
        hit, path = find_app(name)
        exe = (os.path.basename(path) if path and path.lower().endswith(".exe") else (hit or name).split()[0] + ".exe")
    exe = os.path.basename(exe)
    if os.path.splitext(exe)[0].lower() in PROTECTED:
        return False
    r = subprocess.run(["taskkill", "/IM", exe], capture_output=True, creationflags=0x08000000)
    return r.returncode == 0


def action(question):
    """Handle open / play / search requests on this PC. Returns (reply, label) or None."""
    low = tidy(question.lower())
    low = re.sub(r"^(hey |ok |okay )?(ultron[, ]+)?(please |can you |could you |kindly )?", "", low).strip()
    low = re.sub(r"\s+(for me|please|now)$", "", low).strip()
    for words, (code, times, reply) in MEDIA.items():
        if low in words:
            media_key(code, times)
            return reply, "ACTION"
    m = re.match(r"^(?:close|quit|exit|shut)\s+(?:the\s+|my\s+)?(.+?)(?:\s+app)?$", low)
    if m and m.group(1) not in ("yourself", "ultron", "everything", "all", "windows", "computer", "pc"):
        t = tidy(m.group(1))
        return ("Closing " + t + "." if close_app(t) else "I could not find " + t + " running, Arsh."), "ACTION"
    m = re.match(r"^play\s+(?:my\s+|the\s+local\s+|local\s+)(?:song\s+|video\s+|file\s+|music\s+)?(.+)$", low)
    if m:
        path = find_file(tidy(m.group(1)), want_media=True)
        if path:
            os.startfile(path)
            return "Playing " + os.path.splitext(os.path.basename(path))[0] + " from your computer.", "ACTION"
        q = tidy(m.group(1))
        open_url(youtube_first(q))
        return "I could not find " + q + " on your computer, so I am playing it on YouTube.", "ACTION"
    m = re.match(r"^(?:open youtube and |go to youtube and |on youtube )?(?:play|put on|start playing|listen to)\s+(.+?)(?:\s+(?:on|in|from) youtube)?(?:\s+on spotify)?$", low)
    if m and m.group(1) not in ("music", "a song", "some music", "songs", "something"):
        q = tidy(m.group(1))
        if "spotify" in low:
            open_url("https://open.spotify.com/search/" + urllib.parse.quote(q))
            return "Opening " + q + " on Spotify, Arsh.", "ACTION"
        open_url(youtube_first(q))
        return "Playing " + q + " on YouTube, Arsh.", "ACTION"
    if m:
        open_url(youtube_first("trending music songs"))
        return "Playing some music for you on YouTube, Arsh.", "ACTION"
    m = re.match(r"^(?:search|find|look up)\s+(?:for\s+)?(.+?)\s+(?:on|in)\s+youtube$", low) or re.match(r"^youtube\s+(?:search\s+)?(.+)$", low)
    if m:
        q = tidy(m.group(1))
        open_url("https://www.youtube.com/results?search_query=" + urllib.parse.quote_plus(q))
        return "Here are YouTube results for " + q + ".", "ACTION"
    m = re.match(r"^(?:google|search(?:\s+google)?(?:\s+for)?|look up|search the web for|browse for)\s+(.+?)(?:\s+on google)?$", low)
    if m:
        q = tidy(m.group(1))
        open_url("https://www.google.com/search?q=" + urllib.parse.quote_plus(q))
        return "Searching Google for " + q + ".", "ACTION"
    m = re.match(r"^(?:open|launch|start|go to|visit|show me)\s+(?:the\s+|my\s+)?(.+?)(?:\s+(?:app|website|site|page|application|folder|file|document|pdf))?$", low)
    if m:
        t = tidy(m.group(1))
        verb_open = low.startswith(("open", "go to", "visit"))
        if t in APPS and not (t in SITES and t in ("chrome",)):
            if open_app(APPS[t]):
                return "Opening " + t + ", Arsh.", "ACTION"
        if t in SITES and t not in ("youtube", "google", "gmail", "maps", "google maps", "drive", "google drive", "news"):
            if not INDEX_STATE["ready"]:
                build_app_index()
            if t in APP_INDEX:
                launch(APP_INDEX[t])
                return "Opening the " + t + " app.", "ACTION"
        if t in SITES:
            open_url(SITES[t])
            return "Opening " + t + ".", "ACTION"
        if re.match(r"^[\w-]+(\.[\w-]+)+(/\S*)?$", t):
            open_url(t if t.startswith("http") else "https://" + t)
            return "Opening " + t + ".", "ACTION"
        name = re.sub(r"^(?:file|folder|document|app|application)\s+", "", t)
        hit, path = find_app(name)
        if hit and not re.match(r"^(?:file|folder|document)\s", t):
            launch(path)
            return "Opening " + hit + ".", "ACTION"
        path = find_file(name)
        if path:
            os.startfile(path)
            return "Opening " + os.path.basename(path) + ".", "ACTION"
        if verb_open and re.match(r"^[a-z0-9-]{2,30}$", t):
            open_url("https://www." + t + ".com")
            return "Opening " + t + ".com.", "ACTION"
    return None


# ---------------- Phone actions: open apps on the PHONE, not on the PC ----------------
import threading as _th
CLIENT = _th.local()
PHONE_APPS = {
    # name: (web address the app understands, Android package)
    "youtube": ("https://www.youtube.com/", "com.google.android.youtube"),
    "yt": ("https://www.youtube.com/", "com.google.android.youtube"),
    "youtube music": ("https://music.youtube.com/", "com.google.android.apps.youtube.music"),
    "whatsapp": ("https://wa.me/", "com.whatsapp"),
    "whatsapp business": ("https://wa.me/", "com.whatsapp.w4b"),
    "instagram": ("https://www.instagram.com/", "com.instagram.android"),
    "insta": ("https://www.instagram.com/", "com.instagram.android"),
    "telegram": ("https://t.me/", "org.telegram.messenger"),
    "facebook": ("https://www.facebook.com/", "com.facebook.katana"),
    "twitter": ("https://x.com/", "com.twitter.android"), "x": ("https://x.com/", "com.twitter.android"),
    "spotify": ("https://open.spotify.com/", "com.spotify.music"),
    "maps": ("https://www.google.com/maps", "com.google.android.apps.maps"),
    "google maps": ("https://www.google.com/maps", "com.google.android.apps.maps"),
    "gmail": ("https://mail.google.com/", "com.google.android.gm"),
    "linkedin": ("https://www.linkedin.com/", "com.linkedin.android"),
    "netflix": ("https://www.netflix.com/", "com.netflix.mediaclient"),
    "amazon": ("https://www.amazon.in/", "in.amazon.mShop.android.shopping"),
    "flipkart": ("https://www.flipkart.com/", "com.flipkart.android"),
    "snapchat": ("https://www.snapchat.com/", "com.snapchat.android"),
    "snap": ("https://www.snapchat.com/", "com.snapchat.android"),
    "snap chat": ("https://www.snapchat.com/", "com.snapchat.android"),
    "pinterest": ("https://www.pinterest.com/", "com.pinterest"),
    "chatgpt": ("https://chatgpt.com/", "com.openai.chatgpt"),
    "claude": ("https://claude.ai/", "com.anthropic.claude"),
    "physics wallah": ("https://www.pw.live/", "xyz.penpencil.physicswala"),
    "pw": ("https://www.pw.live/", "xyz.penpencil.physicswala"),
    "drive": ("https://drive.google.com/", "com.google.android.apps.docs"),
    "google drive": ("https://drive.google.com/", "com.google.android.apps.docs"),
    "chrome": ("https://www.google.com/", "com.android.chrome"),
    "google": ("https://www.google.com/", "com.google.android.googlequicksearchbox"),
}


def app_link(url, package):
    """An Android link that opens the app itself, and the website if the app is missing."""
    rest = url.split("://", 1)[1]
    return ("intent://" + rest + "#Intent;scheme=https;package=" + package +
            ";S.browser_fallback_url=" + urllib.parse.quote(url, safe="") + ";end")


def phone_number(text):
    digits = re.sub(r"[^\d+]", "", text)
    if len(digits.lstrip("+")) < 6:
        return ""
    if not digits.startswith("+") and len(digits) == 10:
        digits = "+91" + digits
    return digits


def phone_action(question):
    """Returns (reply, label, link to open on the phone) or None."""
    low = tidy(question.lower())
    low = re.sub(r"^(hey |ok |okay )?(ultron[, ]+)?(please |can you |could you |kindly )?", "", low).strip()
    low = re.sub(r"\s+(for me|please|now|on my phone|on phone|in my phone)$", "", low).strip()
    yt = PHONE_APPS["youtube"][1]
    # calls and messages: the phone fills them in, Arsh taps the final button himself
    m = re.match(r"^(?:call|dial|phone)\s+(.+)$", low)
    if m and phone_number(m.group(1)):
        n = phone_number(m.group(1))
        return "Calling " + n + ". Tap the call button to confirm, Arsh.", "PHONE", "tel:" + n
    m = re.match(r"^(?:whatsapp|message on whatsapp|send whatsapp(?: message)?(?: to)?)\s+([+\d][\d\s-]{5,})\s*(.*)$", low)
    if m:
        n = phone_number(m.group(1)).lstrip("+")
        url = "https://wa.me/" + n + ("?text=" + urllib.parse.quote(m.group(2)) if m.group(2) else "")
        return "WhatsApp chat ready. Check the message and tap send, Arsh.", "PHONE", app_link(url, "com.whatsapp")
    m = re.match(r"^(?:sms|text|send (?:an )?sms(?: to)?|message)\s+([+\d][\d\s-]{5,})\s*(.*)$", low)
    if m:
        n = phone_number(m.group(1))
        return "Message ready. Tap send to confirm, Arsh.", "PHONE", "sms:" + n + ("?body=" + urllib.parse.quote(m.group(2)) if m.group(2) else "")
    m = re.match(r"^(?:email|mail|send (?:an )?email to)\s+(\S+@\S+)\s*(.*)$", low)
    if m:
        return "Email ready. Tap send to confirm, Arsh.", "PHONE", "mailto:" + m.group(1) + ("?body=" + urllib.parse.quote(m.group(2)) if m.group(2) else "")
    # music and video
    m = re.match(r"^(?:open youtube and |go to youtube and |on youtube )?(?:play|put on|start playing|listen to)\s+(.+?)(?:\s+(?:on|in|from) youtube)?(?:\s+on spotify)?$", low)
    if m:
        q = tidy(m.group(1))
        if q in ("music", "a song", "some music", "songs", "something"):
            q = "trending music songs"
        if "spotify" in low:
            return "Opening " + q + " on Spotify.", "PHONE", app_link("https://open.spotify.com/search/" + urllib.parse.quote(q), "com.spotify.music")
        return "Playing " + q + " on YouTube, Arsh.", "PHONE", app_link(youtube_first(q), yt)
    m = re.match(r"^(?:search|find|look up)\s+(?:for\s+)?(.+?)\s+(?:on|in)\s+youtube$", low) or re.match(r"^youtube\s+(?:search\s+)?(.+)$", low)
    if m:
        q = tidy(m.group(1))
        return "Here are YouTube results for " + q + ".", "PHONE", app_link("https://www.youtube.com/results?search_query=" + urllib.parse.quote_plus(q), yt)
    m = re.match(r"^(?:google|search(?:\s+google)?(?:\s+for)?|look up|search the web for|browse for)\s+(.+?)(?:\s+on google)?$", low)
    if m:
        q = tidy(m.group(1))
        return "Searching Google for " + q + ".", "PHONE", "https://www.google.com/search?q=" + urllib.parse.quote_plus(q)
    m = re.match(r"^(?:navigate to|directions to|take me to)\s+(.+)$", low)
    if m:
        q = tidy(m.group(1))
        return "Starting directions to " + q + ".", "PHONE", app_link("https://www.google.com/maps/dir/?api=1&destination=" + urllib.parse.quote(q), "com.google.android.apps.maps")
    cant = ""
    m = re.match(r"^(?:open|launch|start|go to|visit|show me)\s+(?:the\s+|my\s+)?(.+?)(?:\s+(?:app|website|site|page|application))?$", low)
    if m:
        t = tidy(m.group(1))
        if t in ("dialer", "phone", "phone app", "calls"):
            return "Opening your dialer.", "PHONE", "tel:"
        if t in ("messages", "sms"):
            return "Opening messages.", "PHONE", "sms:"
        if t in ("email", "mail", "emails", "mails", "inbox"):
            return "Opening Gmail, Arsh.", "PHONE", app_link(*PHONE_APPS["gmail"])
        if t in PHONE_APPS:
            url, pkg = PHONE_APPS[t]
            return "Opening " + t + ", Arsh.", "PHONE", app_link(url, pkg)
        if t in SITES:
            return "Opening " + t + ".", "PHONE", SITES[t]
        if re.match(r"^[\w-]+(\.[\w-]+)+(/\S*)?$", t):
            return "Opening " + t + ".", "PHONE", (t if t.startswith("http") else "https://" + t)
        cant = t
    if low in ("pause", "next", "next song", "previous", "volume up", "volume down", "mute", "skip"):
        return "On the phone, please use the music controls for that, Arsh. Say 'on my PC' to control the computer.", "PHONE", ""
    if re.search(r"\b(dual|second|2nd|other|clone)\b", low) and "whatsapp" in low.replace(" ", ""):
        return ("Opening WhatsApp. Pick the dual WhatsApp in the list, Arsh.", "PHONE",
                "intent://send/#Intent;scheme=whatsapp;end")
    # Forgiving match: the voice often mishears "open" (sopan, upon, oppen) or adds extra words.
    words = re.findall(r"[a-z]+", low)
    openish = re.search(r"\b(?:open|opan|oppen|opn|sopan|upon|oben|hopen|open\w*|launch|start|show|go to|take me to|kholo|khol|chalao|chalu)\b", low)
    if openish or len(words) <= 2:
        names = sorted(PHONE_APPS, key=len, reverse=True)
        extra = {"mail": "gmail", "email": "gmail", "e mail": "gmail", "whats app": "whatsapp", "watsapp": "whatsapp", "whatsap": "whatsapp",
                 "what's app": "whatsapp", "you tube": "youtube", "insta gram": "instagram", "instagram's": "instagram", "tele gram": "telegram"}
        hit = None
        for k, v in extra.items():
            if re.search(r"\b" + re.escape(k) + r"\b", low):
                hit = v
                break
        if not hit:
            for n in names:
                if len(n) > 2 and re.search(r"\b" + re.escape(n) + r"\b", low):
                    hit = n
                    break
        if hit:
            url, pkg = PHONE_APPS[hit]
            more = ""
            if re.search(r"\b(message|messages|reply|respond|response|read|chat|send)\b", low):
                more = " Reading and replying to messages for you needs the phone helper, so for now I have opened the app."
            return "Opening " + hit + ", Arsh." + more, "PHONE", app_link(url, pkg)
    if cant:
        return ("I can't open " + cant + " on your phone from the browser yet, Arsh. That needs the phone helper. "
                "I can open apps like YouTube, WhatsApp, Instagram, Telegram, Spotify, Maps and Gmail."), "PHONE", ""
    return None



# ---------------- Phone helper (Termux on Arsh's phone) ----------------
import random
PHONE_TOKEN_FILE = os.path.join(core.HERE, "ultron_phone_token.txt")
SHARED_MEMORY_FILE = os.path.join(core.HERE, "ultron_shared_memory.json")
PHONE_STATUS_FILE = os.path.join(core.HERE, "ultron_phone_status.json")
SHARED_LOCK = threading.Lock()


def shared_memories():
    """Memories shared between the PC and the phone app: [{text, category, from}]."""
    try:
        with open(SHARED_MEMORY_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def _norm(t):
    return re.sub(r"[^a-z0-9 ]", "", str(t).lower()).strip()


def add_shared(items, source):
    """Adds new memories (skips ones already known). Returns how many were new."""
    with SHARED_LOCK:
        have = shared_memories()
        known = set(_norm(m.get("text", "")) for m in have)
        new = 0
        for m in items:
            text = str(m.get("text", "")).strip()[:400]
            if len(text) < 3 or _norm(text) in known:
                continue
            if re.search(r"(?i)(otp|password|passcode|cvv|card number|account number|\b\d{6}\b.*code)", text):
                continue
            have.append({"text": text, "category": str(m.get("category", "personal"))[:30], "from": source, "at": int(time.time())})
            known.add(_norm(text))
            new += 1
        if new:
            with open(SHARED_MEMORY_FILE, "w", encoding="utf-8") as f:
                json.dump(have[-500:], f, ensure_ascii=False, indent=1)
        return new


WEATHER_WORDS = re.compile(r"(?i)\b(weather|forecast|temperature|mausam|mosam|aqi|air quality|pollution)\b|\b(will it|is it|going to) (rain|snow|be hot|be cold)\b|\brain(ing)? (today|tomorrow|now)\b")
SKY = {0: "clear sky", 1: "mainly clear", 2: "partly cloudy", 3: "overcast", 45: "fog", 48: "fog", 51: "drizzle", 53: "drizzle", 55: "drizzle",
       61: "light rain", 63: "moderate rain", 65: "heavy rain", 80: "light rain showers", 81: "rain showers", 82: "violent rain showers",
       71: "snow", 73: "snow", 75: "snow", 95: "thunderstorm", 96: "thunderstorm with hail", 99: "thunderstorm with hail"}
_WCACHE = {}


def _getjson(url):
    req = urllib.request.Request(url, headers={"User-Agent": "ULTRON"})
    with urllib.request.urlopen(req, timeout=12) as r:
        return json.loads(r.read().decode("utf-8"))


def weather_facts(city, days=1):
    """Live weather + forecast + air quality for a city (Open-Meteo, no key)."""
    k = (city.lower().strip(), days)
    if k in _WCACHE and time.time() - _WCACHE[k][0] < 600:
        return _WCACHE[k][1]
    res = _getjson("https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&format=json&name=" + urllib.parse.quote(city.strip())).get("results") or []
    if not res:
        return "No place called " + city + " was found."
    p = next((r for r in res if r.get("country_code") == "IN"), res[0])
    lat, lon = p["latitude"], p["longitude"]
    days = max(1, min(7, int(days)))
    w = _getjson("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current=temperature_2m,apparent_temperature,relative_humidity_2m,"
                 "weather_code,wind_speed_10m,precipitation&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                 "&timezone=auto&forecast_days=%d" % (lat, lon, days))
    c, d = w["current"], w["daily"]
    out = "%s, %s - now %d°C (feels %d°C), %s, humidity %d%%, wind %d km/h." % (p.get("name"), p.get("country", ""), round(c["temperature_2m"]),
          round(c["apparent_temperature"]), SKY.get(c["weather_code"], "code %s" % c["weather_code"]), c["relative_humidity_2m"], round(c["wind_speed_10m"]))
    for i, day in enumerate(d["time"]):
        name = "Today" if i == 0 else "Tomorrow" if i == 1 else datetime.datetime.strptime(day, "%Y-%m-%d").strftime("%A")
        out += " %s: %s, %d-%d°C, rain chance %s%%." % (name, SKY.get(d["weather_code"][i], "code %s" % d["weather_code"][i]),
               round(d["temperature_2m_min"][i]), round(d["temperature_2m_max"][i]), d["precipitation_probability_max"][i])
    try:
        aq = _getjson("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%s&longitude=%s&current=us_aqi" % (lat, lon))["current"]["us_aqi"]
        out += " Air quality AQI %d." % aq
    except Exception:
        pass
    _WCACHE[k] = (time.time(), out)
    return out


def weather_answer(question):
    keys = load_keys()
    order = [b for b in ORDER if b in keys and not keys[b].startswith("OR:")]
    cities, days = [], 1
    try:
        raw = ask_one(order[0], keys[order[0]], [{"role": "user", "content": "Which cities does this ask the weather for, and how many days of "
              "forecast (1-7)? Text: " + question + "\nAnswer ONLY with JSON {\"places\": [], \"days\": 1}."}], "Output only JSON.", 120)
        j = json.loads(raw[raw.find("{"):raw.rfind("}") + 1])
        cities, days = [str(x) for x in j.get("places", [])][:6], int(j.get("days", 1))
    except Exception:
        cities = re.findall(r"(?:in|of|at|for)\s+([A-Z][a-zA-Z]+)", question)
    if re.search(r"(?i)tomorrow|forecast|week|kal", question):
        days = max(days, 3)
    cities = cities or ["Lucknow"]
    facts = []
    for c in cities:
        try:
            facts.append(weather_facts(c, days))
        except Exception:
            facts.append(c + ": the weather service is not reachable right now.")
    try:
        return clean(ask_one(order[0], keys[order[0]], [{"role": "user", "content": "Arsh asked: " + question + "\nLIVE WEATHER DATA:\n" + "\n".join(facts) +
                     "\nAnswer in 2 to 5 spoken sentences using ONLY this data. Compare cities if several. One practical tip if useful."}], system_prompt("weather"), 300))
    except Exception:
        return " ".join(facts)


OLLAMA = "http://127.0.0.1:11434"
_OLL = {"model": None, "checked": 0}


def ollama_model():
    """The best local model installed in Ollama (free, offline), or None."""
    if time.time() - _OLL["checked"] < 300:
        return _OLL["model"]
    _OLL["checked"] = time.time()
    try:
        with urllib.request.urlopen(OLLAMA + "/api/tags", timeout=2) as r:
            names = [m["name"] for m in json.loads(r.read().decode("utf-8")).get("models", [])]
    except Exception:
        _OLL["model"] = None
        return None
    pref = ["llama3.2", "qwen2.5", "gemma2", "gemma3", "llama3.1", "phi3", "mistral"]
    pick = next((n for p in pref for n in names if n.startswith(p)), names[0] if names else None)
    _OLL["model"] = pick
    return pick


def ollama_reply(question, past=None, system=None):
    """Answer with the local Ollama model; None if Ollama isn't installed / running."""
    model = ollama_model()
    if not model:
        return None
    msgs = [{"role": "system", "content": system or system_prompt("offline")}] + list(past or [])[-8:] + [{"role": "user", "content": question}]
    try:
        req = urllib.request.Request(OLLAMA + "/api/chat", data=json.dumps({"model": model, "messages": msgs, "stream": False}).encode("utf-8"),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=120) as r:
            out = json.loads(r.read().decode("utf-8")).get("message", {}).get("content", "")
        out = clean(out)
        if out:
            print("  > offline brain (" + model + ") answered")
        return out or None
    except Exception as e:
        print("  ! offline brain: " + str(e)[:80])
        return None


OUTBOX = {}
PHONE_INBOX = os.path.join(os.path.expanduser("~"), "Downloads", "ULTRON from phone")


def pc_clipboard_get():
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", "Get-Clipboard -Raw"], capture_output=True, timeout=10)
        return out.stdout.decode("utf-8", "ignore").strip()
    except Exception:
        return ""


def pc_clipboard_set(text):
    try:
        subprocess.run(["clip"], input=("\ufeff" + text).encode("utf-16le"), timeout=10)
        return True
    except Exception:
        return False


def find_pc_file(what):
    """'latest download' / 'last screenshot' / a file name -> path on this PC."""
    home = os.path.expanduser("~")
    folders = [os.path.join(home, d) for d in ("Downloads", "Desktop", "Documents", "Pictures", os.path.join("Pictures", "Screenshots"),
               os.path.join("OneDrive", "Documents"), os.path.join("OneDrive", "Desktop"))]
    files = []
    for d in folders:
        try:
            for n in os.listdir(d):
                p = os.path.join(d, n)
                if os.path.isfile(p) and not n.startswith((".", "~$")) and not n.endswith((".tmp", ".crdownload", ".part")):
                    files.append(p)
        except Exception:
            pass
    if not files:
        return None
    w = what.lower().strip()
    if re.search(r"\bdownload", w):
        files = [f for f in files if os.sep + "Downloads" + os.sep in f] or files
    if re.search(r"screenshot", w):
        files = [f for f in files if "screenshot" in f.lower()] or files
    if re.search(r"\b(latest|last|newest|recent)\b", w):
        return max(files, key=os.path.getmtime)
    words = [x for x in re.findall(r"[a-z0-9]+", w) if x not in ("the", "my", "file", "named", "called", "pdf", "doc", "document")]
    best = [f for f in files if all(x in os.path.basename(f).lower() for x in words)] if words else []
    return max(best, key=os.path.getmtime) if best else None


def phone_status():
    try:
        with open(PHONE_STATUS_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def phone_status_text():
    st = phone_status()
    if not st:
        return "The ULTRON phone app has not synced with me yet, Arsh. Pair it in ULTRON+ on the phone."
    age = int((time.time() * 1000 - st.get("at", 0)) / 60000)
    parts = [str(st.get("messages24h", 0)) + " messages in the last 24 hours",
             str(st.get("waitingForArsh", 0)) + " waiting for you personally",
             str(st.get("missedCalls24h", 0)) + " missed calls"]
    if st.get("routine"):
        parts.append(st["routine"] + " mode is on")
    parts.append("auto-replies are " + str(st.get("replyMode", "")))
    return "On your phone: " + ", ".join(parts) + ". (Updated " + (str(age) + " minutes ago" if age > 0 else "just now") + ".)"
PAIR = {"code": "", "until": 0}
HELPER = {"seen": 0, "jobs": [], "results": {}, "next": 1}
HELPER_COND = threading.Condition()
LAST_NOTES = []
SAMSUNG = {
    "camera": ("com.sec.android.app.camera", "android.media.action.STILL_IMAGE_CAMERA"),
    "gallery": ("com.sec.android.gallery3d", ""), "photos": ("com.google.android.apps.photos", ""),
    "settings": ("", "android.settings.SETTINGS"), "wifi": ("", "android.settings.WIFI_SETTINGS"),
    "bluetooth": ("", "android.settings.BLUETOOTH_SETTINGS"),
    "calculator": ("com.sec.android.app.popupcalculator", ""), "clock": ("com.sec.android.app.clockpackage", ""),
    "alarm": ("com.sec.android.app.clockpackage", ""), "calendar": ("com.samsung.android.calendar", ""),
    "contacts": ("com.samsung.android.app.contacts", ""), "messages": ("com.samsung.android.messaging", ""),
    "phone": ("com.samsung.android.dialer", ""), "dialer": ("com.samsung.android.dialer", ""),
    "my files": ("com.sec.android.app.myfiles", ""), "files": ("com.sec.android.app.myfiles", ""),
    "play store": ("com.android.vending", ""), "notes": ("com.samsung.android.app.notes", ""),
    "samsung notes": ("com.samsung.android.app.notes", ""), "music": ("com.google.android.apps.youtube.music", ""),
}


def phone_token():
    if os.path.exists(PHONE_TOKEN_FILE):
        with open(PHONE_TOKEN_FILE, encoding="utf-8") as f:
            return f.read().strip()
    return ""


def helper_online():
    return ON_PHONE or time.time() - HELPER["seen"] < 50


_LOCAL = {}


def local_job(job):
    """On the phone itself: do the job directly with Termux:API."""
    if "mod" not in _LOCAL:
        import importlib.util as _iu
        spec = _iu.spec_from_file_location("ultron_phone", os.path.join(core.HERE, "ultron_phone.py"))
        _LOCAL["mod"] = _iu.module_from_spec(spec)
        spec.loader.exec_module(_LOCAL["mod"])
    fn = _LOCAL["mod"].JOBS.get(job.get("type"))
    if not fn:
        return {"ok": False, "data": "unknown job"}
    try:
        ok, data = fn(job)
        return {"ok": ok, "data": data}
    except Exception as e:
        return {"ok": False, "data": str(e)}


def helper_job(job, wait=25):
    """Sends a job to the phone helper and waits for the answer."""
    if ON_PHONE:
        return local_job(job)
    with HELPER_COND:
        jid = HELPER["next"]
        HELPER["next"] += 1
        job["id"] = jid
        HELPER["jobs"].append(job)
        HELPER_COND.notify_all()
        end = time.time() + wait
        while jid not in HELPER["results"] and time.time() < end:
            HELPER_COND.wait(max(0.1, end - time.time()))
        if job in HELPER["jobs"]:
            HELPER["jobs"].remove(job)   # never run a late job (like a call) after ULTRON gave up
        return HELPER["results"].pop(jid, None)


APP_LABELS = {"com.whatsapp": "WhatsApp", "com.whatsapp.w4b": "WhatsApp Business", "com.instagram.android": "Instagram",
              "org.telegram.messenger": "Telegram", "com.snapchat.android": "Snapchat", "com.google.android.gm": "Gmail",
              "com.samsung.android.messaging": "Messages", "com.google.android.apps.messaging": "Messages",
              "com.facebook.katana": "Facebook", "com.facebook.orca": "Messenger", "com.linkedin.android": "LinkedIn",
              "com.google.android.youtube": "YouTube"}


def summarise(items, kind):
    if not items:
        return "You have no new " + kind + ", Arsh."
    lines = []
    for n in items[:12]:
        if kind == "messages":
            lines.append("From " + n["from"] + ": " + n["text"])
        else:
            app = APP_LABELS.get(n["app"], n["app"].split(".")[-1].title())
            lines.append(app + " - " + n["title"] + ": " + n["text"])
    text = "\n".join(lines)
    try:
        keys = load_keys()
        order = [b for b in ORDER if b in keys and not keys[b].startswith("OR:")]
        if order:
            return clean(ask_one(order[0], keys[order[0]], [{"role": "user", "content":
                "Here are Arsh's phone " + kind + ":\n" + text + "\n\nTell him briefly, in 2 to 5 spoken sentences, who wrote "
                "and what they want. Group by app. Do not invent anything."}], system_prompt("phone"), 400))
    except Exception:
        pass
    return "Here are your " + kind + ". " + " ".join(lines[:5])


def reply_parts(question):
    """Uses a brain to understand a spoken reply request. Returns (who, message, app) or None."""
    notes = "\n".join(APP_LABELS.get(n["app"], n["app"]) + " | " + n["title"] + " | " + n["text"] for n in LAST_NOTES[:15])
    keys = load_keys()
    order = [b for b in ORDER if b in keys and not keys[b].startswith("OR:")]
    for b in order[:2]:
        try:
            raw = ask_one(b, keys[b], [{"role": "user", "content":
                "Arsh said (speech to text, may have mistakes): " + question + "\n\nRecent phone notifications (app | chat | text):\n" +
                (notes or "none") + "\n\nWork out who he wants to reply to, which app, and the exact reply text he wants to send, "
                "written in first person as Arsh, cleaned up, in the same language he used. Do not add anything he did not say. "
                "Answer ONLY with JSON: {\"who\": \"chat name\", \"app\": \"whatsapp|instagram|telegram|snapchat\", \"message\": \"...\"}. "
                "If there is no reply text, use an empty message."}], "You output only JSON.", 200)
            j = json.loads(raw[raw.find("{"):raw.rfind("}") + 1])
            msg = str(j.get("message", "")).strip()
            app = str(j.get("app", "whatsapp")).lower().strip()
            if msg:
                return str(j.get("who", "")).strip(), msg, (app if app in PHONE_APPS else "whatsapp")
            return None
        except Exception as e:
            print("  ! reply parse: " + str(e)[:100])
    return None


def helper_action(question):
    """Phone jobs for the helper. Returns (reply, label) or None."""
    low = tidy(question.lower())
    low = re.sub(r"^(hey |ok |okay )?(ultron[, ]+)?(please |can you |could you |kindly )?", "", low).strip()
    low = re.sub(r"\s+(for me|please|now|on my phone|on phone|in my phone)$", "", low).strip()
    if re.search(r"\b(notification|notifications|who (messaged|texted) me|any (new )?messages|my messages on (whatsapp|instagram|telegram|snapchat))\b", low) \
            and re.search(r"\b(read|check|show|tell|any|what|who|my)\b", low):
        r = helper_job({"type": "notifications"})
        if not r:
            return "My phone helper did not answer, Arsh. Check that Termux is still running.", "PHONE"
        if not r["ok"]:
            return "I can't read notifications yet: " + str(r["data"]) + ". Allow notification access for Termux API.", "PHONE"
        want = re.search(r"\b(whatsapp|instagram|telegram|snapchat|gmail|email|facebook|linkedin)\b", low)
        items = r["data"]
        LAST_NOTES[:] = items
        if want:
            w = {"email": "gmail"}.get(want.group(1), want.group(1))
            items = [n for n in items if w in APP_LABELS.get(n["app"], n["app"]).lower() or w in n["app"]]
        return summarise(items, "notifications"), "PHONE"
    if re.search(r"\b(read|check|show)\b.*\b(sms|text messages|messages)\b", low):
        r = helper_job({"type": "sms_list", "count": 5})
        if not r or not r["ok"]:
            return "I can't read SMS yet" + (": " + str(r["data"]) if r else "") + ".", "PHONE"
        return summarise(r["data"], "messages"), "PHONE"
    m = re.match(r"^(?:send (?:an )?sms|sms|text)\s+(?:to\s+)?([+\d][\d\s-]{5,})\s+(?:saying\s+|that\s+)?(.+)$", low)
    if m:
        n = phone_number(m.group(1))
        r = helper_job({"type": "sms_send", "number": n, "text": m.group(2)}, 130)
        return ("Message sent to " + n + "." if r and r["ok"] else "SMS not sent" + (" - " + str(r["data"]) if r else "") + "."), "PHONE"
    m = re.match(r"^(?:call|dial|phone)\s+([+\d][\d\s-]{5,})$", low)
    if m:
        n = phone_number(m.group(1))
        r = helper_job({"type": "call", "number": n}, 130)
        return ("Calling " + n + "." if r and r["ok"] else "Call not made" + (" - " + str(r["data"]) if r else "") + "."), "PHONE"
    if re.search(r"\b(reply|replied|respond|answer|tell her|tell him|message back|send her|send him)\b", low):
        got = reply_parts(question)
        if got:
            who, msg, app = got
            dual = bool(re.search(r"\b(dual|second|clone)\b", low))
            helper_job({"type": "clipboard", "text": msg}, 10)
            if app == "whatsapp" and dual:
                helper_job({"type": "open_dual", "package": "com.whatsapp"})
            else:
                url, pkg = PHONE_APPS.get(app, PHONE_APPS["whatsapp"])
                helper_job({"type": "open", "package": pkg, "url": url})
            return ("Your reply is copied: " + msg + ". I opened " + app.title() + ". Tap " + (who or "the chat") +
                    ", hold the message box, tap Paste, then Send, Arsh."), "PHONE"
        return ("Tell me who and what to reply, Arsh. For example: reply to Farooqi on WhatsApp, yes I had my lunch, how are you."), "PHONE"
    m = re.match(r"^(?:reply|respond|answer)\s+(?:to\s+)?(.+?)\s+(?:on\s+)?(whatsapp|instagram|telegram|snapchat)?\s*(?:saying|with|that)\s+(.+)$", low)
    if m:
        app = m.group(2) or "whatsapp"
        helper_job({"type": "clipboard", "text": m.group(3)}, 10)
        url, pkg = PHONE_APPS.get(app, PHONE_APPS["whatsapp"])
        helper_job({"type": "open", "package": pkg, "url": url})
        return ("I copied your reply and opened " + app.title() + ". Open " + m.group(1) + "'s chat, paste, and tap send, Arsh."), "PHONE"
    # open any app with the helper (no tap needed)
    openish = re.search(r"\b(?:open|opan|oppen|sopan|upon|oben|launch|start|show|kholo|khol|chalao)\b", low)
    words = re.findall(r"[a-z]+", low)
    if openish or len(words) <= 2:
        if re.search(r"\b(dual|second|2nd|other|clone|cloned|duplicate)\b", low) and "whatsapp" in low.replace(" ", ""):
            r = helper_job({"type": "open_dual", "package": "com.whatsapp"})
            if r and r["ok"]:
                return "Opening WhatsApp. If Samsung asks, tap the WhatsApp with the dual badge, Arsh.", "PHONE"
            return None
        hit = None
        names = dict((k, (v[1], "", v[0])) for k, v in PHONE_APPS.items())
        names.update(dict((k, (v[0], v[1], "")) for k, v in SAMSUNG.items()))
        for n in sorted(names, key=len, reverse=True):
            if len(n) > 1 and re.search(r"\b" + re.escape(n) + r"\b", low.replace("whats app", "whatsapp").replace("you tube", "youtube")):
                hit = n
                break
        if hit:
            pkg, act, url = names[hit]
            r = helper_job({"type": "open", "package": pkg, "action": act, "url": url})
            if r and r["ok"]:
                return "Opening " + hit + ", Arsh.", "PHONE"
            if r:
                return "I could not open " + hit + ": " + str(r["data"])[:120], "PHONE"
    return None


def think(question):
    """Returns (reply, brain_label)."""
    sw = switch_phrase(question)
    if sw:
        b, tail = sw
        keys = load_keys()
        if b != "auto" and b not in keys:
            return ("I don't have a " + NAMES[b] + " key yet, Arsh. Add it to ai_keys.txt and I will connect.", "SYSTEM")
        STATE["brain"] = b
        if not tail:
            return (("Switching to automatic mode. I will use the fastest connected brains." if b == "auto"
                     else NAMES[b] + " brain activated."), "SYSTEM")
        question = tail
    ql = question.lower()
    wants_code = (re.search(r"\b(pair|pairing|paring|bearing|connect|link)\b.*\b(phone|mobile|helper|code)\b", ql)
                  or re.search(r"\b(termux|terrax|thermax|termax|terminus|tremux)\b", ql)
                  or re.search(r"^\s*(a |the )?(pair ?phone|earphone|air phone|repair phone|pairphone)\s*$", ql)
                  or (re.search(r"\b(repeat|again|tell|say|show|give)\b", ql) and re.search(r"\b(code|number|digits?)\b", ql))
                  or (re.search(r"\b(repeat|again)\b", ql) and PAIR["code"] and time.time() < PAIR["until"]))
    if wants_code and ON_PHONE:
        return "I am already running on your phone, Arsh, so no pairing is needed.", "SYSTEM"
    if wants_code:
        if not (PAIR["code"] and time.time() < PAIR["until"]):
            PAIR["code"] = "%06d" % random.SystemRandom().randint(0, 999999)
            PAIR["until"] = time.time() + 600
            PAIR["bad"] = 0
        CLIENT.show = "PAIRING CODE  " + PAIR["code"]
        return ("Your phone pairing code is " + ", ".join(PAIR["code"]) + ". I also put it on the screen. "
                "Type it in the ULTRON app on your phone (ULTRON+, Phone + PC) within 10 minutes, Arsh."), "SYSTEM"
    if re.search(r"\b(phone helper|helper)\b.*\b(status|online|connected|working)\b", question.lower()):
        return ("My phone helper is connected." if helper_online() else "My phone helper is not connected right now."), "SYSTEM"
    handled = command(question)
    if handled:
        return handled
    if getattr(CLIENT, "phone", False):
        pc = re.search(r"\s+(?:on|in) (?:my |the )?(?:pc|computer|laptop)$", question.lower())
        if pc and ON_PHONE:
            return "This is the phone copy of me, Arsh. PC commands work when you use me on the PC.", "SYSTEM"
        if pc:
            question = question[:pc.start()]
            done = action(question)
        else:
            done = helper_action(question) if helper_online() else None
            if done:
                print("  * phone helper: " + done[0][:90])
                return done
            done = phone_action(question)
            if done:
                CLIENT.link = done[2]
                print("  * phone action: " + done[0])
                return done[0], done[1]
    else:
        onphone = re.search(r"\s+(?:on|in|from) (?:my |the )?(?:phone|mobile)$", question.lower())
        if onphone and helper_online():
            done = helper_action(question[:onphone.start()])
            if done:
                print("  * phone (from PC): " + done[0][:90])
                return done
            return ("I can read your phone's notifications and SMS, open apps on it, and call or text with your approval on the phone. "
                    "Say it like: read my notifications on my phone."), "PHONE"
        if onphone:
            return "Your phone isn't connected to me right now, Arsh. Open ULTRON on the phone (same Wi-Fi) and pair it in ULTRON+.", "PHONE"
        done = action(question)
    if done:
        print("  * action: " + done[0])
        return done
    keys = load_keys()
    if not keys:
        local = ollama_reply(question)
        if local:
            return local[:core.MAX_CHARS], "OFFLINE BRAIN"
        raise RuntimeError("My brain is not connected yet. Put your AI keys in ai_keys.txt.")
    with HISTORY_LOCK:
        past = HISTORY[-12:]
    low = question.lower()
    for lead in ("ask all ", "ask everyone ", "council ", "all brains "):
        if low.startswith(lead) and len(keys) > 1:
            return council(question[len(lead):].strip() or question, keys, past)
    note = ""
    if STATE["brain"] != "auto" and STATE["brain"] in keys and keys[STATE["brain"]].startswith("OR:") and OR_INFO.get("free"):
        note = NAMES[STATE["brain"]] + " needs OpenRouter credit, which is not added yet, so I switched back to my free brains. "
        STATE["brain"] = "auto"
        order = [b for b in ORDER if b in keys and not keys[b].startswith("OR:")]
        reply, label = race(question, keys, past, order) if len(order) > 1 else (None, None)
        if reply:
            return (note + reply)[:core.MAX_CHARS], label
    if STATE["brain"] != "auto" and STATE["brain"] in keys:
        order = [STATE["brain"]]
    else:
        # automatic mode races only the free / direct brains, so paid OpenRouter models are not billed every time
        order = [b for b in ORDER if b in keys and not keys[b].startswith("OR:")] or [b for b in ORDER if b in keys]
    if STATE["brain"] == "auto" and len(order) > 1:
        try:
            return race(question, keys, past, order)
        except Exception:
            local = ollama_reply(question, past)          # free offline brain on this PC
            if local:
                remember(question, local)
                return local[:core.MAX_CHARS], "OFFLINE BRAIN"
            raise
    errors = []
    for b in order:
        try:
            reply = clean(ask_one(b, keys[b], past + [{"role": "user", "content": question}], system_prompt(NAMES[b])))
            if reply:
                remember(question, reply)
                return reply[:core.MAX_CHARS], NAMES[b].upper()
        except Exception as e:
            print("  ! " + str(e))
            errors.append(str(e))
    local = ollama_reply(question, past)
    if local:
        remember(question, local)
        return local[:core.MAX_CHARS], "OFFLINE BRAIN"
    if len(order) == 1:
        raise RuntimeError("My " + NAMES[order[0]] + " brain could not answer, Arsh. " + "; ".join(errors)[:160])
    raise RuntimeError("All my brains failed, Arsh. " + "; ".join(errors)[:200])


def race(question, keys, past, order):
    """Ask every connected brain at once; the first good answer wins."""
    import queue
    results = queue.Queue()
    def run(b):
        try:
            results.put((b, clean(ask_one(b, keys[b], past + [{"role": "user", "content": question}], system_prompt(NAMES[b]))), None))
        except Exception as e:
            results.put((b, "", str(e)))
    for b in order:
        threading.Thread(target=run, args=(b,), daemon=True).start()
    errors = []
    for _ in order:
        try:
            b, reply, err = results.get(timeout=90)
        except Exception:
            break
        if reply:
            remember(question, reply)
            return reply[:core.MAX_CHARS], NAMES[b].upper()
        print("  ! " + err)
        errors.append(err)
    raise RuntimeError("All my brains failed, Arsh. " + "; ".join(errors)[:200])


def council(question, keys, past):
    answers = {}
    def run(b):
        try:
            answers[b] = clean(ask_one(b, keys[b], past + [{"role": "user", "content": question}], system_prompt(NAMES[b]), 300))
        except Exception as e:
            print("  ! " + str(e))
    threads = [threading.Thread(target=run, args=(b,)) for b in ORDER if b in keys]
    for t in threads:
        t.start()
    for t in threads:
        t.join(60)
    got = [(b, a) for b, a in answers.items() if a]
    if not got:
        raise RuntimeError("None of my brains answered, Arsh.")
    for b, a in got:
        print("    [" + NAMES[b] + "] " + a[:120])
    free = [b for b, _ in got if not keys[b].startswith("OR:")]
    judge = STATE["brain"] if STATE["brain"] in answers else (free or [got[0][0]])[0]
    notes = "\n".join(NAMES[b] + " said: " + a for b, a in got)
    try:
        reply = clean(ask_one(judge, keys[judge], [{"role": "user", "content":
            "Arsh asked: " + question + "\n\nAnswers from different AI brains:\n" + notes +
            "\n\nCombine them into one best spoken answer of two to four sentences. "
            "If they disagree, say so briefly. Mention which brains you consulted."}], system_prompt("a council of AIs"), 400))
    except Exception:
        reply = got[0][1]
    remember(question, reply)
    return reply[:core.MAX_CHARS], "COUNCIL · " + " + ".join(NAMES[b].upper() for b, _ in got)


# Make the page ask the brain instead of repeating the command.
PAGE_OLD = "later(2400, function () { enter('speak'); say(reply); });"
PAGE_NEW = ("if (!cmd) { later(2400, function () { enter('speak'); say(reply); }); return; } "
            "var tok = window.__ultronAsk = (window.__ultronAsk || 0) + 1; "
            "addLog('NEURAL CORE · THINKING', 'br'); "
            "fetch('/ask', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: cmd }) })"
            ".then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); })"
            ".then(function (x) { if (tok !== window.__ultronAsk) return; enter('speak'); "
            "if (x.ok && x.j.brain) addLog('BRAIN \u203a ' + x.j.brain, 'hl'); if (x.ok && x.j.open && window.ultronOpen) window.ultronOpen(x.j.open); if (x.ok && x.j.show && window.ultronShow) window.ultronShow(x.j.show); say(x.ok ? x.j.reply : String(x.j.error || 'My brain is offline, Arsh.')); })"
            ".catch(function () { if (tok !== window.__ultronAsk) return; enter('speak'); say('I cannot reach my brain right now, Arsh.'); });")


LOCK_PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><title>ULTRON - Locked</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Orbitron:wght@900&family=Share+Tech+Mono&display=swap">
<style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:radial-gradient(ellipse at 50% 45%,#12060a,#030405 70%);color:#C9CED6;font-family:'Share Tech Mono',monospace}
.box{width:min(420px,90vw);padding:36px 32px;border:1px solid rgba(255,23,40,.45);background:rgba(6,7,10,.85);clip-path:polygon(18px 0,100% 0,100% calc(100% - 18px),calc(100% - 18px) 100%,0 100%,0 18px);text-align:center}
h1{margin:0 0 6px;font-family:'Orbitron',sans-serif;font-size:34px;letter-spacing:.45em;margin-right:-.45em;color:#ECEFF3}
p{margin:0 0 26px;font-size:11px;letter-spacing:.32em;color:#FF4452}
label{display:block;text-align:left;font-size:11px;letter-spacing:.2em;color:#8A919C;margin-bottom:8px}
input{width:100%;box-sizing:border-box;height:46px;padding:0 14px;background:#07080b;border:1px solid rgba(150,160,175,.25);color:#E6E9EE;font-family:inherit;font-size:16px;letter-spacing:.2em;outline:none}
input:focus{border-color:#FF1728}
button{margin-top:16px;width:100%;height:46px;border:1px solid rgba(255,23,40,.6);background:rgba(255,23,40,.12);color:#FF4452;font-family:inherit;font-size:13px;letter-spacing:.3em;cursor:pointer}
button:hover{background:rgba(255,23,40,.22)}
#msg{min-height:18px;margin-top:14px;font-size:12px;letter-spacing:.12em;color:#FF4452}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#FF1728;box-shadow:0 0 10px #FF1728;margin-right:8px;vertical-align:1px}
</style></head><body>
<form class="box" id="f">
<h1>ULTRON</h1>
<p><span class="dot"></span>ACCESS RESTRICTED &middot; OWNER ONLY</p>
<label for="pw">PASSWORD</label>
<input id="pw" type="password" autocomplete="current-password" autofocus>
<button type="submit">UNLOCK</button>
<div id="msg" role="status"></div>
</form>
<script>
document.getElementById('f').addEventListener('submit', async function (e) {
  e.preventDefault();
  var msg = document.getElementById('msg');
  msg.textContent = 'VERIFYING...';
  try {
    var r = await fetch('/login', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({password: document.getElementById('pw').value})});
    if (r.ok) { msg.textContent = 'ACCESS GRANTED'; location.reload(); return; }
    var j = await r.json();
    msg.textContent = (j.error || 'ACCESS DENIED').toUpperCase();
  } catch (err) { msg.textContent = 'ULTRON IS NOT RUNNING'; }
  document.getElementById('pw').value = '';
});
</script></body></html>"""

LOCK_BUTTON_JS = r"""
(function () {
  var b = document.createElement('button');
  b.type = 'button';
  b.className = 'u-seg';
  b.textContent = 'LOCK';
  b.setAttribute('aria-label', 'Lock ULTRON');
  b.style.cssText = 'position:absolute;top:66px;left:40px;z-index:5;border:1px solid rgba(255,23,40,.4)';
  b.onclick = function () { fetch('/logout', {method: 'POST'}).then(function () { location.reload(); }); };
  document.getElementById('root').appendChild(b);
  var st = document.createElement('style');
  st.textContent = '@media (max-width:760px){.u-side,.u-corner{display:none!important}' +
    '#u-exprbar{position:fixed!important;left:0!important;right:0!important;bottom:0!important;width:100%!important;' +
    'overflow-x:auto!important;transform:none!important;justify-content:flex-start!important}' +
    '.u-seg[aria-label="Lock ULTRON"]{top:8px!important;left:8px!important;padding:4px 8px!important;font-size:10px!important}}';
  document.head.appendChild(st);
})();
"""
PHONE_JS = r"""
(function () {
  var box = null, timer = null;
  window.ultronOpen = function (link) {
    if (box) box.remove();
    box = document.createElement('button');
    box.type = 'button';
    box.textContent = 'TAP TO OPEN ›';
    box.style.cssText = 'position:fixed;left:12px;right:12px;bottom:64px;z-index:50;height:56px;font:600 15px "Share Tech Mono",monospace;' +
      'letter-spacing:.2em;color:#fff;background:rgba(255,23,40,.85);border:1px solid #FF4452;box-shadow:0 0 24px rgba(255,23,40,.6)';
    box.onclick = function () { go(link); box.remove(); box = null; };
    document.body.appendChild(box);
    clearTimeout(timer);
    timer = setTimeout(function () { if (box) { box.remove(); box = null; } }, 30000);
    try { if (/^(intent|tel|sms|mailto):/.test(link)) location.href = link; } catch (e) {}
  };
  window.ultronShow = function (text) {
    var el = document.createElement('div');
    el.textContent = text;
    el.style.cssText = 'position:fixed;left:12px;right:12px;top:30%;z-index:60;padding:22px 10px;text-align:center;' +
      'font:700 28px "Share Tech Mono",monospace;letter-spacing:.18em;color:#fff;background:rgba(6,7,10,.96);' +
      'border:2px solid #FF1728;box-shadow:0 0 30px rgba(255,23,40,.6)';
    var x = document.createElement('div');
    x.textContent = 'tap to close';
    x.style.cssText = 'font-size:11px;letter-spacing:.2em;color:#FF4452;margin-top:10px';
    el.appendChild(x);
    el.onclick = function () { el.remove(); };
    document.body.appendChild(el);
    setTimeout(function () { el.remove(); }, 120000);
  };
  function go(link) {
    if (/^(intent|tel|sms|mailto):/.test(link)) location.href = link;
    else window.open(link, '_blank');
  }
  if (/Android|iPhone|Mobile/.test(navigator.userAgent) && !window.isSecureContext) {
    var mic = document.getElementById('mic');
    if (mic) mic.addEventListener('click', function () {
      setTimeout(function () {
        var log = document.querySelector('#log, .u-log, [data-log]');
        var tip = 'MIC NEEDS ONE CHROME SETTING · OR USE THE KEYBOARD MIC IN THE TEXT BOX';
        var el = document.createElement('div');
        el.textContent = tip;
        el.style.cssText = 'position:fixed;left:12px;right:12px;top:44px;z-index:50;padding:10px;font:12px "Share Tech Mono",monospace;' +
          'letter-spacing:.08em;color:#FF4452;background:rgba(6,7,10,.95);border:1px solid rgba(255,23,40,.5)';
        document.body.appendChild(el);
        setTimeout(function () { el.remove(); }, 7000);
      }, 1500);
    }, true);
  }
})();
"""
# No HAPPY or SURPRISED expressions (they show teeth). Everything else stays automatic.
core.EXTRA_JS = (core.EXTRA_JS
                 .replace("var names = ['smile', 'happy', 'curious', 'surprised', 'angry', 'sad'];",
                          "var names = ['smile', 'curious', 'angry', 'sad'];")
                 .replace("show('surprised', 1400)", "show('curious', 1400)")) + LOCK_BUTTON_JS + PHONE_JS
if "var names = ['smile', 'curious', 'angry', 'sad'];" not in core.EXTRA_JS:
    print("  ! could not remove the happy/surprised expressions")


# ---------------- Phone app (install ULTRON on the home screen) ----------------
APP_MANIFEST = json.dumps({
    "name": "ULTRON", "short_name": "ULTRON", "id": "/", "start_url": "/", "scope": "/",
    "display": "standalone", "orientation": "portrait", "background_color": "#030405", "theme_color": "#030405",
    "description": "Arsh's personal AI assistant",
    "icons": [{"src": "/ultron_icon_192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
              {"src": "/ultron_icon_512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
              {"src": "/ultron_icon_512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"}],
}).encode("utf-8")
APP_SW = b"""self.addEventListener('install', function (e) { self.skipWaiting(); });
self.addEventListener('activate', function (e) { e.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', function (e) {
  if (e.request.mode !== 'navigate') return;
  e.respondWith(fetch(e.request).catch(function () {
    return new Response('<body style=\"background:#030405;color:#FF4452;font:16px monospace;padding:40px;text-align:center\">' +
      'ULTRON is offline.<br><br>Check that the PC is on, ULTRON is running, and your phone is on the same Wi-Fi.</body>',
      { headers: { 'Content-Type': 'text/html' } });
  }));
});
"""
APP_HEAD = ('<link rel="manifest" href="/manifest.json"><meta name="theme-color" content="#030405">'
            '<meta name="mobile-web-app-capable" content="yes"><meta name="apple-mobile-web-app-capable" content="yes">'
            '<link rel="icon" href="/ultron_icon_192.png"><link rel="apple-touch-icon" href="/ultron_icon_192.png">'
            '<script>if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(function () {});</script>')
LOCK_PAGE = LOCK_PAGE.replace("</head>", APP_HEAD + "</head>", 1)


def app_file(self, route):
    """Files the phone needs to install ULTRON as an app. No secrets in them."""
    if route == "/manifest.json":
        self._bytes(APP_MANIFEST, "application/manifest+json")
        return True
    if route == "/sw.js":
        self._bytes(APP_SW, "text/javascript")
        return True
    if route in ("/ultron_icon_192.png", "/ultron_icon_512.png"):
        path = os.path.join(core.HERE, route.lstrip("/"))
        if os.path.exists(path):
            with open(path, "rb") as f:
                self._bytes(f.read(), "image/png")
            return True
    return False


PHONE_INSTALL_SH = '#!/data/data/com.termux/files/usr/bin/bash\n# ULTRON phone install - copies ULTRON from the PC to this phone. No keys or passwords are copied.\nset -e\nPC="http://192.168.1.10:8765"\nTOK="$(cat ~/.ultron_phone_token 2>/dev/null || true)"\nif [ -z "$TOK" ]; then echo "This phone is not paired yet. Run: python ultron_phone.py  and pair first."; exit 1; fi\necho "== Installing tools (one time, a few minutes) =="\npkg install -y python termux-api procps unzip nano >/dev/null\necho "== Downloading ULTRON from your PC =="\nmkdir -p ~/ultron\ncurl -fsS -H "X-Ultron-Phone: $TOK" -o ~/ultron_pack.zip "$PC/phone/pack.zip"\nunzip -o -q ~/ultron_pack.zip -d ~/ultron && rm -f ~/ultron_pack.zip\ncat > "$PREFIX/bin/ultron" <<\'EOS\'\n#!/data/data/com.termux/files/usr/bin/bash\ncd ~/ultron\ntermux-wake-lock 2>/dev/null || true\nif ! pgrep -f "python ULTRON.py" >/dev/null; then\n  if [ ! -f ultron_lock.json ]; then python ULTRON.py --no-open; exit; fi\n  nohup python ULTRON.py --no-open >/dev/null 2>&1 &\n  sleep 4\nfi\ntermux-open-url http://127.0.0.1:8765/\necho "ULTRON is running on this phone."\nEOS\ncat > "$PREFIX/bin/ultron-stop" <<\'EOS\'\n#!/data/data/com.termux/files/usr/bin/bash\npkill -f "python ULTRON.py" && echo "ULTRON stopped." || echo "ULTRON was not running."\nEOS\ncat > "$PREFIX/bin/ultron-update" <<\'EOS\'\n#!/data/data/com.termux/files/usr/bin/bash\ncurl -fsS http://192.168.1.10:8765/phone/install.sh | bash && ultron-stop; ultron\nEOS\nchmod +x "$PREFIX/bin/ultron" "$PREFIX/bin/ultron-stop" "$PREFIX/bin/ultron-update"\nif [ ! -f ~/ultron/ai_keys.txt ]; then\ncat > ~/ultron/ai_keys.txt <<\'EOS\'\nPaste your AI keys below, one per line (Gemini, OpenRouter, and any others).\nThen press CTRL + O, Enter to save, and CTRL + X to close.\n\nEOS\nfi\necho ""\necho "== ULTRON is installed on this phone =="\necho "Next: type   nano ~/ultron/ai_keys.txt   and paste your keys."\necho "Then type    ultron    to start it."\n'
PACK_FILES = ("ULTRON.py", "ultron_voice_server.py", "ultron_index.html", "ultron_phone.py",
              "ultron_icon_192.png", "ultron_icon_512.png")


def phone_pack():
    """ULTRON's program files for the phone. Never includes keys, passwords or tokens."""
    import io, zipfile
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name in PACK_FILES:
            path = os.path.join(core.HERE, name)
            if os.path.exists(path):
                z.write(path, name)
        faces = os.path.join(core.HERE, "ULTRON_face2")
        if os.path.isdir(faces):
            for name in os.listdir(faces):
                if name.lower().endswith(".png"):
                    z.write(os.path.join(faces, name), "ULTRON_face2/" + name)
    return buf.getvalue()

H = core.Handler
orig_get, orig_post = H.do_GET, H.do_POST


def authed(self):
    for part in self.headers.get("Cookie", "").split(";"):
        k, _, v = part.strip().partition("=")
        if k == "ultron_session" and v in SESSIONS:
            return True
    return False


def do_GET(self):
    route = self.path.split("?")[0]
    if app_file(self, route):
        return
    if route == "/phone/fileget":
        tok = phone_token()
        if not tok or not hmac.compare_digest(self.headers.get("X-Ultron-Phone", ""), tok):
            return self._json(401, {"error": "not paired"})
        k = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "").get("k", [""])[0]
        path = OUTBOX.get(k)
        if not path or not os.path.isfile(path):
            return self._json(404, {"error": "no such file"})
        size = os.path.getsize(path)
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with open(path, "rb") as f:
            while True:
                chunk = f.read(1 << 20)
                if not chunk:
                    break
                self.wfile.write(chunk)
        print("  > sent " + os.path.basename(path) + " to the phone")
        return
    if route == "/app/info":
        # lets the phone app see if a newer ULTRON.apk is here without downloading it
        path = os.path.join(core.HERE, "ULTRON.apk")
        if not os.path.exists(path):
            return self._json(404, {"error": "no app"})
        st = os.stat(path)
        return self._json(200, {"size": st.st_size, "mtime": int(st.st_mtime)})
    if route in ("/app", "/phone/ULTRON.apk", "/ULTRON.apk"):
        path = os.path.join(core.HERE, "ULTRON.apk")
        if os.path.exists(path):
            with open(path, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="ULTRON.apk"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            print("  > sent the ULTRON app to a phone")
            return
    if route == "/phone/install.sh":
        return self._bytes(PHONE_INSTALL_SH.encode("utf-8"), "text/plain; charset=utf-8")
    if route == "/phone/pack.zip":
        tok = phone_token()
        if not tok or not hmac.compare_digest(self.headers.get("X-Ultron-Phone", ""), tok):
            return self._json(401, {"error": "not paired"})
        print("  > sending ULTRON to the phone")
        return self._bytes(phone_pack(), "application/zip")
    if route == "/phone/helper.py":
        path = os.path.join(core.HERE, "ultron_phone.py")
        if os.path.exists(path):
            with open(path, "rb") as f:
                return self._bytes(f.read(), "text/plain; charset=utf-8")
    if authed(self):
        page = os.path.join(core.HERE, "ultron_index.html")
        if route in ("/", "/index.html") and os.path.exists(page):
            with open(page, "r", encoding="utf-8") as f:
                html = f.read()
            if PAGE_OLD not in html:
                print("  ! could not connect the brain to the page")
            html = html.replace(PAGE_OLD, PAGE_NEW)
            html = html.replace("</body>", '<script src="ultron_extra.js"></script></body>')
            html = html.replace("</head>", APP_HEAD + "</head>", 1)
            return self._bytes(html.encode("utf-8"), "text/html; charset=utf-8")
        return orig_get(self)
    if route in ("/", "/index.html"):
        return self._bytes(LOCK_PAGE.encode("utf-8"), "text/html; charset=utf-8")
    return self._json(401, {"error": "locked"})


def do_POST(self):
    route = self.path.split("?")[0]
    if route == "/login":
        if time.time() < FAILS["until"]:
            return self._json(429, {"error": "Too many tries. Wait 30 seconds."})
        n = int(self.headers.get("Content-Length", "0"))
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            body = {}
        if check(str(body.get("password", ""))):
            FAILS["n"] = 0
            tok = secrets.token_hex(24)
            SESSIONS.add(tok)
            data = b'{"ok": true}'
            self.send_response(200)
            self.send_header("Set-Cookie", "ultron_session=" + tok + "; HttpOnly; SameSite=Strict; Path=/")
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            print("  > unlocked")
            return
        FAILS["n"] += 1
        if FAILS["n"] >= 5:
            FAILS["n"] = 0
            FAILS["until"] = time.time() + 30
        time.sleep(1)
        print("  ! wrong password attempt")
        return self._json(403, {"error": "Access denied"})
    if route == "/logout":
        SESSIONS.clear()
        return self._json(200, {"ok": True})
    if route == "/phone/file":
        tok = phone_token()
        if not tok or not hmac.compare_digest(self.headers.get("X-Ultron-Phone", ""), tok):
            return self._json(401, {"error": "not paired"})
        n = int(self.headers.get("Content-Length", "0") or 0)
        if n <= 0 or n > 500 * 1024 * 1024:
            return self._json(400, {"error": "bad size"})
        name = os.path.basename(urllib.parse.unquote(self.headers.get("X-Name", "file"))).strip() or "file"
        name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name)[:120]
        os.makedirs(PHONE_INBOX, exist_ok=True)
        path = os.path.join(PHONE_INBOX, name)
        base, ext = os.path.splitext(path)
        i = 1
        while os.path.exists(path):
            path = base + " (" + str(i) + ")" + ext
            i += 1
        left = n
        with open(path, "wb") as f:
            while left > 0:
                chunk = self.rfile.read(min(1 << 20, left))
                if not chunk:
                    break
                f.write(chunk)
                left -= len(chunk)
        print("  > file from phone: " + path)
        return self._json(200, {"ok": True, "saved": path})
    if route.startswith("/phone/"):
        n = int(self.headers.get("Content-Length", "0") or 0)
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            body = {}
        if route == "/phone/pair":
            if PAIR["code"] and time.time() < PAIR["until"] and hmac.compare_digest(str(body.get("code", "")), PAIR["code"]):
                PAIR["code"] = ""
                tok = secrets.token_hex(32)
                with open(PHONE_TOKEN_FILE, "w", encoding="utf-8") as f:
                    f.write(tok)
                print("  > phone helper paired")
                return self._json(200, {"token": tok})
            PAIR["bad"] = PAIR.get("bad", 0) + 1
            if PAIR["bad"] >= 5:
                PAIR["code"], PAIR["bad"] = "", 0
            time.sleep(1)
            return self._json(403, {"error": "wrong or old code"})
        tok = phone_token()
        if not tok or not hmac.compare_digest(self.headers.get("X-Ultron-Phone", ""), tok):
            return self._json(401, {"error": "not paired"})
        if route == "/phone/poll":
            with HELPER_COND:
                if not helper_online():
                    print("  > phone helper connected")
                HELPER["seen"] = time.time()
                end = time.time() + 25
                while not HELPER["jobs"] and time.time() < end:
                    HELPER_COND.wait(end - time.time())
                HELPER["seen"] = time.time()
                job = HELPER["jobs"].pop(0) if HELPER["jobs"] else None
            return self._json(200, {"job": job})
        if route == "/phone/brain":
            # offline brain for the phone when its cloud brains fail
            q = str(body.get("question", ""))[:6000]
            out = ollama_reply(q, None, str(body.get("system", ""))[:8000] or None)
            if not out:
                return self._json(503, {"error": "no offline brain (install Ollama on the PC)"})
            return self._json(200, {"reply": out, "model": _OLL["model"]})
        if route == "/phone/clip":
            ok = pc_clipboard_set(str(body.get("text", ""))[:100000])
            print("  > clipboard from phone" + ("" if ok else " (failed)"))
            return self._json(200 if ok else 500, {"ok": ok})
        if route == "/phone/ask":
            # a command from the paired phone app, carried out on this PC ("open chrome", "play music", "shut down")
            HELPER["seen"] = time.time()
            q = str(body.get("text", "")).strip()[:500]
            if not q:
                return self._json(400, {"error": "no command"})
            CLIENT.phone = False
            CLIENT.link = ""
            CLIENT.show = ""
            print("  ? [from phone] " + q[:80])
            try:
                reply, brain = think(q)
            except Exception as e:
                return self._json(502, {"error": str(e)[:200]})
            print("  > [" + brain + "] " + reply[:90])
            return self._json(200, {"reply": reply, "brain": brain})
        if route == "/phone/sync":
            HELPER["seen"] = time.time()
            got = add_shared([m for m in body.get("memories", []) if isinstance(m, dict)], "phone")
            if isinstance(body.get("status"), dict):
                with open(PHONE_STATUS_FILE, "w", encoding="utf-8") as f:
                    json.dump(body["status"], f, ensure_ascii=False)
            if got:
                print("  > phone shared " + str(got) + " new memories")
            return self._json(200, {"memories": [m for m in shared_memories() if m.get("from") != "phone"]})
        if route == "/phone/report":
            with HELPER_COND:
                HELPER["results"][body.get("id")] = {"ok": bool(body.get("ok")), "data": body.get("data")}
                HELPER_COND.notify_all()
            return self._json(200, {"ok": True})
        return self._json(404, {"error": "unknown"})
    if not authed(self):
        return self._json(401, {"error": "locked"})
    if route == "/ask":
        n = int(self.headers.get("Content-Length", "0"))
        try:
            q = str(json.loads(self.rfile.read(n) or b"{}").get("text", "")).strip()[:2000]
            if not q:
                return self._json(400, {"error": "no question"})
            ua = self.headers.get("User-Agent", "")
            CLIENT.phone = ON_PHONE or bool(re.search(r"Android|iPhone|iPad|Mobile", ua))
            CLIENT.link = ""
            CLIENT.show = ""
            print("  ? " + ("[phone] " if CLIENT.phone else "") + q[:80])
            reply, brain = think(q)
            print("  > [" + brain + "] " + reply[:90])
            return self._json(200, {"reply": reply, "brain": brain, "open": CLIENT.link, "show": CLIENT.show})
        except Exception as e:
            print("  ! brain: " + str(e)[:200])
            return self._json(502, {"error": str(e).split(" {")[0][:220]})
    return orig_post(self)


H.do_GET = do_GET
H.do_POST = do_POST

def self_check():
    """Test each brain once at start-up and write the result to ultron_log.txt."""
    time.sleep(4)
    keys = load_keys()
    print("--- brain check " + time.strftime("%Y-%m-%d %H:%M:%S") + " ---")
    if "openrouter" in keys:
        try:
            info = http_json("https://openrouter.ai/api/v1/key", {"Authorization": "Bearer " + keys["openrouter"]}).get("data", {})
            OR_INFO["free"] = bool(info.get("is_free_tier"))
            print("  OpenRouter account: free tier=" + str(info.get("is_free_tier")) + ", usage=" + str(info.get("usage"))
                  + ", limit=" + str(info.get("limit")) + ", limit remaining=" + str(info.get("limit_remaining")))
        except urllib.error.HTTPError as e:
            print("  OpenRouter account check: error " + str(e.code) + " " + " ".join(e.read().decode("utf-8", "replace").split())[:150])
        except Exception as e:
            print("  OpenRouter account check failed: " + str(e)[:150])
    for b in ORDER:
        if b not in keys:
            continue
        if keys[b].startswith("OR:"):
            print("  " + NAMES[b] + ": via OpenRouter (paid model, not tested to save credit)")
            continue
        t0 = time.time()
        try:
            r = clean(ask_one(b, keys[b], [{"role": "user", "content": "Reply with the single word OK."}], "You are a test.", 20))
            print("  " + NAMES[b] + ": OK in " + str(round(time.time() - t0, 1)) + "s (model " + MODELS[b] + ") -> " + r[:40])
        except Exception as e:
            print("  " + NAMES[b] + ": FAILED -> " + str(e)[:250])
    print("--- end of brain check ---")


def lan_ip():
    import socket
    try:
        s_ = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s_.connect(("8.8.8.8", 80))
        ip = s_.getsockname()[0]
        s_.close()
        return ip
    except Exception:
        return "127.0.0.1"


def watch_for_updates():
    """When ULTRON.py is updated, restart ULTRON automatically with the new version."""
    me = os.path.abspath(__file__)
    try:
        first = os.path.getmtime(me)
    except OSError:
        return
    while True:
        time.sleep(3)
        try:
            if os.path.getmtime(me) != first:
                time.sleep(2)
                print("  > ULTRON.py was updated - restarting with the new version")
                import subprocess
                log = open(os.path.join(core.HERE, "ultron_log.txt"), "a", encoding="utf-8")
                subprocess.Popen([sys.executable, me, "--no-open", "--delay"], cwd=core.HERE, stdout=log, stderr=log,
                                 stdin=subprocess.DEVNULL, creationflags=0x08000000)
                os._exit(0)
        except OSError:
            pass


if __name__ == "__main__":
    if sys.stdout is None or not sys.stdout.isatty():
        try:
            _log = open(os.path.join(core.HERE, "ultron_log.txt"), "a", encoding="utf-8", buffering=1)
            _log.write("\n=== ULTRON started " + time.strftime("%Y-%m-%d %H:%M:%S") + " ===\n")
            sys.stdout = sys.stderr = _log
        except Exception:
            pass
    try:
        if "--delay" in sys.argv:
            time.sleep(3)
        if not ON_PHONE:
            threading.Thread(target=watch_for_updates, daemon=True).start()
            threading.Thread(target=build_app_index, daemon=True).start()
        threading.Thread(target=self_check, daemon=True).start()
        setup()
        k = load_keys()
        have = [NAMES[b] + (" (via OpenRouter)" if k[b].startswith("OR:") else "") for b in ORDER if b in k]
        print("Brains connected: " + (", ".join(have) if have else "none yet - add keys to ai_keys.txt"))
        if ON_PHONE:
            print("PHONE MODE: open http://127.0.0.1:" + str(core.PORT) + "/ in Chrome on this phone")
        elif os.environ.get("ULTRON_PHONE", "1") == "1":
            core.HOST = "0.0.0.0"
            print("PHONE: open http://" + lan_ip() + ":" + str(core.PORT) + "/ in Chrome on your phone (same Wi-Fi)")
        core.ThreadingHTTPServer.allow_reuse_address = False; core.main()
    except Exception as e:
        print("ERROR: " + str(e))
        input("Press Enter to close...")
