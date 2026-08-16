package com.groq.voicetyper.sync.drive

import com.groq.voicetyper.sync.auth.GoogleOAuth
import com.groq.voicetyper.sync.engine.DriveStore
import com.groq.voicetyper.sync.engine.FileMeta
import com.groq.voicetyper.sync.engine.SyncError
import com.groq.voicetyper.sync.wire.WireRecord
import java.net.SocketTimeoutException
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Drive REST layer for sync (spec §23).
 *
 * Implements [DriveStore] against the Drive v3 API with the memory-only
 * access token. Error mapping (§23):
 * - `401` → [SyncError.AuthRequired] (reauth)
 * - `403` drive.file scope → [SyncError.Fatal] (skip this pass, never
 *   retry-bomb; the scheduler latches until a manual run)
 * - `429` / 5xx / timeout → [SyncError.Retryable] (scheduler backs off)
 * - fetch-404 during VALIDATE → `null` (drop the file this pass)
 * - partial responses → treated as failures, re-fetch
 *
 * No hardcoded quota figures — only timing constants, per §23/§28.
 */
class GoogleDriveStore(
    private val accessToken: String,
    private val client: OkHttpClient = GoogleOAuth.newHttpClient(),
    private val apiBase: String = API_BASE,
    /** Upload host for create/media writes (§23) — injectable test seam. */
    private val uploadBase: String = UPLOAD_BASE,
) : DriveStore {

    private var folderId: String? = null

    private fun bearer(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    private fun call(builder: Request.Builder): Response {
        val req = builder.build()
        val response = try {
            client.newCall(req).execute()
        } catch (e: SocketTimeoutException) {
            android.util.Log.w("FluenceDrive", "Drive timeout on ${req.method} ${req.url}")
            throw SyncError.Retryable("Drive timeout")
        } catch (e: java.io.IOException) {
            android.util.Log.w("FluenceDrive", "Drive transport error on ${req.method} ${req.url}: ${e.message}")
            throw SyncError.Retryable(e.message ?: "Drive transport failure")
        }
        android.util.Log.d("FluenceDrive", "Drive HTTP ${response.code} for ${req.method} ${req.url}")
        return response
    }

    /**
     * Classify a Drive HTTP status into engine error kinds (§23).
     *
     * Strict: only 2xx is accepted. Every other 4xx (and 3xx) that is not
     * already mapped to `AuthRequired`/`Fatal`/`Retryable` is a permanent
     * client rejection → `Rejected`: surfaced non-success, never backoff-
     * escalated. Op-level handling (e.g. fetch-404 → null) happens BEFORE
     * this call.
     */
    private fun classify(status: Int) {
        when (status) {
            in 200..299 -> return
            401 -> throw SyncError.AuthRequired
            403 -> throw SyncError.Fatal("Drive access not permitted for this account")
            429 -> throw SyncError.Retryable("rate limited")
            in 500..599 -> throw SyncError.Retryable("Drive HTTP $status")
            else -> throw SyncError.Rejected("Drive HTTP $status")
        }
    }

    override fun findOrCreateFolder() {
        if (folderId != null) return
        val query = URLEncoder.encode(
            "name = '${FOLDER_NAME.replace("'", "\\'")}' and mimeType = '$FOLDER_MIME' and trashed = false",
            "UTF-8",
        )
        call(bearer("$apiBase/files?q=$query")).use { response ->
            classify(response.code)
            val body = response.body?.string().orEmpty()
            val (folders, _) = parseFileListing(body)
            folderId = if (folders.isNotEmpty()) {
                folders.first().fileId
            } else {
                createFolder()
            }
        }
    }

    private fun createFolder(): String {
        val body = JSONObject()
            .put("name", FOLDER_NAME)
            .put("mimeType", FOLDER_MIME)
            .toString()
            .toRequestBody("application/json".toMediaType())
        call(bearer("$apiBase/files?fields=id").post(body)).use { response ->
            classify(response.code)
            val text = response.body?.string().orEmpty()
            return parseId(text) ?: throw SyncError.Retryable("folder create response missing id")
        }
    }

    override fun listFiles(): List<FileMeta> {
        findOrCreateFolder()
        val folder = folderId ?: throw SyncError.Retryable("sync folder id missing")
        val all = mutableListOf<FileMeta>()
        var pageToken: String? = null
        while (true) {
            val query = URLEncoder.encode("'$folder' in parents and trashed = false", "UTF-8")
            val url = StringBuilder("$apiBase/files?q=$query")
                .append("&spaces=drive")
                .append("&fields=files(id,name,trashed),nextPageToken")
                .append("&pageSize=1000")
                .append("&supportsAllDrives=false")
            // pageToken is opaque; always percent-encode it (Phase 2).
            pageToken?.let { url.append("&pageToken=${URLEncoder.encode(it, "UTF-8")}") }
            call(bearer(url.toString())).use { response ->
                // A 403 (drive.file scope) on LIST aborts the whole pass —
                // ABSENCE must never run on an unconfirmed listing (§23/Phase 2).
                classify(response.code)
                val body = response.body?.string().orEmpty()
                val (files, next) = parseFileListing(body)
                all += files
                pageToken = next
            }
            if (pageToken == null) break
        }
        return all
    }

    override fun getContent(fileId: String): ByteArray? {
        call(bearer("$apiBase/files/$fileId?alt=media")).use { response ->
            // fetch-404 during VALIDATE must be handled op-level BEFORE the
            // strict transport classification (which maps 404 to Rejected).
            if (response.code == 404) return null
            try {
                classify(response.code)
            } catch (e: SyncError) {
                // 403 drive.file scope: drop the file this pass (§23).
                if (e is SyncError.Fatal) return null
                throw e
            }
            return response.body?.bytes()
        }
    }

    override fun createFile(name: String, record: WireRecord): String {
        findOrCreateFolder()
        val folder = folderId ?: throw SyncError.Retryable("sync folder id missing")
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folder))
            .toString()
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", null, metadata.toRequestBody("application/json".toMediaType()))
            .addFormDataPart(
                "file",
                name,
                record.toJson().toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        call(bearer("$uploadBase/files?uploadType=multipart&fields=id").post(multipart)).use { response ->
            classify(response.code)
            val body = response.body?.string().orEmpty()
            return parseId(body) ?: throw SyncError.Retryable("create response missing id")
        }
    }

    override fun updateContent(fileId: String, record: WireRecord) {
        val body = record.toJson().toByteArray(Charsets.UTF_8)
            .toRequestBody("application/octet-stream".toMediaType())
        call(bearer("$uploadBase/files/$fileId?uploadType=media").patch(body)).use { response ->
            // A 404 means the remote file is already gone — the tombstone is
            // already satisfied, an idempotent success (§23 / Phase 1). Check
            // before the strict transport classification.
            if (response.code == 404) return
            classify(response.code)
        }
    }

    private fun parseId(json: String): String? = runCatching {
        JSONObject(json).optString("id", "").ifEmpty { null }
    }.getOrNull()

    /**
     * Parse a `files(id,name,trashed)` listing page. A partial/corrupt page is
     * a failure — never a silently-empty result — so the caller aborts the
     * pass before any mutation (§23 / Phase 2).
     */
    internal fun parseFileListing(json: String): Pair<List<FileMeta>, String?> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw SyncError.Rejected("corrupt listing response")
        }
        val next = if (root.has("nextPageToken") && !root.isNull("nextPageToken")) {
            root.optString("nextPageToken").ifEmpty { null }
        } else {
            null
        }
        val array = root.optJSONArray("files")
            ?: throw SyncError.Rejected("listing response missing files")
        val files = ArrayList<FileMeta>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
                ?: throw SyncError.Rejected("partial listing response")
            val id = item.optString("id", "")
                .ifEmpty { throw SyncError.Rejected("partial listing response") }
            val name = item.optString("name", "")
                .ifEmpty { throw SyncError.Rejected("partial listing response") }
            files.add(FileMeta(fileId = id, name = name))
        }
        return files to next
    }

    private companion object {
        const val FOLDER_NAME = "Fluence Transcribe"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val API_BASE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
    }
}
