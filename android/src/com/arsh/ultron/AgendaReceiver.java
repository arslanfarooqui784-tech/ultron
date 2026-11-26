package com.arsh.ultron;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** ULTRON's own timed events and the ADD / NO / SNOOZE buttons (not reachable by other apps). */
public class AgendaReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context c, Intent i) {
        String a = i.getAction() == null ? "" : i.getAction();
        final String id = i.getStringExtra("id") == null ? "" : i.getStringExtra("id");
        String toast = null;
        if (a.endsWith(".REMIND")) Agenda.fireReminder(c, id);
        else if (a.endsWith(".SNOOZE")) { Agenda.snooze(c, id); toast = "Snoozed for 10 minutes."; }
        else if (a.endsWith(".BRIEF")) Agenda.fireBriefing(c);
        else if (a.endsWith(".NUDGE")) Smart.nudgeCheck(c);
        else if (a.endsWith(".REFLECT")) {
            Learn.scheduleReflect(c);
            AutoReply.pool.execute(new Runnable() { public void run() { Learn.reflect(c); } });
        }
        else if (a.endsWith(".UPDATE")) Updater.check(c, false);
        else if (a.endsWith(".SENDMSG")) Daily.sendScheduled(c, id);
        else if (a.endsWith(".STOPRING")) Daily.stopRing();
        else if (a.endsWith(".CALSIL_ON")) Daily.calSilentOn(c, id);
        else if (a.endsWith(".CALSIL_OFF")) Daily.calSilentOff(c);
        else if (a.endsWith(".WAKEUP")) Daily.fireWake(c);
        else if (a.endsWith(".DECLINE_TEXT")) toast = Calls.declineAndText(c);
        else if (a.endsWith(".WEEKLY")) Smart.fireWeekly(c);
        else if (a.endsWith(".SUGGEST_ADD")) toast = Agenda.acceptSuggestion(c, id, true);
        else if (a.endsWith(".SUGGEST_NO")) toast = Agenda.acceptSuggestion(c, id, false);
        else if (a.endsWith(".ROUTINE_START")) Routines.start(c, id, "schedule");
        else if (a.endsWith(".ROUTINE_END")) Routines.stop(c, "schedule");
        else if (a.endsWith(".PC_OK")) PcLink.answer(id, true);
        else if (a.endsWith(".PC_NO")) PcLink.answer(id, false);
        if (toast != null) {
            try {
                Toast.makeText(c, "ULTRON: " + toast, Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
            }
        }
    }
}
