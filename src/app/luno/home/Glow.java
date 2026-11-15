package app.luno.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * The focused app's colour washed over the lower screen (apps-list home). Drawn into a bitmap
 * 1/SCALE the size of the screen (240x135 at 1080p, ~130 KB) that the GPU stretches; a wash is soft
 * by nature, so the low resolution doesn't show, and nothing is blurred live.
 */
final class Glow {
    static final int SCALE = 8;

    private Glow() { }

    /**
     * Wash: the lower part of the screen takes the focused app's colours (up to three, spread left
     * to right), fading up to nothing a third of the way down. @param sw, sh the screen size (cx, cy
     * are not used by the wash).
     */
    static Bitmap render(Bitmap banner, float cx, float cy, int sw, int sh) {
        int w = sw / SCALE, h = sh / SCALE;
        if (banner == null || w <= 0 || h <= 0) return null;
        int[] cols = palette(banner);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint();
        if (cols.length == 1) {
            p.setColor(cols[0]);
        } else {
            p.setShader(new LinearGradient(0, 0, w, 0, cols, null, Shader.TileMode.CLAMP));
        }
        c.drawRect(0, h / 3f, w, h, p);
        // The fade: keep the colours' alpha only as strong as the vertical ramp allows.
        Paint fade = new Paint();
        fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        fade.setShader(new LinearGradient(0, h / 3f, 0, h,
                new int[]{0x00000000, 0x10000000, 0x2C000000, 0x5A000000},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fade);
        return out;
    }

    /**
     * Up to three distinct colours from the banner, biggest first: coarse colour buckets that are
     * not neutral (white, grey, black) and cover at least 8% of the banner's coloured pixels (so a
     * small logo on a white banner still counts), hues at least 35 degrees apart.
     * Falls back to one colour (accent) when the banner has fewer.
     */
    static int[] palette(Bitmap banner) {
        Bitmap small = Bitmap.createScaledBitmap(banner, 32, 18, true);
        java.util.Map<Integer, int[]> buckets = new java.util.HashMap<>(); // key -> {count, r, g, b}
        int total = 0;
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int px = small.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                int r = Color.red(px), g = Color.green(px), b = Color.blue(px);
                int key = (r >> 5) << 6 | (g >> 5) << 3 | (b >> 5);
                int[] t = buckets.get(key);
                if (t == null) buckets.put(key, t = new int[4]);
                t[0]++; t[1] += r; t[2] += g; t[3] += b;
                total++;
            }
        }
        if (small != banner) small.recycle();
        java.util.List<int[]> list = new java.util.ArrayList<>(buckets.values());
        java.util.Collections.sort(list, (a, b) -> b[0] - a[0]);
        java.util.List<float[]> picked = new java.util.ArrayList<>();
        float[] hsv = new float[3];
        int coloured = 0;
        for (int[] t : list) {
            Color.colorToHSV(Color.rgb(t[1] / t[0], t[2] / t[0], t[3] / t[0]), hsv);
            if (hsv[1] >= 0.2f && hsv[2] >= 0.15f) coloured += t[0];
        }
        for (int[] t : list) {
            if (picked.size() == 3) break;
            Color.colorToHSV(Color.rgb(t[1] / t[0], t[2] / t[0], t[3] / t[0]), hsv);
            if (hsv[1] < 0.2f || hsv[2] < 0.15f) continue; // neutral
            if (t[0] < Math.max(3, coloured * 0.08f)) continue; // a stray fleck
            boolean distinct = true;
            for (float[] q : picked) {
                float d = Math.abs(q[0] - hsv[0]);
                if (Math.min(d, 360 - d) < 35) { distinct = false; break; }
            }
            if (distinct) picked.add(hsv.clone());
        }
        if (picked.size() < 2) return new int[]{accent(banner)};
        int[] out = new int[picked.size()];
        for (int i = 0; i < out.length; i++) {
            float[] q = picked.get(i);
            q[2] = Math.max(q[2], 0.45f); // visible on the dark background
            out[i] = Color.HSVToColor(q);
        }
        return out;
    }

    /**
     * The colour that covers most of the banner: pixels grouped into coarse colour buckets, the
     * biggest bucket averaged. When that is a neutral (white, black or grey), the banner's most
     * vivid colour is used instead. Very dark colours are lifted a little so the wash still shows.
     */
    static int accent(Bitmap banner) {
        Bitmap small = Bitmap.createScaledBitmap(banner, 32, 18, true);
        java.util.Map<Integer, int[]> buckets = new java.util.HashMap<>(); // key -> {count, r, g, b}
        int[] best = null;
        float[] hsv = new float[3], vivid = null;
        float vividScore = 0;
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int px = small.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                int r = Color.red(px), g = Color.green(px), b = Color.blue(px);
                int key = (r >> 5) << 6 | (g >> 5) << 3 | (b >> 5); // 8 levels a channel
                int[] t = buckets.get(key);
                if (t == null) buckets.put(key, t = new int[4]);
                t[0]++; t[1] += r; t[2] += g; t[3] += b;
                if (best == null || t[0] > best[0]) best = t;
                Color.colorToHSV(px, hsv);
                float score = hsv[1] * hsv[1] * hsv[2]; // saturated and bright
                if (hsv[1] > 0.35f && hsv[2] > 0.25f && score > vividScore) {
                    vividScore = score;
                    vivid = hsv.clone();
                }
            }
        }
        if (small != banner) small.recycle();
        if (best == null) return 0xFF3A4A6A;
        int col = Color.rgb(best[1] / best[0], best[2] / best[0], best[3] / best[0]);
        Color.colorToHSV(col, hsv);
        boolean neutral = hsv[1] < 0.2f || hsv[2] < 0.15f; // white, grey or black
        if (neutral && vivid != null) hsv = vivid;
        if (hsv[2] < 0.4f) hsv[2] = 0.4f; // keep a dark banner's hue visible on the dark background
        return Color.HSVToColor(hsv);
    }
}
