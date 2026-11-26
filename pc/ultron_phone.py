# ULTRON phone helper v2 - runs on Arsh's phone inside Termux.
# It connects to ULTRON on the PC and does phone jobs: open apps, read notifications and SMS,
# and (only after Arsh taps YES on the phone) send SMS or make calls.
import json
import os
import subprocess
import sys
import time
import urllib.request

PC = os.environ.get("ULTRON_PC", "http://192.168.1.10:8765")
TOKEN_FILE = os.path.expanduser("~/.ultron_phone_token")


def run(cmd, timeout=40):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "").strip(), (r.stderr or "").strip()
    except FileNotFoundError:
        return 127, "", "missing: " + cmd[0] + " (run: pkg install termux-api)"
    except Exception as e:
        return 1, "", str(e)


def call_pc(path, body=None, token=None, timeout=35):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(PC + path, data=data, method="POST" if data else "GET")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Ultron-Phone", token)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read() or b"{}")


def toast(text):
    run(["termux-toast", "-s", text[:120]], 10)


def approve(title, text):
    """Shows YES / NO on the phone. Only YES lets the action happen."""
    code, out, _ = run(["termux-dialog", "confirm", "-t", title, "-i", text], 120)
    try:
        return json.loads(out).get("text") == "yes"
    except Exception:
        return False


def open_app(job):
    pkg, url, action = job.get("package"), job.get("url"), job.get("action")
    if action:
        code, _, err = run(["am", "start", "-a", action])
        return (code == 0, "opened" if code == 0 else err)
    if pkg:
        code, out, err = run(["am", "start", "-a", "android.intent.action.MAIN",
                              "-c", "android.intent.category.LAUNCHER", "-p", pkg])
        if code == 0 and "Error" not in out + err:
            return True, "opened"
    if url:
        code, _, err = run(["termux-open-url", url])
        return (code == 0, "opened link" if code == 0 else err)
    return False, "app not found on this phone"


def open_dual(job):
    """Dual Messenger WhatsApp lives in a separate Samsung profile. Ask Android itself (not one app),
    so Samsung shows its 'which WhatsApp?' picker."""
    code, out, err = run(["am", "start", "-a", "android.intent.action.VIEW", "-d", "whatsapp://send"])
    if code == 0 and "Error" not in out + err:
        return True, "picker shown"
    code, _, err = run(["termux-open-url", "whatsapp://send"])
    return (code == 0, "opened" if code == 0 else err)


def notifications(job):
    code, out, err = run(["termux-notification-list"], 30)
    if code != 0:
        return False, err or "no access"
    try:
        items = json.loads(out or "[]")
    except Exception:
        return False, "Notification access is not allowed for Termux:API yet"
    keep = []
    for n in items:
        pkg = n.get("packageName", "")
        if pkg.startswith(("com.termux", "android", "com.android.systemui")):
            continue
        title, text = (n.get("title") or "").strip(), (n.get("content") or "").strip()
        if title or text:
            keep.append({"app": pkg, "title": title[:60], "text": text[:160], "when": n.get("when", "")})
    return True, keep[:25]


def sms_list(job):
    code, out, err = run(["termux-sms-list", "-l", str(job.get("count", 5)), "-t", "inbox"], 30)
    if code != 0:
        return False, err
    try:
        msgs = json.loads(out or "[]")
    except Exception:
        return False, "SMS permission is not allowed for Termux:API yet"
    return True, [{"from": m.get("number", ""), "text": m.get("body", "")[:160], "when": m.get("received", "")} for m in msgs]


def sms_send(job):
    num, text = job.get("number", ""), job.get("text", "")
    if not approve("ULTRON wants to send an SMS", "To: " + num + "\n\n" + text + "\n\nSend it?"):
        return False, "cancelled by Arsh"
    code, _, err = run(["termux-sms-send", "-n", num, text], 30)
    return (code == 0, "sent" if code == 0 else err)


def phone_call(job):
    num = job.get("number", "")
    if not approve("ULTRON wants to make a call", "Call " + num + "?"):
        return False, "cancelled by Arsh"
    code, _, err = run(["termux-telephony-call", num], 20)
    return (code == 0, "calling" if code == 0 else err)


def clipboard(job):
    code, _, err = run(["termux-clipboard-set", job.get("text", "")], 10)
    return (code == 0, "copied" if code == 0 else err)


JOBS = {"open": open_app, "open_dual": open_dual, "notifications": notifications, "sms_list": sms_list,
        "sms_send": sms_send, "call": phone_call, "clipboard": clipboard}


def pair():
    print("\nULTRON PHONE HELPER - pairing")
    print("On ULTRON (PC or phone), say or type:  pair phone")
    code = input("Type the 6-digit code ULTRON tells you: ").strip()
    tok = call_pc("/phone/pair", {"code": code}).get("token")
    if not tok:
        print("That code did not work. Run this again and use a new code.")
        sys.exit(1)
    with open(TOKEN_FILE, "w") as f:
        f.write(tok)
    os.chmod(TOKEN_FILE, 0o600)
    print("Paired with ULTRON.")
    return tok


def self_update():
    """Gets the newest helper from ULTRON and restarts if it changed."""
    try:
        with urllib.request.urlopen(PC + "/phone/helper.py", timeout=15) as r:
            new = r.read()
        me = os.path.abspath(__file__)
        with open(me, "rb") as f:
            old = f.read()
        if new and new != old and b"def main" in new:
            with open(me, "wb") as f:
                f.write(new)
            print("ULTRON helper updated, restarting...")
            os.execv(sys.executable, [sys.executable, me])
    except Exception:
        pass


def main():
    self_update()
    last_check = time.time()
    tok = open(TOKEN_FILE).read().strip() if os.path.exists(TOKEN_FILE) else None
    if not tok:
        tok = pair()
    run(["termux-wake-lock"], 10)
    print("ULTRON phone helper is running. Keep Termux open in the background.")
    toast("ULTRON phone helper connected")
    while True:
        try:
            job = call_pc("/phone/poll", {"hello": 1}, tok, timeout=40).get("job")
        except urllib.error.HTTPError as e:
            if e.code == 401:
                print("ULTRON does not know this phone any more.")
                os.remove(TOKEN_FILE)
                tok = pair()
                continue
            time.sleep(5)
            continue
        except Exception:
            time.sleep(5)
            continue
        if time.time() - last_check > 600:
            last_check = time.time()
            self_update()
        if not job:
            continue
        print("job: " + job.get("type", "?") + " " + json.dumps({k: v for k, v in job.items() if k not in ("id", "type", "text")}))
        try:
            ok, data = JOBS.get(job.get("type"), lambda j: (False, "unknown job"))(job)
        except Exception as e:
            ok, data = False, str(e)
        try:
            call_pc("/phone/report", {"id": job.get("id"), "ok": ok, "data": data}, tok, timeout=15)
        except Exception:
            pass


if __name__ == "__main__":
    main()
