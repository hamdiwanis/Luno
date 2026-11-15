package app.luno.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Luno: a photo-first home screen for Android TV. A full-bleed photo that changes every few
 * minutes, a large clock on the left, a compact floating dock, and every other app one press down.
 * Built to be light: no live blur, the photo released while an app is open, only scale and fade animate,
 * nothing runs while another app is in front.
 */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable nextPhotoTask = this::nextPhoto;

    private FrameLayout root;
    private ImageView wallBack, wallFront;
    private TextView clock, date, dockLabel, netName;
    private ImageView netIcon;
    private View wifiChip, featuresBtn, settingsBtn;
    private LinearLayout dock, clockBox;
    private View topBar;
    private FrameLayout sheet;
    private ScrollView sheetScroll;
    private LinearLayout sheetGrid;
    private PopupWindow popup;

    private final List<View> dockTiles = new ArrayList<>();
    private final List<View> sheetTiles = new ArrayList<>();
    private List<App> sheetApps = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();
    private String[] dockApps = new String[0], appsFirst = new String[0];
    private int lastDockIndex;
    private int packageSeq;

    private String[] photos = new String[0];
    private int photoIndex = -1;
    private long photoAt;
    private long photoMs;
    private boolean photoLoaded;

    private ConnectivityManager.NetworkCallback netCallback;
    private String netKey;
    private boolean ssidBusy;
    private long processLimitAt;

    private static Typeface sans, sansMedium;

    // Dock geometry (dp). The padding and spacing are derived from these so the gap around a
    // focused card is the same on all four sides.
    static final float DOCK_TILE_W = 128f, DOCK_TILE_H = 72f, DOCK_TILE_RADIUS = 11f;
    static final float DOCK_FOCUS = 1.1f, DOCK_GAP = 9f;
    /** Space under the dock, mirrored above the top bar; and the top bar's height. */
    static final float DOCK_BOTTOM = 32f, BAR_H = 36f;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;      // shown-size bitmap, made on demand (artFor) and dropped when not needed
        boolean banner;
        ResolveInfo ri;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        // The dock as arranged on the TV (hold OK on an app); config.xml only sets the first one.
        String saved = getPreferences(MODE_PRIVATE).getString("dock", null);
        if (saved != null) dockApps = saved.isEmpty() ? new String[0] : saved.split(",");
        appsFirst = getResources().getStringArray(R.array.apps_first);
        // The apps list as sorted on the TV (hold OK > Move); config.xml only sets the first order.
        String savedApps = getPreferences(MODE_PRIVATE).getString("apps", null);
        if (savedApps != null) appsFirst = savedApps.isEmpty() ? new String[0] : savedApps.split(",");
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        photoMs = Math.max(1, getResources().getInteger(R.integer.photo_minutes)) * 60_000L;
        try {
            photos = getAssets().list("wallpapers");
        } catch (Exception ignored) { }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        watchNetwork();
        listMode = getPreferences(MODE_PRIVATE).getBoolean("list", false);
        applyMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, tf);
        updateClock();
        // Photos change only while Home is on screen; catch up if one is due.
        long since = SystemClock.elapsedRealtime() - photoAt;
        if (listMode) { /* no photo in the apps-list mode */ }
        else if (photoAt != 0 && since >= photoMs) nextPhoto();
        else {
            if (!photoLoaded && photoIndex >= 0) loadPhoto(photoIndex);
            handler.postDelayed(nextPhotoTask, Math.max(1000, photoMs - since));
        }
        android.content.pm.ChangedPackages changed = getPackageManager().getChangedPackages(packageSeq);
        if (changed != null) buildApps();
        reapplyProcessLimit();
        if (popup == null && (listMode ? !sheetTiles.contains(getCurrentFocus()) : sheet.getVisibility() != View.VISIBLE)) {
            focusDock(lastDockIndex);
        }
        if (listMode) {
            // Back on Home: the focused tile hasn't changed, so light it again (released in onStop).
            handler.post(() -> {
                View f = getCurrentFocus();
                if (f != null && sheetTiles.contains(f) && f.getTag() instanceof App) showGlow(f, (App) f.getTag());
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(tick); } catch (Exception ignored) { }
        handler.removeCallbacks(nextPhotoTask);
    }

    /** Another app is in front: give back the photo and the apps sheet. */
    @Override
    protected void onStop() {
        super.onStop();
        releasePhoto();
        hideGlow();
        washCache.clear();
        if (!listMode && sheet.getVisibility() == View.VISIBLE) {
            sheet.setVisibility(View.GONE);
            homeContent(true);
        }
        releaseSheet();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(packages); } catch (Exception ignored) { }
        if (netCallback != null) {
            try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback); }
            catch (Exception ignored) { }
        }
    }

    /** Home pressed while Home is showing: back to the dock. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismissPopup();
        if (!listMode && sheet.getVisibility() == View.VISIBLE) closeSheet(false);
        focusDock(0);
    }

    @Override
    public void onBackPressed() {
        if (popup != null) dismissPopup();
        else if (movingPkg != null) endMove();
        else if (!listMode && sheet.getVisibility() == View.VISIBLE) closeSheet(true);
        // Home has nowhere to go back to.
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            if (popup == null) showFeatures();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        wallBack = photoLayer();
        wallFront = photoLayer();
        root.addView(wallBack, full());
        root.addView(wallFront, full());
        // Apps-list home: the focused banner's colour washed over the lower screen (Glow), under everything else.
        // Two layers so a new wash crossfades over the old one.
        glowBack = new ImageView(this);
        glowBack.setScaleType(ImageView.ScaleType.FIT_XY);
        glowBack.setAlpha(0f);
        root.addView(glowBack, full());
        glowView = new ImageView(this);
        glowView.setScaleType(ImageView.ScaleType.FIT_XY);
        glowView.setAlpha(0f);
        root.addView(glowView, full());
        // The shade for the clock and dock is painted into each photo (see nextPhoto): on this TV's
        // GPU every extra full-screen layer costs frames.

        // Compact top bar: "time | date" on the left, network and buttons on the right, all on one
        // centre line. Its edges follow the dock's first and last cards (set in alignTopBar), and
        // its top margin equals the space under the dock.
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.HORIZONTAL);
        clockBox.setGravity(Gravity.CENTER_VERTICAL);
        clock = text(28, sansMedium(), Color.WHITE);
        clock.setIncludeFontPadding(false);
        clock.setSingleLine(true);
        clock.setShadowLayer(dp(8), 0, dp(1), 0x80000000);
        View divider = new View(this);
        divider.setBackgroundColor(0x78FFFFFF);
        date = text(15, sans(), 0xD9FFFFFF);
        date.setSingleLine(true);
        date.setShadowLayer(dp(8), 0, dp(1), 0x80000000);
        clockBox.addView(clock);
        clockBox.addView(divider, llp(dp(1), dp(18), dp(12), 0, dp(12), 0));
        clockBox.addView(date);
        root.addView(clockBox, lp(-2, dp(BAR_H), Gravity.START | Gravity.TOP, dp(59), dp(DOCK_BOTTOM), 0, 0));
        this.clockBox = clockBox;

        topBar = buildTopBar();
        root.addView(topBar, lp(-2, dp(BAR_H), Gravity.END | Gravity.TOP, 0, dp(DOCK_BOTTOM), dp(59), 0));

        // The dock: compact and floating, bottom centre. Its padding comes from the focus lift, so a
        // focused card has the same gap to the dock's edge on every side and to its neighbours.
        float growX = DOCK_TILE_W * (DOCK_FOCUS - 1f) / 2f, growY = DOCK_TILE_H * (DOCK_FOCUS - 1f) / 2f;
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(DOCK_GAP + growX), dp(DOCK_GAP + growY), dp(DOCK_GAP + growX), dp(DOCK_GAP + growY));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        GradientDrawable dbg = new GradientDrawable();
        dbg.setCornerRadius(dp(DOCK_TILE_RADIUS * DOCK_FOCUS + DOCK_GAP)); // follows the cards' corners
        dbg.setColor(0x52101214);
        dbg.setStroke(dp(1), 0x24FFFFFF);
        dock.setBackground(dbg);
        root.addView(dock, lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, dp(DOCK_BOTTOM)));
        dock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> alignTopBar());

        // The focused dock app's name, centred above that app (moved there on focus).
        dockLabel = text(15, sansMedium(), Color.WHITE);
        dockLabel.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        dockLabel.setSingleLine(true);
        dockLabel.setAlpha(0f);
        dockLabel.setVisibility(View.GONE); // not shown for now; showDockLabel() is kept if wanted back
        root.addView(dockLabel, lp(-2, -2, Gravity.BOTTOM | Gravity.START, 0, 0, 0,
                dp(DOCK_BOTTOM + DOCK_TILE_H + 2 * (DOCK_GAP + growY) + 12)));

        root.addView(buildSheet(), full());
        return root;
    }

    private void setBarEdges(int left, int right) {
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) clockBox.getLayoutParams();
        FrameLayout.LayoutParams tl = (FrameLayout.LayoutParams) topBar.getLayoutParams();
        cl.leftMargin = left;
        tl.rightMargin = right;
        clockBox.setLayoutParams(cl);
        topBar.setLayoutParams(tl);
    }

    /** The top bar's left and right edges follow the dock's first and last cards. */
    private void alignTopBar() {
        if (listMode || dock.getChildCount() == 0 || topBar == null) return;
        int left = dock.getLeft() + dock.getPaddingLeft();
        int right = root.getWidth() - (dock.getRight() - dock.getPaddingRight());
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) clockBox.getLayoutParams();
        FrameLayout.LayoutParams tl = (FrameLayout.LayoutParams) topBar.getLayoutParams();
        if (cl.leftMargin == left && tl.rightMargin == right) return;
        cl.leftMargin = left;
        tl.rightMargin = right;
        clockBox.setLayoutParams(cl);
        topBar.setLayoutParams(tl);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(16), 0);
        netIcon = new ImageView(this);
        netIcon.setImageResource(R.drawable.ic_wifi);
        chip.addView(netIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        netName = text(14, sansMedium(), Color.WHITE);
        netName.setSingleLine(true);
        chip.addView(netName, llp(-2, -2, dp(8), 0, 0, 0));
        wifiChip = chip;
        focusable(chip, dp(18), () -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)), netIcon, netName);
        bar.addView(chip, new LinearLayout.LayoutParams(-2, dp(BAR_H)));

        featuresBtn = roundButton(R.drawable.ic_features, this::showFeatures);
        settingsBtn = roundButton(R.drawable.ic_settings, () -> launch(new Intent(Settings.ACTION_SETTINGS)));
        bar.addView(featuresBtn, llp(dp(BAR_H), dp(BAR_H), dp(10), 0, 0, 0));
        bar.addView(settingsBtn, llp(dp(BAR_H), dp(BAR_H), dp(10), 0, 0, 0));

        View[] order = {wifiChip, featuresBtn, settingsBtn};
        for (int i = 0; i < order.length; i++) {
            View v = order[i];
            v.setNextFocusLeftId(order[Math.max(0, i - 1)].getId());
            v.setNextFocusRightId(order[Math.min(order.length - 1, i + 1)].getId());
            v.setNextFocusUpId(v.getId());
            v.setOnKeyListener((view, code, ev) -> {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && ev.getAction() == KeyEvent.ACTION_DOWN) {
                    focusDock(lastDockIndex);
                    return true;
                }
                return false;
            });
        }
        return bar;
    }

    private View roundButton(int icon, Runnable action) {
        FrameLayout b = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setImageResource(icon);
        b.addView(img, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        focusable(b, dp(18), action, img, null);
        return b;
    }

    /** Top-bar control: dark translucent capsule, white with dark content when focused. */
    private void focusable(View v, int radius, Runnable action, ImageView icon, TextView label) {
        v.setId(View.generateViewId());
        v.setFocusable(true);
        v.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        v.setBackground(bg);
        v.setOnClickListener(x -> action.run());
        Object[] part = {v, bg, icon, label, radius};
        topParts.add(part);
        paintTop(part, false);
        v.setOnFocusChangeListener((x, has) -> {
            paintTop(part, has);
            if (has && listMode) {
                // The bottom title names the focused control, as it does the focused app.
                showTitle(x == wifiChip ? "Network" : x == featuresBtn ? "Features" : "Settings");
                fadeGlowOut();
            }
            x.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(160).start();
        });
    }

    private final List<Object[]> topParts = new ArrayList<>(); // {view, bg, icon, label, radius}

    /**
     * The top controls look like the app tiles: a dark card with the banners' corners and a white
     * ring when focused (both homes).
     */
    private void paintTop(Object[] part, boolean has) {
        GradientDrawable bg = (GradientDrawable) part[1];
        ImageView icon = (ImageView) part[2];
        TextView label = (TextView) part[3];
        // Both homes: the tiles' own look, a dark card with the banners' corners, a white ring on focus.
        bg.setCornerRadius(dp(12));
        // Photo home: see-through so the photo shows behind; apps list: the tiles' solid card.
        bg.setColor(listMode ? 0xFF24262B : 0x8C24262B);
        bg.setStroke(has ? dp(2.5f) : 0, Color.WHITE);
        if (icon != null) icon.setColorFilter(Color.WHITE);
        if (label != null) label.setTextColor(Color.WHITE);
    }

    private void repaintTop() {
        for (Object[] part : topParts) paintTop(part, ((View) part[0]).isFocused());
    }

    private View buildSheet() {
        sheet = new FrameLayout(this);
        sheet.setBackgroundColor(0xFA0E1013);
        sheet.setVisibility(View.GONE);
        sheet.setClickable(true);

        TextView title = text(22, sansMedium(), Color.WHITE);
        sheetTitle = title;
        title.setText("Apps");
        sheet.addView(title, lp(-2, -2, Gravity.TOP | Gravity.START, dp(64), dp(40), 0, 0));

        sheetScroll = new ScrollView(this);
        sheetScroll.setVerticalScrollBarEnabled(false);
        sheetScroll.setClipToPadding(false);
        sheetScroll.setClipChildren(false); // else the grid is clipped to its edges, cutting focused cards
        // Room inside the scroller so a focused (enlarged) card isn't clipped at its edges.
        sheetScroll.setPadding(dp(24), dp(16), dp(24), dp(48));
        sheetGrid = new LinearLayout(this);
        sheetGrid.setOrientation(LinearLayout.VERTICAL);
        sheetGrid.setClipChildren(false);
        sheetScroll.addView(sheetGrid, new FrameLayout.LayoutParams(-1, -2));
        sheet.addView(sheetScroll, lp(-1, -1, Gravity.TOP, dp(40), dp(84), dp(40), 0));
        return sheet;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            a.ri = ri;
            byPkg.put(a.pkg, a);
        }
        List<App> inDock = new ArrayList<>();
        for (String p : dockApps) if (byPkg.containsKey(p)) inDock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>();
        for (String p : appsFirst) if (byPkg.containsKey(p)) rest.add(byPkg.remove(p));
        List<App> others = new ArrayList<>(byPkg.values());
        Collections.sort(others, (x, y) -> x.label.compareToIgnoreCase(y.label));
        rest.addAll(others);

        dock.removeAllViews();
        dockTiles.clear();
        // Half the space between two cards on each side: a focused card then sits DOCK_GAP from its
        // neighbours, as from the dock's edges. The ends get none (the dock's padding covers them).
        int half = dp((DOCK_GAP + DOCK_TILE_W * (DOCK_FOCUS - 1f) / 2f) / 2f);
        for (int i = 0; i < inDock.size(); i++) {
            View t = tile(inDock.get(i), dp(DOCK_TILE_W), true, i);
            dock.addView(t, llp(dp(DOCK_TILE_W), -2, i == 0 ? 0 : half, 0, i == inDock.size() - 1 ? 0 : half, 0));
        }
        for (int i = 0; i < dockTiles.size(); i++) {
            View t = dockTiles.get(i);
            t.setNextFocusLeftId(dockTiles.get(Math.max(0, i - 1)).getId());
            t.setNextFocusRightId(dockTiles.get(Math.min(dockTiles.size() - 1, i + 1)).getId());
            t.setNextFocusUpId(featuresBtn.getId());
        }

        if (listMode) {
            // Everything in one list: the saved order, else the dock first and then the rest.
            List<App> all = new ArrayList<>(inDock);
            all.addAll(rest);
            String saved = getPreferences(MODE_PRIVATE).getString("list_order", null);
            if (saved != null) {
                List<String> order = Arrays.asList(saved.split(","));
                List<App> base = all, sorted = new ArrayList<>(all);
                int n = base.size();
                Collections.sort(sorted, (x, y) -> {
                    int a = order.indexOf(x.pkg), b = order.indexOf(y.pkg);
                    if (a < 0) a = Integer.MAX_VALUE - n + base.indexOf(x); // new apps at the end
                    if (b < 0) b = Integer.MAX_VALUE - n + base.indexOf(y);
                    return Integer.compare(a, b);
                });
                all = sorted;
            }
            sheetApps = all;
        } else {
            sheetApps = rest;
        }
        releaseSheet();
        if (sheet.getVisibility() == View.VISIBLE) buildSheet(sheetApps);
        lastDockIndex = Math.min(lastDockIndex, Math.max(0, dockTiles.size() - 1));
    }

    /** The apps sheet's tiles, built when it opens (their art is loaded then, not at start). */
    private void buildSheet(List<App> rest) {
        sheetGrid.removeAllViews();
        sheetTiles.clear();
        int cols = sheetCols(), gap = dp(listMode ? 24 : 18);
        int width = getResources().getDisplayMetrics().widthPixels - 2 * dp(64);
        int tileW = (width - (cols - 1) * gap) / cols;
        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                sheetGrid.addView(row, llp(-1, -2, 0, 0, 0, dp(22)));
            }
            row.addView(tile(rest.get(i), tileW, false, i), llp(tileW, -2, i % cols == 0 ? 0 : gap, 0, 0, 0));
        }
        for (int i = 0; i < sheetTiles.size(); i++) {
            View t = sheetTiles.get(i);
            int col = i % cols;
            t.setNextFocusLeftId(col == 0 ? t.getId() : sheetTiles.get(i - 1).getId());
            t.setNextFocusRightId(col == cols - 1 || i == sheetTiles.size() - 1 ? t.getId() : sheetTiles.get(i + 1).getId());
            t.setNextFocusUpId(i < cols ? t.getId() : sheetTiles.get(i - cols).getId());
            // Down: the app below; where the next row is shorter, its last (nearest) app.
            int n = sheetTiles.size(), below = i + cols;
            boolean nextRow = (i / cols + 1) * cols < n;
            t.setNextFocusDownId(below < n ? sheetTiles.get(below).getId()
                    : nextRow ? sheetTiles.get(n - 1).getId() : t.getId());
        }
    }

    /** Drops the sheet's tiles and their art; it's rebuilt the next time it opens. */
    private void releaseSheet() {
        if (sheet.getVisibility() == View.VISIBLE) return;
        sheetGrid.removeAllViews();
        sheetTiles.clear();
        for (App a : sheetApps) a.art = null;
    }

    /**
     * The app's banner (or icon) drawn once at the size it's shown, focus lift included, instead of
     * the full-size art the app ships (often 640x360 or more: about 1 MB each).
     */
    private Drawable artFor(App a) {
        if (a.art != null) return a.art;
        PackageManager pm = getPackageManager();
        Drawable d = a.ri.activityInfo.loadBanner(pm);
        if (d == null) d = a.ri.activityInfo.applicationInfo.loadBanner(pm);
        a.banner = d != null;
        if (d == null) d = a.ri.loadIcon(pm);
        int w = artWidth(), h = w * 9 / 16;
        Bitmap b;
        if (a.banner) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int iw = Math.max(1, d.getIntrinsicWidth()), ih = Math.max(1, d.getIntrinsicHeight());
            float sc = Math.max((float) w / iw, (float) h / ih); // centre-crop into the card
            int dw = Math.round(iw * sc), dh = Math.round(ih * sc);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
        } else {
            b = Bitmap.createBitmap(h, h, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, h, h);
        }
        d.draw(new android.graphics.Canvas(b));
        a.art = new android.graphics.drawable.BitmapDrawable(getResources(), b);
        return a.art;
    }

    /** The widest a card is ever drawn: a focused dock card or a focused sheet card. */
    private int artWidth() {
        int cols = sheetCols(), gap = dp(listMode ? 24 : 18);
        int sheetW = (getResources().getDisplayMetrics().widthPixels - 2 * dp(64) - (cols - 1) * gap) / cols;
        return Math.max(Math.round(dp(DOCK_TILE_W) * DOCK_FOCUS), Math.round(sheetW * 1.08f));
    }

    /** A 16:9 card with the app's banner (or its icon on a dark card). */
    private View tile(App app, int w, boolean inDock, int index) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);

        FrameLayout card = new FrameLayout(this);
        card.setTag(app); // read when arranging the dock
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        int radius = inDock ? dp(DOCK_TILE_RADIUS) : dp(12);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setBackgroundColor(0xFF24262B);
        ImageView img = new ImageView(this);
        img.setImageDrawable(artFor(app));
        img.setScaleType(app.banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (!app.banner) img.setPadding(w / 4, w / 10, w / 4, w / 10);
        card.addView(img, full());
        // Focus ring: a crisp white edge inside the card's rounded outline, shown only when focused.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        // Apps list: a faint light edge so dark banners stand out from the dark background.
        boolean edge = listMode && !inDock;
        ring.setStroke(edge ? dp(1) : dp(2.5f), edge ? 0x38FFFFFF : Color.WHITE);
        ring.setAlpha(edge ? 255 : 0);
        card.setForeground(ring);
        box.addView(card, new LinearLayout.LayoutParams(w, w * 9 / 16));

        TextView name = null;
        if (!inDock && !listMode) { // the apps list shows one title at the top instead
            name = text(12, sans(), 0xB3FFFFFF);
            name.setText(app.label);
            name.setSingleLine(true);
            name.setGravity(Gravity.CENTER);
            box.addView(name, llp(-1, -2, 0, dp(10), 0, 0));
        }
        TextView label = name;
        card.setOnClickListener(v -> {
            launch(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        });
        card.setOnFocusChangeListener((v, has) -> {
            // Scale only: a moving elevation shadow is redrawn every frame of the lift.
            float s = has ? (inDock ? DOCK_FOCUS : 1.08f) : 1f;
            v.animate().scaleX(s).scaleY(s)
                    .setInterpolator(new DecelerateInterpolator(2f)).setDuration(has ? 180 : 140).start();
            if (edge) ring.setStroke(has ? dp(2.5f) : dp(1), has ? Color.WHITE : 0x38FFFFFF);
            else ring.setAlpha(has ? 255 : 0);
            if (label != null) label.setTextColor(has ? Color.WHITE : 0xB3FFFFFF);
            if (inDock) {
                if (has) lastDockIndex = index; // no name over dock apps: the banners carry it
                // After focus settles: dim the other dock apps while one is focused, else none.
                handler.post(this::updateDockDim);
            } else if (has) {
                if (listMode) {
                    showTitle(app.label);
                    showGlow(v, app);
                }
                scrollSheetTo(box);
            }
        });
        // Hold OK: dock apps can be moved or removed, other apps added to the dock.
        card.setOnLongClickListener(v -> {
            if (movingPkg != null) return true;
            if (inDock) {
                showActions(v, new String[]{"Move", "Remove from dock"},
                        new Runnable[]{() -> startMove(app.pkg), () -> removeFromDock(app.pkg)});
            } else {
                if (listMode) showActions(v, new String[]{"Move"}, new Runnable[]{() -> startMove(app.pkg)});
                else showActions(v, new String[]{"Move", "Add to dock"},
                        new Runnable[]{() -> startMove(app.pkg), () -> addToDock(app.pkg)});
            }
            return true;
        });
        if (inDock) {
            card.setOnKeyListener((v, code, ev) -> {
                if (longPressKey(v, code, ev)) return true;
                if (movingPkg != null) return moveKey(code, ev);
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && ev.getAction() == KeyEvent.ACTION_DOWN) {
                    openSheet();
                    return true;
                }
                return false;
            });
        } else {
            card.setOnKeyListener((v, code, ev) -> {
                if (longPressKey(v, code, ev)) return true;
                if (movingPkg != null) return moveKey(code, ev);
                if (code == KeyEvent.KEYCODE_DPAD_UP && ev.getAction() == KeyEvent.ACTION_DOWN
                        && sheetTiles.indexOf(v) < sheetCols()) {
                    if (listMode) featuresBtn.requestFocus();
                    else closeSheet(true);
                    return true;
                }
                return false;
            });
        }
        (inDock ? dockTiles : sheetTiles).add(card);
        return box;
    }

    /** The name above the focused dock app, centred on it; slides along when focus moves. */
    private void showDockLabel(View card, String name) {
        boolean wasShown = dockLabel.getAlpha() > 0.5f;
        dockLabel.setText(name);
        dockLabel.post(() -> {
            int[] at = new int[2];
            card.getLocationOnScreen(at); // the scaled card's left edge; its centre doesn't move
            float x = at[0] + card.getWidth() * card.getScaleX() / 2f - dockLabel.getWidth() / 2f;
            if (wasShown) dockLabel.animate().translationX(x).alpha(1f).setDuration(160).start();
            else {
                dockLabel.setTranslationX(x);
                dockLabel.animate().alpha(1f).setDuration(160).start();
            }
        });
    }

    /** While a dock app is focused the others step back a little; otherwise all are full. */
    private void updateDockDim() {
        View f = getCurrentFocus();
        boolean onDock = f != null && dockTiles.contains(f);
        for (View t : dockTiles) {
            float a = !onDock || t == f ? 1f : 0.85f;
            if (t.getAlpha() != a) t.animate().alpha(a).setDuration(160).start();
        }
        if (!onDock) dockLabel.animate().alpha(0f).setDuration(120).start();
    }


    /** Photo home (photo, dock, apps one press down) or the apps list (no photo, all apps). */
    private void applyMode() {
        FrameLayout.LayoutParams sl = (FrameLayout.LayoutParams) sheetScroll.getLayoutParams();
        if (listMode) {
            handler.removeCallbacks(nextPhotoTask);
            releasePhoto();
            root.setBackgroundColor(0xFF0E1013);
            dock.setVisibility(View.GONE);
            // One title above the grid: the focused app's name, large.
            sheetTitle.setVisibility(View.VISIBLE);
            sheetTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            setTitleBottom(dp(DOCK_BOTTOM + 1)); // bottom left: the text sits as far from the bottom edge as the time and date from the top
            sheet.setBackgroundColor(Color.TRANSPARENT);
            sl.topMargin = dp(DOCK_BOTTOM + BAR_H + 34); // under the top bar
            sl.bottomMargin = dp(DOCK_BOTTOM + 1 + 38 + 16); // above the title
            // No dock to align to: the top bar lines up with the grid's edges instead.
            setBarEdges(dp(64), dp(64));
            sheet.animate().cancel();
            sheet.setAlpha(1f);
            sheet.setTranslationY(0);
            sheet.setVisibility(View.VISIBLE);
            clockBox.setAlpha(1f);
        } else {
            hideGlow();
            sheet.setVisibility(View.GONE);
            sheetTitle.setVisibility(View.VISIBLE);
            sheetTitle.setText("Apps");
            sheetTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            setTitleTop(dp(40));
            sl.bottomMargin = 0;
            sheet.setBackgroundColor(0xFA0E1013);
            sl.topMargin = dp(84);
            dock.setVisibility(View.VISIBLE);
            dock.post(this::alignTopBar);
            homeContent(true);
            nextPhoto();
        }
        sheetScroll.setLayoutParams(sl);
        repaintTop();
        buildApps();
        focusDock(0);
    }

    /** Apps per row: 4 large tiles in the apps-list home, 6 in the photo home's sheet. */
    private int sheetCols() {
        return 6;
    }

    /** Lights the background behind the focused banner in its colour; crossfades on each move. */
    private void showGlow(View card, App app) {
        if (app.pkg.equals(glowPkg)) return;
        glowPkg = app.pkg;
        // Wait until focus rests: moving quickly across tiles glides to the final colour instead of
        // starting (and cutting) a fade for every tile passed on the way.
        handler.removeCallbacks(glowSettle);
        handler.postDelayed(glowSettle, 150);
    }

    private final Runnable glowSettle = this::settleGlow;

    private void settleGlow() {
        String pkg = glowPkg;
        if (!listMode || pkg == null) return;
        Bitmap g = washCache.get(pkg);
        if (g != null) crossfadeGlow(g, pkg);
        else prerenderWashes(); // not drawn yet: it's drawn next and faded in when ready
    }

    /** ~130 KB each, a dozen apps at most: kept while Home shows, dropped in onStop. */
    private final java.util.Map<String, Bitmap> washCache = new java.util.HashMap<>();
    private boolean washRendering;

    /**
     * Draws the washes of every app in the list on one background thread, the focused app first,
     * so moving between tiles never waits for one.
     */
    private void prerenderWashes() {
        if (washRendering || !listMode) return;
        List<App> todo = new ArrayList<>();
        for (App a : sheetApps) {
            if (washCache.containsKey(a.pkg)) continue;
            if (a.pkg.equals(glowPkg)) todo.add(0, a); else todo.add(a);
        }
        if (todo.isEmpty()) return;
        washRendering = true;
        List<Bitmap> banners = new ArrayList<>();
        for (App a : todo) {
            Drawable art = artFor(a);
            banners.add(art instanceof android.graphics.drawable.BitmapDrawable
                    ? ((android.graphics.drawable.BitmapDrawable) art).getBitmap() : null);
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        Thread t = new Thread(() -> {
            for (int i = 0; i < todo.size(); i++) {
                Bitmap g = Glow.render(banners.get(i), 0, 0, sw, sh);
                String pkg = todo.get(i).pkg;
                runOnUiThread(() -> {
                    if (g == null || !listMode) return;
                    washCache.put(pkg, g);
                    if (pkg.equals(glowPkg) && glowShown == null) crossfadeGlow(g, pkg);
                });
            }
            runOnUiThread(() -> washRendering = false);
        }, "wash");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** The package whose wash is on screen (or fading in). */
    private String glowShown;

    /**
     * A true crossfade on GPU layers: the old wash fades out while the new one fades in. If a fade
     * is still running, the more visible layer is the one kept and faded out, so nothing jumps.
     */
    private void crossfadeGlow(Bitmap g, String pkg) {
        if (!pkg.equals(glowPkg) || pkg.equals(glowShown)) return;
        glowShown = pkg;
        glowView.animate().cancel();
        glowBack.animate().cancel();
        ImageView out = glowView.getAlpha() >= glowBack.getAlpha() ? glowView : glowBack;
        ImageView in = out == glowView ? glowBack : glowView;
        glowView = in;
        glowBack = out;
        root.removeView(in);
        root.addView(in, root.indexOfChild(out) + 1, full()); // incoming above outgoing
        in.setImageBitmap(g);
        in.setAlpha(0f);
        android.view.animation.Interpolator ease = new android.view.animation.DecelerateInterpolator(1.5f);
        in.animate().alpha(1f).setDuration(420).setInterpolator(ease).withLayer().start();
        out.animate().alpha(0f).setDuration(420).setInterpolator(ease).withLayer()
                .withEndAction(() -> out.setImageDrawable(null)).start();
    }

    /** Fades the wash out (focus moved to the top bar); the cached washes stay for coming back. */
    private void fadeGlowOut() {
        glowPkg = null;
        glowShown = null;
        handler.removeCallbacks(glowSettle);
        for (ImageView v : new ImageView[]{glowView, glowBack}) {
            v.animate().cancel();
            v.animate().alpha(0f).setDuration(300).withLayer().withEndAction(() -> v.setImageDrawable(null)).start();
        }
    }

    private void hideGlow() {
        glowPkg = null;
        glowShown = null;
        handler.removeCallbacks(glowSettle);
        for (ImageView v : new ImageView[]{glowView, glowBack}) {
            v.animate().cancel();
            v.setAlpha(0f);
            v.setImageDrawable(null);
        }
    }

    /** The apps-list title: the old name fades out and down, the new one fades in and rises. */
    private void showTitle(CharSequence name) {
        if (name.toString().contentEquals(sheetTitle.getText()) && sheetTitle.getAlpha() > 0.99f) return;
        sheetTitle.animate().cancel();
        sheetTitle.animate().alpha(0f).translationY(dp(6)).setDuration(110).withEndAction(() -> {
            sheetTitle.setText(name);
            sheetTitle.setTranslationY(dp(10));
            sheetTitle.animate().alpha(1f).translationY(0).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }).start();
    }

    private void setTitleBottom(int bottom) {
        FrameLayout.LayoutParams tl = (FrameLayout.LayoutParams) sheetTitle.getLayoutParams();
        tl.gravity = Gravity.BOTTOM | Gravity.START;
        tl.topMargin = 0;
        tl.bottomMargin = bottom;
        sheetTitle.setLayoutParams(tl);
    }

    private void setTitleTop(int top) {
        FrameLayout.LayoutParams tl = (FrameLayout.LayoutParams) sheetTitle.getLayoutParams();
        tl.gravity = Gravity.TOP | Gravity.START;
        tl.bottomMargin = 0;
        tl.topMargin = top;
        sheetTitle.setLayoutParams(tl);
    }

    private void focusDock(int i) {
        if (listMode) {
            if (!sheetTiles.isEmpty()) sheetTiles.get(0).requestFocus();
            return;
        }
        if (dockTiles.isEmpty()) return;
        dockTiles.get(Math.max(0, Math.min(i, dockTiles.size() - 1))).requestFocus();
    }

    private void openSheet() {
        if (sheetTiles.isEmpty()) buildSheet(sheetApps);
        if (sheetTiles.isEmpty()) return;
        sheet.setVisibility(View.VISIBLE);
        sheet.setAlpha(0f);
        sheet.setTranslationY(dp(24));
        sheet.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        homeContent(false);
        sheetScroll.scrollTo(0, 0);
        sheetTiles.get(0).requestFocus();
    }

    /** Clock and dock step back while the apps sheet is open. */
    private void homeContent(boolean show) {
        float a = show ? 1f : 0f;
        for (View v : new View[]{clockBox, dock, dockLabel}) {
            if (v == dockLabel && show) continue; // the label comes back with the focused tile
            v.animate().alpha(a).setDuration(160).start();
        }
    }

    private void closeSheet(boolean animate) {
        homeContent(true);
        Runnable hide = () -> { sheet.setVisibility(View.GONE); releaseSheet(); };
        if (animate) sheet.animate().alpha(0f).translationY(dp(24)).setDuration(160).withEndAction(hide).start();
        else hide.run();
        focusDock(lastDockIndex);
    }

    private void scrollSheetTo(View tileBox) {
        View row = (View) tileBox.getParent();
        // Apps list: scroll only as far as needed to show the row whole; sheet: row near the top.
        int target = listMode ? row.getBottom() + dp(40) - sheetScroll.getHeight() : row.getTop() - dp(40);
        sheetScroll.smoothScrollTo(0, Math.max(0, target));
    }

    private void launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- photos

    private ImageView photoLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_XY); // photos are prepared at exactly screen size
        return v;
    }

    /** Decodes the next photo off the main thread (full colour, screen size), then fades it in. */
    private void nextPhoto() {
        handler.removeCallbacks(nextPhotoTask);
        if (photos.length == 0) return;
        if (photoIndex < 0) photoIndex = getPreferences(MODE_PRIVATE).getInt("photo", -1);
        photoIndex = (photoIndex + 1) % photos.length;
        getPreferences(MODE_PRIVATE).edit().putInt("photo", photoIndex).apply();
        photoAt = SystemClock.elapsedRealtime();
        loadPhoto(photoIndex);
        handler.postDelayed(nextPhotoTask, photoMs);
    }

    /** Decodes photo i off the main thread and fades it in. */
    private void loadPhoto(int i) {
        photoLoaded = true;
        String name = "wallpapers/" + photos[i];
        // Prepared at 1/PHOTO_SCALE of the screen and scaled up by the GPU (the view is FIT_XY):
        // at 2, a 1080p photo takes ~1 MB instead of ~4 MB.
        int sw = getResources().getDisplayMetrics().widthPixels / PHOTO_SCALE;
        int sh = getResources().getDisplayMetrics().heightPixels / PHOTO_SCALE;
        new Thread(() -> {
            Bitmap bm = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getAssets().open(name)) { BitmapFactory.decodeStream(in, null, o); }
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= sw && o.outHeight / (o.inSampleSize * 2) >= sh) {
                    o.inSampleSize *= 2;
                }
                o.inJustDecodeBounds = false;
                o.inPreferredConfig = Bitmap.Config.ARGB_8888; // full colour: no banding in skies
                Bitmap src;
                try (InputStream in = getAssets().open(name)) { src = BitmapFactory.decodeStream(in, null, o); }
                if (src != null) {
                    bm = prepare(src, sw, sh);
                    src.recycle();
                }
            } catch (Exception ignored) { }
            Bitmap result = bm;
            runOnUiThread(() -> {
                if (photoLoaded) showPhoto(result);
                else if (result != null) result.recycle(); // Home was left while it decoded
            });
        }, "photo").start();
    }

    /** While another app is in front Home holds no photo (about 4 MB); it's reloaded on return. */
    private void releasePhoto() {
        photoLoaded = false;
        for (ImageView v : new ImageView[]{wallFront, wallBack}) {
            v.animate().cancel();
            Drawable d = v.getDrawable();
            v.setImageDrawable(null);
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (b != null) b.recycle();
            }
        }
        root.setBackgroundColor(Color.BLACK);
    }

    /**
     * The photo centre-cropped to exactly the screen (drawn 1:1, nothing scaled per frame), with the
     * shade for the clock (left) and the dock (bottom) painted in. Full colour: ~8 MB at 1080p, held
     * only while Home is showing (released in onStop).
     */
    private static Bitmap prepare(Bitmap src, int sw, int sh) {
        Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        float s = Math.max((float) sw / src.getWidth(), (float) sh / src.getHeight());
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(s, s);
        m.postTranslate((sw - src.getWidth() * s) / 2f, (sh - src.getHeight() * s) / 2f);
        c.drawBitmap(src, m, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        android.graphics.Paint p = new android.graphics.Paint();
        p.setShader(new android.graphics.LinearGradient(0, 0, sw, 0,
                new int[]{0x8C000000, 0x40000000, 0x00000000, 0x00000000}, null,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, sw, sh, p);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, sh,
                new int[]{0x00000000, 0x00000000, 0x14000000, 0x66000000}, null,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, sw, sh, p);
        // Overlay that hides the photos' compression softness: a light dark tint, then a fine grain
        // that breaks up JPEG blocks. Baked in once, free to draw.
        p.setShader(null);
        p.setColor(PHOTO_TINT);
        c.drawRect(0, 0, sw, sh, p);
        p.setShader(new android.graphics.BitmapShader(grain(),
                android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT));
        c.drawRect(0, 0, sw, sh, p);
        return out;
    }

    /** Photos are kept at 1/PHOTO_SCALE of the screen size (1 = full resolution). */
    static final int PHOTO_SCALE = 1;

    /** Dark tint over every photo (alpha 0x26 = 15%). */
    static final int PHOTO_TINT = 0x26000000;
    /** Strength of the grain, 0..255 (16 = about 6%). */
    static final int GRAIN_ALPHA = 16;

    /** A 128x128 tile of soft monochrome noise, light and dark around zero. */
    private static Bitmap grain() {
        int n = 128;
        int[] px = new int[n * n];
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < px.length; i++) {
            float v = (float) r.nextGaussian();
            int a = Math.min(255, Math.round(Math.abs(v) * GRAIN_ALPHA));
            px[i] = (a << 24) | (v > 0 ? 0xFFFFFF : 0x000000);
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    private void showPhoto(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // The photo covers the screen: no black window or root fill drawn under it every frame.
        root.setBackground(null);
        getWindow().setBackgroundDrawable(null);
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        root.removeView(wallFront);
        root.addView(wallFront, 1, full());
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(1500).withEndAction(() -> wallBack.setImageDrawable(null)).start();
    }

    // ---------------------------------------------------------------- clock

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now.getTime()));
    }

    // ---------------------------------------------------------------- network

    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { handler.post(MainActivity.this::updateNet); }
            @Override public void onLost(Network n) { handler.post(MainActivity.this::updateNet); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                handler.post(MainActivity.this::updateNet);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
        updateNet();
    }

    /** Wi-Fi icon and the network's name; the name is read through adb once per network (Location is off). */
    private void updateNet() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        if (nc == null) {
            netIcon.setImageResource(R.drawable.ic_wifi_off);
            netName.setText("Offline");
            netKey = null;
            return;
        }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            netIcon.setImageResource(R.drawable.ic_ethernet);
            netName.setText("Ethernet");
            netKey = null;
            return;
        }
        netIcon.setImageResource(R.drawable.ic_wifi);
        String key = String.valueOf(n);
        if (key.equals(netKey) || ssidBusy) return;
        if (netName.getText().length() == 0 || "Offline".contentEquals(netName.getText())) netName.setText("Wi-Fi");
        ssidBusy = true;
        new Thread(() -> {
            String ssid = null;
            try {
                Matcher m = Pattern.compile("connected to \"(.*)\"").matcher(LocalAdb.shell(this, "cmd wifi status"));
                if (m.find()) ssid = m.group(1);
            } catch (Exception ignored) { }
            String found = ssid;
            runOnUiThread(() -> {
                ssidBusy = false;
                if (found != null) {
                    netName.setText(found);
                    netKey = key;
                }
            });
        }, "ssid").start();
    }

    // ---------------------------------------------------------------- background limit

    /** Keeps Android's cached-app limit; Google's flag sync wipes it, so it's set again (at most every 30 min). */
    private void reapplyProcessLimit() {
        int limit = getResources().getInteger(R.integer.process_limit);
        long now = SystemClock.elapsedRealtime();
        if (limit < 0 || (processLimitAt != 0 && now - processLimitAt < 30 * 60_000L)) return;
        processLimitAt = now;
        new Thread(() -> {
            try {
                LocalAdb.shell(this, "device_config put activity_manager max_cached_processes " + limit);
            } catch (Exception e) {
                processLimitAt = 0; // try again next time Home shows
            }
        }, "process-limit").start();
    }

    // ---------------------------------------------------------------- features

    private void showFeatures() {
        String[] pkgs = getResources().getStringArray(R.array.feature_packages);
        String[] labels = getResources().getStringArray(R.array.feature_labels);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable panel = new GradientDrawable();
        panel.setCornerRadius(dp(20));
        panel.setColor(0xF21C1D21);
        panel.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(panel);
        int width = dp(280);
        PopupWindow pw = new PopupWindow(list, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);

        List<TextView> rows = new ArrayList<>();
        TextView homeRow = text(14, sans(), Color.WHITE);
        homeRow.setSingleLine(true);
        homeRow.setGravity(Gravity.CENTER_VERTICAL);
        homeRow.setPadding(dp(16), 0, dp(16), 0);
        homeRow.setFocusable(true);
        homeRow.setClickable(true);
        homeRow.setId(View.generateViewId());
        homeRow.setText("Home  \u00b7  " + (listMode ? "Apps list" : "Photo"));
        GradientDrawable homeBg = new GradientDrawable();
        homeBg.setCornerRadius(dp(13));
        homeBg.setColor(Color.TRANSPARENT);
        homeRow.setBackground(homeBg);
        homeRow.setOnFocusChangeListener((v, has) -> {
            homeBg.setColor(has ? 0xF7FFFFFF : Color.TRANSPARENT);
            homeRow.setTextColor(has ? 0xFF111114 : Color.WHITE);
        });
        homeRow.setOnClickListener(v -> {
            dismissPopup();
            listMode = !listMode;
            getPreferences(MODE_PRIVATE).edit().putBoolean("list", listMode).apply();
            applyMode();
        });
        list.addView(homeRow, new LinearLayout.LayoutParams(-1, dp(44)));
        rows.add(homeRow);
        for (int i = 0; i < pkgs.length && i < labels.length; i++) {
            String pkg = pkgs[i], label = labels[i];
            if (!present(pkg)) continue;
            TextView r = text(14, sans(), Color.WHITE);
            r.setSingleLine(true);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(16), 0, dp(16), 0);
            r.setFocusable(true);
            r.setClickable(true);
            r.setId(View.generateViewId());
            r.setText(featureText(label, enabled(pkg)));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(13));
            bg.setColor(Color.TRANSPARENT);
            r.setBackground(bg);
            r.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? 0xF7FFFFFF : Color.TRANSPARENT);
                r.setTextColor(has ? 0xFF111114 : Color.WHITE);
            });
            r.setOnClickListener(v -> toggleFeature(r, pkg, label));
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(44)));
            rows.add(r);
        }
        if (rows.isEmpty()) return;
        TextView note = text(11, sans(), 0x99FFFFFF);
        note.setText("Off frees memory. Applies at once.");
        note.setPadding(dp(16), dp(8), dp(16), dp(4));
        list.addView(note);
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        View anchor = featuresBtn;
        pw.setOnDismissListener(() -> { popup = null; anchor.requestFocus(); });
        popup = pw;
        pw.showAsDropDown(anchor, anchor.getWidth() - width, dp(12));
        rows.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- arranging the dock

    /** Most apps the dock shows (more don't fit across a 1080p screen at this card size). */
    static final int DOCK_MAX = 6;
    private String movingPkg;
    private TextView moveHint, sheetHint, sheetTitle;
    /** Home without the photo: all apps in one grid (Features > Home). */
    private boolean listMode;
    private ImageView glowView, glowBack;
    private String glowPkg;
    private boolean movingInSheet;

    private boolean longFired;

    /**
     * Holding OK on a remote sends repeated key-downs; the first repeat opens the card's actions and
     * the matching key-up is swallowed, so the app isn't also opened.
     */
    private boolean longPressKey(View v, int code, KeyEvent ev) {
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 1 && movingPkg == null) {
            longFired = true;
            v.performLongClick();
            return true;
        }
        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() > 1) return longFired;
        if (ev.getAction() == KeyEvent.ACTION_UP && longFired) {
            longFired = false;
            return true;
        }
        return false;
    }

    private void saveDock() {
        getPreferences(MODE_PRIVATE).edit().putString("dock", TextUtils.join(",", dockApps)).apply();
    }

    /** Index of pkg among the dock apps actually shown (installed ones). */
    private int shownIndex(String pkg) {
        for (int i = 0; i < dockTiles.size(); i++) {
            Object t = dockTiles.get(i).getTag();
            if (t instanceof App && ((App) t).pkg.equals(pkg)) return i;
        }
        return -1;
    }

    private void startMove(String pkg) {
        movingPkg = pkg;
        if (moveHint == null) {
            moveHint = text(15, sansMedium(), Color.WHITE);
            moveHint.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
            moveHint.setText("\u25C0  \u25B6  to move   \u00b7   OK when done");
            root.addView(moveHint, lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0,
                    dp(DOCK_BOTTOM + DOCK_TILE_H + 2 * (DOCK_GAP + DOCK_TILE_H * (DOCK_FOCUS - 1f) / 2f) + 14)));
        }
        movingInSheet = sheet.getVisibility() == View.VISIBLE;
        if (movingInSheet) {
            moveHint.setVisibility(View.GONE); // the dock's hint, not this one
            if (sheetHint == null) {
                sheetHint = text(15, sansMedium(), 0xD9FFFFFF);
                sheetHint.setText("\u25C0 \u25B6 \u25B2 \u25BC  to move   \u00b7   OK when done");
                sheet.addView(sheetHint, lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, dp(36)));
            }
            sheetHint.setVisibility(View.VISIBLE);
            int i = sheetIndex(pkg);
            if (i >= 0) sheetTiles.get(i).requestFocus();
            return;
        }
        moveHint.setVisibility(View.VISIBLE);
        int i = shownIndex(pkg);
        if (i >= 0) dockTiles.get(i).requestFocus();
    }

    private int sheetIndex(String pkg) {
        for (int i = 0; i < sheetApps.size(); i++) if (sheetApps.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    /** Moves the app within the apps list (arrows, one place or one row) and saves the order. */
    private boolean moveInSheet(int code) {
        int from = sheetIndex(movingPkg), cols = sheetCols(), to;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) to = from - 1;
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) to = from + 1;
        else if (code == KeyEvent.KEYCODE_DPAD_UP) to = from - cols;
        else to = from + cols;
        if (from < 0 || to < 0 || to >= sheetApps.size()) return true;
        List<String> order = new ArrayList<>();
        for (App a : sheetApps) order.add(a.pkg);
        order.add(to, order.remove(from));
        if (listMode) {
            getPreferences(MODE_PRIVATE).edit().putString("list_order", TextUtils.join(",", order)).apply();
        } else {
            appsFirst = order.toArray(new String[0]);
            getPreferences(MODE_PRIVATE).edit().putString("apps", TextUtils.join(",", appsFirst)).apply();
        }
        buildApps(); // rebuilds the open sheet in the new order
        sheetTiles.get(to).requestFocus();
        return true;
    }

    private boolean moveKey(int code, KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (movingInSheet) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                return moveInSheet(code);
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) endMove();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int from = shownIndex(movingPkg);
            int to = from + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (from < 0 || to < 0 || to >= dockTiles.size()) return true;
            String other = ((App) dockTiles.get(to).getTag()).pkg;
            List<String> list = new ArrayList<>(Arrays.asList(dockApps));
            int a = list.indexOf(movingPkg), b = list.indexOf(other);
            list.set(a, other);
            list.set(b, movingPkg);
            dockApps = list.toArray(new String[0]);
            saveDock();
            buildApps();
            dockTiles.get(to).requestFocus();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            endMove();
        }
        return true;
    }

    private void endMove() {
        movingPkg = null;
        movingInSheet = false;
        if (moveHint != null) moveHint.setVisibility(View.GONE);
        if (sheetHint != null) sheetHint.setVisibility(View.GONE);
    }

    private void removeFromDock(String pkg) {
        int i = shownIndex(pkg);
        List<String> list = new ArrayList<>(Arrays.asList(dockApps));
        list.remove(pkg);
        dockApps = list.toArray(new String[0]);
        saveDock();
        buildApps();
        focusDock(Math.max(0, i - 1));
    }

    private void addToDock(String pkg) {
        if (dockTiles.size() >= DOCK_MAX) {
            Toast.makeText(this, "The dock is full: remove an app from it first", Toast.LENGTH_LONG).show();
            return;
        }
        List<String> list = new ArrayList<>(Arrays.asList(dockApps));
        list.remove(pkg);
        list.add(pkg);
        dockApps = list.toArray(new String[0]);
        saveDock();
        closeSheet(false);
        buildApps();
        focusDock(dockTiles.size() - 1);
    }

    /** A small menu of actions just above (or below) the given card. */
    private void showActions(View anchor, String[] labels, Runnable[] actions) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable panel = new GradientDrawable();
        panel.setCornerRadius(dp(18));
        panel.setColor(0xF21C1D21);
        panel.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(panel);
        int width = dp(220);
        PopupWindow pw = new PopupWindow(list, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        List<TextView> rows = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            Runnable act = actions[i];
            TextView r = text(14, sans(), Color.WHITE);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(16), 0, dp(16), 0);
            r.setFocusable(true);
            r.setClickable(true);
            r.setId(View.generateViewId());
            r.setText(labels[i]);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(Color.TRANSPARENT);
            r.setBackground(bg);
            r.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? 0xF7FFFFFF : Color.TRANSPARENT);
                r.setTextColor(has ? 0xFF111114 : Color.WHITE);
            });
            r.setOnClickListener(v -> {
                pw.setOnDismissListener(() -> popup = null); // the action places focus itself
                pw.dismiss();
                act.run();
            });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(42)));
            rows.add(r);
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int x = at[0] + Math.round(anchor.getWidth() * anchor.getScaleX()) / 2 - width / 2;
        int y = at[1] - list.getMeasuredHeight() - dp(14);
        if (y < dp(16)) y = at[1] + Math.round(anchor.getHeight() * anchor.getScaleY()) + dp(14);
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { popup = null; anchor.requestFocus(); });
        popup = pw;
        pw.showAtLocation(root, Gravity.TOP | Gravity.START, Math.max(dp(16), x), y);
        rows.get(0).requestFocus();
    }

    private void dismissPopup() {
        if (popup != null) popup.dismiss();
        popup = null;
    }

    private static String featureText(String label, boolean on) {
        return label + "  ·  " + (on ? "On" : "Off");
    }

    private void toggleFeature(TextView row, String pkg, String label) {
        if (row.getTag() == Boolean.TRUE) return;
        boolean on = enabled(pkg);
        row.setTag(Boolean.TRUE);
        row.setText(label + "  ·  " + (on ? "Turning off…" : "Turning on…"));
        new Thread(() -> {
            String err = null;
            try {
                // Disabling alone can leave the process running until the next restart.
                String out = LocalAdb.shell(this, on
                        ? "pm disable-user --user 0 " + pkg + " && am force-stop " + pkg
                        : "pm enable " + pkg);
                if (!out.contains("new state")) err = "Couldn't switch it";
            } catch (Exception e) {
                err = "Allow debugging on the TV, then try again";
            }
            String message = err;
            runOnUiThread(() -> {
                row.setTag(null);
                row.setText(message == null ? featureText(label, enabled(pkg)) : label + "  ·  " + message);
            });
        }, "feature").start();
    }

    private boolean present(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean enabled(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Google Sans from the TV's own fonts (Google TV ships it); the system sans where it's missing. */
    private static Typeface sans() {
        loadSans();
        return sans;
    }

    private static Typeface sansMedium() {
        loadSans();
        return sansMedium;
    }

    private static void loadSans() {
        if (sans != null) return;
        try {
            sans = Typeface.createFromFile("/system/fonts/Google_Sans-400-100_0-0_0.ttf");
            sansMedium = Typeface.createFromFile("/system/fonts/Google_Sans-500-100_0-0_0.ttf");
        } catch (RuntimeException e) {
            sans = Typeface.create("sans-serif", Typeface.NORMAL);
            sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        }
    }


    private TextView text(float sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }
}
