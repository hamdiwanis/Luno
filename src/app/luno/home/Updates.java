package app.luno.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInstaller;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Apps being installed or updated (by the Play Store or anything else), with their progress, names
 * and icons (a new app has no card yet: Home shows one from these until it's installed). Android
 * shares every install session with the home screen through LauncherApps (Android 10 and later).
 * Idle unless something installs: no polling, the system calls in.
 */
final class Updates extends PackageInstaller.SessionCallback {
    private final LauncherApps apps;
    private final Context ctx;
    private final Runnable changed;
    /** Active sessions: id → package, and id → progress 0..1. */
    private final Map<Integer, String> pkgs = new HashMap<>();
    private final Map<Integer, Float> progress = new HashMap<>();
    /** The name and icon the installer gave each session (the Play Store gives both). */
    private final Map<Integer, String> labels = new HashMap<>();
    private final Map<Integer, Bitmap> icons = new HashMap<>();
    /**
     * Sessions seen active while Luno watched. The Play Store closes a session between steps, so one
     * stays shown until it finishes instead of blinking; a stale session never seen active stays out.
     */
    private final Set<Integer> seen = new HashSet<>();
    /** Goes up when the apps installing, or their names or icons, change (not on progress). */
    private int version;
    private boolean on;

    Updates(Context ctx, Runnable changed) {
        this.ctx = ctx;
        this.apps = ctx.getSystemService(LauncherApps.class);
        this.changed = changed;
    }

    void start() {
        if (Build.VERSION.SDK_INT < 29 || apps == null || on) return;
        try {
            apps.registerPackageInstallerSessionCallback(ctx.getMainExecutor(), this);
            on = true;
        } catch (Exception ignored) { return; } // not the home app: no sessions to see
        reload();
    }

    void stop() {
        if (!on) return;
        on = false;
        try { apps.unregisterPackageInstallerSessionCallback(this); } catch (Exception ignored) { }
    }

    /** Progress 0..1 of the app's update, or -1 when it isn't updating. */
    float of(String pkg) {
        float p = -1;
        for (Map.Entry<Integer, String> e : pkgs.entrySet()) {
            if (pkg.equals(e.getValue())) p = Math.max(p, progress.getOrDefault(e.getKey(), 0f));
        }
        return p;
    }

    boolean any() { return !pkgs.isEmpty(); }

    int version() { return version; }

    /** Every app installing or updating: package → {name or null, icon or null}. */
    Map<String, Object[]> apps() {
        Map<String, Object[]> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : pkgs.entrySet()) {
            Object[] cur = out.get(e.getValue());
            String label = labels.get(e.getKey());
            Bitmap icon = icons.get(e.getKey());
            if (cur == null) out.put(e.getValue(), new Object[] {label, icon});
            else {
                if (cur[0] == null) cur[0] = label;
                if (cur[1] == null) cur[1] = icon;
            }
        }
        return out;
    }

    /** Reads every session again (on start and when one is created, finished or changes state). */
    private void reload() {
        String before = signature();
        pkgs.clear();
        progress.clear();
        labels.clear();
        icons.clear();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                for (PackageInstaller.SessionInfo s : apps.getAllPackageInstallerSessions()) {
                    int id = s.getSessionId();
                    if (s.isActive()) seen.add(id);
                    if (s.getAppPackageName() == null || !seen.contains(id)) continue;
                    pkgs.put(id, s.getAppPackageName());
                    progress.put(id, s.getProgress());
                    CharSequence label = s.getAppLabel();
                    if (label != null && label.length() > 0) labels.put(id, label.toString());
                    Bitmap icon = s.getAppIcon();
                    if (icon != null) icons.put(id, icon);
                }
            } catch (Exception ignored) { }
        }
        if (!before.equals(signature())) version++;
        changed.run();
    }

    private String signature() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, String> e : pkgs.entrySet()) {
            b.append(e.getKey()).append(e.getValue()).append(labels.get(e.getKey()))
                    .append(icons.containsKey(e.getKey())).append(';');
        }
        return b.toString();
    }

    @Override public void onCreated(int id) { reload(); }
    @Override public void onBadgingChanged(int id) { reload(); }
    @Override public void onActiveChanged(int id, boolean active) { reload(); }
    @Override public void onFinished(int id, boolean success) {
        seen.remove(id);
        labels.remove(id);
        icons.remove(id);
        if (pkgs.remove(id) != null) {
            progress.remove(id);
            version++;
        }
        changed.run();
    }
    @Override public void onProgressChanged(int id, float p) {
        if (!pkgs.containsKey(id)) { reload(); return; }
        progress.put(id, p);
        changed.run();
    }

    /** Over an installing or updating app's card: the card darkened, the word and a thin progress bar. */
    static final class Overlay extends View {
        private final Paint scrim = new Paint(), track = new Paint(Paint.ANTI_ALIAS_FLAG),
                fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private final String word;
        private float shown; // the drawn progress, eased towards the real one
        private ValueAnimator anim;

        Overlay(Context c, Typeface face, String word) {
            super(c);
            this.word = word;
            scrim.setColor(0xB8000000);
            track.setColor(0x47FFFFFF);
            fill.setColor(0xFFFFFFFF);
            text.setColor(0xFFFFFFFF);
            text.setTypeface(face);
            text.setTextAlign(Paint.Align.CENTER);
            setAlpha(0f);
            animate().alpha(1f).setDuration(200).start();
        }

        void setProgress(float p) {
            p = Math.max(0f, Math.min(1f, p));
            if (anim != null) anim.cancel();
            if (p < shown) { setShown(p); return; } // a new session starts from the start
            anim = ValueAnimator.ofFloat(shown, p);
            anim.addUpdateListener(a -> setShown((float) a.getAnimatedValue()));
            anim.setDuration(300);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.start();
        }

        private void setShown(float v) {
            shown = v;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawRect(0, 0, w, h, scrim);
            float dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());
            float thick = Math.max(3 * dp, h * 0.04f), inset = w * 0.14f;
            float y = h * 0.66f;
            bar.set(inset, y, w - inset, y + thick);
            c.drawRoundRect(bar, thick / 2, thick / 2, track);
            if (shown > 0) {
                bar.right = inset + (w - 2 * inset) * shown;
                c.drawRoundRect(bar, thick / 2, thick / 2, fill);
            }
            text.setTextSize(Math.max(11 * dp, h * 0.15f));
            c.drawText(word, w / 2f, y - h * 0.1f, text);
        }
    }
}
