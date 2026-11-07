package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ShelfItemsTest {
    private static ShelfItems.Item wn(String title, int kind, long engaged) {
        ShelfItems.Item i = new ShelfItems.Item();
        i.title = title;
        i.kind = kind;
        i.engaged = engaged;
        return i;
    }

    private static ShelfItems.Item pp(String title, long channel, int weight, boolean browsable, long id) {
        ShelfItems.Item i = new ShelfItems.Item();
        i.title = title;
        i.channel = channel;
        i.weight = weight;
        i.browsable = browsable;
        i.id = id;
        return i;
    }

    private static List<String> titles(List<ShelfItems.Item> items) {
        List<String> out = new ArrayList<>();
        for (ShelfItems.Item i : items) out.add(i.title);
        return out;
    }

    @Test
    public void watchNextContinueFirstThenByKindNewestFirst() {
        List<ShelfItems.Item> rows = Arrays.asList(
                wn("new", ShelfItems.NEW, 900), wn("old continue", ShelfItems.CONTINUE, 100),
                wn("next", ShelfItems.NEXT, 500), wn("recent continue", ShelfItems.CONTINUE, 800),
                wn("watchlist", ShelfItems.WATCHLIST, 999));
        assertEquals(Arrays.asList("recent continue", "old continue", "next", "new", "watchlist"),
                titles(ShelfItems.pick(rows, Collections.emptyList())));
    }

    @Test
    public void atMostFiveAndWatchNextBeatsPreview() {
        List<ShelfItems.Item> rows = new ArrayList<>();
        for (int k = 0; k < 8; k++) rows.add(wn("t" + k, ShelfItems.CONTINUE, k));
        List<ShelfItems.Item> picked = ShelfItems.pick(rows, Collections.singletonList(pp("p", 1, 0, true, 1)));
        assertEquals(Arrays.asList("t7", "t6", "t5", "t4", "t3"), titles(picked));
    }

    @Test
    public void finishedContinueRowsDrop() {
        ShelfItems.Item done = wn("done", ShelfItems.CONTINUE, 5);
        done.duration = 1000;
        done.position = 990;
        ShelfItems.Item half = wn("half", ShelfItems.CONTINUE, 1);
        half.duration = 1000;
        half.position = 500;
        assertEquals(Collections.singletonList("half"), titles(ShelfItems.pick(Arrays.asList(done, half), Collections.emptyList())));
    }

    @Test
    public void previewBrowsableChannelFirstThenWeight() {
        List<ShelfItems.Item> rows = Arrays.asList(pp("hidden", 1, 9, false, 1), pp("light", 2, 1, true, 2),
                pp("heavy", 2, 5, true, 3), pp("other", 3, 9, true, 4));
        assertEquals(Arrays.asList("heavy", "light", "other", "hidden"),
                titles(ShelfItems.pick(Collections.emptyList(), rows)));
    }

    @Test
    public void pictureWiderWinsPosterOnTie() {
        ShelfItems.Item i = new ShelfItems.Item();
        assertNull(ShelfItems.picture(i));
        i.thumbnail = "t";
        assertEquals("t", ShelfItems.picture(i));
        i.poster = "p";
        assertEquals("p", ShelfItems.picture(i));
        i.posterAspect = 4;       // 2:3
        i.thumbnailAspect = 0;    // 16:9
        assertEquals("t", ShelfItems.picture(i));
        i.posterAspect = 0;
        assertEquals("p", ShelfItems.picture(i));
    }

    @Test
    public void subtitleLikeTheMockup() {
        ShelfItems.Item i = wn("Brume", ShelfItems.CONTINUE, 0);
        i.season = "2";
        i.episode = "4";
        i.episodeTitle = "Le lac gelé";
        assertEquals("Saison 2 · Épisode 4 — « Le lac gelé »",
                ShelfItems.subtitle(i, "Saison %1$s · Épisode %2$s", "Épisode %1$s", "« %1$s »"));
        i.season = null;
        assertEquals("Episode 4 — “Le lac gelé”", ShelfItems.subtitle(i, "S%1$s E%2$s", "Episode %1$s", "“%1$s”"));
        i.episode = " ";
        assertEquals("“Le lac gelé”", ShelfItems.subtitle(i, "S%1$s E%2$s", "Episode %1$s", "“%1$s”"));
        i.season = "0";             // Plex's films: season 0, episode -1, the film's name again
        i.episode = "-1";
        i.episodeTitle = "Brume";
        assertEquals("", ShelfItems.subtitle(i, "S%1$s E%2$s", "Episode %1$s", "“%1$s”"));
        i.season = "Special";
        i.episode = "2";
        assertEquals("SSpecial E2",   // not a number: shown as it is
                ShelfItems.subtitle(i, "S%1$s E%2$s", "Episode %1$s", "“%1$s”"));
        i.episode = null;
        i.episodeTitle = "brume";   // only the series name again
        assertEquals("", ShelfItems.subtitle(i, "S%1$s E%2$s", "Episode %1$s", "“%1$s”"));
    }

    @Test
    public void progressAndMinutesLeft() {
        assertEquals(-1, ShelfItems.permille(0, 10));
        assertEquals(-1, ShelfItems.permille(1000, 0));
        assertEquals(620, ShelfItems.permille(60_000, 37_200));
        assertEquals(1000, ShelfItems.permille(1000, 5000));
        assertEquals(23, ShelfItems.minutesLeft(60 * 60_000, 37 * 60_000));
        assertEquals(1, ShelfItems.minutesLeft(60 * 60_000, 60 * 60_000 - 5_000));
        assertEquals(0, ShelfItems.minutesLeft(1000, 5000));
        assertEquals(-1, ShelfItems.minutesLeft(0, 0));
    }

    @Test
    public void titleShrinksAsItGrows() {
        assertEquals(66f, ShelfItems.titleSp("Brume"), 0f);
        assertEquals(52f, ShelfItems.titleSp("The Lord of the Rings"), 0f);
        assertEquals(40f, ShelfItems.titleSp("The Lord of the Rings: The Rings of Power"), 0f);
    }

    @Test
    public void crop16x9() {
        assertArrayEquals(new int[]{0, 0, 1920, 1080}, ShelfItems.crop16x9(1920, 1080));
        assertArrayEquals(new int[]{0, 180, 1920, 1080}, ShelfItems.crop16x9(1920, 1440));   // 4:3, centred
        assertArrayEquals(new int[]{480, 0, 1920, 1080}, ShelfItems.crop16x9(2880, 1080));   // wider than 16:9
        // A 2:3 poster: full width, the band a third of the way down.
        int[] c = ShelfItems.crop16x9(1000, 1500);
        assertArrayEquals(new int[]{0, (1500 - 563) / 3, 1000, 563}, c);
        assertArrayEquals(new int[]{0, 0, 0, 0}, ShelfItems.crop16x9(0, 0));
    }

    @Test
    public void evictOldestUntilUnderTheCap() {
        long[] sizes = {8, 5, 7, 3};
        long[] times = {40, 10, 30, 20};
        assertEquals(Collections.emptyList(), ShelfItems.evict(sizes, times, 23));
        assertEquals(Collections.singletonList(1), ShelfItems.evict(sizes, times, 22));
        assertEquals(Arrays.asList(1, 3, 2), ShelfItems.evict(sizes, times, 8));
        assertEquals(Arrays.asList(1, 3, 2, 0), ShelfItems.evict(sizes, times, 0));
    }

    @Test
    public void cacheNameIsStableAndFlat() {
        String a = ShelfItems.cacheName("https://img.example/a.jpg");
        assertEquals(a, ShelfItems.cacheName("https://img.example/a.jpg"));
        assertTrue(a.matches("[0-9a-f]+\\.jpg"));
        assertTrue(!a.equals(ShelfItems.cacheName("https://img.example/b.jpg")));
    }
}
