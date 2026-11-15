package app.luno.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
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
     * Wash: the lower part of the screen takes the focused app's colours (up to three soft glows
     * along the bottom, blended), fading up to nothing a third of the way down. @param sw, sh the screen size (cx, cy
     * are not used by the wash).
     */
    static Bitmap render(Bitmap banner, float cx, float cy, int sw, int sh) {
        int w = sw / SCALE, h = sh / SCALE;
        if (banner == null || w <= 0 || h <= 0) return null;
        int[] cols = palette(banner);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        // Each colour is a wide soft glow rising from the bottom, spread evenly across the width
        // and overlapping its neighbours, so the colours melt into each other with no seams.
        int n = cols.length;
        for (int i = 0; i < n; i++) {
            float x = n == 1 ? w / 2f : w * (0.5f + i) / n;
            float rx = n == 1 ? w * 0.95f : w * 0.75f / n * 1.9f;
            float[] stops = {0f, 0.35f, 0.7f, 1f};
            int col = cols[i] & 0xFFFFFF;
            int[] ring = {0xFF000000 | col, 0xF0000000 | col, 0xA0000000 | col, col};
            RadialGradient g = new RadialGradient(x, h * 1.05f, rx, ring, stops, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(1f, (h * 0.75f) / rx, x, h * 1.05f); // flatter than wide: a glow along the floor
            g.setLocalMatrix(m);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(g);
            c.drawRect(0, 0, w, h, p);
        }
        blur(out, 3); // soften where the glows meet
        // The fade: as strong as the vertical ramp allows, nothing above a third of the way down.
        Paint fade = new Paint();
        fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        fade.setShader(new LinearGradient(0, h / 3f, 0, h,
                new int[]{0x00000000, 0x10000000, 0x2C000000, 0x5A000000},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fade);
        return out;
    }

    /** Three box-blur passes each way (about a Gaussian); fine on a 240x135 bitmap. */
    private static void blur(Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxPass(px, w, h, r, true);
            boxPass(px, w, h, r, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxPass(int[] px, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, n = 2 * r + 1;
        int[] line = new int[len];
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = px[horizontal ? l * w + i : i * w + l];
            int as = 0, rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int p = line[Math.min(len - 1, Math.max(0, i))];
                as += p >>> 24; rs += (p >> 16) & 0xff; gs += (p >> 8) & 0xff; bs += p & 0xff;
            }
            for (int i = 0; i < len; i++) {
                px[horizontal ? l * w + i : i * w + l] = (as / n) << 24 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
                int add = line[Math.min(len - 1, i + r + 1)], drop = line[Math.max(0, i - r)];
                as += (add >>> 24) - (drop >>> 24);
                rs += ((add >> 16) & 0xff) - ((drop >> 16) & 0xff);
                gs += ((add >> 8) & 0xff) - ((drop >> 8) & 0xff);
                bs += (add & 0xff) - (drop & 0xff);
            }
        }
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
