package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AppleTvTest {
    @Test
    public void sixColumnsFillTheScreenFromTheLeftEdge() {
        int cell = AppleTv.TILE_W + AppleTv.GAP;
        // The first tile starts at LEFT and six cells plus both paddings are the 960dp screen.
        assertEquals(AppleTv.LEFT, AppleTv.sidePadding() + (cell - AppleTv.TILE_W) / 2);
        assertEquals(960, 2 * AppleTv.sidePadding() + 6 * cell);
    }

    @Test
    public void aGridRowIs115dpAndTheNextRowPeeksAt523() {
        int item = AppleTv.ITEM_PAD * 2 + AppleTv.TILE_H + AppleTv.NAME_GAP + AppleTv.NAME_H;
        assertEquals(115, item);
        assertEquals(523, AppleTv.DOCK_TOP + item + AppleTv.DOCK_BOTTOM);
    }

    @Test
    public void gridRowsFadeBeforeReachingTheStatusCapsule() {
        // The top row at GRID_TOP, zoomed (its top 5dp higher), stays whole.
        assertEquals(1f, AppleTv.statusFade(AppleTv.GRID_TOP), 0f);
        assertEquals(1f, AppleTv.statusFade(AppleTv.GRID_TOP - 4), 0f);
        assertEquals(0.5f, AppleTv.statusFade(AppleTv.GRID_TOP - 4 - AppleTv.STATUS_FADE / 2f), 0.001f);
        // Gone by the time the tile would reach the capsule's bottom edge.
        assertEquals(0f, AppleTv.statusFade(AppleTv.GRID_TOP - 4 - AppleTv.STATUS_FADE), 0f);
        assertEquals(0f, AppleTv.statusFade(-40), 0f);
        assertEquals(true, AppleTv.GRID_TOP - 4 - AppleTv.STATUS_FADE <= AppleTv.STATUS_BOTTOM);
    }

    @Test
    public void centerCropCutsTheSidesOfAWidePicture() {
        // A 16:9 banner in a 5:3 tile: 1600x900 keeps a 1500x900 window in the middle.
        assertArrayEquals(new int[]{50, 0, 1500, 900}, AppleTv.centerCrop(1600, 900, 125, 75));
    }

    @Test
    public void centerCropCutsTopAndBottomOfATallPicture() {
        // A 4:3 photo on a 16:9 screen: 1600x1200 keeps 1600x900, 150 off the top and the bottom.
        assertArrayEquals(new int[]{0, 150, 1600, 900}, AppleTv.centerCrop(1600, 1200, 320, 180));
    }

    @Test
    public void centerCropKeepsAPictureOfTheSameShape() {
        assertArrayEquals(new int[]{0, 0, 1920, 1080}, AppleTv.centerCrop(1920, 1080, 320, 180));
    }

    @Test
    public void centerCropOfNothingIsNothing() {
        assertArrayEquals(new int[]{0, 0, 0, 0}, AppleTv.centerCrop(0, 0, 320, 180));
    }

    @Test
    public void initialIsTheFirstLetterInCapitals() {
        assertEquals("L", AppleTv.initial("lucas"));
        assertEquals("\u00C9", AppleTv.initial("  \u00E9lodie"));
        assertEquals("A", AppleTv.initial("@anna"));
        assertEquals("7", AppleTv.initial("7even"));
    }

    @Test
    public void initialKeepsALetterOutsideTheBasicPlane() {
        assertEquals("𝐀", AppleTv.initial("𝐀bc"));   // MATHEMATICAL BOLD CAPITAL A
    }

    @Test
    public void noNameHasNoInitial() {
        assertEquals("", AppleTv.initial(null));
        assertEquals("", AppleTv.initial(""));
        assertEquals("", AppleTv.initial(" - "));
    }

    @Test
    public void downFromTheDockLiftsTheGridUntilTheDockRowIsAtTheTop() {
        // Row 2 peeks at 523, 123 below the dock: the grid moves 330 up, the dock row to 70, row 2 to 193.
        assertEquals(193, AppleTv.rowTop(523, 123, 70, 530, 116));
    }

    @Test
    public void aRowAlreadyInViewDoesNotScroll() {
        assertEquals(308, AppleTv.rowTop(308, 238, 70, 530, 116));
        assertEquals(184, AppleTv.rowTop(184, 238, 70, 530, 116));
    }

    @Test
    public void aRowBelowTheScreenComesUpToTheBottomLimit() {
        assertEquals(414, AppleTv.rowTop(538, 468, 70, 530, 116));
    }

    @Test
    public void aRowAboveTheTopComesDownToIt() {
        assertEquals(70, AppleTv.rowTop(-45, 123, 70, 530, 116));
    }

    @Test
    public void blurOfAFlatPictureIsTheSamePicture() {
        int[] px = new int[8 * 4];
        java.util.Arrays.fill(px, 0xFF336699);
        AppleTv.blur(px, 8, 4, 2, 3);
        for (int p : px) assertEquals(0xFF336699, p);
    }

    @Test
    public void blurSpreadsABrightPixelEvenlyAndKeepsItsLight() {
        int w = 9, h = 9;
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xFF000000);
        px[4 * w + 4] = 0xFFFFFFFF;
        AppleTv.blur(px, w, h, 1, 1);
        // A 3x3 box: the centre and its eight neighbours each get a ninth of 255 (28), the rest stays black.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int expected = Math.abs(x - 4) <= 1 && Math.abs(y - 4) <= 1 ? 28 : 0;
                assertEquals("pixel " + x + "," + y, expected, px[y * w + x] & 0xFF);
                assertEquals(0xFF, px[y * w + x] >>> 24);
            }
        }
    }

    @Test
    public void blurClampsAtTheEdges() {
        // One row: white at the left end. With the edge clamped, the end pixel averages two whites and a black.
        int[] px = {0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFF000000};
        AppleTv.blur(px, 4, 1, 1, 1);
        assertEquals(170, px[0] & 0xFF);
        assertEquals(85, px[1] & 0xFF);
        assertEquals(0, px[2] & 0xFF);
    }

    @Test
    public void darkenScalesEveryChannelAndStaysOpaque() {
        int[] px = {0xFFC86432};
        AppleTv.darken(px, 0.5f);
        assertEquals(0xFF643219, px[0]);
    }
}
