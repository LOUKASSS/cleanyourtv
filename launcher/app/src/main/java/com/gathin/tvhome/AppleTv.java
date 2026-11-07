package com.gathin.tvhome;

/**
 * appletv_home's layout and the pure maths behind it, kept free of Android calls so the JVM tests can run it.
 * Sizes are dp on the usual 960x540 dp TV screen (the mockup's 1920x1080 pixels halved).
 */
final class AppleTv {
    private AppleTv() {
    }

    /** A 5:3 tile, the gap between tiles and the left edge of the first column. */
    static final int TILE_W = 125, TILE_H = 75, GAP = 20, LEFT = 55;
    /** Top of the dock's tiles, of the shelf's text and of its dots, and where the top row sits in the grid. */
    static final int DOCK_TOP = 400, SHELF_TOP = 165, DOTS_TOP = 345, GRID_TOP = 70;
    /** Item padding above and below a tile, the gap to its name and the name's height: a grid row is 115dp. */
    static final int ITEM_PAD = 4, NAME_GAP = 8, NAME_H = 24;
    /** Extra room under the dock row, so the next row peeks at the bottom edge (top at about 523dp). */
    static final int DOCK_BOTTOM = 8;
    /** Room around the focus shadow (GlassView's shadow mode): its blur and its 17dp drop. */
    static final int SHADOW_PAD = 48;
    static final float FOCUS_SCALE = 1.14f, PEEK_ALPHA = 0.55f;
    /** The blurred backdrop's size and how bright it is kept. */
    static final int BLUR_W = 320, BLUR_H = 180;
    static final float BLUR_BRIGHTNESS = 0.42f;

    /** Grid padding left and right: the tile is centred in its cell, so the cell starts half a gap before LEFT. */
    static int sidePadding() {
        return LEFT - GAP / 2;
    }

    /**
     * {left, top, width, height} of the part of a srcW x srcH picture that fills a dstW x dstH box with no bars,
     * centred: the same crop ImageView's centerCrop makes, for the blurred copy of the wallpaper.
     */
    static int[] centerCrop(int srcW, int srcH, int dstW, int dstH) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return new int[]{0, 0, Math.max(0, srcW), Math.max(0, srcH)};
        if ((long) srcW * dstH > (long) srcH * dstW) {
            int w = (int) Math.round((double) srcH * dstW / dstH);
            return new int[]{(srcW - w) / 2, 0, w, srcH};
        }
        int h = (int) Math.round((double) srcW * dstH / dstW);
        return new int[]{0, (srcH - h) / 2, srcW, h};
    }

    /** The avatar's letter: the first letter or digit of the name, in capitals; "" when there is none. */
    static String initial(String name) {
        if (name == null) return "";
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(cp)).toUpperCase(java.util.Locale.ROOT);
            i += Character.charCount(cp);
        }
        return "";
    }

    /**
     * Where a focused grid row's tiles should sit (their top, px from the grid's top) given where they are now:
     * the dock row up at gridTop at least (fromDock is the distance from the dock's top down to this row), the row
     * itself no higher than gridTop and its zoomed tile and name (extent) above bottomLimit. A row that already fits
     * stays put, so moving between rows in view does not scroll.
     */
    static int rowTop(int current, int fromDock, int gridTop, int bottomLimit, int extent) {
        int highest = Math.min(gridTop + fromDock, bottomLimit - extent);
        return Math.max(gridTop, Math.min(current, highest));
    }

    /**
     * Box blur of opaque ARGB pixels in place, along rows then columns, edges clamped; passes of it approach a
     * gaussian. The cinematic shelf's HeroArt softens its artwork with it too.
     */
    static void blur(int[] px, int w, int h, int radius, int passes) {
        int[] tmp = new int[px.length];
        for (int p = 0; p < passes; p++) {
            boxPass(px, tmp, h, w, radius, 1, w);   // rows
            boxPass(tmp, px, w, h, radius, w, 1);   // columns
        }
    }

    /**
     * One sliding-window pass over `lines` lines of `length` pixels each, a pixel `step` apart within a line and a
     * line `stride` after the one before; dst keeps src's layout.
     */
    private static void boxPass(int[] src, int[] dst, int lines, int length, int radius, int step, int stride) {
        int span = 2 * radius + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * stride;
            int r = 0, g = 0, b = 0;
            for (int k = -radius; k <= radius; k++) {
                int p = src[base + clamp(k, length) * step];
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int i = 0; i < length; i++) {
                dst[base + i * step] = 0xFF000000 | (r / span) << 16 | (g / span) << 8 | b / span;
                int out = src[base + clamp(i - radius, length) * step];
                int in = src[base + clamp(i + radius + 1, length) * step];
                r += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                g += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                b += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int clamp(int i, int length) {
        return i < 0 ? 0 : i >= length ? length - 1 : i;
    }

    /** Multiplies every channel by brightness (0 to 1), keeping the pixels opaque. */
    static void darken(int[] px, float brightness) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            px[i] = 0xFF000000 | Math.round(((p >> 16) & 0xFF) * brightness) << 16
                    | Math.round(((p >> 8) & 0xFF) * brightness) << 8 | Math.round((p & 0xFF) * brightness);
        }
    }
}
