package com.arsh.ultron;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** ULTRON on the home screen: what waits for Arsh, the weather, the next reminder, and a TALK button. */
public class Widget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        refresh(c);
    }

    public static void refresh(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        AutoReply.pool.execute(new Runnable() {
            public void run() {
                try {
                    AppWidgetManager m = AppWidgetManager.getInstance(c);
                    int[] ids = m.getAppWidgetIds(new ComponentName(c, Widget.class));
                    if (ids == null || ids.length == 0) return;
                    Store s = new Store(c);
                    int waiting = 0, today = 0;
                    JSONArray a = AutoReply.Events.all(s);
                    long since = System.currentTimeMillis() - 24 * 3600000L;
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject e = a.optJSONObject(i);
                        if (e == null || !"personal".equals(e.optString("kind"))) continue;
                        if ("attention".equals(e.optString("status")) || "awaiting_approval".equals(e.optString("status"))) waiting++;
                        if (e.optLong("received") >= since) today++;
                    }
                    String weather = Weather.shortNow(Weather.home(s));
                    JSONArray r = Agenda.upcomingReminders(s, System.currentTimeMillis() + 3 * 86400000L);
                    String next = r.length() > 0 ? "⏰ " + new SimpleDateFormat("EEE h:mm a", Locale.ENGLISH).format(new Date(r.optJSONObject(0).optLong("when")))
                        + " · " + r.optJSONObject(0).optString("what") : "No reminders coming up";
                    String routine = Routines.active(s);
                    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
                    v.setTextViewText(R.id.w_title, routine.isEmpty() ? "ULTRON" : "ULTRON · " + routine.toUpperCase(Locale.ROOT));
                    v.setTextViewText(R.id.w_line1, "💬 " + today + " messages today" + (waiting > 0 ? " · " + waiting + " need you" : " · all handled"));
                    v.setTextViewText(R.id.w_line2, weather.isEmpty() ? "" : "🌤 " + Weather.home(s) + " " + weather);
                    v.setTextViewText(R.id.w_line3, next);
                    Intent talk = new Intent(c, MainActivity.class).putExtra("listen", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    v.setOnClickPendingIntent(R.id.w_talk, PendingIntent.getActivity(c, 9100, talk, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
                    Intent open = new Intent(c, MainActivity.class).putExtra("page", "control.html").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 9101, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
                    m.updateAppWidget(ids, v);
                } catch (Exception ignored) {
                }
            }
        });
    }
}
