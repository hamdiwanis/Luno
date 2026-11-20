package app.luno.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Luno's own updates, from the latest GitHub release, installed with no prompt through the TV's
 * adb (as the Features switches are). Checked when another app comes in front, at most every six
 * hours, so Home restarting with the new version is never on screen. Does nothing unless network
 * debugging already trusts Luno; never asks.
 */
final class SelfUpdate {
    private static final String LATEST = "https://api.github.com/repos/hamdiwanis/Luno/releases/latest";
    private static final long EVERY_MS = 6 * 60 * 60 * 1000L;
    private static volatile boolean running;

    private SelfUpdate() { }

    /** The automatic check: quiet, at most every six hours. */
    static void maybeCheck(Context context) {
        Context ctx = context.getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences("self_update", Context.MODE_PRIVATE);
        if (Math.abs(System.currentTimeMillis() - prefs.getLong("checked", 0)) < EVERY_MS) return;
        start(ctx, status -> { });
    }

    /**
     * Features > Luno: checks now and reports each step as a short status ("Checking…", "Up to
     * date", "Installing 1.4…"), on a background thread. Returns false if a check is already running.
     */
    static boolean checkNow(Context context, Consumer<String> status) {
        return start(context.getApplicationContext(), status);
    }

    /** This Luno's version, as in the release names ("1.3"). */
    static String version(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static synchronized boolean start(Context ctx, Consumer<String> status) {
        if (running) return false;
        running = true;
        ctx.getSharedPreferences("self_update", Context.MODE_PRIVATE).edit()
                .putLong("checked", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                status.accept(check(ctx, status));
            } catch (AdbNotAllowed e) {
                status.accept("Allow debugging on the TV, then try again");
            } catch (Exception e) {
                // Offline, GitHub busy: the automatic check tries again later.
                status.accept("Couldn't check, try again later");
            } finally {
                running = false;
            }
        }, "luno-update").start();
        return true;
    }

    private static final class AdbNotAllowed extends Exception { }

    /** Returns the final status; an update in progress ends this process before it returns. */
    private static String check(Context ctx, Consumer<String> status) throws Exception {
        status.accept("Checking…");
        PackageInfo self = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        JSONObject release = new JSONObject(new String(get(LATEST), StandardCharsets.UTF_8));
        String tag = release.getString("tag_name");
        if (release.optBoolean("draft") || release.optBoolean("prerelease")
                || compare(tag, self.versionName) <= 0) return "Up to date";

        String url = null, name = null;
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.getString("name").endsWith(".apk")) {
                url = a.getString("browser_download_url");
                name = a.getString("name");
                break;
            }
        }
        // Release notes end with "SHA-256 of <apk>: <hex>": the file must match it.
        Matcher m = name == null ? null : Pattern.compile("SHA-256 of " + Pattern.quote(name)
                + ":\\s*([0-9a-fA-F]{64})").matcher(release.optString("body"));
        if (m == null || !m.find()) return "Version " + tag.replaceAll("^[vV]", "") + " has no APK to install";

        String next = tag.replaceAll("^[vV]", "");
        status.accept("Downloading " + next + "…");
        File apk = new File(ctx.getCacheDir(), "update.apk");
        try {
            byte[] data = get(url);
            if (!hex(MessageDigest.getInstance("SHA-256").digest(data)).equals(m.group(1).toLowerCase(Locale.ROOT))) {
                return "Download of " + next + " didn't match, not installed";
            }
            try (OutputStream out = new FileOutputStream(apk)) { out.write(data); }
            PackageInfo info = ctx.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (info == null || !ctx.getPackageName().equals(info.packageName)
                    || info.getLongVersionCode() <= self.getLongVersionCode()) return "Up to date";
            status.accept("Installing " + next + "…");
            // Android checks the signature matches the installed Luno. Replacing Luno ends this
            // process; Home comes back as the new version.
            String out;
            try {
                out = LocalAdb.install(ctx, apk);
            } catch (LocalAdb.NotTrusted e) {
                throw new AdbNotAllowed();
            }
            return out.contains("Success") ? "Updated to " + next : "Couldn't install " + next;
        } finally {
            apk.delete();
        }
    }

    /** Compares "v1.10" with "1.9" and the like, number by number. */
    static int compare(String a, String b) {
        String[] x = a.replaceAll("^[vV]", "").split("\\."), y = b.replaceAll("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String s) {
        Matcher m = Pattern.compile("^\\d+").matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    private static byte[] get(String url) throws Exception {
        for (int hops = 0; hops < 5; hops++) { // release assets redirect to GitHub's file host
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Luno");
            c.setRequestProperty("Accept", url.equals(LATEST) ? "application/vnd.github+json" : "application/octet-stream");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }
}
