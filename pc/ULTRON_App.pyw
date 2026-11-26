"""
ULTRON App launcher.
Double-click to open ULTRON in its own app window.
The first time, it also creates a ULTRON icon on the Desktop and in the Start menu.
The brain runs quietly in the background; its log is saved in ultron_log.txt.
"""
import glob
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import webbrowser

HERE = os.path.dirname(os.path.abspath(__file__))
URL = "http://127.0.0.1:8765/"
NO_WINDOW = 0x08000000


def running():
    try:
        urllib.request.urlopen(URL + "health", timeout=2)
        return True
    except urllib.error.HTTPError:
        return True  # it answered (locked), so it is running
    except Exception:
        return False


def start_brain():
    log = open(os.path.join(HERE, "ultron_log.txt"), "a", encoding="utf-8", buffering=1)
    log.write("\n=== ULTRON started " + time.strftime("%Y-%m-%d %H:%M:%S") + " ===\n")
    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUNBUFFERED="1")
    subprocess.Popen([sys.executable, os.path.join(HERE, "ULTRON.py"), "--no-open"],
                     cwd=HERE, stdout=log, stderr=log, stdin=subprocess.DEVNULL,
                     creationflags=NO_WINDOW, env=env)
    for _ in range(120):
        if running():
            return True
        time.sleep(0.5)
    return False


def chrome():
    for base in (os.environ.get("PROGRAMFILES", ""), os.environ.get("PROGRAMFILES(X86)", ""), os.environ.get("LOCALAPPDATA", "")):
        path = os.path.join(base, "Google", "Chrome", "Application", "chrome.exe")
        if base and os.path.exists(path):
            return path
    for base in (os.environ.get("PROGRAMFILES(X86)", ""), os.environ.get("PROGRAMFILES", "")):
        path = os.path.join(base, "Microsoft", "Edge", "Application", "msedge.exe")
        if base and os.path.exists(path):
            return path
    return None


def open_window():
    exe = chrome()
    if exe:
        subprocess.Popen([exe, "--app=" + URL, "--start-maximized"], creationflags=NO_WINDOW)
    else:
        webbrowser.open(URL)


def make_icon():
    ico = os.path.join(HERE, "ULTRON.ico")
    if os.path.exists(ico):
        return ico
    try:
        import cv2
        pngs = sorted(glob.glob(os.path.join(HERE, "ULTRON_face2", "*.png")), key=os.path.getsize, reverse=True)
        img = cv2.imread(pngs[0], cv2.IMREAD_UNCHANGED)
        h, w = img.shape[:2]
        side = min(h, w)
        img = img[(h - side) // 2:(h - side) // 2 + side, (w - side) // 2:(w - side) // 2 + side]
        img = cv2.resize(img, (256, 256), interpolation=cv2.INTER_AREA)
        ok, png = cv2.imencode(".png", img)
        data = png.tobytes()
        header = (bytes([0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 32, 0])
                  + len(data).to_bytes(4, "little") + (22).to_bytes(4, "little"))
        with open(ico, "wb") as f:
            f.write(header + data)
        return ico
    except Exception:
        return ""


def make_shortcuts():
    flag = os.path.join(HERE, "ultron_shortcuts_done.txt")
    if os.path.exists(flag):
        return
    pyw = sys.executable
    if pyw.lower().endswith("python.exe"):
        pyw = pyw[:-10] + "pythonw.exe"
    icon = make_icon()
    script = os.path.abspath(__file__)
    ps = (
        "$w = New-Object -ComObject WScript.Shell;"
        "foreach ($d in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {"
        " $s = $w.CreateShortcut((Join-Path $d 'ULTRON.lnk'));"
        " $s.TargetPath = '" + pyw.replace("'", "''") + "';"
        " $s.Arguments = '" + chr(34) + script.replace("'", "''") + chr(34) + "';"
        " $s.WorkingDirectory = '" + HERE.replace("'", "''") + "';"
        " $s.Description = 'ULTRON - personal AI assistant';"
        + (" $s.IconLocation = '" + icon.replace("'", "''") + "';" if icon else "")
        + " $s.Save() }"
    )
    try:
        subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps],
                       creationflags=NO_WINDOW, timeout=60)
        with open(flag, "w") as f:
            f.write("shortcuts created\n")
    except Exception:
        pass


if __name__ == "__main__":
    make_shortcuts()
    if not running() and not start_brain():
        import ctypes
        ctypes.windll.user32.MessageBoxW(0, "ULTRON could not start. See ultron_log.txt in Documents.", "ULTRON", 0x10)
        sys.exit(1)
    open_window()