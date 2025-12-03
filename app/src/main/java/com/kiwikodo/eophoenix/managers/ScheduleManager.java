package com.kiwikodo.eophoenix.managers;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.kiwikodo.eophoenix.Settings;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Collections;
import java.util.Locale;
import java.util.TimeZone;

/**
 * ScheduleManager: computes next on/off transition from Settings.Schedule and schedules an Alarm.
 * This schedules only the next transition; when fired the app should reschedule the following transition.
 */
public class ScheduleManager {
    private final Context context;
    private final LogManager logManager;
    private final SettingsManager settingsManager;
    private final AlarmManager alarmManager;

    private PendingIntent scheduledIntent;
    // persisted last scheduled epoch for observability (eo_schedule.next_scheduled_at)
    private static final String PREFS_NAME = "eo_schedule";
    private static final String PREF_NEXT_SCHEDULED_AT = "next_scheduled_at";

    public ScheduleManager(Context ctx, LogManager logManager, SettingsManager settingsManager) {
        this.context = ctx.getApplicationContext();
        this.logManager = logManager;
        this.settingsManager = settingsManager;
        this.alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
    }

    public void startFromSettings() {
        try {
            Settings s = settingsManager.getCurrentSettings();
            if (s == null) return;
            scheduleNextFrom(s.getSchedule(), s.getTimeZone());
        } catch (Exception e) {
            logManager.addLog("ScheduleManager start error: " + e.getMessage());
        }
    }

    /**
     * Determine whether the given schedule says the system should be ON at the current time.
     * This inspects today's and yesterday's slots to handle overnight ranges.
     */
    public boolean isNowScheduledOn(Settings.Schedule schedule, String tzName) {
        if (schedule == null) return true; // default to on if no schedule
        TimeZone tz = tzName != null ? TimeZone.getTimeZone(tzName) : TimeZone.getDefault();
        long nowMs = Calendar.getInstance(tz).getTimeInMillis();

        try {
            // Check yesterday and today slots
            Calendar base = Calendar.getInstance(tz);
            for (int dayOffset = -1; dayOffset <= 0; dayOffset++) {
                Calendar cursor = (Calendar) base.clone();
                cursor.add(Calendar.DAY_OF_YEAR, dayOffset);
                int dow = cursor.get(Calendar.DAY_OF_WEEK);
                List<Settings.Schedule.TimeSlot> slots = daySlotsFor(schedule, dow);
                if (slots == null) continue;
                for (Settings.Schedule.TimeSlot ts : slots) {
                    try {
                        boolean hasOn = ts.on != null && !ts.on.isEmpty();
                        boolean hasOff = ts.off != null && !ts.off.isEmpty();

                        if (hasOn) {
                            long onMs = parseTimeForDay(ts.on, cursor, tz, 0);
                            if (hasOff) {
                                long offMs = parseTimeForDay(ts.off, cursor, tz, 0);
                                if (offMs <= onMs) {
                                    // off rolls to next day
                                    offMs = parseTimeForDay(ts.off, cursor, tz, 1);
                                }
                                if (nowMs >= onMs && nowMs < offMs) {
                                    return true;
                                }
                            } else {
                                // ON-only timeslot: consider it active from onMs until an explicit OFF
                                if (nowMs >= onMs) {
                                    return true;
                                }
                            }
                        }
                        // If there is no ON time in this timeslot we ignore it for determining ON state.
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            logManager.addLog("Error computing current schedule state: " + e.getMessage());
        }
        return false;
    }

    public void stop() {
        try {
            if (scheduledIntent != null && alarmManager != null) {
                alarmManager.cancel(scheduledIntent);
                scheduledIntent = null;
                logManager.addLog("ScheduleManager: canceled scheduled alarm");
            }
        } catch (Exception e) {
            logManager.addLog("ScheduleManager stop error: " + e.getMessage());
        }
    }

    private static class Transition {
        long epochMs;
        boolean turnOn;
        Transition(long epochMs, boolean turnOn) { this.epochMs = epochMs; this.turnOn = turnOn; }
    }

    // Compute next transition and schedule an alarm
    public void scheduleNextFrom(Settings.Schedule schedule, String tzName) {
        if (schedule == null) {
            logManager.addLog("ScheduleManager: no schedule present");
            return;
        }

        TimeZone tz = tzName != null ? TimeZone.getTimeZone(tzName) : TimeZone.getDefault();
        List<Transition> upcoming = computeUpcomingTransitions(schedule, tz, 14); // look ahead two weeks to be safe
        if (upcoming.isEmpty()) {
            logManager.addLog("ScheduleManager: no upcoming transitions found");
            return;
        }

        // Determine current authoritative state and pick the next transition that toggles state.
        boolean currentOn = isNowScheduledOn(schedule, tzName);
        Transition chosen = null;
        for (Transition t : upcoming) {
            if (t.turnOn != currentOn) { chosen = t; break; }
        }
        if (chosen == null) {
            // Fallback: schedule the first upcoming transition
            chosen = upcoming.get(0);
        }
        scheduleAlarm(chosen);
    }

    private void scheduleAlarm(Transition t) {
        try {
            Intent i = new Intent(context, ScheduleReceiver.class);
            i.setAction("com.kiwikodo.eophoenix.ACTION_SCHEDULE_TRANSITION");
            i.putExtra("turnOn", t.turnOn);
            i.putExtra("scheduledAt", t.epochMs);
            PendingIntent pi = PendingIntent.getBroadcast(context, (int) (t.epochMs % Integer.MAX_VALUE), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (alarmManager != null) {
                // Read persisted next scheduled epoch to avoid duplicate identical schedules
                try {
                    android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
                    long prev = prefs.getLong(PREF_NEXT_SCHEDULED_AT, -1L);
                    if (prev == t.epochMs) {
                        logManager.addLog("ScheduleManager: next scheduled epoch already set to " + new Date(t.epochMs).toString() + " - skipping duplicate");
                        // still update scheduledIntent reference for potential cancellation later
                        scheduledIntent = pi;
                        return;
                    }
                } catch (Exception ignored) {}

                // Cancel any previously scheduled alarm to avoid duplicates
                try {
                    if (scheduledIntent != null) alarmManager.cancel(scheduledIntent);
                } catch (Exception ignored) {}

                alarmManager.setExact(AlarmManager.RTC_WAKEUP, t.epochMs, pi);
                scheduledIntent = pi;
                // Persist the next scheduled epoch for diagnostics
                try {
                    android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
                    prefs.edit().putLong(PREF_NEXT_SCHEDULED_AT, t.epochMs).apply();
                } catch (Exception ignored) {}

                logManager.addLog("ScheduleManager: scheduled " + (t.turnOn ? "ON" : "OFF") + " at " + new Date(t.epochMs).toString());
            }
        } catch (Exception e) {
            logManager.addLog("ScheduleManager scheduleAlarm error: " + e.getMessage());
        }
    }

    /**
     * Public helper: returns the next ON epoch in ms for the provided schedule and timezone,
     * or -1 if none found.
     */
    public long getNextOnEpoch(Settings.Schedule schedule, String tzName) {
        if (schedule == null) return -1L;
        try {
            TimeZone tz = tzName != null ? TimeZone.getTimeZone(tzName) : TimeZone.getDefault();
            List<Transition> upcoming = computeUpcomingTransitions(schedule, tz, 8);
            for (Transition tr : upcoming) {
                if (tr.turnOn) return tr.epochMs;
            }
        } catch (Exception e) {
            logManager.addLog("getNextOnEpoch error: " + e.getMessage());
        }
        return -1L;
    }

    // Build list of next transitions (sorted) for the coming days
    private List<Transition> computeUpcomingTransitions(Settings.Schedule schedule, TimeZone tz, int daysAhead) {
        List<Transition> list = new ArrayList<>();
        Calendar now = Calendar.getInstance(tz);
        Calendar cursor = (Calendar) now.clone();

        for (int d = 0; d < daysAhead; d++) {
            int dow = cursor.get(Calendar.DAY_OF_WEEK); // 1=Sun ..7=Sat
            List<Settings.Schedule.TimeSlot> slots = daySlotsFor(schedule, dow);
            if (slots != null) {
                for (Settings.Schedule.TimeSlot ts : slots) {
                        try {
                            // Try parsing ON if present
                            if (ts.on != null) {
                                try {
                                    long onMs = parseTimeForDay(ts.on, cursor, tz, 0);
                                    if (onMs > now.getTimeInMillis()) list.add(new Transition(onMs, true));
                                } catch (Exception e) {
                                    logManager.addLog("Schedule parse error (on): " + e.getMessage());
                                }
                            }

                            // Try parsing OFF if present
                            if (ts.off != null) {
                                try {
                                    long offMs = parseTimeForDay(ts.off, cursor, tz, 0);
                                    // If on was present and off <= on, assume off is next day
                                    if (ts.on != null) {
                                        try {
                                            long onMs = parseTimeForDay(ts.on, cursor, tz, 0);
                                            if (offMs <= onMs) offMs = parseTimeForDay(ts.off, cursor, tz, 1);
                                        } catch (Exception ignored) {}
                                    }
                                    if (offMs > now.getTimeInMillis()) list.add(new Transition(offMs, false));
                                } catch (Exception e) {
                                    logManager.addLog("Schedule parse error (off): " + e.getMessage());
                                }
                            }
                        } catch (Exception e) {
                            logManager.addLog("Schedule parse error: " + e.getMessage());
                        }
                }
            }
            cursor.add(Calendar.DAY_OF_YEAR, 1);
            cursor.set(Calendar.HOUR_OF_DAY, 0);
            cursor.set(Calendar.MINUTE, 0);
            cursor.set(Calendar.SECOND, 0);
            cursor.set(Calendar.MILLISECOND, 0);
        }
        // sort by epoch
        Collections.sort(list, (a,b) -> Long.compare(a.epochMs, b.epochMs));
        return list;
    }

    private List<Settings.Schedule.TimeSlot> daySlotsFor(Settings.Schedule schedule, int dow) {
        // Map Calendar day to Schedule field
        switch (dow) {
            case Calendar.MONDAY: return schedule.monday;
            case Calendar.TUESDAY: return schedule.tuesday;
            case Calendar.WEDNESDAY: return schedule.wednesday;
            case Calendar.THURSDAY: return schedule.thursday;
            case Calendar.FRIDAY: return schedule.friday;
            case Calendar.SATURDAY: return schedule.saturday;
            case Calendar.SUNDAY: return schedule.sunday;
            default: return null;
        }
    }

    private long parseTimeForDay(String hhmm, Calendar dayCursor, TimeZone tz, int addDays) throws ParseException {
        if (hhmm == null) throw new ParseException("null time", 0);
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);
        sdf.setTimeZone(tz);
        Date d = sdf.parse(hhmm);
        Calendar c = (Calendar) dayCursor.clone();
        c.add(Calendar.DAY_OF_YEAR, addDays);
        // set hour/minute from parsed date
        Calendar tmp = Calendar.getInstance(tz);
        tmp.setTime(d);
        c.set(Calendar.HOUR_OF_DAY, tmp.get(Calendar.HOUR_OF_DAY));
        c.set(Calendar.MINUTE, tmp.get(Calendar.MINUTE));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
