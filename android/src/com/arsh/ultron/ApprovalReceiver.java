package com.arsh.ultron;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** APPROVE / REJECT / STOP buttons on ULTRON's notifications. */
public class ApprovalReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction() == null ? "" : i.getAction();
        int id = i.getIntExtra("id", -1);
        String result;
        if (a.endsWith(".APPROVE")) result = Approvals.approve(c, id, null);
        else if (a.endsWith(".REJECT")) result = Approvals.reject(c, id);
        else if (a.endsWith(".STOP")) result = AutoReply.stop(c);
        else if (a.endsWith(".ACK")) {
            Owner.acknowledge(c);
            result = "Emergency acknowledged. No more alerts for it.";
        }
        else return;
        try {
            Toast.makeText(c, "ULTRON: " + result, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }
}
