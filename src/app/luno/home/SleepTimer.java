package app.luno.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Turns the TV off after a set time (Features > Sleep timer). An alarm wakes this receiver, which
 * sends the sleep key through the TV's adb. Turning the TV off by hand cancels it.
 */
public final class SleepTimer extends BroadcastReceiver {
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sleep", Context.MODE_PRIVATE);
    }

    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, 0, new Intent(c, SleepTimer.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Starts the timer for this many minutes; 0 turns it off. */
    static void set(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(alarm(c));
        if (minutes <= 0) {
            prefs(c).edit().remove("at").apply();
            return;
        }
        long at = System.currentTimeMillis() + minutes * 60_000L;
        am.setExact(AlarmManager.RTC, at, alarm(c));
        prefs(c).edit().putLong("at", at).apply();
    }

    /** Whole minutes left (rounded up), or 0 when the timer is off. */
    static int minutesLeft(Context c) {
        long left = prefs(c).getLong("at", 0) - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        prefs(c).edit().remove("at").apply();
        PendingResult result = goAsync();
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try { LocalAdb.shell(app, "input keyevent KEYCODE_SLEEP"); } catch (Exception ignored) { }
            result.finish();
        }, "sleep").start();
    }
}
