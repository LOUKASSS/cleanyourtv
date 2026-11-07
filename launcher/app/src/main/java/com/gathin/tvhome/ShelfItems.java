package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * appletv_shelf's pure part: which Watch Next or preview rows of an app the top shelf shows, in what order, and the
 * text under the title. No Android calls, so the JVM tests can run it; TvShelf reads the rows and loads the posters.
 */
final class ShelfItems {
    private ShelfItems() {
    }

    /** TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_*; PREVIEW marks a preview channel's program. */
    static final int CONTINUE = 0, NEXT = 1, NEW = 2, WATCHLIST = 3, PREVIEW = -1;
    /** The most items one app gets on the shelf, its dots. */
    static final int MAX = 5;
    /** A "continue" row this far through (per mille) is finished, however long it lingers in the table. */
    static final int DONE_PERMILLE = 970;
    /** TvContract's ASPECT_RATIO_* values, widest first: 16:9, 3:2, 4:3, 1:1, 2:3, movie poster (about 1:1.441). */
    private static final int[] WIDEST_FIRST = {0, 1, 2, 3, 4, 5};

    /** One row, as TvShelf reads it. Strings may be null; numbers are 0 (or -1 for an aspect) when absent. */
    static final class Item {
        String pkg;
        int kind = PREVIEW;
        String title, episodeTitle, season, episode, description;
        String poster, thumbnail, intentUri;
        int posterAspect = -1, thumbnailAspect = -1;
        long id, channel, engaged, duration, position;
        int weight;
        /** A preview program whose channel the system shows on Home. */
        boolean browsable;
    }

    /**
     * The shelf for one app: its Watch Next rows, continue first, then next, new and watchlist, each most recently
     * engaged first, with finished "continue" rows dropped; or, with none, its preview programs, browsable channels
     * first, a channel's programs by weight. At most MAX.
     */
    static List<Item> pick(List<Item> watchNext, List<Item> preview) {
        List<Item> out = new ArrayList<>();
        for (Item i : watchNext) if (!(i.kind == CONTINUE && permille(i.duration, i.position) >= DONE_PERMILLE)) out.add(i);
        if (out.isEmpty()) {
            out.addAll(preview);
            Collections.sort(out, Comparator.<Item>comparingInt(i -> i.browsable ? 0 : 1)
                    .thenComparingLong(i -> i.channel)
                    .thenComparing(Comparator.<Item>comparingInt(i -> i.weight).reversed())
                    .thenComparingLong(i -> i.id));
        } else {
            Collections.sort(out, Comparator.<Item>comparingInt(i -> rank(i.kind))
                    .thenComparing(Comparator.<Item>comparingLong(i -> i.engaged).reversed()));
        }
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    private static int rank(int kind) {
        return kind >= CONTINUE && kind <= WATCHLIST ? kind : WATCHLIST + 1;
    }

    /**
     * The picture for the backdrop: of the poster and the thumbnail, the wider (16:9 first), the poster when they
     * tie or neither says; null when there is neither.
     */
    static String picture(Item i) {
        if (i.poster == null || i.poster.isEmpty()) return empty(i.thumbnail) ? null : i.thumbnail;
        if (empty(i.thumbnail)) return i.poster;
        return wideness(i.thumbnailAspect) < wideness(i.posterAspect) ? i.thumbnail : i.poster;
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    /** 0 for 16:9 up to 5 for a movie poster; an unknown aspect sits between 4:3 and 1:1. */
    private static int wideness(int aspect) {
        for (int k = 0; k < WIDEST_FIRST.length; k++) if (WIDEST_FIRST[k] == aspect) return k * 2;
        return 5;
    }

    /** How far through, per mille (0 to 1000), or -1 when the duration or the position is missing. */
    static int permille(long duration, long position) {
        if (duration <= 0 || position <= 0) return -1;
        return (int) Math.min(1000, position * 1000 / duration);
    }

    /** Whole minutes left, rounded up so the last seconds still read "1 min"; -1 without both numbers. */
    static long minutesLeft(long duration, long position) {
        if (permille(duration, position) < 0) return -1;
        long ms = Math.max(0, duration - position);
        return (ms + 59_999) / 60_000;
    }

    /**
     * "Saison 2 · Épisode 4 — « Le lac gelé »" from the formats in strings.xml: seasonEpisode takes the season and
     * the episode (%1$s, %2$s), episodeOnly the episode, quoted the episode's title. Parts that are missing drop out;
     * "" when nothing is left. A season or episode that is not a number is shown as it is; 0 or below is a
     * placeholder (Plex writes season 0, episode -1 on its films) and drops out.
     */
    static String subtitle(Item i, String seasonEpisode, String episodeOnly, String quoted) {
        String se = "", season = number(i.season), episode = number(i.episode);
        if (season != null && episode != null) se = String.format(Locale.ROOT, seasonEpisode, season, episode);
        else if (episode != null) se = String.format(Locale.ROOT, episodeOnly, episode);
        String title = trim(i.episodeTitle);
        // An episode title that only repeats the series' name says nothing.
        if (empty(title) || title.equalsIgnoreCase(trim(i.title))) return se;
        String q = String.format(Locale.ROOT, quoted, title);
        return se.isEmpty() ? q : se + " — " + q;
    }

    /** A season or episode number to show, or null when it is missing or 0 and below. */
    private static String number(String s) {
        s = trim(s);
        if (empty(s)) return null;
        try {
            return Integer.parseInt(s) > 0 ? s : null;
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** Text size of the title, sp: the shelf's 66 for a short title, smaller as it grows; at 40 it takes two lines. */
    static float titleSp(String title) {
        int n = title == null ? 0 : title.codePointCount(0, title.length());
        return n <= 14 ? 66f : n <= 22 ? 52f : 40f;
    }

    /**
     * {left, top, width, height} of the 16:9 part of a srcW x srcH picture the backdrop shows: centred, as a 4:3 video
     * thumbnail keeps its 16:9 frame between black bars; on a portrait poster a third of the way down, where it keeps
     * its faces and its title.
     */
    static int[] crop16x9(int srcW, int srcH) {
        if (srcW <= 0 || srcH <= 0) return new int[]{0, 0, Math.max(0, srcW), Math.max(0, srcH)};
        if ((long) srcW * 9 >= (long) srcH * 16) {
            int w = (int) Math.round(srcH * 16.0 / 9);
            return new int[]{(srcW - w) / 2, 0, w, srcH};
        }
        int h = (int) Math.round(srcW * 9.0 / 16);
        return new int[]{0, (srcH - h) / (srcH > srcW ? 3 : 2), srcW, h};
    }

    /** Which files to delete, oldest first, to bring a cache of these sizes and times down to max bytes. */
    static List<Integer> evict(long[] sizes, long[] modified, long max) {
        long total = 0;
        Integer[] order = new Integer[sizes.length];
        for (int k = 0; k < sizes.length; k++) {
            total += sizes[k];
            order[k] = k;
        }
        java.util.Arrays.sort(order, Comparator.comparingLong(k -> modified[k]));
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < order.length && total > max; k++) {
            out.add(order[k]);
            total -= sizes[order[k]];
        }
        return out;
    }

    /** A file name for a poster's URI in the disk cache: a 64-bit FNV-1a hash in hex, never a path. */
    static String cacheName(String uri) {
        long h = 0xcbf29ce484222325L;
        for (int k = 0; k < uri.length(); k++) {
            h ^= uri.charAt(k);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h) + ".jpg";
    }
}
