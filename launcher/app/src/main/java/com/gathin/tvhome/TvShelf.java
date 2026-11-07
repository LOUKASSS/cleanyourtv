package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * appletv_shelf: reads the Watch Next and preview rows other apps publish (READ_TV_LISTINGS) and loads their posters
 * for the top shelf, all on one background thread. Created only with the switch on, so with it off nothing here runs.
 * See docs/watch-next.md for what the provider returns and its traps.
 */
final class TvShelf {
    static final String TAG = "TvShelf";
    /** The largest backdrop kept, px; the posters are cropped to 16:9 and decoded no larger. */
    private static final int MAX_W = 1280, MAX_H = 720;
    private static final int TIMEOUT_MS = 8000;
    private static final long DISK_MAX = 20L * 1024 * 1024;
    /** A download larger than this is not a poster. */
    private static final int DOWNLOAD_MAX = 12 * 1024 * 1024;
    /** The disk cache holds posters already cropped: a new crop rule bumps this, and the old files age out. */
    private static final String CROP = "c4:";
    /** How bright a softened poster is kept: a little darker, as a scrim, so the text stays the sharp thing. */
    private static final float SOFT_BRIGHTNESS = 0.88f;

    private static final String[] WATCH_NEXT = {
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_TYPE,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
    };
    private static final String[] PREVIEW = {
            TvContract.PreviewPrograms._ID,
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContract.PreviewPrograms.COLUMN_TYPE,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_WEIGHT,
    };
    private static final String[] CHANNELS = {
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_BROWSABLE,
    };

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The last three backdrops, by URI. */
    private final LruCache<String, Bitmap> memory = new LruCache<>(3);
    private final File dir;
    /** The posters still wanted: a load queued for any other is dropped when its turn comes. */
    private volatile String wanted, prefetch;
    private boolean probed;

    TvShelf(Context context) {
        this.context = context.getApplicationContext();
        dir = new File(context.getCacheDir(), "shelf");
    }

    /** Reads every app's rows off the UI thread; done gets them on it, by package (ShelfItems.pick applied). */
    void refresh(Consumer<Map<String, List<ShelfItems.Item>>> done) {
        io.execute(() -> {
            Map<String, List<ShelfItems.Item>> rows = read();
            main.post(() -> done.accept(rows));
        });
    }

    private Map<String, List<ShelfItems.Item>> read() {
        ContentResolver cr = context.getContentResolver();
        Map<String, List<ShelfItems.Item>> watchNext = new HashMap<>(), preview = new HashMap<>();
        // No selection and named columns: a caller without ACCESS_ALL_EPG_DATA may not pass a selection, and a null
        // projection fails on some providers (split_id; docs/watch-next.md).
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT, null, null, null)) {
            while (c != null && c.moveToNext()) {
                ShelfItems.Item i = common(c, 4);
                i.id = c.getLong(0);
                i.pkg = c.getString(1);
                i.kind = c.isNull(2) ? ShelfItems.WATCHLIST : c.getInt(2);
                i.engaged = c.getLong(14);
                i.duration = c.getLong(15);
                i.position = c.getLong(16);
                if (i.pkg != null) watchNext.computeIfAbsent(i.pkg, k -> new ArrayList<>()).add(i);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "watch next not readable: " + e);
        }
        Map<Long, Boolean> browsable = new HashMap<>();
        try (Cursor c = cr.query(TvContract.Channels.CONTENT_URI, CHANNELS, null, null, null)) {
            while (c != null && c.moveToNext()) browsable.put(c.getLong(0), c.getInt(1) != 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "channels not readable: " + e);
        }
        try (Cursor c = cr.query(TvContract.PreviewPrograms.CONTENT_URI, PREVIEW, null, null, null)) {
            while (c != null && c.moveToNext()) {
                ShelfItems.Item i = common(c, 4);
                i.id = c.getLong(0);
                i.pkg = c.getString(1);
                i.channel = c.getLong(2);
                i.duration = c.getLong(14);
                i.position = c.getLong(15);
                i.weight = c.getInt(16);
                i.browsable = Boolean.TRUE.equals(browsable.get(i.channel));
                if (i.pkg != null) preview.computeIfAbsent(i.pkg, k -> new ArrayList<>()).add(i);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "preview programs not readable: " + e);
        }
        if (!probed) {
            probed = true;
            probe(watchNext, preview, browsable);
        }
        Map<String, List<ShelfItems.Item>> out = new HashMap<>();
        java.util.Set<String> pkgs = new java.util.HashSet<>(watchNext.keySet());
        pkgs.addAll(preview.keySet());
        long now = System.currentTimeMillis();
        for (String pkg : pkgs) {
            List<ShelfItems.Item> picked = ShelfItems.pick(watchNext.getOrDefault(pkg, Collections.emptyList()),
                    preview.getOrDefault(pkg, Collections.emptyList()), now);
            if (!picked.isEmpty()) out.put(pkg, picked);
        }
        return out;
    }

    /** The columns both tables share, from `title` on (at index from): the type is read for the probe only. */
    private static ShelfItems.Item common(Cursor c, int from) {
        ShelfItems.Item i = new ShelfItems.Item();
        i.title = c.getString(from);
        i.episodeTitle = c.getString(from + 1);
        i.season = c.getString(from + 2);
        i.episode = c.getString(from + 3);
        i.description = c.getString(from + 4);
        i.poster = c.getString(from + 5);
        i.posterAspect = c.isNull(from + 6) ? -1 : c.getInt(from + 6);
        i.thumbnail = c.getString(from + 7);
        i.thumbnailAspect = c.isNull(from + 8) ? -1 : c.getInt(from + 8);
        i.intentUri = c.getString(from + 9);
        return i;
    }

    /** One summary per app in logcat (tag TvShelf), once per process: what a shelf could show on this TV. */
    private static void probe(Map<String, List<ShelfItems.Item>> watchNext, Map<String, List<ShelfItems.Item>> preview,
                              Map<Long, Boolean> browsable) {
        int channelsOn = 0;
        for (boolean b : browsable.values()) if (b) channelsOn++;
        Log.i(TAG, "probe: " + browsable.size() + " channels (" + channelsOn + " browsable), watch next from "
                + watchNext.size() + " apps, preview programs from " + preview.size() + " apps");
        for (Map.Entry<String, List<ShelfItems.Item>> e : new TreeMap<>(watchNext).entrySet()) {
            Log.i(TAG, "WN " + e.getKey() + ": " + summary(e.getValue(), true));
        }
        for (Map.Entry<String, List<ShelfItems.Item>> e : new TreeMap<>(preview).entrySet()) {
            Log.i(TAG, "PP " + e.getKey() + ": " + summary(e.getValue(), false));
        }
    }

    private static String summary(List<ShelfItems.Item> rows, boolean watchNext) {
        Map<String, Integer> types = new TreeMap<>(), schemes = new TreeMap<>(), aspects = new TreeMap<>();
        int intents = 0, episodes = 0, epTitles = 0, durations = 0, positions = 0, engaged = 0, browsable = 0;
        long newest = 0, oldest = Long.MAX_VALUE;
        java.util.Set<Long> channels = new java.util.HashSet<>();
        for (ShelfItems.Item i : rows) {
            if (watchNext) types.merge(String.valueOf(i.kind), 1, Integer::sum);
            String pic = ShelfItems.picture(i);
            schemes.merge(pic == null ? "none" : scheme(pic), 1, Integer::sum);
            aspects.merge("p" + i.posterAspect + "/t" + i.thumbnailAspect, 1, Integer::sum);
            if (i.intentUri != null && !i.intentUri.isEmpty()) intents++;
            if (i.season != null || i.episode != null) episodes++;
            if (i.episodeTitle != null) epTitles++;
            if (i.duration > 0) durations++;
            if (i.position > 0) positions++;
            if (i.engaged > 0) {
                engaged++;
                newest = Math.max(newest, i.engaged);
                oldest = Math.min(oldest, i.engaged);
            }
            if (i.browsable) browsable++;
            channels.add(i.channel);
        }
        StringBuilder s = new StringBuilder().append(rows.size()).append(" rows");
        if (watchNext) s.append(", types ").append(types);
        else s.append(", ").append(channels.size()).append(" channels, ").append(browsable).append(" in browsable ones");
        s.append(", picture ").append(schemes).append(", aspect poster/thumb ").append(aspects)
                .append(", intent ").append(intents).append(", season/episode ").append(episodes)
                .append(", episode_title ").append(epTitles).append(", duration ").append(durations)
                .append(", position ").append(positions);
        if (watchNext) {
            s.append(", engaged ").append(engaged);
            if (engaged > 0) {
                long now = System.currentTimeMillis();
                s.append(" (newest ").append((now - newest) / 86_400_000L).append(" d, oldest ")
                        .append((now - oldest) / 86_400_000L).append(" d ago)");
            }
        }
        // Two examples, so the report can say what a title and an episode line look like.
        for (int k = 0; k < Math.min(2, rows.size()); k++) {
            ShelfItems.Item i = rows.get(k);
            s.append(" | e.g. \"").append(i.title).append("\" S").append(i.season).append(" E").append(i.episode)
                    .append(" \"").append(i.episodeTitle).append("\" ")
                    .append(ShelfItems.permille(i.duration, i.position)).append("‰");
        }
        return s.toString();
    }

    private static String scheme(String uri) {
        int colon = uri.indexOf(':');
        return colon > 0 ? uri.substring(0, colon) : "?";
    }

    /**
     * The backdrop for a poster URI, cropped to 16:9 and at most 1280x720: at once from memory, else loaded on the
     * background thread (from the disk cache, a content or resource URI, or the network) and handed to done on the UI
     * thread, null when it cannot be had. A load still queued when another poster is asked for is dropped.
     */
    void poster(String uri, Consumer<Bitmap> done) {
        Bitmap hit = memory.get(uri);
        if (hit != null) {
            done.accept(hit);
            return;
        }
        wanted = uri;
        io.execute(() -> {
            if (!uri.equals(wanted)) return;
            Bitmap bmp = load(uri);
            main.post(() -> {
                if (bmp != null) memory.put(uri, bmp);
                if (uri.equals(wanted)) done.accept(bmp);
            });
        });
    }

    /** Warms the disk cache for the next item, so the auto-advance finds it there; never decoded into memory. */
    void prefetch(String uri) {
        if (uri == null || memory.get(uri) != null || !uri.startsWith("https://")) return;
        prefetch = uri;
        io.execute(() -> {
            if (!uri.equals(prefetch)) return;
            if (!cacheFile(PosterUrl.upgrade(uri)).exists() && !cacheFile(uri).exists()) {
                Bitmap bmp = load(uri);
                if (bmp != null) bmp.recycle();
            }
        });
    }

    /**
     * The poster at its CDN's larger size where PosterUrl knows one, falling back once to the URL its app wrote when
     * that fails; a picture still narrower than SHARP_W comes back softened (soften). Each URL is cached on disk under
     * its own name, so a new upgrade rule fetches again; an original found on disk means the larger one failed before,
     * and it is not asked for again.
     */
    private Bitmap load(String uri) {
        String better = PosterUrl.upgrade(uri);
        Bitmap bmp = null;
        if (!better.equals(uri)) {
            bmp = fromDisk(uri);
            if (bmp == null) bmp = loadOne(better);
            if (bmp == null) Log.d(TAG, "larger poster failed, trying the original: " + PosterUrl.pattern(uri));
        }
        if (bmp == null) bmp = loadOne(uri);
        int radius = bmp == null ? 0 : ShelfItems.softRadius(bmp.getWidth());
        return radius == 0 ? bmp : soften(bmp, radius);
    }

    private File cacheFile(String uri) {
        return new File(dir, ShelfItems.cacheName(CROP + uri));
    }

    /** A poster already fitted and cached on disk, or null. */
    private Bitmap fromDisk(String uri) {
        File file = cacheFile(uri);
        if (!file.exists()) return null;
        Bitmap cached = BitmapFactory.decodeFile(file.getPath(), argb());
        if (cached != null) {
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis());
            Log.d(TAG, "poster from disk " + cached.getWidth() + "x" + cached.getHeight() + " " + PosterUrl.pattern(uri));
        }
        return cached;
    }

    private Bitmap loadOne(String uri) {
        try {
            if (uri.startsWith("https://")) return fromNetwork(uri);
            if (uri.startsWith("content://") || uri.startsWith("android.resource://")) {
                Uri u = Uri.parse(uri);
                ContentResolver cr = context.getContentResolver();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = cr.openInputStream(u)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                prepare(o, uri);
                try (InputStream in = cr.openInputStream(u)) {
                    return fit(BitmapFactory.decodeStream(in, null, o));
                }
            }
            // http:// stays out: plaintext, and Android 9+ refuses it anyway.
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "poster not loaded (" + scheme(uri) + "): " + e);
        }
        return null;
    }

    /** From the disk cache, else downloaded once, fitted and written back as a JPEG. */
    private Bitmap fromNetwork(String uri) throws IOException {
        Bitmap cached = fromDisk(uri);
        if (cached != null) return cached;
        File file = cacheFile(uri);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File part = new File(dir, file.getName() + ".part");
        HttpURLConnection http = (HttpURLConnection) new URL(uri).openConnection();
        try {
            http.setConnectTimeout(TIMEOUT_MS);
            http.setReadTimeout(TIMEOUT_MS);
            http.setInstanceFollowRedirects(true);
            if (http.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "poster http " + http.getResponseCode() + " " + PosterUrl.pattern(uri));
                return null;
            }
            long total = 0;
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > DOWNLOAD_MAX) throw new IOException("poster larger than " + DOWNLOAD_MAX);
                    out.write(buf, 0, n);
                }
            }
        } finally {
            http.disconnect();
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(part.getPath(), o);
            prepare(o, uri);
            Bitmap bmp = fit(BitmapFactory.decodeFile(part.getPath(), o));
            if (bmp != null) {
                // High quality: this copy is what the backdrop shows from now on.
                try (OutputStream out = new FileOutputStream(file)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
                }
                trimDisk();
            } else {
                Log.w(TAG, "poster not decodable " + PosterUrl.pattern(uri));
            }
            return bmp;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    private static BitmapFactory.Options argb() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return o;
    }

    /**
     * After a bounds-only decode: a power-of-two sample that keeps the 16:9 crop at least 1280x720, in 32 bits (16 bits
     * bands the dark gradients of a film's poster). Logs the source's size, which is how the per-app sizes are known.
     */
    private static void prepare(BitmapFactory.Options o, String uri) {
        int[] crop = ShelfItems.crop16x9(o.outWidth, o.outHeight);
        int sample = 1;
        while (crop[2] / (sample * 2) >= MAX_W && crop[3] / (sample * 2) >= MAX_H) sample *= 2;
        Log.d(TAG, "poster " + o.outWidth + "x" + o.outHeight + " " + o.outMimeType + " sample " + sample + " "
                + PosterUrl.pattern(uri));
        o.inSampleSize = sample;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
    }

    /** Crops the decoded picture to 16:9 and scales it down, filtered, to 1280x720 at most, in one copy. */
    private static Bitmap fit(Bitmap src) {
        if (src == null) return null;
        int[] crop = ShelfItems.crop16x9(src.getWidth(), src.getHeight());
        if (crop[2] <= 0 || crop[3] <= 0) return null;
        float scale = Math.min(1f, Math.min((float) MAX_W / crop[2], (float) MAX_H / crop[3]));
        if (scale >= 1f && crop[2] == src.getWidth() && crop[3] == src.getHeight()) return src;
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        Bitmap out = Bitmap.createBitmap(src, crop[0], crop[1], crop[2], crop[3], m, true);
        if (out != src) src.recycle();
        return out;
    }

    /**
     * A picture too small to fill the screen sharp (ShelfItems.SHARP_W): blurred a little at its own size (two box
     * passes of radius, ShelfItems.softRadius) and darkened, so the backdrop stretches a soft picture instead of its
     * JPEG blocks. In place, once, on this thread.
     */
    private static Bitmap soften(Bitmap bmp, int radius) {
        Bitmap out = bmp.isMutable() ? bmp : bmp.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return bmp;
        if (out != bmp) bmp.recycle();
        int w = out.getWidth(), h = out.getHeight();
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        AppleTv.blur(px, w, h, radius, 2);
        AppleTv.darken(px, SOFT_BRIGHTNESS);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /** Keeps the disk cache under 20 MB, the least recently used posters going first. */
    private void trimDisk() {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (files == null) return;
        long[] sizes = new long[files.length], times = new long[files.length];
        for (int k = 0; k < files.length; k++) {
            sizes[k] = files[k].length();
            times[k] = files[k].lastModified();
        }
        //noinspection ResultOfMethodCallIgnored
        for (int k : ShelfItems.evict(sizes, times, DISK_MAX)) files[k].delete();
    }

    /** Memory is short: drop the decoded backdrops (the disk cache stays). */
    void trim() {
        memory.evictAll();
    }

    void close() {
        wanted = prefetch = null;
        io.shutdownNow();
    }
}
