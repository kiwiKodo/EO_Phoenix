package com.kiwikodo.eophoenix.managers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.kiwikodo.eophoenix.managers.LogManager;

/**
 * Receives AlarmManager intents scheduled by ScheduleManager. Shows a brief message activity,
 * then forwards a local broadcast so the running app can stop/start slideshow, and requests
 * that the ScheduleManager reschedule the next transition.
 */
public class ScheduleReceiver extends BroadcastReceiver {
    public static final String ACTION_RESCHEDULE = "com.kiwikodo.eophoenix.ACTION_RESCHEDULE_SCHEDULE";
    public static final String ACTION_TRANSITION_FIRED = "com.kiwikodo.eophoenix.ACTION_SCHEDULE_TRANSITION_FIRED";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Log.d("ScheduleReceiver", "Alarm fired: " + intent);
            // Determine transition once so it's available to all handlers below
            boolean turnOn = intent.getBooleanExtra("turnOn", true);

            // Do NOT broadcast ACTION_TRANSITION_FIRED here - we'll instead start the
            // MainActivity with the intent so it handles the transition in onNewIntent().
            // This avoids duplicate handling where both a broadcast receiver and the
            // onNewIntent path attempt cleanup/brightness changes.

            // Persist the current schedule state so restarts can honor it
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences("eo_schedule", Context.MODE_PRIVATE);
                prefs.edit().putBoolean("schedule_currently_on", turnOn).apply();
            } catch (Exception e) {
                LogManager.getInstance().addLog("Failed to persist schedule state: " + e.getMessage());
            }

            // Diagnostic log: record which transition fired and when it was scheduled for
            try {
                long scheduledAt = intent.getLongExtra("scheduledAt", -1L);
                String msg = "ScheduleReceiver fired: turnOn=" + turnOn + " scheduledAt=" + (scheduledAt > 0 ? new java.util.Date(scheduledAt).toString() : "<unknown>");
                Log.d("ScheduleReceiver", msg);
                try { LogManager.getInstance().addLog(msg); } catch (Exception ignored) {}
            } catch (Exception ignored) {}

            // Also explicitly start or bring MainActivity to foreground with the transition Intent so
            // the Activity receives the intent in onNewIntent() even if it was backgrounded. This
            // increases reliability on devices where LocalBroadcastManager delivery may be delayed.
            try {
                Intent main = new Intent(context, com.kiwikodo.eophoenix.MainActivity.class);
                main.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                main.putExtra("turnOn", turnOn);
                context.startActivity(main);
            } catch (Exception e) {
                LogManager.getInstance().addLog("Failed to start MainActivity for transition: " + e.getMessage());
            }

            // Ask the running app to reschedule the next transition
            try {
                Intent resch = new Intent(ACTION_RESCHEDULE);
                context.sendBroadcast(resch);
            } catch (Exception e) {
                LogManager.getInstance().addLog("Failed to broadcast reschedule: " + e.getMessage());
            }
        } catch (Exception e) {
            try { LogManager.getInstance().addLog("ScheduleReceiver error: " + e.getMessage()); } catch (Exception ignored){}
        }
    }
}
