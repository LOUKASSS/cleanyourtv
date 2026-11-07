package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The cinematic shelf's backdrop: an app's artwork reduced to 48×27 pixels, saturated and smoothed,
 * then stretched over the screen by its ImageView. Bilinear filtering of an image that small is a
 * blur that costs one tiny texture; a real blur would mean RenderScript or a shader pass per frame,
 * which a 1 GB TV cannot spare.
 */
final class HeroArt {
    static final int W = 48, H = 27;
    private static final float SATURATION = 1.7f;

    private HeroArt() {
    }

    /** The softened artwork, about 5 KB; null when there is nothing to draw. */
    static Bitmap from(Drawable live) {
        if (live == null) return null;
        // Draw a private copy: changing the bounds of the drawable a tile shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        Bitmap big = Bitmap.createBitmap(W * 4, H * 4, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(big);
        c.drawColor(Color.BLACK);
        d.setBounds(0, 0, big.getWidth(), big.getHeight());
        d.draw(c);
        d.setBounds(saved);
        // Halve twice: each filtered halving averages 2×2 pixels, where one 4× step would skip most of them.
        Bitmap half = Bitmap.createScaledBitmap(big, W * 2, H * 2, true);
        big.recycle();
        Bitmap small = Bitmap.createScaledBitmap(half, W, H, true);
        half.recycle();

        int[] px = new int[W * H];
        small.getPixels(px, 0, W, 0, 0, W, H);
        saturate(px);
        // Three box passes approximate a gaussian, so the stretched image shows no seams between pixels.
        AppleTv.blur(px, W, H, 2, 3);
        Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (out != small) small.recycle();
        out.setPixels(px, 0, W, 0, 0, W, H);
        return out;
    }

    private static void saturate(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            px[i] = Color.rgb(clamp(lum + (r - lum) * SATURATION), clamp(lum + (g - lum) * SATURATION),
                    clamp(lum + (b - lum) * SATURATION));
        }
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }
}
