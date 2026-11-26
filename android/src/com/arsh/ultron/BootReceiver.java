package com.arsh.ultron;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After the phone restarts or ULTRON is updated: reminders, briefing, routines and the PC link come back. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction() == null ? "" : i.getAction();
        if (!a.equals(Intent.ACTION_BOOT_COMPLETED) && !a.equals(Intent.ACTION_MY_PACKAGE_REPLACED) && !a.equals("android.intent.action.LOCKED_BOOT_COMPLETED")) return;
        try {
            Agenda.rescheduleAll(c.getApplicationContext());
            PcLink.start(c.getApplicationContext());
        } catch (Exception ignored) {
        }
    }
}
