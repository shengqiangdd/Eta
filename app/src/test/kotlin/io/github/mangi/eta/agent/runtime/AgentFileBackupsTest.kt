package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentFileBackupsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun backupsPersistAcrossInstancesAndFilterByTarget() {
        val root = temporary.newFolder("backups")
        val store = AgentFileBackups.forDirectory(root, "run-1")!!
        val id = store.save("android", "user", "/tmp/a.txt", "hello".toByteArray(), "edit_file")!!
        assertEquals(1, store.list("android", "user", "/tmp/a.txt").size)
        assertArrayEquals("hello".toByteArray(), store.load(id))
        assertEquals(0, store.list("android", "user", "/tmp/b.txt").size)
        assertEquals(0, store.list("linux", "root", "/tmp/a.txt").size)

        val reopened = AgentFileBackups.forDirectory(root, "run-1")!!
        assertEquals(1, reopened.list("android", "user", "/tmp/a.txt").size)
        assertArrayEquals("hello".toByteArray(), reopened.load(id))
    }

    @Test fun emptyBackupsAreStoredAndLoaded() {
        val root = temporary.newFolder("backups")
        val store = AgentFileBackups.forDirectory(root, "run-1")!!
        val id = store.save("android", "user", "/tmp/empty.txt", ByteArray(0), "write_file")!!
        assertArrayEquals(ByteArray(0), store.load(id))
    }

    @Test fun differentRunsCannotSeeEachOthersBackups() {
        val root = temporary.newFolder("backups")
        val first = AgentFileBackups.forDirectory(root, "run-1")!!
        val id = first.save("android", "user", "/tmp/a.txt", "hello".toByteArray(), "edit_file")!!
        val second = AgentFileBackups.forDirectory(root, "run-2")!!
        assertEquals(0, second.list("android", "user", "/tmp/a.txt").size)
        assertNull(second.load(id))
    }

    @Test fun entriesArePrunedToTheConfiguredLimit() {
        val root = temporary.newFolder("backups")
        val store = AgentFileBackups.forDirectory(root, "run-1")!!
        repeat(45) { index ->
            store.save("android", "user", "/tmp/a.txt", "v$index".toByteArray(), "edit_file")
        }
        assertEquals(40, store.list("android", "user", "/tmp/a.txt", limit = 50).size)
    }

    @Test fun invalidOrMissingIdsAndOversizedBackupsFailSafely() {
        val root = temporary.newFolder("backups")
        val store = AgentFileBackups.forDirectory(root, "run-1")!!
        assertNull(store.load("../escape"))
        assertNull(store.load("00000000-0000-0000-0000-000000000000"))
        assertNull(store.save("android", "user", "/tmp/big", ByteArray(512 * 1024 + 1), "edit_file"))
        assertTrue(store.list("android", "user", "/tmp/big").isEmpty())
    }

    @Test fun onlyRecentRunDirectoriesAreKept() {
        val root = temporary.newFolder("backups")
        repeat(30) { index ->
            AgentFileBackups.forDirectory(root, "run-$index")!!
                .save("android", "user", "/tmp/a.txt", "v".toByteArray(), "edit_file")
        }
        val dirs = root.listFiles { file -> file.isDirectory }?.size ?: 0
        assertTrue("expected <= 24 run dirs, got $dirs", dirs <= 24)
    }
}
