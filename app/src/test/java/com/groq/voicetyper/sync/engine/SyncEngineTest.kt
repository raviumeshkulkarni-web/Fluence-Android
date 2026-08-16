package com.groq.voicetyper.sync.engine

import com.groq.voicetyper.sync.wire.ParseResult
import com.groq.voicetyper.sync.wire.RecordType
import com.groq.voicetyper.sync.wire.WireRecord
import com.groq.voicetyper.sync.wire.parse
import com.groq.voicetyper.sync.wire.tombstone
import com.groq.voicetyper.sync.wire.uuidBasename
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    companion object {
        const val ACCOUNT = "account-a"
        const val UUID_A = "00000000-0000-4000-8000-000000000001"
        const val UUID_B = "00000000-0000-4000-8000-0000000000bb"
        const val UUID_C = "00000000-0000-4000-8000-0000000000cc"
        const val UUID_D = "00000000-0000-4000-8000-0000000000dd"
        const val UUID_E = "00000000-0000-4000-8000-0000000000ee"
        const val CREATED_AT = 1713456000123L
        const val DELETED_AT = 1713462000456L
        const val TEXT = "Meeting notes: rename the module before the demo."

        private fun fixture(name: String): ByteArray =
            checkNotNull(SyncEngineTest::class.java.getResourceAsStream("/sync/$name.json")) {
                "missing fixture $name"
            }.readBytes()

        fun wireA(): WireRecord = when (
            val r = parse(fixture("00000000-0000-4000-8000-000000000001"), UUID_A)
        ) {
            is ParseResult.Ok -> r.record
            is ParseResult.Err -> error("fixture must parse")
        }

        fun liveRow(uuid: String): LocalRow = LocalRow(
            uuid = uuid,
            timestampMs = CREATED_AT,
            text = TEXT,
            mode = "transcription",
            durationMs = 8400,
            provider = "groq",
            model = "whisper-large-v3",
            language = "en",
            deletedAt = null,
            serverFileId = null,
            syncAccount = null,
            syncState = SYNC_STATE_LOCAL,
            quarantineReason = null,
        )

        fun liveRowClean(uuid: String, sfi: String): LocalRow =
            liveRow(uuid).copy(serverFileId = sfi, syncState = SYNC_STATE_CLEAN)

        fun tombstoneRow(uuid: String): LocalRow =
            liveRow(uuid).copy(deletedAt = DELETED_AT, syncState = SYNC_STATE_DIRTY)

        fun tombstoneRowClean(uuid: String, sfi: String): LocalRow =
            tombstoneRow(uuid).copy(serverFileId = sfi, syncState = SYNC_STATE_CLEAN)

        const val SNIPPET_CREATED_AT = 1713468000123L

        fun snippetWire(id: String, trigger: String, expansion: String, deletedAt: Long? = null): WireRecord =
            WireRecord(
                v = 1,
                id = id,
                createdAt = SNIPPET_CREATED_AT,
                deletedAt = deletedAt,
                rtype = RecordType.Snippet,
                trigger = trigger,
                expansion = expansion,
            )

        fun snippetRow(uuid: String, trigger: String, expansion: String): LocalRow = LocalRow(
            uuid = uuid,
            timestampMs = SNIPPET_CREATED_AT,
            text = "",
            mode = "",
            durationMs = 0,
            provider = "",
            model = null,
            language = null,
            deletedAt = null,
            serverFileId = null,
            syncAccount = null,
            syncState = SYNC_STATE_LOCAL,
            quarantineReason = null,
            rtype = RecordType.Snippet,
            trigger = trigger,
            expansion = expansion,
        )

        fun snippetRowClean(uuid: String, trigger: String, expansion: String, sfi: String): LocalRow =
            snippetRow(uuid, trigger, expansion).copy(serverFileId = sfi, syncState = SYNC_STATE_CLEAN)

        const val SETTINGS_CREATED_AT = 1713471000123L

        fun settingsWire(id: String, key: String, value: String): WireRecord = WireRecord(
            v = 1,
            id = id,
            createdAt = SETTINGS_CREATED_AT,
            deletedAt = null,
            rtype = RecordType.Settings,
            settingsKey = key,
            settingsValue = value,
        )

        fun settingsRow(uuid: String, key: String, value: String): LocalRow = LocalRow(
            uuid = uuid,
            timestampMs = SETTINGS_CREATED_AT,
            text = "",
            mode = "",
            durationMs = 0,
            provider = "",
            model = null,
            language = null,
            deletedAt = null,
            serverFileId = null,
            syncAccount = null,
            syncState = SYNC_STATE_LOCAL,
            quarantineReason = null,
            rtype = RecordType.Settings,
            settingsKey = key,
            settingsValue = value,
        )

        fun settingsRowClean(uuid: String, key: String, value: String, sfi: String): LocalRow =
            settingsRow(uuid, key, value).copy(serverFileId = sfi, syncState = SYNC_STATE_CLEAN)
    }

    private fun LocalRow.withSfi(sfi: String): LocalRow = copy(serverFileId = sfi)

    private class FakeLocalStore(
        val rows: MutableList<LocalRow> = mutableListOf(),
        val ops: MutableList<String> = mutableListOf(),
    ) : LocalStore {
        fun row(uuid: String): LocalRow? = rows.firstOrNull { it.uuid == uuid }

        override fun listRows(account: String?): List<LocalRow> = rows
            .filter { r ->
                when {
                    account == null -> r.syncAccount == null
                    else -> r.syncAccount?.let { it == account } ?: true
                }
            }
            .sortedBy { it.uuid }

        override fun findRow(uuid: String): LocalRow? = row(uuid)

        override fun import(row: LocalRow) {
            ops.add("import:${row.uuid}")
            val existing = rows.firstOrNull { it.uuid == row.uuid }
            if (existing != null) {
                rows[rows.indexOf(existing)] = row
            } else {
                rows.add(row)
            }
        }

        override fun markTombstoned(uuid: String, deletedAt: Long) {
            ops.add("tombstone:$uuid")
            rows.firstOrNull { it.uuid == uuid }?.let { existing ->
                rows[rows.indexOf(existing)] = existing.copy(
                    deletedAt = deletedAt,
                    syncState = SYNC_STATE_DIRTY
                )
            }
        }

        override fun setServerFileId(uuid: String, fileId: String) {
            ops.add("sfi:$uuid:$fileId")
            rows.firstOrNull { it.uuid == uuid }?.let { existing ->
                rows[rows.indexOf(existing)] = existing.copy(serverFileId = fileId)
            }
        }

        override fun setSyncState(uuid: String, state: String) {
            ops.add("state:$uuid:$state")
            rows.firstOrNull { it.uuid == uuid }?.let { existing ->
                rows[rows.indexOf(existing)] = existing.copy(syncState = state)
            }
        }

        override fun quarantine(uuid: String, reason: QuarantineReason) {
            ops.add("quarantine:$uuid:${reason.asStr}")
            rows.firstOrNull { it.uuid == uuid }?.let { existing ->
                rows[rows.indexOf(existing)] = existing.copy(
                    quarantineReason = reason.asStr,
                    syncState = SYNC_STATE_QUARANTINED
                )
            }
        }

        override fun clearQuarantine(uuid: String) {}

        override fun hardDelete(uuid: String) {
            ops.add("hard_delete:$uuid")
            rows.removeAll { it.uuid == uuid }
        }
    }

    private class FakeFile(
        val fileId: String,
        var name: String,
        var bytes: String,
        var trashed: Boolean,
    ) {
        /** Fake Drive `md5Checksum` change-detection token (§31). */
        val md5: String
            get() = MessageDigest.getInstance("MD5")
                .digest(bytes.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }

    private class FakeDrive(
        val files: MutableList<FakeFile> = mutableListOf(),
        var folderCreated: Boolean = false,
        var nextFileId: Int = 1,
        var listCalls: Int = 0,
        var createFail: Boolean = false,
        var patchFaultAfter: Int? = null,
        var patchOk: Int = 0,
        var hideOnce: String? = null,
        var missingContent: String? = null,
        var listMd5: Boolean = true,
        val ops: MutableList<String> = mutableListOf(),
    ) : DriveStore {
        fun addFile(name: String, record: WireRecord): String {
            val fileId = "F${nextFileId}"
            nextFileId += 1
            files.add(FakeFile(fileId, name, record.toJson(), false))
            return fileId
        }

        fun addIdentical(name: String, record: WireRecord, count: Int): List<String> =
            (0 until count).map { addFile(name, record) }

        fun addRaw(name: String, json: String): String {
            val fileId = "F${nextFileId}"
            nextFileId += 1
            files.add(FakeFile(fileId, name, json, false))
            return fileId
        }

        fun file(fileId: String): FakeFile =
            files.firstOrNull { it.fileId == fileId } ?: error("no file $fileId")

        fun rename(fileId: String, newName: String) {
            files.firstOrNull { it.fileId == fileId }?.name = newName
        }

        fun trash(fileId: String) {
            files.firstOrNull { it.fileId == fileId }?.trashed = true
        }

        fun restore(fileId: String) {
            files.firstOrNull { it.fileId == fileId }?.trashed = false
        }

        fun remove(fileId: String) {
            files.removeAll { it.fileId == fileId }
        }

        fun parsed(fileId: String): WireRecord {
            val f = file(fileId)
            return when (val r = parse(f.bytes.toByteArray(), UUID_A)) {
                is ParseResult.Ok -> r.record
                is ParseResult.Err -> error("fake file parses")
            }
        }

        fun parsedNamed(fileId: String): WireRecord {
            val f = file(fileId)
            val uuid = checkNotNull(uuidBasename(f.name)) { "file has uuid name" }
            return when (val r = parse(f.bytes.toByteArray(), uuid)) {
                is ParseResult.Ok -> r.record
                is ParseResult.Err -> error("fake file parses")
            }
        }

        override fun findOrCreateFolder() {
            folderCreated = true
            ops.add("folder")
        }

        override fun listFiles(): List<FileMeta> {
            listCalls += 1
            val hidden = hideOnce
            hideOnce = null
            val out = files
                .filter { !it.trashed && it.fileId != hidden }
                .map { FileMeta(it.fileId, it.name, if (listMd5) it.md5 else null) }
                .sortedBy { it.fileId }
            ops.add("list#$listCalls")
            return out
        }

        override fun getContent(fileId: String): ByteArray? {
            ops.add("get:$fileId")
            if (missingContent == fileId) return null
            return files.firstOrNull { it.fileId == fileId }?.bytes?.toByteArray()
        }

        override fun createFile(name: String, record: WireRecord): String {
            val fileId = addFile(name, record)
            ops.add("create:$name:$fileId")
            if (createFail) throw SyncError.Retryable("injected create failure")
            return fileId
        }

        override fun updateContent(fileId: String, record: WireRecord) {
            ops.add("patch:$fileId")
            val n = patchFaultAfter
            if (n != null && patchOk >= n) {
                throw SyncError.Retryable("injected patch failure")
            }
            files.firstOrNull { it.fileId == fileId }?.bytes = record.toJson()
            patchOk += 1
        }
    }

    private class FakeToken(private val valid: Boolean) : TokenProvider {
        override fun hasValidToken(): Boolean = valid
    }

    private fun runPass(
        local: FakeLocalStore,
        drive: FakeDrive,
        cache: FileCacheStore = FakeCache(),
    ): SyncOutcome =
        SyncEngine.run(RecordType.History, ACCOUNT, local, drive, FakeToken(true), cache)

    private fun runPassKind(
        kind: RecordType,
        local: FakeLocalStore,
        drive: FakeDrive,
        cache: FileCacheStore = FakeCache(),
    ): SyncOutcome =
        SyncEngine.run(kind, ACCOUNT, local, drive, FakeToken(true), cache)

    private fun driveParsedTombstoned(file: FakeFile): Boolean {
        val uuid = uuidBasename(file.name) ?: return false
        val r = parse(file.bytes.toByteArray(), uuid)
        return r is ParseResult.Ok && r.record.deletedAt != null
    }

    private fun derivedFromFacts(row: LocalRow, drive: FakeDrive): String {
        if (row.isLatched()) return SYNC_STATE_QUARANTINED
        val group = drive.files.filter { !it.trashed && it.name == "${row.uuid}.json" }
        val allTombstoned = group.isNotEmpty() && group.all {
            val r = parse(it.bytes.toByteArray(), row.uuid)
            r is ParseResult.Ok && r.record.deletedAt != null
        }
        if (row.isTombstoned()) {
            return if (allTombstoned) SYNC_STATE_CLEAN else SYNC_STATE_DIRTY
        }
        if (group.isEmpty()) {
            return if (row.serverFileId != null) SYNC_STATE_CLEAN else SYNC_STATE_LOCAL
        }
        val anyTombstoned = group.any {
            val r = parse(it.bytes.toByteArray(), row.uuid)
            r is ParseResult.Ok && r.record.deletedAt != null
        }
        if (anyTombstoned) return SYNC_STATE_DIRTY
        return if (row.serverFileId != null) SYNC_STATE_CLEAN else SYNC_STATE_LOCAL
    }

    @Test
    fun import_healthy_group() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        val local = FakeLocalStore()

        val o = runPass(local, drive)
        assertEquals(1, o.imported)
        val rows = local.listRows(ACCOUNT)
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(UUID_A, r.uuid)
        assertEquals(ACCOUNT, r.syncAccount)
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
        assertEquals(f1, r.serverFileId)
        assertFalse(r.isTombstoned())
        assertEquals(TEXT, r.text)
        assertEquals(CREATED_AT, r.timestampMs)

        val o2 = runPass(local, drive)
        assertEquals(SyncOutcome(), o2)
        assertEquals(1, local.rows.size)
        assertTrue(local.ops.any { it == "import:$UUID_A" })
        assertEquals("$UUID_A.json", drive.file(f1).name)
    }

    @Test
    fun importLiveRow_stampsServerFileId() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        val local = FakeLocalStore()

        val o = runPass(local, drive)
        assertEquals(1, o.imported)
        val r = checkNotNull(local.row(UUID_A))
        assertEquals("imported row receives exact Drive file ID", f1, r.serverFileId)
        assertEquals("sync account is preserved", ACCOUNT, r.syncAccount)
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
        assertFalse(r.isTombstoned())
        assertEquals(TEXT, r.text)
        assertEquals(CREATED_AT, r.timestampMs)
    }

    @Test
    fun importTombstoneRow_stampsServerFileId() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", tombstone(wireA(), DELETED_AT))
        val local = FakeLocalStore()

        val o = runPass(local, drive)
        assertEquals(1, o.imported)
        val r = checkNotNull(local.row(UUID_A))
        assertEquals("imported tombstone receives exact Drive file ID", f1, r.serverFileId)
        assertEquals("sync account is preserved", ACCOUNT, r.syncAccount)
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
        assertTrue(r.isTombstoned())
        assertEquals(DELETED_AT, r.deletedAt)
    }

    @Test
    fun importedRow_deleteDoesNotResurrect() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        val local = FakeLocalStore()

        // 1. Initial import from Drive
        val o1 = runPass(local, drive)
        assertEquals(1, o1.imported)
        val imported = checkNotNull(local.row(UUID_A))
        assertEquals(f1, imported.serverFileId)
        assertEquals(SYNC_STATE_CLEAN, imported.syncState)

        // 2. User deletes the row locally. Because serverFileId is set,
        // it generates a tombstone rather than being silently hard-deleted.
        local.markTombstoned(UUID_A, DELETED_AT)
        assertEquals(SYNC_STATE_DIRTY, local.row(UUID_A)?.syncState)
        assertTrue(local.row(UUID_A)?.isTombstoned() == true)

        // 3. Sync pass pushes tombstone to Drive
        val o2 = runPass(local, drive)
        assertEquals(1, o2.patches)
        assertEquals(0, o2.imported)
        assertEquals("Drive file is patched with tombstone", DELETED_AT, drive.parsed(f1).deletedAt)
        assertEquals(SYNC_STATE_CLEAN, local.row(UUID_A)?.syncState)

        // 4. Subsequent sync pass converges to steady state — NO resurrection
        val o3 = runPass(local, drive)
        assertEquals(SyncOutcome(), o3)
        assertTrue("Row remains tombstoned without resurrecting", local.row(UUID_A)?.isTombstoned() == true)
    }

    @Test
    fun duplicate_identical_files_import_once() {
        val drive = FakeDrive()
        drive.addIdentical("$UUID_A.json", wireA(), 2)
        val local = FakeLocalStore()

        assertEquals(1, runPass(local, drive).imported)
        assertEquals(1, local.rows.size)
        assertEquals(SyncOutcome(), runPass(local, drive))
        assertEquals(1, local.rows.size)
    }

    @Test
    fun duplicate_divergent_files_quarantine_whole_group() {
        val drive = FakeDrive()
        drive.addFile("$UUID_A.json", wireA())
        val diverged = wireA().copy(text = "Different text.")
        drive.addFile("$UUID_A.json", diverged)
        val local = FakeLocalStore()

        val o = runPass(local, drive)
        assertEquals(1, o.imported)
        val r = checkNotNull(local.row(UUID_A))
        assertEquals(SYNC_STATE_QUARANTINED, r.syncState)
        assertEquals("collision", r.quarantineReason)
        assertEquals("", r.text)
        assertEquals(0L, r.timestampMs)
        assertEquals(ACCOUNT, r.syncAccount)

        val o2 = runPass(local, drive)
        assertEquals(SyncOutcome(), o2)
        assertEquals(2, drive.files.size)
        assertTrue(drive.ops.none { it.startsWith("patch:") })
    }

    @Test
    fun tombstone_plus_live_duplicate_resolves_deleted() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        val f2 = drive.addFile("$UUID_A.json", tombstone(wireA(), DELETED_AT))
        val local = FakeLocalStore()
        local.import(liveRowClean(UUID_A, f1))

        val o = runPass(local, drive)
        assertEquals(1, o.tombstonedLocal)
        assertEquals(1, o.patches)
        val r = checkNotNull(local.row(UUID_A))
        assertTrue(r.isTombstoned())
        assertEquals(DELETED_AT, r.deletedAt)
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
        assertEquals(DELETED_AT, drive.parsed(f1).deletedAt)
        assertEquals(DELETED_AT, drive.parsed(f2).deletedAt)

        assertEquals(SyncOutcome(), runPass(local, drive))
    }

    @Test
    fun live_local_row_group_deleted_converts_to_tombstone() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", tombstone(wireA(), DELETED_AT))
        val local = FakeLocalStore()
        local.import(liveRowClean(UUID_A, f1))

        val o = runPass(local, drive)
        assertEquals(1, o.tombstonedLocal)
        assertEquals(0, o.imported)
        val r = checkNotNull(local.row(UUID_A))
        assertTrue(r.isTombstoned())
        assertEquals(DELETED_AT, r.deletedAt)
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
        assertEquals(tombstone(wireA(), DELETED_AT).toJson(), drive.file(f1).bytes)

        assertEquals(SyncOutcome(), runPass(local, drive))
    }

    @Test
    fun never_uploaded_deleted_is_hard_deleted() {
        val drive = FakeDrive()
        val local = FakeLocalStore()
        local.import(tombstoneRow(UUID_A))

        val o = runPass(local, drive)
        assertEquals(1, o.hardDeleted)
        assertTrue(local.rows.isEmpty())
        assertTrue(local.ops.any { it == "hard_delete:$UUID_A" })

        assertEquals(SyncOutcome(), runPass(local, drive))
    }

    @Test
    fun account_scope_excludes_foreign_rows() {
        val drive = FakeDrive()
        var foreign = liveRowClean(UUID_B, "F1")
        foreign = foreign.copy(text = "Foreign text.", syncAccount = "account-b")
        drive.addFile("$UUID_B.json", foreign.toWire())
        val local = FakeLocalStore()
        local.import(foreign)
        local.import(liveRow(UUID_A))
        local.ops.clear()

        val o = runPass(local, drive)
        assertEquals(1, o.created)
        assertEquals(foreign, checkNotNull(local.row(UUID_B)))
        assertTrue(local.ops.none { it.contains(UUID_B) })
        assertEquals(2, drive.files.size)
        val fForeign = drive.files.first { it.name == "$UUID_B.json" }
        assertEquals(foreign.toWire().toJson(), fForeign.bytes)
    }

    @Test
    fun stale_offline_device_reconnects_no_resurrection() {
        val drive = FakeDrive()
        drive.addFile("$UUID_A.json", tombstone(wireA(), DELETED_AT))
        val local = FakeLocalStore()
        local.import(liveRowClean(UUID_A, "F1"))

        val o = runPass(local, drive)
        assertEquals(0, o.imported)
        assertEquals(0, o.created)
        assertEquals(1, o.tombstonedLocal)
        val rows = local.listRows(ACCOUNT)
        assertEquals(1, rows.size)
        assertTrue(rows[0].isTombstoned())
        assertEquals(SYNC_STATE_CLEAN, rows[0].syncState)
    }

    @Test
    fun repeated_sync_reaches_fixed_point() {
        val drive = FakeDrive()
        val local = FakeLocalStore()

        val f1 = drive.addFile("$UUID_A.json", wireA())
        local.import(liveRowClean(UUID_A, f1))

        val bWire = tombstoneRowClean(UUID_B, "").toWire()
        val f2 = drive.addFile("$UUID_B.json", bWire)
        local.import(tombstoneRowClean(UUID_B, f2))

        local.import(liveRow(UUID_C))

        val latched = liveRow(UUID_D).copy(
            quarantineReason = "corrupt_file",
            syncState = SYNC_STATE_QUARANTINED
        )
        local.import(latched)

        val first = runPass(local, drive)
        assertEquals(1, first.created)
        assertEquals(0, first.imported)

        repeat(4) {
            assertEquals(SyncOutcome(), runPass(local, drive))
        }
        val c = checkNotNull(local.row(UUID_C))
        assertTrue(c.serverFileId != null)
        assertEquals(SYNC_STATE_CLEAN, c.syncState)
        assertEquals(SYNC_STATE_QUARANTINED, checkNotNull(local.row(UUID_D)).syncState)
    }

    @Test
    fun absence_confirmed_by_listing_only() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        drive.hideOnce = f1
        val local = FakeLocalStore()
        local.import(liveRowClean(UUID_A, f1))

        val o = runPass(local, drive)
        assertEquals(SyncOutcome(), o)
        assertEquals(2, drive.listCalls)
        assertEquals(1, drive.files.size)
        assertEquals(f1, local.row(UUID_A)?.serverFileId)
        assertEquals(SYNC_STATE_CLEAN, local.row(UUID_A)?.syncState)

        assertEquals(SyncOutcome(), runPass(local, drive))
    }

    @Test
    fun trashed_file_counts_as_absent_reupload() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        drive.trash(f1)
        val local = FakeLocalStore()
        local.import(liveRowClean(UUID_A, f1))

        val o = runPass(local, drive)
        assertEquals(1, o.reuploaded)
        assertEquals(2, drive.listCalls)
        val r = checkNotNull(local.row(UUID_A))
        val f2 = checkNotNull(r.serverFileId)
        assertNotEquals(f1, f2)
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
        assertEquals("$UUID_A.json", drive.file(f2).name)
        assertEquals(wireA().toJson(), drive.file(f2).bytes)
        assertTrue(drive.file(f1).trashed)
        assertEquals(wireA().toJson(), drive.file(f1).bytes)

        assertEquals(SyncOutcome(), runPass(local, drive))
    }

    @Test
    fun renamed_file_counts_as_absent_reupload() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        drive.rename(f1, "X-copy.json")
        val local = FakeLocalStore()
        local.import(liveRowClean(UUID_A, f1))

        val o = runPass(local, drive)
        assertEquals(1, o.reuploaded)
        val r = checkNotNull(local.row(UUID_A))
        val f2 = checkNotNull(r.serverFileId)
        assertEquals("$UUID_A.json", drive.file(f2).name)
        assertEquals(wireA().toJson(), drive.file(f2).bytes)
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
    }

    @Test
    fun no_token_returns_auth_required() {
        val drive = FakeDrive()
        val local = FakeLocalStore()
        assertThrows(SyncError.AuthRequired::class.java) {
            SyncEngine.run(RecordType.History, ACCOUNT, local, drive, FakeToken(false), FakeCache())
        }
        assertFalse(drive.folderCreated)
        assertTrue(local.rows.isEmpty())
    }

    @Test
    fun fetch_404_drops_file_this_pass() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        drive.missingContent = f1
        val local = FakeLocalStore()
        local.import(liveRowClean(UUID_A, f1))

        val o = runPass(local, drive)
        assertEquals(SyncOutcome(), o)
        assertEquals(1, drive.listCalls)
        assertEquals(f1, local.row(UUID_A)?.serverFileId)
    }

    @Test
    fun post_timeout_creates_duplicate_identical_file() {
        val drive = FakeDrive()
        drive.createFail = true
        val local = FakeLocalStore()
        local.import(liveRow(UUID_A))

        val o1 = runPass(local, drive)
        assertEquals(1, o1.retryableFailures)
        assertEquals(0, o1.created)
        assertEquals(SYNC_STATE_LOCAL, local.row(UUID_A)?.syncState)
        assertNull(local.row(UUID_A)?.serverFileId)
        assertEquals(1, drive.files.size)

        drive.createFail = false
        val o2 = runPass(local, drive)
        assertEquals(1, o2.created)
        assertEquals(0, o2.retryableFailures)
        val r = checkNotNull(local.row(UUID_A))
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
        assertEquals(2, drive.files.size)
        assertTrue(drive.files.all { it.bytes == wireA().toJson() })

        assertEquals(SyncOutcome(), runPass(local, drive))
    }

    @Test
    fun crash_halfway_through_100_duplicate_tombstones_completes_next_pass() {
        val drive = FakeDrive()
        val ids = drive.addIdentical("$UUID_A.json", wireA(), 100)
        val f1 = ids[0]
        val local = FakeLocalStore()
        local.import(tombstoneRow(UUID_A).withSfi(f1))

        drive.patchFaultAfter = 50
        val o1 = runPass(local, drive)
        assertEquals(50, o1.patches)
        assertEquals(50, o1.retryableFailures)
        assertEquals(SYNC_STATE_DIRTY, local.row(UUID_A)?.syncState)
        val tombstonedCount = drive.files.count { driveParsedTombstoned(it) }
        assertEquals(50, tombstonedCount)

        drive.patchFaultAfter = null
        val o2 = runPass(local, drive)
        assertEquals(50, o2.patches)
        assertEquals(0, o2.retryableFailures)
        assertEquals(SYNC_STATE_CLEAN, local.row(UUID_A)?.syncState)
        assertTrue(drive.files.all { driveParsedTombstoned(it) })
    }

    @Test
    fun remote_tombstone_deleted_reuploaded_by_tombstone_holder() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", tombstone(wireA(), DELETED_AT))
        drive.remove(f1)
        val local = FakeLocalStore()
        local.import(tombstoneRowClean(UUID_A, f1))

        val o = runPass(local, drive)
        assertEquals(1, o.reuploaded)
        val r = checkNotNull(local.row(UUID_A))
        val f2 = checkNotNull(r.serverFileId)
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
        val rep = drive.parsed(f2)
        assertEquals(DELETED_AT, rep.deletedAt)
        assertEquals(wireA().content(), rep.content())

        assertEquals(SyncOutcome(), runPass(local, drive))
    }

    @Test
    fun local_tombstone_destroyed_boundary_documented() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", tombstone(wireA(), DELETED_AT))
        val local = FakeLocalStore()
        local.import(tombstoneRowClean(UUID_A, f1))

        drive.remove(f1)
        local.hardDelete(UUID_A)

        assertEquals(SyncOutcome(), runPass(local, drive))

        drive.addFile("$UUID_A.json", wireA())
        val o = runPass(local, drive)
        assertEquals(1, o.imported)
        val r = checkNotNull(local.row(UUID_A))
        assertFalse(r.isTombstoned())
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
    }

    @Test
    fun entire_folder_deleted_all_records_restored() {
        val drive = FakeDrive()
        val local = FakeLocalStore()

        val f1 = drive.addFile("$UUID_A.json", wireA())
        local.import(liveRowClean(UUID_A, f1))
        val bWire = tombstoneRowClean(UUID_B, "").toWire()
        val f2 = drive.addFile("$UUID_B.json", bWire)
        local.import(tombstoneRowClean(UUID_B, f2))

        drive.remove(f1)
        drive.remove(f2)

        val o = runPass(local, drive)
        assertEquals(2, o.reuploaded)
        assertTrue(drive.folderCreated)
        val a = checkNotNull(local.row(UUID_A))
        val b = checkNotNull(local.row(UUID_B))
        assertEquals(SYNC_STATE_CLEAN, a.syncState)
        assertEquals(SYNC_STATE_CLEAN, b.syncState)
        val fa = drive.file(checkNotNull(a.serverFileId))
        assertEquals("$UUID_A.json", fa.name)
        assertNull(drive.parsedNamed(fa.fileId).deletedAt)
        val fb = drive.file(checkNotNull(b.serverFileId))
        assertEquals("$UUID_B.json", fb.name)
        assertEquals(DELETED_AT, drive.parsedNamed(fb.fileId).deletedAt)

        assertEquals(SyncOutcome(), runPass(local, drive))
    }

    @Test
    fun drive_list_lag_absorbed_by_duplicate_identical() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        drive.hideOnce = f1
        val local = FakeLocalStore()
        local.import(liveRowClean(UUID_A, f1))

        val o1 = runPass(local, drive)
        assertEquals(SyncOutcome(), o1)
        assertEquals(2, drive.listCalls)
        assertEquals(1, drive.files.size)

        val o2 = runPass(local, drive)
        assertEquals(SyncOutcome(), o2)
        assertEquals(1, drive.files.size)
        assertEquals(f1, local.row(UUID_A)?.serverFileId)
    }

    @Test
    fun folder_recreation_restores_records() {
        val drive = FakeDrive()
        val local = FakeLocalStore()
        local.import(liveRowClean(UUID_A, "F1"))

        val o = runPass(local, drive)
        assertTrue(drive.folderCreated)
        assertEquals(1, o.reuploaded)
        val r = checkNotNull(local.row(UUID_A))
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
        assertEquals("$UUID_A.json", drive.file(checkNotNull(r.serverFileId)).name)
    }

    @Test
    fun renamed_file_counts_as_absent_reupload_left_untouched() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        val f1Before = drive.file(f1)
        val local = FakeLocalStore()
        local.import(liveRowClean(UUID_A, f1))

        drive.rename(f1, "X-copy.json")

        val o = runPass(local, drive)
        assertEquals(1, o.reuploaded)
        assertEquals(0, o.hardDeleted)
        assertEquals(0, o.quarantined)

        val r = checkNotNull(local.row(UUID_A))
        val f2 = checkNotNull(r.serverFileId)
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
        assertFalse(r.isTombstoned())
        assertNull(r.quarantineReason)
        assertEquals(1, local.rows.size)
        assertTrue(local.ops.none { it.startsWith("hard_delete") })

        assertEquals("$UUID_A.json", drive.file(f2).name)
        assertEquals(wireA().toJson(), drive.file(f2).bytes)

        val f1After = drive.file(f1)
        assertEquals("X-copy.json", f1After.name)
        assertEquals(f1Before.bytes, f1After.bytes)
        assertTrue(drive.ops.none { it == "get:F1" })
    }

    @Test
    fun restored_trash_file_joins_healthy_group() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        val local = FakeLocalStore()
        local.import(liveRowClean(UUID_A, f1))

        drive.trash(f1)
        val o1 = runPass(local, drive)
        assertEquals(1, o1.reuploaded)
        val r1 = checkNotNull(local.row(UUID_A))
        val f2 = checkNotNull(r1.serverFileId)

        drive.restore(f1)
        val o2 = runPass(local, drive)
        assertEquals(SyncOutcome(), o2)
        val r2 = checkNotNull(local.row(UUID_A))
        assertEquals(f2, r2.serverFileId)
        assertEquals(SYNC_STATE_CLEAN, r2.syncState)
        assertEquals(2, drive.files.size)
        assertTrue(drive.files.all { it.bytes == wireA().toJson() })
    }

    @Test
    fun sync_state_equals_derived() {
        val drive = FakeDrive()
        val local = FakeLocalStore()

        val f1 = drive.addFile("$UUID_A.json", wireA())
        local.import(liveRowClean(UUID_A, f1))

        val bWire = tombstoneRowClean(UUID_B, "").toWire()
        val f2 = drive.addFile("$UUID_B.json", bWire)
        local.import(tombstoneRowClean(UUID_B, f2))

        local.import(liveRow(UUID_C))

        val latched = liveRow(UUID_D).copy(
            quarantineReason = "corrupt_file",
            syncState = SYNC_STATE_QUARANTINED
        )
        local.import(latched)

        runPass(local, drive)

        for (row in local.listRows(ACCOUNT)) {
            assertEquals(derivedFromFacts(row, drive), row.syncState)
        }
        assertEquals(SYNC_STATE_CLEAN, checkNotNull(local.row(UUID_A)).syncState)
        assertEquals(SYNC_STATE_CLEAN, checkNotNull(local.row(UUID_B)).syncState)
        assertEquals(SYNC_STATE_CLEAN, checkNotNull(local.row(UUID_C)).syncState)
        assertEquals(SYNC_STATE_QUARANTINED, checkNotNull(local.row(UUID_D)).syncState)
    }

    // ---------------------------------------------------------------------
    // Layer 2 — §30.5 record kinds (pure engine)
    // ---------------------------------------------------------------------

    @Test
    fun unknown_group_of_other_kind_not_imported_by_this_pass() {
        val drive = FakeDrive()
        drive.addFile("$UUID_B.json", snippetWire(UUID_B, "addr", "456 Oak Ave"))
        val local = FakeLocalStore()

        val o = runPass(local, drive)
        assertEquals("history pass imports nothing", SyncOutcome(), o)
        assertTrue(local.rows.isEmpty())

        val o2 = runPassKind(RecordType.Snippet, local, drive)
        assertEquals(1, o2.imported)
        assertEquals("addr", local.row(UUID_B)?.trigger)
    }

    @Test
    fun unknown_type_file_quarantines_with_unknown_type_reason() {
        val drive = FakeDrive()
        drive.addRaw(
            "$UUID_A.json",
            """{"v":1,"id":"00000000-0000-4000-8000-000000000001","created_at":1713456000123,"deleted_at":null,"type":"note","text":"x","mode":"transcription","duration_ms":1,"provider":"groq"}"""
        )
        val local = FakeLocalStore()

        val o = runPass(local, drive)
        assertEquals(1, o.imported)
        val r = checkNotNull(local.row(UUID_A))
        assertEquals(SYNC_STATE_QUARANTINED, r.syncState)
        assertEquals("unknown_type", r.quarantineReason)
        assertEquals("", r.text)
        assertEquals(0L, r.timestampMs)
    }

    @Test
    fun edit_creates_new_uuid_tombstones_old() {
        // §30.2: an edit = tombstone of the old row + a new UUID row. The
        // snippet pass propagates both: patch the old file, create the new one.
        val drive = FakeDrive()
        val fOld = drive.addFile("$UUID_B.json", snippetWire(UUID_B, "addr", "123 Main St"))
        val local = FakeLocalStore()
        local.import(
            snippetRowClean(UUID_B, "addr", "123 Main St", fOld).copy(
                deletedAt = DELETED_AT,
                syncState = SYNC_STATE_DIRTY
            )
        )
        local.import(snippetRow(UUID_C, "addr", "456 Oak Ave"))

        val o = runPassKind(RecordType.Snippet, local, drive)
        assertEquals(1, o.patches)
        assertEquals(1, o.created)
        assertEquals(0, o.quarantined)

        // Old file tombstoned, content preserved (§4 tombstone keeps T).
        val p = drive.parsedNamed(fOld)
        assertEquals(DELETED_AT, p.deletedAt)
        assertEquals("addr", p.trigger)
        assertEquals("123 Main St", p.expansion)
        // New file carries the edited content under its own UUID.
        val r = checkNotNull(local.row(UUID_C))
        val fNew = checkNotNull(r.serverFileId)
        val pn = drive.parsedNamed(fNew)
        assertEquals(RecordType.Snippet, pn.rtype)
        assertEquals("addr", pn.trigger)
        assertEquals("456 Oak Ave", pn.expansion)
        assertEquals(SYNC_STATE_CLEAN, local.row(UUID_B)?.syncState)
        assertEquals(SYNC_STATE_CLEAN, local.row(UUID_C)?.syncState)

        assertEquals(SyncOutcome(), runPassKind(RecordType.Snippet, local, drive))
    }

    @Test
    fun edit_propagates_to_other_device() {
        // Device A edited (tombstone B + live C); device B starts empty and
        // must receive both rows with correct states.
        val drive = FakeDrive()
        drive.addFile("$UUID_B.json", snippetWire(UUID_B, "addr", "123 Main St", DELETED_AT))
        drive.addFile("$UUID_C.json", snippetWire(UUID_C, "addr", "456 Oak Ave"))
        val local = FakeLocalStore()

        val o = runPassKind(RecordType.Snippet, local, drive)
        assertEquals(2, o.imported)
        val b = checkNotNull(local.row(UUID_B))
        assertTrue(b.isTombstoned())
        assertEquals(SYNC_STATE_CLEAN, b.syncState)
        assertEquals("addr", b.trigger)
        val c = checkNotNull(local.row(UUID_C))
        assertFalse(c.isTombstoned())
        assertEquals(SYNC_STATE_CLEAN, c.syncState)
        assertEquals("456 Oak Ave", c.expansion)

        assertEquals(SyncOutcome(), runPassKind(RecordType.Snippet, local, drive))
    }

    @Test
    fun concurrent_edits_both_survive_no_winner() {
        // Two devices edit the same snippet independently → two new UUIDs;
        // both edits survive, nothing is quarantined.
        val drive = FakeDrive()
        val fA = drive.addFile("$UUID_B.json", snippetWire(UUID_B, "addr", "123 Main St"))
        val fB = drive.addFile("$UUID_B.json", snippetWire(UUID_B, "addr", "123 Main St"))

        // Device A: tombstone B (own file fA), new live row C.
        val localA = FakeLocalStore()
        localA.import(
            snippetRowClean(UUID_B, "addr", "123 Main St", fA).copy(
                deletedAt = DELETED_AT,
                syncState = SYNC_STATE_DIRTY
            )
        )
        localA.import(snippetRow(UUID_C, "addr", "456 Oak Ave"))
        val oa = runPassKind(RecordType.Snippet, localA, drive)
        assertEquals("tombstone-wins patches every listed live copy", 2, oa.patches)
        assertEquals(1, oa.created)

        // Device B: tombstone B (own file fB), new live row E. A's pass
        // already tombstoned every live copy of the old record, so B has
        // nothing to patch — it only uploads its own edit and imports A's.
        val localB = FakeLocalStore()
        localB.import(
            snippetRowClean(UUID_B, "addr", "123 Main St", fB).copy(
                deletedAt = DELETED_AT,
                syncState = SYNC_STATE_DIRTY
            )
        )
        localB.import(snippetRow(UUID_E, "addr", "789 Elm St"))
        val ob = runPassKind(RecordType.Snippet, localB, drive)
        assertEquals(0, ob.patches)
        assertEquals("B's own new row is uploaded", 1, ob.created)
        assertEquals("B imports A's new row", 1, ob.imported)

        // Both rows survive on B, in protocol-valid states, nothing latched.
        val c = checkNotNull(localB.row(UUID_C))
        assertFalse(c.isTombstoned())
        assertEquals("456 Oak Ave", c.expansion)
        assertEquals(SYNC_STATE_CLEAN, c.syncState)
        val e = checkNotNull(localB.row(UUID_E))
        assertFalse(e.isTombstoned())
        assertEquals("789 Elm St", e.expansion)
        assertEquals(SYNC_STATE_CLEAN, e.syncState)
        assertEquals(SYNC_STATE_CLEAN, localB.row(UUID_B)?.syncState)
        assertTrue(localB.rows.all { it.quarantineReason == null })

        // Fixed point on both devices (A picks up B's new row on this pass).
        val oa2 = runPassKind(RecordType.Snippet, localA, drive)
        assertEquals("A imports B's new row on its next pass", 1, oa2.imported)
        val eA = checkNotNull(localA.row(UUID_E))
        assertFalse(eA.isTombstoned())
        assertEquals("789 Elm St", eA.expansion)
        assertEquals(SYNC_STATE_CLEAN, eA.syncState)
        assertEquals(SyncOutcome(), runPassKind(RecordType.Snippet, localB, drive))
    }

    // ---------------------------------------------------------------------
    // Layer 5 — §30.5 record kinds (Fake Drive integration)
    // ---------------------------------------------------------------------

    @Test
    fun edited_snippet_reuploaded_as_new_uuid() {
        // The old file vanished from Drive entirely (trashed/deleted by hand);
        // the tombstoned old row is re-uploaded as a tombstone and the edited
        // row uploads under its new UUID.
        val drive = FakeDrive()
        val fOld = drive.addFile("$UUID_B.json", snippetWire(UUID_B, "addr", "123 Main St"))
        drive.remove(fOld)
        val local = FakeLocalStore()
        local.import(
            snippetRowClean(UUID_B, "addr", "123 Main St", fOld).copy(
                deletedAt = DELETED_AT,
                syncState = SYNC_STATE_DIRTY
            )
        )
        local.import(snippetRow(UUID_C, "addr", "456 Oak Ave"))

        val o = runPassKind(RecordType.Snippet, local, drive)
        assertEquals(1, o.reuploaded)
        assertEquals(1, o.created)
        assertEquals(0, o.patches)
        assertEquals(2, drive.listCalls)

        val b = checkNotNull(local.row(UUID_B))
        assertTrue(b.isTombstoned())
        assertEquals(SYNC_STATE_CLEAN, b.syncState)
        val rb = drive.parsedNamed(checkNotNull(b.serverFileId))
        assertEquals(DELETED_AT, rb.deletedAt)
        assertEquals("addr", rb.trigger)
        assertEquals("123 Main St", rb.expansion)

        val c = checkNotNull(local.row(UUID_C))
        assertEquals(SYNC_STATE_CLEAN, c.syncState)
        val rc = drive.parsedNamed(checkNotNull(c.serverFileId))
        assertEquals(RecordType.Snippet, rc.rtype)
        assertEquals("456 Oak Ave", rc.expansion)

        assertEquals(SyncOutcome(), runPassKind(RecordType.Snippet, local, drive))
    }

    @Test
    fun toggled_enabled_propagates() {
        // §30.3: `snippets_enabled` is a settings record; toggling tombstones
        // the old record and creates a new UUID with the new value.
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_B.json", settingsWire(UUID_B, "snippets_enabled", "true"))
        val local = FakeLocalStore()
        local.import(settingsRowClean(UUID_B, "snippets_enabled", "true", f1))
        local.import(
            settingsRowClean(UUID_B, "snippets_enabled", "true", f1).copy(
                deletedAt = DELETED_AT,
                syncState = SYNC_STATE_DIRTY
            )
        )
        local.import(settingsRow(UUID_C, "snippets_enabled", "false"))

        val o = runPassKind(RecordType.Settings, local, drive)
        assertEquals(1, o.patches)
        assertEquals(1, o.created)

        val p = drive.parsedNamed(f1)
        assertEquals(DELETED_AT, p.deletedAt)
        assertEquals("snippets_enabled", p.settingsKey)
        assertEquals("true", p.settingsValue)
        val r = checkNotNull(local.row(UUID_C))
        assertEquals(SYNC_STATE_CLEAN, r.syncState)
        val p2 = drive.parsedNamed(checkNotNull(r.serverFileId))
        assertEquals("snippets_enabled", p2.settingsKey)
        assertEquals("false", p2.settingsValue)
        assertEquals(SYNC_STATE_CLEAN, local.row(UUID_B)?.syncState)

        // A second device sees exactly the toggled state.
        val other = FakeLocalStore()
        val o2 = runPassKind(RecordType.Settings, other, drive)
        assertEquals(2, o2.imported)
        val b2 = checkNotNull(other.row(UUID_B))
        assertTrue(b2.isTombstoned())
        assertEquals("true", b2.settingsValue)
        val c2 = checkNotNull(other.row(UUID_C))
        assertFalse(c2.isTombstoned())
        assertEquals("false", c2.settingsValue)

        assertEquals(SyncOutcome(), runPassKind(RecordType.Settings, local, drive))
    }

    // ---------------------------------------------------------------------
    // Layer 6 — §31 incremental sync (change-detection cache)
    // ---------------------------------------------------------------------

    @Test
    fun unchanged_files_are_not_redownloaded() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        drive.addFile("$UUID_B.json", wireA().copy(id = UUID_B, createdAt = CREATED_AT + 1))
        val local = FakeLocalStore()
        val cache = FakeCache()

        val o1 = runPass(local, drive, cache)
        assertEquals(2, o1.imported)
        assertEquals("cold cache downloads every file", 2, drive.ops.count { it.startsWith("get:") })
        assertEquals(2, cache.entries.size)

        drive.ops.clear()
        val o2 = runPass(local, drive, cache)
        assertEquals(SyncOutcome(), o2)
        assertTrue(
            "unchanged files must be served from cache, not re-downloaded",
            drive.ops.none { it.startsWith("get:") }
        )
        assertEquals(2, local.rows.size)
    }

    @Test
    fun changed_file_is_redownloaded_but_unchanged_ones_are_not() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        val f2 = drive.addFile("$UUID_B.json", wireA().copy(id = UUID_B, createdAt = CREATED_AT + 1))
        val local = FakeLocalStore()
        val cache = FakeCache()
        runPass(local, drive, cache)

        // Remote content of f1 changes (edited on another device → new token).
        drive.file(f1).bytes = wireA().copy(text = "Edited remotely.").toJson()

        drive.ops.clear()
        runPass(local, drive, cache)
        assertEquals(1, drive.ops.count { it == "get:$f1" })
        assertTrue("unchanged f2 is still served from cache", drive.ops.none { it == "get:$f2" })
    }

    @Test
    fun corrupt_cache_row_triggers_redownload_not_quarantine() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        val local = FakeLocalStore()
        val cache = FakeCache()
        runPass(local, drive, cache)

        // Poison the cache row while the remote token stays the same.
        cache.put(f1, drive.file(f1).md5, "{not json")

        drive.ops.clear()
        val o = runPass(local, drive, cache)
        assertEquals(SyncOutcome(), o)
        assertEquals(1, drive.ops.count { it == "get:$f1" })
        assertEquals("cache re-populated from a fresh download", 1, cache.entries.size)
        assertTrue(local.rows.none { it.quarantineReason != null })
    }

    @Test
    fun files_without_a_token_are_always_downloaded() {
        val drive = FakeDrive()
        val f1 = drive.addFile("$UUID_A.json", wireA())
        val local = FakeLocalStore()
        val cache = FakeCache()
        runPass(local, drive, cache)
        assertEquals(1, cache.entries.size)

        // Listing stops carrying tokens (e.g. API edge case): the engine must
        // degrade to a full download, never reuse blindly.
        drive.listMd5 = false
        drive.ops.clear()
        runPass(local, drive, cache)
        assertEquals(1, drive.ops.count { it == "get:$f1" })
    }

    @Test
    fun cache_prunes_files_removed_from_folder() {
        val drive = FakeDrive()
        drive.addFile("$UUID_A.json", wireA())
        val f2 = drive.addFile("$UUID_B.json", wireA().copy(id = UUID_B, createdAt = CREATED_AT + 1))
        val local = FakeLocalStore()
        val cache = FakeCache()
        runPass(local, drive, cache)
        assertEquals(2, cache.entries.size)

        drive.remove("F1")
        runPass(local, drive, cache)
        assertEquals("pruned to the surviving folder contents", setOf(f2), cache.entries.keys)
    }
}