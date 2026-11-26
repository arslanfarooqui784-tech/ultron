# ULTRON — feature list

Status: **Done** = built and in use. **Partial** = some of it is built. **Not possible** = Android doesn't allow it for a normal app.

## Core
| Feature | Status | Notes |
|---|---|---|
| Multi-model brain | Done | Gemini, Claude, ChatGPT, Grok (OpenRouter), Groq. Latency ranking, 3-strike rest, ranking kept across restarts |
| Offline brain | Done | Falls back to a local Ollama model on the PC when every cloud brain fails |
| Owner voice | Done | Fish Audio TTS: one deep voice only, worker queue, retries, 60-phrase cache, never silent |
| Voice input | Done | English, Hindi and auto mode; conversation mode; tap the face to interrupt |
| Wake word "Hey ULTRON" | Partial | Works while the app is open; always-on listening needs a microphone foreground service |
| Lock | Done | Password (PBKDF2 on PC), fingerprint or phone PIN on Android |

## Messaging agent
| Feature | Status | Notes |
|---|---|---|
| Reads notifications | Done | WhatsApp, Instagram, Telegram, Snapchat, SMS, Messenger, email apps |
| Auto-replies | Done | Same language and tone, one reply per message, intro as "ULTRON, the owner's AI assistant" |
| 3-level approvals | Done | Routine = automatic; personal or commitments = approval; money, security or legal = always explicit; emergency STOP |
| Recipient verification | Done | Chat identity checked before every send; any doubt means the reply is held and the owner alerted |
| Photo understanding | Done | Photos shown in notifications are read with vision models; videos and voice notes are reported honestly as not viewable |
| Scam alerts | Done | KYC, lottery, parcel, job, OTP-sharing and digital-arrest patterns are flagged and never answered |
| Learns reply style | Done | From the owner's edits and rejected drafts |
| Chat search and summaries | Done | Encrypted archive of the last 2,500 messages, answers only from real messages |

## Calls
| Feature | Status | Notes |
|---|---|---|
| Call detection | Done | SIM calls plus app calls from notifications; encrypted call log |
| Decline with text | Done | One tap or by voice |
| Call back / call history | Done | Confirmed before calling |
| Emergency escalation | Done | Alarm, SMS, WhatsApp, then a call, all with strict limits |
| AI answering calls / recording | Not possible | Android doesn't let normal apps speak into or record calls |

## Daily life
| Feature | Status | Notes |
|---|---|---|
| Daily briefing and weekly report | Done | Messages, missed calls, emails, calendar, reminders, weather, news; spoken |
| Routines | Done | Sleep, driving, meeting, study: Do Not Disturb, reply mode, read-aloud; auto-silent from calendar |
| Reminders | Done | Exact alarms that survive restarts; times found in chats are suggested, never added without approval |
| Email and calendar | Done | Important emails flagged; emails never answered automatically; add events by voice (confirmed) |
| Scheduled and group messages | Done | Confirmed once, sent at the set time |
| Lists, notes, contacts by voice | Done | |
| Weather, news, cricket, currency | Done | Open-Meteo, Google News RSS, ESPNcricinfo RSS, open.er-api.com |
| Find my phone | Done | Secret word by SMS rings the phone at full volume |
| Home screen widget | Done | |

## Smart features
| Feature | Status | Notes |
|---|---|---|
| Multi-step commands | Done | "Do X and then Y": split by the AI, shown once for approval (up to 8 steps) |
| App agent | Done | Taps through apps for simple tasks; never presses pay, order, book, send or delete; owner finishes |
| Camera and screenshot vision | Done | "What is this", "solve this", "translate this" |
| Study brain | Done | Explain or quiz from the owner's notes and PDFs |
| Nightly self-review | Done | Learns up to 3 lessons and shortcuts a day; anything touching approvals, privacy, money or security is discarded; FORGET button for everything learned |
| Money tracker | Done | Amount, merchant and category only; never balances, account numbers or OTPs |
| App lock and intruder photo | Done | |

## Phone + PC
| Feature | Status | Notes |
|---|---|---|
| Pairing | Done | Pairing code over home Wi-Fi |
| Shared memory | Done | "Remember ..." on either device |
| Files and clipboard | Done | Both directions |
| PC control from phone | Done | Open apps, lock, sleep, restart, shut down (confirmed, 30 s cancel window) |
| Self-update | Done | Checks package name, signature and version; Android always asks the owner to install |

## Deliberately not built
- Location tracking and location-based routines (owner's choice).
- Speaking caller or sender names aloud (owner's choice).
