package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The theme's "folders" and folder_hidden_apps, put together the way Home shows them: every folder in the theme's
 * order with its installed apps in the order listed, an app only in the first folder that names it, a folder with
 * nothing installed left out, and the hidden apps in a last folder of their own. Free of Android calls for the JVM
 * tests; AppAdapter builds the tiles from the result.
 */
final class Folders {
    private Folders() {
    }

    /** A folder: its name and its packages, in order. */
    static final class Folder {
        final String name;
        final List<String> apps;

        Folder(String name, List<String> apps) {
            this.name = name == null ? "" : name.trim();
            this.apps = Collections.unmodifiableList(new ArrayList<>(apps));
        }
    }

    /**
     * @param folders     the theme's folders, as written
     * @param hidden      hidden_apps, in order; they make the last folder when hiddenFolder is on
     * @param hiddenName  that folder's name
     * @param installed   the packages Home could show a tile for; anything else is left out
     */
    static List<Folder> assemble(List<Folder> folders, List<String> hidden, boolean hiddenFolder, String hiddenName,
                                 Collection<String> installed) {
        List<Folder> out = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (Folder f : folders) {
            List<String> apps = pick(f.apps, installed, placed);
            if (!apps.isEmpty() && !f.name.isEmpty()) out.add(new Folder(f.name, apps));
            else placed.removeAll(apps);   // a nameless folder gives its apps back
        }
        if (hiddenFolder) {
            List<String> apps = pick(hidden, installed, placed);
            if (!apps.isEmpty()) out.add(new Folder(hiddenName, apps));
        }
        return out;
    }

    private static List<String> pick(List<String> wanted, Collection<String> installed, Set<String> placed) {
        List<String> apps = new ArrayList<>();
        for (String pkg : wanted) {
            if (pkg == null) continue;
            String p = pkg.trim();
            if (!p.isEmpty() && installed.contains(p) && placed.add(p)) apps.add(p);
        }
        return apps;
    }

    /** Every package in these folders. */
    static Set<String> members(List<Folder> folders) {
        Set<String> all = new HashSet<>();
        for (Folder f : folders) all.addAll(f.apps);
        return all;
    }

    /** The folder tile's mini grid: 3 across and 2 down, so at most 6 of its apps. */
    static final int PREVIEW_COLUMNS = 3, PREVIEW_ROWS = 2;

    /** Columns of the open folder: up to 6 as in the grid, fewer for a small folder so its tiles stay centred. */
    static int overlayColumns(int apps) {
        return Math.max(1, Math.min(6, apps));
    }
}
