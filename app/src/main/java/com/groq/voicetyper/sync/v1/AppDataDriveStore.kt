package com.groq.voicetyper.sync.v1

import com.groq.voicetyper.sync.auth.GoogleOAuth
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
        .build(),
    private val apiBase: String = API_BASE,
    private val uploadBase: String = UPLOAD_BASE
) : V1SyncEngine.DomainGateway {
    private var fluenceFolderId: String? = null
    private var v1FolderId: String? = null
// GET and PUT are performed through this same instance. Remember the
    // deterministic valid target so version-checking and updating cannot
    // accidentally switch to a corrupt/oversized sibling.
    private val preferredDomainFileIds = mutableMapOf<DomainFile, String>()
    private val validDuplicateFileIds = mutableMapOf<DomainFile, List<String>>()
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

    /**
     * Map an HTTP status to a [SyncError]. Non-2xx statuses that carry a body
     * only read it via [bodyProvider], and only when classified (403) — a
     * success path never consumes the stream, so callers can still read it.
     * A 429 surfacing a `Retry-After` header becomes [SyncError.RateLimited]
     * so the scheduler honors the explicit delay.
     */
    private fun classify(response: Response, status: Int, bodyProvider: () -> String = { "" }) {
        when (status) {
            in 200..299 -> return
            401 -> throw SyncError.AuthRequired
            403 -> throw classifyForbidden(bodyProvider())
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
            val body = resp.body?.string().orEmpty()
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
            val body = resp.body?.string().orEmpty()
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
            val responseBody = resp.body?.string().orEmpty()
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
        val v1 = ensureV1Folder()
        val name = domainFileName(domain)
        val query = URLEncoder.encode("'$v1' in parents and name = '$name' and trashed = false", "UTF-8")
        val all = mutableListOf<FileMetaLite>()
        var pageToken: String? = null
        do {
            val token = pageToken?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
            call(bearer("$apiBase/files?q=$query&spaces=appDataFolder&fields=files(id,name,version),nextPageToken&pageSize=1000$token")).use { resp ->
                val body = resp.body?.string().orEmpty()
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
                    val dup = FileMetaLite("${first.fileId}-dup", first.name, "999")
                    android.util.Log.w("FluenceSync", "FAULT INJECTED duplicate listDomainFile: cloning ${first.fileId} -> ${dup.fileId}")
                    all.add(dup)
                } else if (all.isNotEmpty()) {
                    val f = all[0]
                    val dup = FileMetaLite("${f.fileId}-dup", f.name, "999")
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
     * GET domain bytes. All valid same-name files are merged before the
     * engine sees them. Invalid and oversized siblings are never treated as
     * empty data and are retained for manual recovery.
     */
    override fun getDomain(domain: DomainFile): DomainFetch {
        val files = listDomainFile(domain).sortedBy { it.fileId }
        if (files.isEmpty()) {
            preferredDomainFileIds.remove(domain)
            validDuplicateFileIds.remove(domain)
            return DomainFetch(null, null)
        }

        // Corrupt-body injection: return malformed bytes for the first file, simulating a corrupt remote envelope.
        maybeInjectCorruptBody("get_domain_content")?.let { corrupt ->
            val target = files.first()
            preferredDomainFileIds[domain] = target.fileId
            validDuplicateFileIds.remove(domain)
            android.util.Log.w("FluenceSync", "FAULT INJECTED corrupt getDomain domain=$domain fileId=${target.fileId} bytes=${corrupt.size}")
            return DomainFetch(corrupt, target.version)
        }

        val valid = mutableListOf<Pair<FileMetaLite, ByteArray>>()
        var firstCorrupt: Pair<FileMetaLite, ByteArray>? = null
        var largestOversized = 0
        for (meta in files) {
            // Synthetic duplicate file: return a valid but distinct envelope without touching Drive.
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
            val bytes = call(bearer("$apiBase/files/${meta.fileId}?alt=media")).use { resp ->
                if (resp.code == 404) return@use null
                val responseBody = resp.body?.bytes() ?: ByteArray(0)
                classify(resp, resp.code) { responseBody.toString(Charsets.UTF_8) }
                responseBody
            } ?: continue
            if (bytes.size > MAX_DOMAIN_BYTES) {
                largestOversized = maxOf(largestOversized, bytes.size)
                continue
            }
            val parseable = when (domain) {
                DomainFile.DICTIONARY -> DomainSerializer.parseDictionary(bytes) != null
                DomainFile.SNIPPETS -> DomainSerializer.parseSnippets(bytes) != null
                DomainFile.STATS -> DomainSerializer.parseStats(bytes) != null
                DomainFile.SETTINGS -> DomainSerializer.parseSettings(bytes) != null
            }
            if (parseable) valid.add(meta to bytes)
            else if (firstCorrupt == null) firstCorrupt = meta to bytes
        }

        if (valid.isNotEmpty()) {
            val target = valid.first().first
            preferredDomainFileIds[domain] = target.fileId
            validDuplicateFileIds[domain] = valid.drop(1).map { it.first.fileId }
            val bytes = if (valid.size == 1) {
                valid.first().second
            } else {
                mergeValidDuplicatePayloads(domain, valid.map { it.second })
            }
            return DomainFetch(bytes, target.version, valid.size > 1)
        }

        preferredDomainFileIds[domain] = files.first().fileId
        validDuplicateFileIds.remove(domain)
        if (largestOversized > 0) {
            throw SyncError.Rejected("domain payload $largestOversized bytes exceeds cap")
        }
        val corrupt = firstCorrupt ?: return DomainFetch(null, null)
        return DomainFetch(corrupt.second, corrupt.first.version)
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
        val v1 = ensureV1Folder()
        val name = domainFileName(domain)
        val existing = listDomainFile(domain)
        val preferredId = preferredDomainFileIds[domain]
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
            validDuplicateFileIds.remove(domain).orEmpty().forEach { duplicateId ->
                if (duplicateId != meta.fileId) deleteDomainFile(duplicateId)
            }
            newVersion
        } else {
            // File absent — creating is always safe (recreate-after-vanish).
            val metadata = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(v1)).toString()
            val body = relatedBody(metadata, name, bytes)
            call(bearer("$uploadBase/files?uploadType=multipart&fields=id,version").post(body)).use { resp ->
                val responseBody = resp.body?.string().orEmpty()
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
            val responseBody = resp.body?.string().orEmpty()
            classify(resp, resp.code) { responseBody }
            val newVersion = parseVersion(responseBody)
            if (newVersion != null) return newVersion
        }
        // Defensive fallback: fetch the version explicitly so staleness
        // detection stays armed for the next pass.
        call(bearer("$apiBase/files/$fileId?fields=version")).use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            classify(resp, resp.code) { responseBody }
            return parseVersion(responseBody)
                ?: throw SyncError.Retryable("update succeeded but no file version was returned")
        }
    }

    private fun mergeValidDuplicatePayloads(domain: DomainFile, payloads: List<ByteArray>): ByteArray {
        return when (domain) {
            DomainFile.DICTIONARY -> DomainSerializer.serializeDictionary(
                DictionaryDomain(entries = Merge.mergeDictionaries(
                    emptyList(), payloads.flatMap { DomainSerializer.parseDictionary(it)!!.entries }
                ))
            )
            DomainFile.SNIPPETS -> DomainSerializer.serializeSnippets(
                SnippetDomain(entries = Merge.mergeSnippets(
                    emptyList(), payloads.flatMap { DomainSerializer.parseSnippets(it)!!.entries }
                ))
            )
            DomainFile.STATS -> DomainSerializer.serializeStats(
                StatsDomain(entries = Merge.mergeStats(
                    emptyList(), payloads.flatMap { DomainSerializer.parseStats(it)!!.entries }
                ))
            )
            DomainFile.SETTINGS -> DomainSerializer.serializeSettings(
                SettingsDomain(entries = Merge.mergeSettings(
                    emptyList(), payloads.flatMap { DomainSerializer.parseSettings(it)!!.entries }
                ))
            )
        }.toByteArray()
    }

    private fun deleteDomainFile(fileId: String) {
        call(bearer("$apiBase/files/$fileId").delete()).use { resp ->
            if (resp.code == 404) return
            classify(resp, resp.code) { resp.body?.string().orEmpty() }
        }
    }

    private fun domainFileName(d: DomainFile): String = when (d) {
        DomainFile.DICTIONARY -> "dictionary.json"
        DomainFile.SNIPPETS -> "snippets.json"
        DomainFile.STATS -> "stats.json"
        DomainFile.SETTINGS -> "settings.json"
    }

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

    private fun parseFileListLite(json: String): Pair<List<FileMetaLite>, String?> {
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
            out.add(FileMetaLite(id, name, version))
        }
        return out to root.optString("nextPageToken").ifEmpty { null }
    }

    data class FileMetaLite(val fileId: String, val name: String, val version: String?)
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
    return secs * 1000L
}

/**
 * Pure Drive 403 classification — mirrors Windows `classify_forbidden`
 * (drive.rs): transient/quota reasons surface as [SyncError.Retryable] so the
 * next pass (with backoff) handles them, while a genuine ownership/scope
 * denial stays [SyncError.Fatal]. An unparseable body is assumed transient
 * (Fail-closed retry is safer than wedging the account).
 */
fun classifyForbidden(body: String): SyncError {
    val reason = forbiddenReason(body)
    return when {
        reason == null -> SyncError.Retryable("Drive HTTP 403")
        reason in TRANSIENT_403_REASONS -> SyncError.Retryable("Drive rate limited ($reason)")
        else -> SyncError.Fatal("Drive access not permitted ($reason)")
    }
}

private val TRANSIENT_403_REASONS = setOf(
    "userRateLimitExceeded",
    "rateLimitExceeded",
    "dailyLimitExceeded",
    "sharedLimitExceeded",
    "quotaExceeded",
    "backendError"
)

/** Read `error.reason`, falling back to `error.errors[0].reason` (Drive API v3). */
private fun forbiddenReason(body: String): String? {
    return try {
        val error = JSONObject(body).optJSONObject("error") ?: return null
        error.optString("reason").ifEmpty { null }
            ?: error.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}
