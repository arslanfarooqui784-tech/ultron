package com.arsh.ultron;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lets ULTRON tap, type, scroll and go back or home, only when Arsh asks. */
public class TapService extends AccessibilityService {
    static TapService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null)
                LockGate.check(this, event.getPackageName().toString());   // app lock
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    public static boolean on() {
        return instance != null;
    }

    public static boolean global(int action) {
        return instance != null && instance.performGlobalAction(action);
    }

    /** Taps the first thing on screen whose text or description matches. */
    public static String tap(String what) {
        TapService s = instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return null;
        String want = what.toLowerCase(Locale.ROOT).trim();
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        for (AccessibilityNodeInfo n : all(root)) {
            String t = (n.getText() == null ? "" : n.getText().toString()) + " "
                + (n.getContentDescription() == null ? "" : n.getContentDescription().toString());
            t = t.toLowerCase(Locale.ROOT).trim();
            if (t.isEmpty() || !n.isVisibleToUser()) continue;
            int score = t.equals(want) ? 3 : t.startsWith(want) ? 2 : t.contains(want) ? 1 : 0;
            if (score > bestScore) { best = n; bestScore = score; }
        }
        if (best == null) return null;
        AccessibilityNodeInfo n = best;
        while (n != null && !n.isClickable()) n = n.getParent();
        if (n == null) return null;
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        CharSequence label = best.getText() != null ? best.getText() : best.getContentDescription();
        return label == null ? what : label.toString();
    }

    /** Types into the focused text box (or the first text box on screen). */
    public static boolean type(String text) {
        TapService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo box = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (box == null || !box.isEditable()) {
            box = null;
            for (AccessibilityNodeInfo n : all(root)) if (n.isEditable() && n.isVisibleToUser()) { box = n; break; }
        }
        if (box == null) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    public static boolean scroll(boolean down) {
        TapService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        for (AccessibilityNodeInfo n : all(root)) {
            if (n.isScrollable() && n.isVisibleToUser()) {
                if (n.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true;
            }
        }
        return false;
    }

    /** Short description of what is on screen, so ULTRON can read it out. */
    public static String screenText() {
        TapService s = instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder b = new StringBuilder();
        for (AccessibilityNodeInfo n : all(root)) {
            if (!n.isVisibleToUser()) continue;
            CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
            if (t != null && t.length() > 0 && b.length() < 3000) b.append(t).append(" | ");
        }
        return b.toString();
    }

    /**
     * Replies inside an app whose notification has no Reply button (Snapchat and some others):
     * opens that exact chat from its notification, types the text into the message box and presses Send.
     * Returns null when sent, otherwise the reason it could not.
     */
    public static String replyOnScreen(android.app.PendingIntent open, String pkg, String text, String expectedChat) {
        return sendOnScreen(open, pkg, text, expectedChat);
    }

    public static String sendOnScreen(Object open, String pkg, String text) {
        return sendOnScreen(open, pkg, text, null);
    }

    /** Same as replyOnScreen, opening the chat with a PendingIntent or an Intent (for example a wa.me link). */
    public static String sendOnScreen(Object open, String pkg, String text, String expectedChat) {
        TapService s = instance;
        if (s == null) return "the screen helper (Accessibility) is off";
        try {
            if (open instanceof android.app.PendingIntent) ((android.app.PendingIntent) open).send();
            else s.startActivity((android.content.Intent) open);
        } catch (Exception e) {
            return "the chat could not be opened";
        }
        AccessibilityNodeInfo box = null;
        for (int i = 0; i < 20 && box == null; i++) {
            pause(500);
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !pkg.equals(root.getPackageName().toString())) continue;
            for (AccessibilityNodeInfo n : all(root)) if (n.isEditable() && n.isVisibleToUser()) { box = n; break; }
        }
        if (box == null) {
            global(GLOBAL_ACTION_HOME);
            return "no message box appeared in the chat";
        }
        if (expectedChat != null) {
            // the opened chat must show the right person's name at the top before anything is typed
            String want = NotifService.exact(expectedChat);
            AccessibilityNodeInfo top = s.getRootInActiveWindow();
            boolean match = false;
            android.util.DisplayMetrics dm = s.getResources().getDisplayMetrics();
            if (top != null && !want.isEmpty()) {
                for (AccessibilityNodeInfo n : all(top)) {
                    android.graphics.Rect r = new android.graphics.Rect();
                    n.getBoundsInScreen(r);
                    if (r.top > dm.heightPixels / 4 || n.isEditable()) continue;
                    String t = (n.getText() == null ? "" : n.getText().toString()) + " " + (n.getContentDescription() == null ? "" : n.getContentDescription().toString());
                    if (NotifService.exact(t).equals(want) || NotifService.exact(n.getText() == null ? "" : n.getText().toString()).equals(want)) {
                        match = true;
                        break;
                    }
                }
            }
            if (!match) {
                global(GLOBAL_ACTION_HOME);
                return "the chat that opened could not be confirmed as '" + expectedChat + "'";
            }
        }
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
            global(GLOBAL_ACTION_HOME);
            return "could not type into the message box";
        }
        pause(700);
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        boolean clicked = false;
        if (root != null) {
            android.graphics.Rect bb = new android.graphics.Rect();
            box.getBoundsInScreen(bb);
            AccessibilityNodeInfo best = null;
            int bestDist = Integer.MAX_VALUE;
            for (AccessibilityNodeInfo n : all(root)) {
                if (!n.isVisibleToUser()) continue;
                String d = ((n.getContentDescription() == null ? "" : n.getContentDescription().toString()) + " "
                    + (n.getText() == null ? "" : n.getText().toString())).trim().toLowerCase(Locale.ROOT);
                String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().toLowerCase(Locale.ROOT);
                if (!(d.matches("(send|send message|send chat|भेजें|ارسال کریں|بھیجیں)") || id.endsWith("send") || id.contains("send_button")
                    || id.contains("sendbutton") || id.contains("send_btn"))) continue;
                AccessibilityNodeInfo c = n;
                while (c != null && !c.isClickable()) c = c.getParent();
                if (c == null) continue;
                android.graphics.Rect r = new android.graphics.Rect();
                c.getBoundsInScreen(r);
                int dist = Math.abs(r.centerY() - bb.centerY()) + Math.abs(r.centerX() - bb.right);
                if (dist < bestDist) { best = c; bestDist = dist; }
            }
            if (best != null) clicked = best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        if (!clicked) clicked = box.performAction(0x01020036);   // the keyboard's Enter/Send key (Android 11+)
        pause(1200);
        boolean sent = false;
        AccessibilityNodeInfo after = s.getRootInActiveWindow();
        if (clicked && after != null) {
            sent = true;
            for (AccessibilityNodeInfo n : all(after)) {
                if (n.isEditable() && n.getText() != null && n.getText().toString().trim().equals(text.trim())) { sent = false; break; }
            }
        }
        if (!sent && after != null) {
            // not sent: clear what ULTRON typed so nothing goes out by accident later
            for (AccessibilityNodeInfo n : all(after)) {
                if (n.isEditable() && n.getText() != null && n.getText().toString().trim().equals(text.trim())) {
                    Bundle e = new Bundle();
                    e.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                    n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, e);
                }
            }
        }
        global(GLOBAL_ACTION_HOME);
        return sent ? null : "the Send button could not be pressed";
    }

    static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    static List<AccessibilityNodeInfo> all(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty() && out.size() < 2000) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null) continue;
            out.add(n);
            for (int i = n.getChildCount() - 1; i >= 0; i--) stack.add(n.getChild(i));
        }
        return out;
    }
}
