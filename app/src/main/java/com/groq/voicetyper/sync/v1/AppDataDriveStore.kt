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
    private val accessToken: String,
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

    data class DomainFetch(val bytes: ByteArray?, val version: String?)

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
        val req = builder.build()
        val response = try { client.newCall(req).execute() }
        catch (e: SocketTimeoutException) { throw SyncError.Retryable("Drive timeout") }
        catch (e: java.io.IOException) { throw SyncError.Retryable(e.message ?: "Drive transport failure") }
        return response
    }

    /**
     * Map an HTTP status to a [SyncError]. Non-2xx statuses that carry a body
     * only read it via [bodyProvider], and only when classified (403) — a
     * success path never consumes the stream, so callers can still read it.
     */
    private fun classify(status: Int, bodyProvider: () -> String = { "" }) {
        when (status) {
            in 200..299 -> return
            401 -> throw SyncError.AuthRequired
            403 -> throw classifyForbidden(bodyProvider())
            429 -> throw SyncError.Retryable("rate limited")
            in 500..599 -> throw SyncError.Retryable("Drive HTTP $status")
            else -> throw SyncError.Rejected("Drive HTTP $status")
        }
    }

    /** Ensure appDataFolder/fluence/v1 exists, handling duplicate folders (pick first). */
    fun ensureV1Folder(): String {
        v1FolderId?.let { return it }
        val fluenceId = ensureFluenceFolder()
        val query = URLEncoder.encode("'$fluenceId' in parents and mimeType = '$FOLDER_MIME' and name = 'v1' and trashed = false", "UTF-8")
        call(bearer("$apiBase/files?q=$query&spaces=appDataFolder&fields=files(id,name)&pageSize=10")).use { resp ->
            classify(resp.code) { resp.body?.string().orEmpty() }
            val body = resp.body?.string().orEmpty()
            val id = parseFirstId(body)
            v1FolderId = id ?: createFolder("v1", fluenceId)
            return v1FolderId!!
        }
    }

    private fun ensureFluenceFolder(): String {
        fluenceFolderId?.let { return it }
        val query = URLEncoder.encode("name = 'fluence' and mimeType = '$FOLDER_MIME' and trashed = false and 'appDataFolder' in parents", "UTF-8")
        call(bearer("$apiBase/files?q=$query&spaces=appDataFolder&fields=files(id,name)&pageSize=10")).use { resp ->
            classify(resp.code) { resp.body?.string().orEmpty() }
            val body = resp.body?.string().orEmpty()
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
            classify(resp.code) { resp.body?.string().orEmpty() }
            return parseId(resp.body?.string().orEmpty()) ?: throw SyncError.Retryable("folder create missing id")
        }
    }

    /** List domain file with exact name — handle 0/1/>1 (duplicate) */
    fun listDomainFile(domain: DomainFile): List<FileMetaLite> {
        val v1 = ensureV1Folder()
        val name = domainFileName(domain)
        val query = URLEncoder.encode("'$v1' in parents and name = '$name' and trashed = false", "UTF-8")
        call(bearer("$apiBase/files?q=$query&spaces=appDataFolder&fields=files(id,name,version)&pageSize=10")).use { resp ->
            classify(resp.code) { resp.body?.string().orEmpty() }
            val body = resp.body?.string().orEmpty()
            return parseFileListLite(body)
        }
    }

    /**
     * GET domain file bytes. The concurrent-revision marker comes from the
     * listing ([FileMetaLite.version]), not from this media response.
     * Returns null bytes if 0 files. If >1 duplicate, deterministically picks
     * the lowest fileId (others left untouched — never auto-deleted).
     * Corruption is surfaced as unparseable bytes so the caller auto-skips.
     */
    override fun getDomain(domain: DomainFile): DomainFetch {
        val files = listDomainFile(domain)
        if (files.isEmpty()) return DomainFetch(null, null)
        // Duplicate handling: deterministic pick (lowest fileId); duplicates coexist.
        val meta = files.minByOrNull { it.fileId }!!
        call(bearer("$apiBase/files/${meta.fileId}?alt=media")).use { resp ->
            if (resp.code == 404) return DomainFetch(null, null)
            classify(resp.code) { resp.body?.string().orEmpty() }
            val bytes = resp.body?.bytes()
            if (bytes != null && bytes.size > MAX_DOMAIN_BYTES) {
                throw SyncError.Rejected("domain payload ${bytes.size} bytes exceeds cap")
            }
            return DomainFetch(bytes, meta.version)
        }
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
        return if (existing.isNotEmpty()) {
            val meta = existing.minByOrNull { it.fileId }!!
            // Concurrency check: live version must still match what the caller
            // merged against. A missing live version fails closed (fail-safe).
            val fresh = when {
                expectedVersion != null && meta.version != null -> expectedVersion == meta.version
                expectedVersion != null || meta.version != null -> false
                else -> true
            }
            if (!fresh) throw SyncError.StaleVersion(meta.version)
            patchMultipart(meta.fileId, name, bytes)
        } else {
            // File absent — creating is always safe (recreate-after-vanish).
            val metadata = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(v1)).toString()
            val body = relatedBody(metadata, name, bytes)
            call(bearer("$uploadBase/files?uploadType=multipart&fields=id,version").post(body)).use { resp ->
                classify(resp.code) { resp.body?.string().orEmpty() }
                parseVersion(resp.body?.string().orEmpty())
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
            classify(resp.code) { resp.body?.string().orEmpty() }
            val newVersion = parseVersion(resp.body?.string().orEmpty())
            if (newVersion != null) return newVersion
        }
        // Defensive fallback: fetch the version explicitly so staleness
        // detection stays armed for the next pass.
        call(bearer("$apiBase/files/$fileId?fields=version")).use { resp ->
            classify(resp.code) { resp.body?.string().orEmpty() }
            return parseVersion(resp.body?.string().orEmpty())
                ?: throw SyncError.Retryable("update succeeded but no file version was returned")
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
        if (arr.length() == 0) return null
        arr.getJSONObject(0).optString("id").ifEmpty { null }
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

    private fun parseFileListLite(json: String): List<FileMetaLite> {
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
        return out
    }

    data class FileMetaLite(val fileId: String, val name: String, val version: String?)
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
