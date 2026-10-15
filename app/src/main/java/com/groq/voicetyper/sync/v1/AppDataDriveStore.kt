package com.groq.voicetyper.sync.v1

import com.groq.voicetyper.sync.auth.GoogleOAuth
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * On-demand access-token refresher used for the single silent 401 retry.
 * Returns a fresh token, or throws [SyncError] (e.g. [SyncError.AuthRequired]
 * when consent is needed or the account is gone) to stop the pass.
 */
fun interface AccessTokenRefresher {
    fun refresh(staleToken: String): String
}

/**
 * Drive appDataFolder domain store — frozen v1.2 (drive.appdata).
 * Path: appDataFolder/fluence/v1/{dictionary,snippets,stats,settings}.json
 *
 * Concurrency model (v1.2): Drive API v3 does NOT honor If-Match on media
 * updates, so optimistic concurrency uses the per-file monotonically
 * increasing `version` revision instead:
 *
 *   LIST (id+version) -> GET content -> merge -> PUT(expectedVersion)
 *
 * [putDomain] re-checks the live version immediately before writing and throws
 * [SyncError.StaleVersion] when another device changed the file in between;
 * the engine re-fetches, re-merges and retries. Check-then-write is not
 * atomic — a race can slip through that window — but every device keeps its
 * merged state locally, so the next pass converges with no silent loss.
 *
 * Also handles duplicate files (deterministic pick), corruption skip, and
 * payload size caps.
 */
class AppDataDriveStore(
    private var accessToken: String,
    private val tokenRefresher: AccessTokenRefresher? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .readTimeout(java.time.Duration.ofSeconds(30))
        .writeTimeout(java.time.Duration.ofSeconds(60))
        // An OVERALL deadline, which the three socket timeouts above are not:
        // they bound each individual socket operation, so a peer that dribbles a
        // response in under the read timeout forever holds `execute()` open
        // indefinitely. Every Drive call runs inside `SyncPassGate.mutex`, so one
        // stuck call wedged the whole sync engine — the UI stayed on "Syncing…",
        // "Sync Now" was disabled, and no error was ever surfaced, until the
        // process was killed. Windows bounds each call with reqwest's
        // `.timeout(30s)`, so this restores parity.
        //
        // 90s, and it MUST stay below the worker's own budget: `SyncWorker`
        // wraps the pass in `withTimeout(120_000L)`, so any deadline above 120s
        // can never fire in the worker path — it would be inert exactly where the
        // wedge is most likely. 90s leaves headroom under that budget while
        // still being 3x the read timeout. It is also deliberately far more
        // generous than Windows' 30s total, because `callTimeout` spans the WHOLE
        // call including upload: at 8 MiB per pass this allows ~91 KB/s, so a
        // merely slow link still completes while a genuinely dead one fails fast.
        .callTimeout(java.time.Duration.ofSeconds(90))
        .build(),
    private val apiBase: String = API_BASE,
    private val uploadBase: String = UPLOAD_BASE
) : V1SyncEngine.DomainGateway {
    private var fluenceFolderId: String? = null
    private var v1FolderId: String? = null
    // STAGE 3: per-account partition folders, cached by folder name. Keyed by
    // name rather than a single field so that switching accounts cannot reuse
    // the previous account's folder id.
    private val accountFolderIds = mutableMapOf<String, String>()
// GET and PUT are performed through this same instance. Remember the
    // deterministic valid target so version-checking and updating cannot
    // accidentally switch to a corrupt/oversized sibling.
    //
    // STAGE 3: keyed by canonical relative path, NOT by DomainFile. Under
    // partitioning `agents.json` exists once per account, so a DomainFile key
    // would let account B reuse account A's remembered file id.
    private val preferredDomainFileIds = mutableMapOf<String, String>()
    private val validDuplicateFileIds = mutableMapOf<String, List<String>>()
    // Bound the silent token recovery to one retry per pass (per store
    // instance). A second 401 means the refreshed authorization is genuinely
    // insufficient and must surface as AuthRequired, never loop.
    private var authRetried = false

    data class DomainFetch(
        val bytes: ByteArray?,
        val version: String?,
        val hasDuplicateValidFiles: Boolean = false
    )

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val API_BASE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"

        /** Hard cap on a domain payload we will read or write. */
        const val MAX_DOMAIN_BYTES = 8 * 1024 * 1024

        /** Maximum records accepted in one envelope (corruption/abuse guard). */
        const val MAX_ENVELOPE_ITEMS = 50_000

        /**
         * F14: bytes of a FAILURE body we keep. Drive's error JSON is a few
         * hundred bytes; past this it is an abuse page or a proxy error and
         * carries no classification signal worth buffering. Success bodies use
         * the far larger [MAX_DOMAIN_BYTES] instead — a folder listing
         * legitimately returns ~1000 rows, well past a diagnostic-sized cap.
         */
        const val MAX_DIAGNOSTIC_BODY_BYTES = 64 * 1024
    }

    private fun bearer(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    private fun call(builder: Request.Builder): Response {
        var request = builder.build()
        var response = execute(request)
        if (response.code == 401 && !authRetried) {
            val refresher = tokenRefresher ?: return response
            response.close()
            authRetried = true
            val freshToken = refresher.refresh(accessToken)
            accessToken = freshToken
            request = request.newBuilder()
                .header("Authorization", "Bearer $freshToken")
                .build()
            response = execute(request)
        }
        return response
    }

    private fun execute(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: SocketTimeoutException) {
        throw SyncError.Retryable("Drive timeout")
    } catch (e: java.io.IOException) {
        throw SyncError.Retryable(e.message ?: "Drive transport failure")
    }

    // -----------------------------------------------------------------------
    // F14 — bounded response bodies (mirror of Windows `drive.rs`).
    //
    // The payload cap used to be enforced on download only AFTER the whole body
    // had already been buffered, so a single oversized (or simply lying) remote
    // cost one unbounded allocation before anything could refuse it, and the
    // listing / metadata / error bodies had no ceiling at all. Two guards close
    // that:
    //   1. the declared Content-Length is checked BEFORE a byte is read;
    //   2. the body is streamed under a hard ceiling, so an absent, chunked or
    //      simply wrong Content-Length still costs a bounded allocation.
    // Both are needed: the header is only a claim, the ceiling is the guarantee.
    // -----------------------------------------------------------------------

    /**
     * Read this response under a hard byte ceiling and classify it.
     *
     * The success cap is the domain cap (listing, media, about); a failure body
     * is only read to CLASSIFY the failure, so it gets the small diagnostic cap
     * and a hostile error page can never make us buffer megabytes.
     */
    private fun Response.readBoundedBody(): BoundedBody {
        val cap = if (code in 200..299) MAX_DOMAIN_BYTES else MAX_DIAGNOSTIC_BODY_BYTES
        val bodyNow = body ?: return BoundedBody.Complete(ByteArray(0))
        val src = bodyNow.byteStream() ?: return BoundedBody.Complete(ByteArray(0))
        val bytes = try {
            readBodyCapped(bodyNow.contentLength(), src, cap)
        } catch (e: IOException) {
            throw SyncError.Retryable(e.message ?: "Drive body read failure")
        }
        return if (bytes == null) BoundedBody.Truncated else BoundedBody.Complete(bytes)
    }

    /** Whole body as text, under the same two guards (see [readBoundedBody]). */
    private fun Response.readTextBounded(): String = decodedBoundedBody(code, readBoundedBody())

    /**
     * One bounded media read, shared by [getDomain] and [getStatsShardBytes].
     *
     * CRITICAL: an over-cap body is [BoundedRead.OverCap], NEVER [BoundedRead.Absent].
     * The engine reads "absent" as "safe to (re)create", so returning null for an
     * oversized remote would pick that very file as the CAS write target and
     * overwrite it — the opposite of the keep-the-remote-intact contract.
     */
    private fun readMedia(url: String, label: String): BoundedRead =
        call(bearer(url)).use { resp ->
            if (resp.code == 404) return@use BoundedRead.Absent
            when (val bounded = resp.readBoundedBody()) {
                is BoundedBody.Complete -> {
                    classify(resp, resp.code) { bounded.bytes.toString(Charsets.UTF_8) }
                    BoundedRead.Complete(bounded.bytes)
                }

                is BoundedBody.Truncated -> {
                    if (resp.code in 200..299) {
                        android.util.Log.w("FluenceSync", "$label body passed the $MAX_DOMAIN_BYTES byte cap, abandoned unread")
                        BoundedRead.OverCap
                    } else {
                        // Failure path: the body is only diagnostic. Discard it
                        // and classify from the status + headers alone, which
                        // preserves the documented fail-safe (an unparseable 403
                        // is Retryable, never the Fatal "not ours" latch).
                        classify(resp, resp.code) { "" }
                        BoundedRead.Absent
                    }
                }
            }
        }

    /**
     * Map an HTTP status to a [SyncError]. Non-2xx statuses that carry a body
     * only read it via [bodyProvider], and only when classified (403) — a
     * success path never consumes the stream, so callers can still read it.
     * A 429 OR a quota-shaped 403 surfacing a `Retry-After` header becomes
     * [SyncError.RateLimited] so the scheduler honors the explicit delay.
     */
    private fun classify(response: Response, status: Int, bodyProvider: () -> String = { "" }) {
        when (status) {
            in 200..299 -> return
            401 -> throw SyncError.AuthRequired
            403 -> throw classifyForbidden(bodyProvider(), readRetryAfterMs(response))
            429 -> {
                val retryAfterMs = readRetryAfterMs(response)
                if (retryAfterMs != null) throw SyncError.RateLimited(retryAfterMs)
                throw SyncError.Retryable("rate limited")
            }
            in 500..599 -> throw SyncError.Retryable("Drive HTTP $status")
            else -> throw SyncError.Rejected("Drive HTTP $status")
        }
    }

    /** Read a Drive `429 Retry-After` header into an explicit delay in ms. */
    private fun readRetryAfterMs(response: Response): Long? =
        parseRetryAfterMs(response.header("Retry-After"))

    /** Ensure appDataFolder/fluence/v1 exists, handling duplicate folders (pick first). */
    fun ensureV1Folder(): String {
        v1FolderId?.let { return it }
        val fluenceId = ensureFluenceFolder()
        val query = URLEncoder.encode("'$fluenceId' in parents and mimeType = '$FOLDER_MIME' and name = 'v1' and trashed = false", "UTF-8")
        call(bearer("$apiBase/files?q=$query&spaces=appDataFolder&fields=files(id,name)&pageSize=10")).use { resp ->
            val body = resp.readTextBounded()
            classify(resp, resp.code) { body }
            val id = parseFirstId(body)
            v1FolderId = id ?: createFolder("v1", fluenceId)
            return v1FolderId!!
        }
    }

    private fun ensureFluenceFolder(): String {
        fluenceFolderId?.let { return it }
        val query = URLEncoder.encode("name = 'fluence' and mimeType = '$FOLDER_MIME' and trashed = false and 'appDataFolder' in parents", "UTF-8")
        call(bearer("$apiBase/files?q=$query&spaces=appDataFolder&fields=files(id,name)&pageSize=10")).use { resp ->
            val body = resp.readTextBounded()
            classify(resp, resp.code) { body }
            val id = parseFirstId(body)
            fluenceFolderId = id ?: createFolder("fluence", "appDataFolder")
            return fluenceFolderId!!
        }
    }

    private fun createFolder(name: String, parent: String): String {
        val body = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
            .put("parents", org.json.JSONArray().put(parent)).toString()
            .toRequestBody("application/json".toMediaType())
        call(bearer("$apiBase/files?fields=id").post(body)).use { resp ->
            val responseBody = resp.readTextBounded()
            classify(resp, resp.code) { responseBody }
            return parseId(responseBody) ?: throw SyncError.Retryable("folder create missing id")
        }
    }

    // Test-only fault injection: check __fault.json for corrupt/duplicate simulation.
    // Format: {"remaining":1,"op":"get_domain_content","corrupt":true,"corrupt_body":"not json"} or {"duplicate":true}
    // Checks both external and internal locations; internal is the hotspot-safe path used by the test harness
    // (adb push via run-as). The file is consumed (remaining--) on each matching fault.
    private fun readFault(): org.json.JSONObject? {
        if (!com.groq.voicetyper.BuildConfig.DEBUG) return null
        return try {
            val candidates = listOf(
                java.io.File("/sdcard/__fault.json"),
                java.io.File("/data/data/com.groq.voicetyper.debug.ui/files/__fault.json"),
                java.io.File("/data/user/0/com.groq.voicetyper.debug.ui/files/__fault.json")
            )
            val f = candidates.firstOrNull { it.exists() } ?: return null
            org.json.JSONObject(f.readText())
        } catch (_: Exception) { null }
    }
    private fun consumeFault(): Boolean {
        return try {
            val candidates = listOf(
                java.io.File("/sdcard/__fault.json"),
                java.io.File("/data/data/com.groq.voicetyper.debug.ui/files/__fault.json"),
                java.io.File("/data/user/0/com.groq.voicetyper.debug.ui/files/__fault.json")
            )
            val target = candidates.firstOrNull { it.exists() } ?: return false
            val raw = target.readText()
            val obj = org.json.JSONObject(raw)
            val remaining = obj.optLong("remaining", 1)
            if (remaining <= 0) return false
            if (remaining - 1 == 0L) {
                target.delete()
            } else {
                obj.put("remaining", remaining - 1)
                target.writeText(obj.toString(2))
            }
            true
        } catch (_: Exception) { false }
    }
    private fun maybeInjectCorruptBody(op: String): ByteArray? {
        if (!com.groq.voicetyper.BuildConfig.DEBUG) return null
        val obj = readFault() ?: return null
        if (!obj.optBoolean("corrupt", false)) return null
        val filter = obj.optString("op", "")
        if (filter.isNotEmpty() && filter != op && filter != "any") return null
        val remaining = obj.optLong("remaining", 1)
        if (remaining <= 0) return null
        consumeFault()
        val body = obj.optString("corrupt_body", "corrupt")
        android.util.Log.w("FluenceSync", "FAULT INJECTED corrupt_body op=$op remaining=$remaining body=${body.take(50)}")
        return body.toByteArray(Charsets.UTF_8)
    }
    private fun maybeInjectDuplicate(op: String): Boolean {
        if (!com.groq.voicetyper.BuildConfig.DEBUG) return false
        val obj = readFault() ?: return false
        if (!obj.optBoolean("duplicate", false)) return false
        val filter = obj.optString("op", "")
        if (filter.isNotEmpty() && filter != op && filter != "any") return false
        val remaining = obj.optLong("remaining", 1)
        if (remaining <= 0) return false
        consumeFault()
        android.util.Log.w("FluenceSync", "FAULT INJECTED duplicate op=$op remaining=$remaining")
        return true
    }

    /** List domain file with exact name — handle 0/1/>1 (duplicate) */
    fun listDomainFile(domain: DomainFile): List<FileMetaLite> {
        val parent = domainFolder(domain)
        // STAGE 3 fail-closed: a partitioned domain with no usable account hash
        // has no addressable location, so it is reported as absent rather than
        // falling back to the pooled path.
        if (parent == null) return emptyList()
        val name = domainFileName(domain)
        val query = URLEncoder.encode("'$parent' in parents and name = '$name' and trashed = false", "UTF-8")
        val all = mutableListOf<FileMetaLite>()
        var pageToken: String? = null
        do {
            val token = pageToken?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
            call(bearer("$apiBase/files?q=$query&spaces=appDataFolder&fields=files(id,name,version,size),nextPageToken&pageSize=1000$token")).use { resp ->
                val body = resp.readTextBounded()
                classify(resp, resp.code) { body }
                val page = parseFileListLite(body)
                all += page.first
                pageToken = page.second
            }
        } while (pageToken != null)
        if (maybeInjectDuplicate("list_v1_files")) {
            val dictName = domainFileName(DomainFile.DICTIONARY)
            val targetName = domainFileName(domain)
            if (targetName == dictName) {
                val first = all.firstOrNull { it.name == dictName }
                if (first != null) {
                    val dup = FileMetaLite("${first.fileId}-dup", first.name, "999", first.size)
                    android.util.Log.w("FluenceSync", "FAULT INJECTED duplicate listDomainFile: cloning ${first.fileId} -> ${dup.fileId}")
                    all.add(dup)
                } else if (all.isNotEmpty()) {
                    val f = all[0]
                    val dup = FileMetaLite("${f.fileId}-dup", f.name, "999", f.size)
                    android.util.Log.w("FluenceSync", "FAULT INJECTED duplicate listDomainFile: cloning ${f.fileId} -> ${dup.fileId}")
                    all.add(dup)
                } else {
                    val dup1 = FileMetaLite("dup-1", dictName, "1")
                    val dup2 = FileMetaLite("dup-2", dictName, "2")
                    android.util.Log.w("FluenceSync", "FAULT INJECTED duplicate listDomainFile: synthetic pair dup-1/dup-2")
                    all.add(dup1)
                    all.add(dup2)
                }
            }
        }
        return all
    }

    /**
     * List ALL files in the v1 folder (no name predicate), paginated.
     * Stats shard discovery filters this by [StatsShards.isStatsShardFileName];
     * exact-name domains keep using [listDomainFile] so old behavior is
     * untouched.
     */
    private fun listV1FilesAll(): List<FileMetaLite> {
        val parent = ensureV1Folder()
        val query = URLEncoder.encode("'$parent' in parents and trashed = false", "UTF-8")
        val all = mutableListOf<FileMetaLite>()
        var pageToken: String? = null
        do {
            val token = pageToken?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
            call(bearer("$apiBase/files?q=$query&spaces=appDataFolder&fields=files(id,name,version,size),nextPageToken&pageSize=1000$token")).use { resp ->
                val body = resp.readTextBounded()
                classify(resp, resp.code) { body }
                val page = parseFileListLite(body)
                all += page.first
                pageToken = page.second
            }
        } while (pageToken != null)
        return all
    }

    /**
     * Stats shard siblings in v1 (legacy `stats.json` excluded), sorted by
     * (month, part, fileId) for deterministic merges and CAS targets.
     */
    override fun listStatsShards(): List<StatsShardMeta> =
        listV1FilesAll()
            .filter { StatsShards.isStatsShardFileName(it.name) }
            .sortedWith(compareBy(
                { StatsShards.shardSortKey(it.name)!!.first },
                { StatsShards.shardSortKey(it.name)!!.second },
                { it.fileId }
            ))
            .map { StatsShardMeta(it.name, it.fileId, it.version, it.size) }

    /**
     * Shard bytes by file id. Null when vanished (404). Oversized payloads
     * throw Rejected; the engine catches that per shard (skip + retain) so
     * one oversized sibling never blocks the other months.
     */
    override fun getStatsShardBytes(fileId: String): ByteArray? =
        when (val read = readMedia("$apiBase/files/$fileId?alt=media", "stats shard $fileId")) {
            is BoundedRead.Complete -> {
                // Defence in depth: `readCapped` already guarantees this, but
                // the cap is the load-bearing invariant of this function and
                // must not depend on one call site's correctness.
                if (read.bytes.size > MAX_DOMAIN_BYTES) {
                    throw SyncError.Rejected("stats shard payload ${read.bytes.size} bytes exceeds cap")
                }
                read.bytes
            }
            // Rejected, never `null`: null reads as "absent", and the engine
            // would then create/replace this very shard with our own rows.
            is BoundedRead.OverCap -> throw SyncError.Rejected(
                "stats shard $fileId payload exceeds the $MAX_DOMAIN_BYTES byte cap"
            )
            is BoundedRead.Absent -> null
        }

    /**
     * CAS upload of one Stats shard file. Mirrors [putDomain] freshness logic
     * (live version must match [expectedVersion]; create only when absent),
     * but addresses an explicit shard name. Non-shard names are refused
     * fail-closed — the frozen legacy `stats.json` can never be written here.
     */
    override fun putStatsShard(
        name: String,
        bytes: ByteArray,
        expectedVersion: String?,
        preferredFileId: String?
    ): String {
        if (!StatsShards.isStatsShardFileName(name)) {
            throw SyncError.Rejected("refusing to upload non-shard stats file $name")
        }
        if (bytes.size > MAX_DOMAIN_BYTES) {
            throw SyncError.Rejected("refusing to upload ${bytes.size} byte stats shard payload")
        }
        val parent = ensureV1Folder()
        val existing = listV1FilesAll().filter { it.name == name }
        val selected = if (preferredFileId != null) {
            existing.firstOrNull { it.fileId == preferredFileId }
                ?: if (expectedVersion != null) throw SyncError.StaleVersion(null)
                else existing.minByOrNull { it.fileId }
        } else {
            existing.minByOrNull { it.fileId }
        }
        return if (selected != null) {
            val fresh = when {
                expectedVersion != null && selected.version != null -> expectedVersion == selected.version
                expectedVersion != null || selected.version != null -> false
                else -> true
            }
            if (!fresh) throw SyncError.StaleVersion(selected.version)
            patchMultipart(selected.fileId, name, bytes)
        } else {
            val metadata = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(parent)).toString()
            val body = relatedBody(metadata, name, bytes)
            call(bearer("$uploadBase/files?uploadType=multipart&fields=id,version").post(body)).use { resp ->
                val responseBody = resp.readTextBounded()
                classify(resp, resp.code) { responseBody }
                parseVersion(responseBody)
                    ?: throw SyncError.Retryable("create succeeded but no file version was returned")
            }
        }
    }

    /** Delete one shard file (same-name valid-duplicate cleanup only; shards are never otherwise deleted). */
    override fun deleteStatsShard(fileId: String) {
        deleteDomainFile(fileId)
    }

    /**
     * GET domain bytes. All valid same-name files are merged before the
     * engine sees them. Invalid and oversized siblings are never treated as
     * empty data and are retained for manual recovery.
     */
    override fun getDomain(domain: DomainFile): DomainFetch {
        // STAGE 3: a partitioned domain with no usable account hash has no
        // addressable location. Fail closed instead of reading a pooled path.
        val key = domainKey(domain) ?: return DomainFetch(null, null)
        val files = listDomainFile(domain).sortedBy { it.fileId }
        if (files.isEmpty()) {
            preferredDomainFileIds.remove(key)
            validDuplicateFileIds.remove(key)
            return DomainFetch(null, null)
        }

        // Corrupt-body injection: return malformed bytes for the first file, simulating a corrupt remote envelope.
        maybeInjectCorruptBody("get_domain_content")?.let { corrupt ->
            val target = files.first()
            preferredDomainFileIds[key] = target.fileId
            validDuplicateFileIds.remove(key)
            android.util.Log.w("FluenceSync", "FAULT INJECTED corrupt getDomain domain=$domain fileId=${target.fileId} bytes=${corrupt.size}")
            return DomainFetch(corrupt, target.version)
        }

        val valid = mutableListOf<Pair<FileMetaLite, ByteArray>>()
        var firstCorrupt: Pair<FileMetaLite, ByteArray>? = null
        var firstUnusableReadable: Pair<FileMetaLite, ByteArray>? = null
        var largestOversized = 0L
        for (meta in files) {
            // F5: listing-reported oversize skips the download, not just the
            // decode — the bytes would only be discarded. Unknown sizes still
            // download and check below.
            if (meta.listingReportsOversized()) {
                largestOversized = maxOf(largestOversized, meta.size ?: 0L)
                android.util.Log.w("FluenceSync", "domain $domain file ${meta.fileId} listed ${meta.size} bytes over cap; skipping download")
                continue
            }            // Synthetic duplicate file: return a valid but distinct envelope without touching Drive.
            // DIRECT PRODUCTION: debug-only.
            if (com.groq.voicetyper.BuildConfig.DEBUG && (meta.fileId.endsWith("-dup") || meta.fileId.startsWith("dup-"))) {
                val synthetic = org.json.JSONObject().apply {
                    put("v", 1)
                    val arr = org.json.JSONArray()
                    val e = org.json.JSONObject().apply {
                        put("syncId", "00000000-0000-4000-8000-000000000001")
                        put("businessKey", "duplicateTest")
                        put("spoken", "duplicateTest")
                        put("corrected", "DuplicateAA")
                        put("isEnabled", true)
                        put("updatedAt", 1788100000000L)
                        put("deletedAt", org.json.JSONObject.NULL)
                        put("deviceId", "dup-device-0000")
                    }
                    arr.put(e)
                    put("entries", arr)
                }.toString().toByteArray(Charsets.UTF_8)
                android.util.Log.w("FluenceSync", "DUPLICATE INJECTED getDomain fileId=${meta.fileId} synthetic valid")
                valid.add(meta to synthetic)
                continue
            }
            val bytes = when (
                val read = readMedia("$apiBase/files/${meta.fileId}?alt=media", "domain $domain file ${meta.fileId}")
            ) {
                is BoundedRead.Complete -> read.bytes
                // Over-cap: never "absent". An absent file would be reported as
                // `DomainFetch(null, …)` below only when NOTHING is usable —
                // and here the file is counted so the caller raises Rejected
                // and leaves the remote intact.
                is BoundedRead.OverCap -> {
                    largestOversized = maxOf(largestOversized, MAX_DOMAIN_BYTES + 1L)
                    continue
                }
                is BoundedRead.Absent -> continue
            }
            if (bytes.size > MAX_DOMAIN_BYTES) {
                largestOversized = maxOf(largestOversized, bytes.size.toLong())
                continue
            }
            val parsedEntries: List<*>? = when (domain) {
                  DomainFile.DICTIONARY -> DomainSerializer.parseDictionary(bytes)?.entries
                  DomainFile.SNIPPETS -> DomainSerializer.parseSnippets(bytes)?.entries
                  DomainFile.STATS -> DomainSerializer.parseStats(bytes)?.entries
                  DomainFile.SETTINGS -> DomainSerializer.parseSettings(bytes)?.entries
                  DomainFile.AGENTS -> DomainSerializer.parseAgents(bytes)?.entries
                  DomainFile.STYLES -> DomainSerializer.parseStyles(bytes)?.entries
              }
            if (parsedEntries != null) {
                if (parsedEntries.isNotEmpty()) {
                    valid.add(meta to bytes)
                } else if (firstUnusableReadable == null) {
                    // FENCE (Windows frozen.rs:259-279, `let usable = !items.is_empty();`).
                    // Per-record isolation means an envelope whose every record is
                    // structurally malformed decodes to an EMPTY list rather than
                    // `null`. That is still a READABLE file, so it must NOT be
                    // recorded as a VALID duplicate: consolidation DELETES valid
                    // duplicates, and we cannot tell "the user emptied this file"
                    // apart from "every record in it was garbage this client could
                    // not parse". Registering it would let a pass DELETE a sibling
                    // whose real contents we simply failed to read. It contributes
                    // its (empty) records to the merge, is never a delete target,
                    // and is never shed.
                    android.util.Log.w("FluenceSync", "domain $domain file ${meta.fileId} decoded to zero valid records; holding it as readable-but-unusable (not a delete target)")
                    firstUnusableReadable = meta to bytes
                }
            } else if (firstCorrupt == null) firstCorrupt = meta to bytes
        }

        if (valid.isNotEmpty()) {
            val target = valid.first().first
            preferredDomainFileIds[key] = target.fileId
            validDuplicateFileIds[key] = valid.drop(1).map { it.first.fileId }
            val bytes = if (valid.size == 1) {
                valid.first().second
            } else {
                mergeValidDuplicatePayloads(domain, valid.map { it.second })
            }
            return DomainFetch(bytes, target.version, valid.size > 1)
        }

        // Deliberately NO `preferredDomainFileIds[key] = files.first().fileId`
        // here. Every path that actually returns content sets the target from
        // the pair it returns (below), and the two early-exit paths must not set
        // it at all:
        //  - the oversized `throw` escapes immediately;
        //  - `DomainFetch(null, null)` is reached only when every listed file
        //    VANISHED between list and get, so `files.first().fileId` is a dead
        //    id. Recording it made the caller treat a vanished file as the PUT
        //    target for a "remote absent" create — pointing a write at a file
        //    that no longer exists.
        // Leaving a wrong entry is worse than leaving none.
        validDuplicateFileIds.remove(key)
        if (largestOversized > 0) {
            throw SyncError.Rejected("domain payload $largestOversized bytes exceeds cap")
        }
        val unusable = firstCorrupt ?: firstUnusableReadable ?: return DomainFetch(null, null)
        // The PUT target and the CAS version MUST describe the SAME file.
        // `files.first()` is listing order and can differ from `unusable` when a
        // domain has several same-name files — then the caller PUTs to one file
        // while CAS-checking a revision belonging to another, which either
        // livelocks on a spurious StaleVersion or, if two revisions happen to
        // coincide, permits an unguarded overwrite. Deriving the target from the
        // pair actually returned makes target and version inseparable.
        preferredDomainFileIds[key] = unusable.first.fileId
        return DomainFetch(unusable.second, unusable.first.version)
    }

    /**
     * PUT domain bytes with version-number staleness detection.
     *
     * @param expectedVersion the file version the caller based its merge on;
     *   null means the caller believes the file does not exist yet.
     * @return the new post-write version.
     * @throws SyncError.StaleVersion when the live state does not match what
     *   the caller merged against — never clobber a concurrent writer.
     */
    override fun putDomain(domain: DomainFile, bytes: ByteArray, expectedVersion: String?): String {
        if (bytes.size > MAX_DOMAIN_BYTES) {
            throw SyncError.Rejected("refusing to upload ${bytes.size} byte domain payload")
        }
        // STAGE 3 fail-closed: refuse to upload Agents/Styles when the token
        // identity is unresolved. Without this a signed-out or unverified
        // device would create a pooled file that another account later adopts.
        val key = domainKey(domain)
            ?: throw SyncError.Rejected("refusing to upload ${domain.name} with no account partition")
        val parent = domainFolder(domain)
            ?: throw SyncError.Rejected("refusing to upload ${domain.name} with no account partition")
        val name = domainFileName(domain)
        val existing = listDomainFile(domain)
        val preferredId = preferredDomainFileIds[key]
        val selected = if (preferredId != null) {
            existing.firstOrNull { it.fileId == preferredId }
                ?: if (expectedVersion != null) throw SyncError.StaleVersion(null)
                else existing.minByOrNull { it.fileId }
        } else {
            existing.minByOrNull { it.fileId }
        }
        return if (selected != null) {
            val meta = selected
            // Concurrency check: live version must still match what the caller
            // merged against. A missing live version fails closed (fail-safe).
            val fresh = when {
                expectedVersion != null && meta.version != null -> expectedVersion == meta.version
                expectedVersion != null || meta.version != null -> false
                else -> true
            }
            if (!fresh) throw SyncError.StaleVersion(meta.version)
            val newVersion = patchMultipart(meta.fileId, name, bytes)
            // The merged payload contains every valid sibling. Delete only
            // those valid duplicates; corrupt/oversized files remain intact.
            validDuplicateFileIds.remove(key).orEmpty().forEach { duplicateId ->
                if (duplicateId != meta.fileId) deleteDomainFile(duplicateId)
            }
            newVersion
        } else {
            // File absent — creating is always safe (recreate-after-vanish).
            // STAGE 3: `parent` is the account partition folder for
            // Agents/Styles, so a create can never land in a pooled location.
            val metadata = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(parent)).toString()
            val body = relatedBody(metadata, name, bytes)
            call(bearer("$uploadBase/files?uploadType=multipart&fields=id,version").post(body)).use { resp ->
                val responseBody = resp.readTextBounded()
                classify(resp, resp.code) { responseBody }
                parseVersion(responseBody)
                    ?: throw SyncError.Retryable("create succeeded but no file version was returned")
            }
        }
    }

    /**
     * Drive's `uploadType=multipart` requires RFC 2387 `multipart/related`.
     * OkHttp's MultipartBody.FORM emits `multipart/form-data`, which Drive
     * mis-parses: the metadata part is dropped and an appDataFolder create
     * becomes parentless => 403 insufficientFilePermissions.
     */
    private fun relatedBody(metadata: String, name: String, bytes: ByteArray): okhttp3.RequestBody {
        val boundary = "fluence_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/json\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val payload = head.toByteArray(Charsets.UTF_8) + bytes + tail.toByteArray(Charsets.UTF_8)
        return payload.toRequestBody("multipart/related; boundary=$boundary".toMediaType())
    }

    /** Multipart media update returning the new file version. */
    private fun patchMultipart(fileId: String, name: String, bytes: ByteArray): String {
        val metadata = JSONObject().put("name", name).toString()
        val body = relatedBody(metadata, name, bytes)
        call(bearer("$uploadBase/files/$fileId?uploadType=multipart&fields=version").patch(body)).use { resp ->
            val responseBody = resp.readTextBounded()
            classify(resp, resp.code) { responseBody }
            val newVersion = parseVersion(responseBody)
            if (newVersion != null) return newVersion
        }
        // Defensive fallback: fetch the version explicitly so staleness
        // detection stays armed for the next pass.
        call(bearer("$apiBase/files/$fileId?fields=version")).use { resp ->
            val responseBody = resp.readTextBounded()
            classify(resp, resp.code) { responseBody }
            return parseVersion(responseBody)
                ?: throw SyncError.Retryable("update succeeded but no file version was returned")
        }
    }

    /**
     * Consolidate same-name VALID siblings through the shared LWW authority.
     *
     * F24: this used to `!!` the re-parse of every payload. It was safe only
     * because [getDomain] pre-proved parseability ~130 lines earlier, an
     * invariant nothing recorded — so a single drift between the two parses
     * would crash the pass with a `NullPointerException` from a merge path.
     *
     * The parse is now total: an unparseable payload is SKIPPED, and a batch
     * where nothing parses is refused outright rather than uploading an empty
     * envelope over data we merely failed to read (which would delete the very
     * rows the consolidation exists to preserve).
     */
    internal fun mergeValidDuplicatePayloads(domain: DomainFile, payloads: List<ByteArray>): ByteArray {
        fun survived(parsed: Int) {
            if (parsed == 0) {
                // Nothing survived the re-parse: refuse rather than upload an
                // empty envelope. Skipping the write keeps the remote siblings
                // intact, which is exactly what consolidation must never undo.
                throw SyncError.Retryable(
                    "no duplicate $domain payload survived the consolidation re-parse; leaving the remote intact"
                )
            }
        }
        fun logSkipped(parsed: Int) {
            val skipped = payloads.size - parsed
            if (skipped > 0) {
                android.util.Log.w(
                    "FluenceSync",
                    "duplicate $domain payload re-parse dropped $skipped of ${payloads.size}; merging the rest"
                )
            }
        }
        return when (domain) {
            DomainFile.DICTIONARY -> {
                val parsed = payloads.mapNotNull { DomainSerializer.parseDictionary(it) }
                logSkipped(parsed.size); survived(parsed.size)
                DomainSerializer.serializeDictionary(
                    DictionaryDomain(entries = Merge.mergeDictionaries(emptyList(), parsed.flatMap { it.entries }))
                )
            }
            DomainFile.SNIPPETS -> {
                val parsed = payloads.mapNotNull { DomainSerializer.parseSnippets(it) }
                logSkipped(parsed.size); survived(parsed.size)
                DomainSerializer.serializeSnippets(
                    SnippetDomain(entries = Merge.mergeSnippets(emptyList(), parsed.flatMap { it.entries }))
                )
            }
            DomainFile.STATS -> {
                val parsed = payloads.mapNotNull { DomainSerializer.parseStats(it) }
                logSkipped(parsed.size); survived(parsed.size)
                DomainSerializer.serializeStats(
                    StatsDomain(entries = Merge.mergeStats(emptyList(), parsed.flatMap { it.entries }))
                )
            }
            DomainFile.SETTINGS -> {
                val parsed = payloads.mapNotNull { DomainSerializer.parseSettings(it) }
                logSkipped(parsed.size); survived(parsed.size)
                DomainSerializer.serializeSettings(
                    SettingsDomain(entries = Merge.mergeSettings(emptyList(), parsed.flatMap { it.entries }))
                )
            }
            // Duplicate valid files are consolidated through the SAME 2B LWW
            // authority as every other keyed domain — no agent-specific rule.
            DomainFile.AGENTS -> {
                val parsed = payloads.mapNotNull { DomainSerializer.parseAgents(it) }
                logSkipped(parsed.size); survived(parsed.size)
                DomainSerializer.serializeAgents(
                    AgentDomain(entries = Merge.mergeAgents(emptyList(), parsed.flatMap { it.entries }))
                )
            }
            DomainFile.STYLES -> {
                val parsed = payloads.mapNotNull { DomainSerializer.parseStyles(it) }
                logSkipped(parsed.size); survived(parsed.size)
                DomainSerializer.serializeStyles(
                    StyleDomain(entries = Merge.mergeStyles(emptyList(), parsed.flatMap { it.entries }))
                )
            }
        }.toByteArray()
    }

    private fun deleteDomainFile(fileId: String) {
        call(bearer("$apiBase/files/$fileId").delete()).use { resp ->
            if (resp.code == 404) return
            classify(resp, resp.code) { resp.readTextBounded() }
        }
    }

    /**
     * The account this store instance addresses for partitioned domains.
     *
     * `null` (signed out / identity unresolved) makes every partitioned read and
     * write fail closed rather than fall back to the pooled location.
     */
    @Volatile
    var accountHash: String? = null

    private fun domainFileName(d: DomainFile): String = AccountPartition.plainFileName(d)

    /**
     * Cache key identifying exactly one remote domain file.
     *
     * Flat domains key identically for every account. Partitioned domains key by
     * the account-scoped path, so remembered file ids can never leak across an
     * account switch. `null` means "do not address the remote at all".
     */
    private fun domainKey(d: DomainFile): String? =
        AccountPartition.relativePath(d, accountHash)

    /**
     * The folder a domain file lives in: `v1` for the flat domains, and the
     * per-account partition subfolder for Agents/Styles.
     *
     * Returns `null` for a partitioned domain with no usable account hash, which
     * every caller treats as "do not touch the remote at all".
     */
    private fun domainFolder(domain: DomainFile): String? {
        if (!AccountPartition.isPartitioned(domain)) return ensureV1Folder()
        val folder = AccountPartition.folderName(accountHash) ?: return null
        return ensureAccountFolder(folder)
    }

    /**
     * Resolve (or create) the per-account partition folder inside `v1`.
     *
     * The name is already validated by [AccountPartition.folderName], so it
     * cannot contain a path separator or a dot and cannot escape `v1`.
     */
    private fun ensureAccountFolder(name: String): String {
        accountFolderIds[name]?.let { return it }
        val v1 = ensureV1Folder()
        val query = URLEncoder.encode("'$v1' in parents and mimeType = '$FOLDER_MIME' and name = '$name' and trashed = false", "UTF-8")
        call(bearer("$apiBase/files?q=$query&spaces=appDataFolder&fields=files(id,name)&pageSize=10")).use { resp ->
            val body = resp.readTextBounded()
            classify(resp, resp.code) { body }
            val id = parseFirstId(body)
            val resolved = id ?: createFolder(name, v1)
            accountFolderIds[name] = resolved
            return resolved
        }
    }

    /** Ensure appDataFolder/fluence/v1 exists, handling duplicate folders (pick first). */

    private fun parseFirstId(json: String): String? = runCatching {
        val arr = JSONObject(json).optJSONArray("files") ?: return null
        var best: String? = null
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id")?.ifEmpty { null } ?: continue
            if (best == null || id < best!!) best = id
        }
        best
    }.getOrNull()

    private fun parseId(json: String): String? = runCatching { JSONObject(json).optString("id").ifEmpty { null } }.getOrNull()

    /** Drive serializes int64 `version` as a string; tolerate bare numbers. */
    private fun parseVersion(json: String): String? = runCatching {
        val o = JSONObject(json)
        when (val v = o.opt("version")) {
            is String -> v.ifEmpty { null }
            is Number -> v.toString()
            else -> null
        }
    }.getOrNull()
    /**
     * F5: parses Drive's `size` field (int64 as a string; bare numbers
     * tolerated). Absent or garbage sizes are unknown (`null`) — folders,
     * Google-native files, trimmed listings — never a listing failure: the
     * caller downloads and checks the bytes instead.
     */
    internal fun parseFileListLite(json: String): Pair<List<FileMetaLite>, String?> {
        val root = try { JSONObject(json) } catch (_: Exception) { throw SyncError.Rejected("corrupt listing") }
        val arr = root.optJSONArray("files") ?: throw SyncError.Rejected("missing files")
        val out = mutableListOf<FileMetaLite>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw SyncError.Rejected("partial listing")
            val id = o.optString("id").ifEmpty { throw SyncError.Rejected("partial listing") }
            val name = o.optString("name").ifEmpty { throw SyncError.Rejected("partial listing") }
            val version = when (val v = o.opt("version")) {
                is String -> v.ifEmpty { null }
                is Number -> v.toString()
                else -> null
            }
            val size = when (val v = o.opt("size")) {
                is String -> v.toLongOrNull()
                is Number -> v.toLong()
                else -> null
            }
            out.add(FileMetaLite(id, name, version, size))
        }
        return out to root.optString("nextPageToken").ifEmpty { null }
    }

    data class FileMetaLite(val fileId: String, val name: String, val version: String?, val size: Long? = null) {
        /**
         * F5: the listing already reports over-cap — skip the content download
         * entirely instead of re-fetching an oversized file every pass only to
         * discard it. Unknown sizes download and check as before.
         */
        fun listingReportsOversized(): Boolean = size != null && size > MAX_DOMAIN_BYTES
    }
}

/**
 * Parse a `Retry-After` header into an explicit delay in ms. Drive sends
 * integer seconds (RFC 7231 `Retry-After`); an HTTP-date form is intentionally
 * ignored (cannot be mapped to a relative delay easily) — the scheduler falls
 * back to its exponential backoff in that case. Returns null when the value is
 * absent, unparseable, or non-positive. Pure and offline-testable.
 */
fun parseRetryAfterMs(headerValue: String?): Long? {
    val trimmed = headerValue?.trim() ?: return null
    val secs = trimmed.toLongOrNull() ?: return null
    if (secs <= 0L) return null
    // Saturating multiply, exactly as Windows `read_retry_after_ms` does
    // (`secs.saturating_mul(1000)`). A plain `secs * 1000L` overflowed to a
    // NEGATIVE delay for any header above ~2.3e16 seconds, which the scheduler
    // would then treat as "retry immediately" instead of the requested wait.
    return if (secs > Long.MAX_VALUE / 1000L) Long.MAX_VALUE else secs * 1000L
}

// ---------------------------------------------------------------------------
// F14 — bounded bodies, exactly as Windows `drive.rs` shapes them.
//
//   BoundedBody::Complete  <->  BoundedBody.Complete
//   BoundedBody::Truncated <->  BoundedBody.Truncated
//   content_length_over_cap<->  contentLengthOverCap
//   read_capped             <->  readCapped
//   read_body_capped        <->  readBodyCapped
//   decoded_bounded_body    <->  decodedBoundedBody
// ---------------------------------------------------------------------------

/**
 * A response body read under a hard byte ceiling.
 *
 * [Truncated] means the body passed the cap and was abandoned mid-stream; at
 * most `cap + 1` bytes were ever held. It is a DISTINCT outcome from "absent":
 * an over-cap body can never be decoded into valid state, and treating it as a
 * missing file would make the engine pick that file as a CAS write target.
 */
internal sealed interface BoundedBody {
    /** The whole body arrived and is <= the cap. */
    class Complete(val bytes: ByteArray) : BoundedBody

    /** The body exceeded the cap and was abandoned unread. */
    object Truncated : BoundedBody
}

/** Outcome of one bounded media read, as the sync engine consumes it. */
internal sealed interface BoundedRead {
    /** The file is gone (404). Reads as absent to the engine. */
    object Absent : BoundedRead

    /**
     * The body passed the cap. NEVER reads as absent — see [BoundedBody.Truncated].
     */
    object OverCap : BoundedRead

    /** The complete body, guaranteed <= the success cap. */
    class Complete(val bytes: ByteArray) : BoundedRead
}

/**
 * Whether a declared `Content-Length` already exceeds [cap].
 *
 * Pre-read half of the guard: when the length is declared we refuse before a
 * single body byte is buffered. `null` — absent, chunked, or simply a lie —
 * falls through to the streaming ceiling, which is what actually holds.
 */
internal fun contentLengthOverCap(contentLength: Long?, cap: Int): Long? =
    contentLength?.takeIf { it > cap.toLong() }

/**
 * Stream [source] into memory, abandoning it the moment the body passes [cap].
 *
 * Streaming half of the guard. `null` = exceeded `cap`; a `ByteArray` = the
 * complete body, guaranteed <= `cap`. At most `cap + 1` bytes are ever held, so
 * an unbounded or hostile body costs a bounded allocation. Reads through a
 * plain [InputStream], so it is exercisable offline with a byte counter.
 */
internal fun readCapped(source: InputStream, cap: Int): ByteArray? {
    require(cap >= 0) { "cap must be non-negative" }
    val out = ByteArrayOutputStream(minOf(cap, READ_CHUNK_BYTES).coerceAtLeast(1))
    val chunk = ByteArray(READ_CHUNK_BYTES)
    var total = 0
    while (true) {
        // `total <= cap` holds here (we return as soon as it exceeds), so
        // `room >= 1` and the read never asks for a zero-length buffer.
        val want = minOf(cap + 1 - total, chunk.size)
        val n = source.read(chunk, 0, want)
        if (n < 0) return out.toByteArray()
        out.write(chunk, 0, n)
        total += n
        if (total > cap) return null
    }
}

/**
 * The whole two-guard read as a pure function of (declared length, stream).
 *
 * Guard 1 checks the declared `Content-Length` before a byte is read; guard 2
 * streams under the ceiling for the case where that header is absent, chunked
 * or simply wrong. `null` means "do not use this body" for either reason.
 */
internal fun readBodyCapped(contentLength: Long?, source: InputStream, cap: Int): ByteArray? {
    if (contentLengthOverCap(contentLength, cap) != null) return null
    return readCapped(source, cap)
}

/**
 * Decode a [BoundedBody] for parsing or classification.
 *
 * A truncated SUCCESS body cannot yield valid state, so it is
 * [SyncError.Rejected] — never decoded, never handed to the engine. A truncated
 * FAILURE body decodes to an empty string so the status and headers alone still
 * classify the failure, which keeps the documented fail-safe (an unparseable
 * 403 is Retryable, never the "not ours" fatal latch).
 */
internal fun decodedBoundedBody(status: Int, body: BoundedBody): String = when (body) {
    is BoundedBody.Complete -> String(body.bytes, Charsets.UTF_8)
    is BoundedBody.Truncated -> {
        if (status in 200..299) {
            throw SyncError.Rejected(
                "response body exceeds the ${AppDataDriveStore.MAX_DOMAIN_BYTES} byte cap"
            )
        }
        ""
    }
}

/** Streaming chunk for [readCapped]; also the initial buffer hint. */
private const val READ_CHUNK_BYTES = 16 * 1024

/**
 * Pure Drive 403 classification — mirrors Windows `classify_forbidden_with_retry_after`
 * (drive.rs): transient/quota reasons surface as a retryable error so the next
 * pass (with backoff) handles them, while a genuine ownership/scope denial stays
 * [SyncError.Fatal]. An unparseable body is assumed transient (fail-closed retry
 * is safer than wedging the account).
 *
 * `retryAfterMs` is the response's `Retry-After`, when present. A quota-shaped
 * 403 that arrived with an explicit delay becomes [SyncError.RateLimited] so the
 * scheduler honors it. Without this the `dailyLimitExceeded` header is dropped on
 * the floor and the scheduler re-polls on its 60 s exponential cap forever against
 * a server that asked for a day (~1,440 doomed requests/day, each re-tripping the
 * quota counter). With `retryAfterMs = null` the classification is identical to
 * the previous behaviour — the only change is that a present header now survives.
 */
fun classifyForbidden(body: String, retryAfterMs: Long? = null): SyncError {
    val reason = forbiddenReason(body)
    return when {
        reason == null -> SyncError.Retryable("Drive HTTP 403")
        reason in TRANSIENT_403_REASONS -> when {
            // RateLimited takes the same retryable scheduling path as Retryable
            // (see SyncManager's outcome mapping); it only carries the delay.
            retryAfterMs != null -> SyncError.RateLimited(retryAfterMs)
            else -> SyncError.Retryable("Drive rate limited ($reason)")
        }
        // A `Retry-After` on a genuine permission denial must NOT convert the
        // fatal stop into a retry loop (Windows pins this: drive.rs tests
        // `non_quota_403_ignores_retry_after_and_stays_not_ours`).
        else -> SyncError.Fatal("Drive access not permitted ($reason)")
    }
}

/**
 * Drive 403 reasons that are a throttle/quota condition, not a permission
 * decision. `internal` so [DriveIdentity.classify] reuses the exact same set
 * instead of keeping a second copy that can drift.
 */
internal val TRANSIENT_403_REASONS = setOf(
    "userRateLimitExceeded",
    "rateLimitExceeded",
    "dailyLimitExceeded",
    "sharedLimitExceeded",
    "quotaExceeded",
    "backendError"
)

/** Read `error.reason`, falling back to `error.errors[0].reason` (Drive API v3). */
/** `internal` so [DriveIdentity.classify] reuses the same reason parser. */
internal fun forbiddenReason(body: String): String? {
    return try {
        val error = JSONObject(body).optJSONObject("error") ?: return null
        error.optString("reason").ifEmpty { null }
            ?: error.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}
