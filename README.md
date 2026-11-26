<p align="center"><img src="android/assets/icon.png" width="120" alt="ULTRON icon"></p>

# ULTRON — Cross-Platform Personal AI Agent

ULTRON is a voice-controlled personal AI assistant that I designed and built for my own daily use. It runs as a **native Android app** (Java, 30 classes, about 11,300 lines) and as a **Python server on Windows**. The two pair over home Wi-Fi so they work as one assistant.

It reads incoming messages and calls, writes context-aware replies in the sender's language, runs voice commands, manages reminders, routines and the calendar, and keeps the owner in control through a 3-level approval system and an emergency STOP.

> Status: in daily use on my Samsung Galaxy A54 (Android 17 / One UI 9) and Windows PC. Current Android version: **9.4** (28 releases).

## Highlights

| Area | What ULTRON does |
|---|---|
| **Multi-model AI brain** | Routes each request across Gemini, Claude, ChatGPT, Grok (via OpenRouter) and Groq. Every answer is timed, the fastest reliable brain is tried first, a brain that fails 3 times in a row rests for 10 minutes, and the ranking survives restarts. If every cloud brain fails, it falls back to a local open-source model on the PC (Ollama). |
| **Messaging agent** | Writes same-language, context-aware replies across WhatsApp, Instagram, Telegram, Snapchat, SMS and email notifications. One reply per message, duplicate suppression, and chat-identity checks before every send. If anything is uncertain, the reply is held for the owner. |
| **Human-in-the-loop safety** | 3 approval levels: routine replies are automatic, anything personal or a commitment needs approval, and money, security or legal matters are always explicit. Plus an emergency STOP. ULTRON always introduces itself as an assistant and never pretends to be the owner. It refuses OTPs and payment actions. |
| **Voice** | Speech recognition in English and Hindi, a custom deep voice (Fish Audio TTS) with a worker queue, retries and a 60-phrase cache. Conversation mode listens again after every answer. A "Hey ULTRON" wake word works while the app is open. |
| **Calls** | Detects SIM and app calls (WhatsApp, Instagram, Telegram and others) through Android listeners, keeps an encrypted call log, and can decline a call with a text. It also handles call-backs and emergency escalation with strict limits. |
| **Daily life** | Daily briefing, routines (sleep, driving, meeting, study) with Do Not Disturb, exact-alarm reminders that survive restarts, calendar read and add, lists, notes, scheduled and group messages, news, cricket, weather, currency rates. |
| **Learning** | A nightly self-review learns reply style and shortcuts from the owner's edits. Lessons that touch approvals, privacy, money or security are automatically thrown away. Everything learned is listed in the app with a FORGET button. |
| **Vision and study** | Camera "what is this / solve this / translate this", screenshot reading, and quizzes from the owner's own notes and PDFs (vision models). |
| **Security** | API keys in encrypted storage (Android Keystore), fingerprint or PIN lock, app lock with an intruder photo, scam-message detection, and a money tracker that never stores balances or account numbers. |
| **Phone + PC link** | Pairing code, shared memory, file and clipboard transfer both ways, PC control from the phone (confirmed first), and self-update from the PC with package and signature checks. |

A full feature list with status is in [docs/FEATURES.md](docs/FEATURES.md).

## Architecture

```
Android app (com.arsh.ultron, Java)            Windows PC (Python)
├─ NotifService   notification listener        ├─ ULTRON.py          password-protected launcher,
├─ TapService     accessibility helper         │                     commands, phone link API
├─ AutoReply      reply agent + approvals      ├─ ultron_voice_server.py  voice + AI brain server
├─ Brain          multi-model routing          ├─ ultron_index.html  ULTRON's face + UI
├─ Speaker        Fish TTS queue + cache       └─ ultron_phone.py    phone helper (Termux)
├─ Calls, Owner   calls + owner alerts
├─ Agenda, Routines, Daily   reminders, routines, briefing
├─ Learn, Memory  self-review + long-term memory
├─ PcLink, Updater   pairing + self-update
└─ assets/*.html  UI screens (WebView)
```

- **Android:** Java 8 with no Gradle. It's built with the Android SDK command-line tools (`aapt`, `javac`, `d8/dx`, `zipalign`, `apksigner`) using `android/build.sh`. It uses the notification listener, accessibility, telephony and SMS, calendar, exact alarms, biometrics, the Android Keystore, and speech recognition plus text-to-speech.
- **PC:** Python 3, standard library HTTP server, PBKDF2 password lock, and a local network API for the phone.

## Run it yourself

ULTRON is a personal assistant, so you need your **own** API keys. No keys are included in this repository.

1. **PC:** put your keys in `ai_keys.txt` (and your Fish Audio key in `fish_api_key.txt`) next to `pc/ULTRON.py`, then run `python ULTRON.py` and create a password on first start.
2. **Android:** run `android/build.sh` with the Android SDK build tools installed. It creates a new signing key on first build. Then install the APK, open ULTRON, enter your keys in **Keys**, and grant the permissions it asks for.

## How it was built

I wrote the full product specification (30+ sections covering identity, approvals, safety, calls, memory and routines), made the architecture and safety decisions, and tested every release on a real phone. I built it with AI-assisted engineering (LLM pair programming) and own the requirements, design, testing and deployment.

## Author

**Arslan Farooqui** — [GitHub](https://github.com/arslanfarooqui784-tech) · [LinkedIn](https://www.linkedin.com/in/arslan-farooqui-7734a126b)
