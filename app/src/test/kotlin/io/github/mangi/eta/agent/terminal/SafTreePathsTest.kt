package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafTreePathsTest {
    private val primary = "/storage/emulated/0"

    @Test fun treeIdComesFromTheTreeSegment() {
        assertEquals("primary:Download/demo", SafTreePaths.treeDocumentId(listOf("tree", "primary:Download/demo")))
        assertEquals(
            "primary:Download/demo",
            SafTreePaths.treeDocumentId(listOf("tree", "primary:Download/demo", "document", "primary:Download/demo/sub")),
        )
        assertNull(SafTreePaths.treeDocumentId(emptyList()))
        assertNull(SafTreePaths.treeDocumentId(listOf("tree", " ")))
    }

    @Test fun primaryHomeAndRemovableVolumesMapToPaths() {
        assertEquals(
            "/storage/emulated/0/Download/demo",
            SafTreePaths.pathForDocumentId("primary:Download/demo", primary),
        )
        assertEquals("/storage/emulated/0", SafTreePaths.pathForDocumentId("primary:", primary))
        assertEquals("/storage/emulated/0/Documents/notes", SafTreePaths.pathForDocumentId("home:notes", primary))
        assertEquals("/storage/1A2B-3C4D/project", SafTreePaths.pathForDocumentId("1A2B-3C4D:project", primary))
    }

    @Test fun documentIdsCannotEscapeTheSelectedFolder() {
        assertNull(SafTreePaths.pathForDocumentId("primary:../etc", primary))
        assertNull(SafTreePaths.pathForDocumentId("primary:a/../../etc", primary))
        assertNull(SafTreePaths.pathForDocumentId("primary", primary))
        assertNull(SafTreePaths.pathForDocumentId(":demo", primary))
    }

    @Test fun pathsBecomeDocumentIdsForTheInitialPickerLocation() {
        assertEquals("primary:", SafTreePaths.documentIdForPath(primary, primary))
        assertEquals("primary:Download/demo", SafTreePaths.documentIdForPath("$primary/Download/demo", primary))
        assertEquals("1A2B-3C4D:project", SafTreePaths.documentIdForPath("/storage/1A2B-3C4D/project", primary))
        assertNull(SafTreePaths.documentIdForPath("/data/local/tmp", primary))
        assertNull(SafTreePaths.documentIdForPath("/storage/emulated/1/project", primary))
    }

    @Test fun documentIdRoundTripKeepsTheFolder() {
        val path = "$primary/Documents/notes"
        val id = SafTreePaths.documentIdForPath(path, primary)
        assertEquals(path, id?.let { SafTreePaths.pathForDocumentId(it, primary) })
    }
}
