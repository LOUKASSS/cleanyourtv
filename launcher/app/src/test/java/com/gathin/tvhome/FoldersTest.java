package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class FoldersTest {
    private static final Set<String> INSTALLED = new HashSet<>(Arrays.asList("a", "b", "c", "d", "e", "h1", "h2"));

    private static Folders.Folder folder(String name, String... apps) {
        return new Folders.Folder(name, Arrays.asList(apps));
    }

    @Test
    public void foldersKeepTheThemesOrderAndTheirAppsTheirs() {
        List<Folders.Folder> out = Folders.assemble(Arrays.asList(folder("Kids", "c", "a"), folder("Music", "e", "b")),
                Collections.emptyList(), false, "Others", INSTALLED);
        assertEquals(2, out.size());
        assertEquals("Kids", out.get(0).name);
        assertEquals(Arrays.asList("c", "a"), out.get(0).apps);
        assertEquals("Music", out.get(1).name);
        assertEquals(Arrays.asList("e", "b"), out.get(1).apps);
    }

    @Test
    public void anAppGoesInTheFirstFolderThatNamesItOnlyOnce() {
        List<Folders.Folder> out = Folders.assemble(Arrays.asList(folder("One", "a", "b", "a"), folder("Two", "b", "c")),
                Collections.emptyList(), false, "Others", INSTALLED);
        assertEquals(Arrays.asList("a", "b"), out.get(0).apps);
        assertEquals(Collections.singletonList("c"), out.get(1).apps);
    }

    @Test
    public void missingAppsAndEmptyFoldersAreLeftOut() {
        List<Folders.Folder> out = Folders.assemble(Arrays.asList(folder("Gone", "x", "y"), folder("Some", "x", "d", " ", null)),
                Collections.emptyList(), false, "Others", INSTALLED);
        assertEquals(1, out.size());
        assertEquals("Some", out.get(0).name);
        assertEquals(Collections.singletonList("d"), out.get(0).apps);
    }

    @Test
    public void aNamelessFolderIsLeftOutAndGivesItsAppsBack() {
        List<Folders.Folder> out = Folders.assemble(Arrays.asList(folder("  ", "a"), folder("Named", "a")),
                Collections.emptyList(), false, "Others", INSTALLED);
        assertEquals(1, out.size());
        assertEquals(Collections.singletonList("a"), out.get(0).apps);
    }

    @Test
    public void hiddenAppsMakeTheLastFolderWhenAskedFor() {
        List<String> hidden = Arrays.asList("h2", "missing", "a", "h1");
        List<Folders.Folder> out = Folders.assemble(Collections.singletonList(folder("Mine", "a")), hidden, true, "Autres", INSTALLED);
        assertEquals(2, out.size());
        assertEquals("Autres", out.get(1).name);
        // "a" is already in a folder of the theme's: the hidden folder does not show it twice.
        assertEquals(Arrays.asList("h2", "h1"), out.get(1).apps);
        // Off, the hidden apps stay hidden.
        assertEquals(1, Folders.assemble(Collections.singletonList(folder("Mine", "a")), hidden, false, "Autres", INSTALLED).size());
        // Nothing hidden that is installed: no empty folder.
        assertTrue(Folders.assemble(Collections.emptyList(), Collections.singletonList("missing"), true, "Autres", INSTALLED).isEmpty());
    }

    @Test
    public void membersAreEveryPackageInAFolder() {
        List<Folders.Folder> out = Folders.assemble(Arrays.asList(folder("One", "a"), folder("Two", "b")),
                Collections.singletonList("h1"), true, "Others", INSTALLED);
        assertEquals(new HashSet<>(Arrays.asList("a", "b", "h1")), Folders.members(out));
    }

    @Test
    public void anOpenFolderHasUpToSixColumns() {
        assertEquals(1, Folders.overlayColumns(0));
        assertEquals(3, Folders.overlayColumns(3));
        assertEquals(6, Folders.overlayColumns(6));
        assertEquals(6, Folders.overlayColumns(17));
    }
}
