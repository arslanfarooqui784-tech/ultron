package com.arsh.ultron;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * App agent: ULTRON uses an app the way Arsh would (open it, read the screen, tap, type, scroll) to reach a goal,
 * for example "book an Uber to college" or "search Swiggy for biryani". It always STOPS before anything that pays,
 * orders, books, sends or deletes, and hands over to Arsh with the screen ready. Max 20 steps / 3 minutes; STOP ends it.
 */
public class Agent {
    static volatile boolean stop = false;
    static final Pattern FINAL = Pattern.compile("(?i)\\b(pay|payment|place order|confirm (order|booking|ride)|book (now|ride|cab)|proceed to pay|"
        + "checkout|buy now|upi|card|cvv|wallet|send money|transfer|delete|remove account|confirm and pay|request (ride|uber|ola)|slide to pay)\\b");

    public static String run(Context c, Brain brain, String goal, String app) {
        Store s = new Store(c);
        TapService t = TapService.instance;
        if (t == null) return "Turn on ULTRON's screen helper first (Settings › Accessibility › ULTRON), Arsh.";
        if (AutoReply.locked(c)) return "Unlock your phone first, Arsh.";
        stop = false;
        if (app != null && !app.isEmpty()) {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(app);
            if (i == null) return "That app isn't installed.";
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            TapService.pause(3000);
        }
        s.activity("agent", "App agent started: " + goal);
        List<String> history = new ArrayList<>();
        long end = System.currentTimeMillis() + 180000L;
        for (int step = 1; step <= 20 && !stop && System.currentTimeMillis() < end; step++) {
            AccessibilityNodeInfo root = t.getRootInActiveWindow();
            if (root == null) { TapService.pause(1000); continue; }
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            String screen = describe(root, nodes);
            JSONObject a;
            try {
                String raw = brain.quick("You are operating Arsh's Android phone to reach this goal: \"" + goal + "\".\nApp on screen: "
                    + root.getPackageName() + "\nSteps so far: " + (history.isEmpty() ? "none" : android.text.TextUtils.join(" | ", history))
                    + "\nScreen elements (number: kind 'text'):\n" + screen
                    + "\nChoose ONE next action as JSON: {\"do\": \"tap\"|\"type\"|\"scroll\"|\"back\"|\"done\"|\"handover\", \"n\": element number, "
                    + "\"text\": \"text to type\", \"say\": \"short note for Arsh\"}. Use \"handover\" BEFORE any pay / place order / confirm booking / "
                    + "send / delete button, or if you need Arsh's choice or a login. Use \"done\" when the goal is reached.", "Output only JSON.");
                a = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
            } catch (Exception e) {
                return "I lost track of the app (" + e.getMessage() + "). I've stopped, Arsh.";
            }
            String act = a.optString("do"), say = a.optString("say");
            if (act.equals("done")) return say.isEmpty() ? "Done, Arsh." : say;
            if (act.equals("handover")) {
                s.activity("agent", "Handed over to Arsh: " + say);
                return (say.isEmpty() ? "Over to you, Arsh." : say) + " I stopped here so you can check and confirm yourself.";
            }
            int n = a.optInt("n", -1);
            AccessibilityNodeInfo node = n >= 0 && n < nodes.size() ? nodes.get(n) : null;
            String label = node == null ? "" : label(node);
            if ((act.equals("tap") || act.equals("type")) && FINAL.matcher(label).find()) {
                s.activity("agent", "Refused to press \"" + label + "\": that's for Arsh");
                return "The next button is \"" + label + "\", which pays or confirms. I stopped so you can do it yourself, Arsh.";
            }
            boolean ok = false;
            if (act.equals("tap") && node != null) {
                AccessibilityNodeInfo x = node;
                while (x != null && !x.isClickable()) x = x.getParent();
                ok = x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } else if (act.equals("type") && node != null) {
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.optString("text"));
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                if (ok) node.performAction(0x01020036);                       // keyboard search / enter
            } else if (act.equals("scroll")) {
                for (AccessibilityNodeInfo x : nodes) if (x.isScrollable()) { ok = x.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); break; }
                if (!ok) for (AccessibilityNodeInfo x : TapService.all(root)) if (x.isScrollable()) { ok = x.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); break; }
            } else if (act.equals("back")) {
                ok = t.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            }
            history.add(act + (label.isEmpty() ? "" : " '" + label + "'") + (act.equals("type") ? " '" + a.optString("text") + "'" : "") + (ok ? "" : " (failed)"));
            TapService.pause(1800);
        }
        if (stop) return "Stopped, Arsh.";
        return "I couldn't finish that in 20 steps, so I stopped. The app is open where I left it, Arsh.";
    }

    static boolean lastWasNear(String screen) {
        return screen.length() < 4000;
    }

    static String label(AccessibilityNodeInfo n) {
        String t = (n.getText() == null ? "" : n.getText().toString()) + " " + (n.getContentDescription() == null ? "" : n.getContentDescription().toString());
        return t.trim().replaceAll("\\s+", " ");
    }

    /** Numbered list of the visible, useful elements (text, buttons, fields). */
    static String describe(AccessibilityNodeInfo root, List<AccessibilityNodeInfo> out) {
        StringBuilder b = new StringBuilder();
        for (AccessibilityNodeInfo n : TapService.all(root)) {
            if (!n.isVisibleToUser()) continue;
            String l = label(n);
            boolean field = n.isEditable(), click = n.isClickable() || (n.getParent() != null && n.getParent().isClickable());
            if (l.isEmpty() && !field) continue;
            if (l.length() > 80) l = l.substring(0, 80);
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 1 || r.height() <= 1) continue;
            b.append(out.size()).append(": ").append(field ? "field" : click ? "button" : "text").append(" '").append(l).append("'\n");
            out.add(n);
            if (out.size() >= 70) break;
        }
        return b.toString();
    }

    /** "uber" -> its package, from what's installed. */
    public static String findApp(Context c, String name) {
        String want = name.toLowerCase(Locale.ROOT).trim();
        try {
            for (android.content.pm.ApplicationInfo ai : c.getPackageManager().getInstalledApplications(0)) {
                if (c.getPackageManager().getLaunchIntentForPackage(ai.packageName) == null) continue;
                String lab = String.valueOf(c.getPackageManager().getApplicationLabel(ai)).toLowerCase(Locale.ROOT);
                if (lab.equals(want) || lab.startsWith(want + " ") || lab.replace(" ", "").equals(want.replace(" ", ""))) return ai.packageName;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
