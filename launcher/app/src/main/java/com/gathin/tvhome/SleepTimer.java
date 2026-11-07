package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * appletv_control_center's sleep timer and Standby tile. The TV goes to sleep through Android's "lock screen" global
 * action, which only an accessibility service can send: this launcher's InputKeyService or HomeKeyService, whichever
 * is on (the Sleep Timer add-on does the same with a service of its own). The end time lives in a preference; an
 * alarm wakes this receiver at the end even when Home is in the background or its process was stopped, and while the
 * process lives a main-thread callback fires on the minute, since without SCHEDULE_EXACT_ALARM (which the launcher
 * does not ask for) Android may deliver the alarm a few minutes late.
 */
public class SleepTimer extends BroadcastReceiver {
    static final String ACTION_END = "com.gathin.tvhome.SLEEP_TIMER";
    private static final String PREFS = "sleep_timer";

    /** Created on first use: a Handler built while the class loads would break the JVM tests of other classes. */
    private static Handler handler;
    private static final Runnable onTime = SleepTimer::fireIfDue;
    private static Context app;

    /** A helper that can send the TV to sleep is bound. */
    static boolean helperBound() {
        return InputKeyService.isRunning() || HomeKeyService.canOpenPowerMenu();
    }

    /** Puts the TV to sleep now; false when no helper is bound (or Android is older than 9). */
    static boolean sleepNow() {
        return InputKeyService.lockScreen() || HomeKeyService.lockScreen();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** When the TV goes to sleep, wall-clock millis, or 0 when the timer is off. */
    static long end(Context ctx) {
        long end = prefs(ctx).getLong("end", 0);
        return end > System.currentTimeMillis() - 5_000 ? end : 0;
    }

    /** The step the timer was last set to (ControlCenter.TIMER_STEPS), 0 when it is off. */
    static int chosen(Context ctx) {
        return end(ctx) == 0 ? 0 : prefs(ctx).getInt("minutes", 0);
    }

    /** Sets the timer to minutes from now, or turns it off for 0. */
    static void set(Context ctx, int minutes) {
        cancel(ctx);
        if (minutes <= 0) return;
        long end = System.currentTimeMillis() + minutes * 60_000L;
        prefs(ctx).edit().putLong("end", end).putInt("minutes", minutes).apply();
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) {
            PendingIntent pi = pending(ctx);
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi);
            } else {
                // Allowed without the exact-alarm permission; Android may stretch the window to 10 minutes.
                am.setWindow(AlarmManager.RTC_WAKEUP, end, 60_000, pi);
            }
        }
        app = ctx.getApplicationContext();
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(onTime, minutes * 60_000L);
    }

    static void cancel(Context ctx) {
        prefs(ctx).edit().remove("end").remove("minutes").apply();
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pending(ctx));
        if (handler != null) handler.removeCallbacks(onTime);
    }

    private static void fireIfDue() {
        Context ctx = app;
        if (ctx != null) end(ctx, false);
    }

    /** At the end: the timer goes off and the TV to sleep. An alarm that comes after a change of mind does nothing. */
    private static void end(Context ctx, boolean fromAlarm) {
        long end = prefs(ctx).getLong("end", 0);
        if (end == 0 || System.currentTimeMillis() < end - (fromAlarm ? 0 : 2_000)) return;
        cancel(ctx);
        sleepNow();
    }

    private static PendingIntent pending(Context ctx) {
        Intent intent = new Intent(ctx, SleepTimer.class).setAction(ACTION_END);
        return PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_END.equals(intent.getAction())) end(context, true);
    }
}
