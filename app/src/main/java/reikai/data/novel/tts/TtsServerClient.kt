package reikai.data.novel.tts

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Talks to the user's PC render server, per the contract in the pre-rendered audio plan (version
 * [CONTRACT]). Everything the server sends is untrusted: [TtsPackImporter] checks a pack before any of it
 * is kept. The token is read per call and never logged.
 */
@Inject
@SingleIn(AppScope::class)
class TtsServerClient(
    network: NetworkHelper,
    private val preferences: NovelPreferences,
) {

    // A server on the local network answers fast or not at all, so a dead one is noticed in seconds.
    private val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(TIMEOUT.toJavaDuration())
        .readTimeout(READ_TIMEOUT.toJavaDuration())
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun info(): ServerInfo = withIOContext {
        val info = call(get("/v1/info"), authorized = false) { json.decodeFromString<ServerInfo>(it.body.string()) }
        if (info.contract != CONTRACT) throw TtsServerException(TtsServerFailure.UNSUPPORTED)
        info
    }

    /** Trades the code the server shows for a token, and keeps it. */
    suspend fun pair(code: String, device: String) = withIOContext {
        val body = json.encodeToString(PairRequest(code, device)).toRequestBody(JSON)
        val reply = call(post("/v1/pair", body), authorized = false) {
            json.decodeFromString<PairReply>(it.body.string())
        }
        preferences.readerTtsServerToken().set(reply.token)
    }

    suspend fun submit(job: JobRequest): JobStatus = withIOContext {
        val body = json.encodeToString(job).toRequestBody(JSON)
        call(post("/v1/jobs", body)) { json.decodeFromString<JobStatus>(it.body.string()) }
    }

    suspend fun status(jobId: String): JobStatus = withIOContext {
        call(get("/v1/jobs/$jobId")) { json.decodeFromString<JobStatus>(it.body.string()) }
    }

    suspend fun cancel(jobId: String) {
        withIOContext { call(Request.Builder().url(url("/v1/jobs/$jobId")).delete().build()) {} }
    }

    /** Downloads chapter [index] of [jobId] into [into]; false when nothing in it is finished yet. */
    suspend fun downloadPack(jobId: String, index: Int, into: File): Boolean = withIOContext {
        call(get("/v1/jobs/$jobId/chapters/$index.ttspack")) { response ->
            if (response.code == NO_CONTENT) return@call false
            val length = response.body.contentLength()
            if (length > TtsPackImporter.MAX_PACK_BYTES) throw TtsServerException(TtsServerFailure.UNREACHABLE)
            into.outputStream().use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        // A server that lied about its length, or sent none, is cut off at the same cap.
                        if (total >
                            TtsPackImporter.MAX_PACK_BYTES
                        ) {
                            throw TtsServerException(TtsServerFailure.UNREACHABLE)
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            true
        }
    }

    private fun get(path: String) = Request.Builder().url(url(path)).get().build()

    private fun post(path: String, body: okhttp3.RequestBody) = Request.Builder().url(url(path)).post(body).build()

    private fun url(path: String): String {
        val base = preferences.readerTtsServerUrl().get().trim().trimEnd('/')
        if (base.isEmpty()) throw TtsServerException(TtsServerFailure.UNREACHABLE)
        return if (base.startsWith("http://") || base.startsWith("https://")) "$base$path" else "http://$base$path"
    }

    private fun <T> call(request: Request, authorized: Boolean = true, read: (Response) -> T): T {
        val sent = if (authorized) {
            request.newBuilder().header("Authorization", "Bearer ${preferences.readerTtsServerToken().get()}").build()
        } else {
            request
        }
        val response = try {
            client.newCall(sent).execute()
        } catch (e: IOException) {
            throw TtsServerException(TtsServerFailure.UNREACHABLE, e)
        }
        return response.use {
            when {
                it.code == UNAUTHORIZED -> throw TtsServerException(TtsServerFailure.UNAUTHORIZED)
                it.code == BAD_REQUEST -> throw TtsServerException(TtsServerFailure.UNSUPPORTED)
                !it.isSuccessful -> throw TtsServerException(TtsServerFailure.UNREACHABLE)
                else -> read(it)
            }
        }
    }

    companion object {
        const val CONTRACT = 1
        private val JSON = "application/json".toMediaType()
        private val TIMEOUT = 5.seconds
        private val READ_TIMEOUT = 60.seconds
        private const val BUFFER = 64 * 1024
        private const val NO_CONTENT = 204
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
    }
}

enum class TtsServerFailure { UNREACHABLE, UNAUTHORIZED, UNSUPPORTED }

class TtsServerException(val failure: TtsServerFailure, cause: Throwable? = null) : Exception(failure.name, cause)

@Serializable
data class ServerInfo(
    val contract: Int,
    val name: String = "",
    val models: List<ServerModel> = emptyList(),
)

@Serializable
data class ServerModel(val id: String, val voices: List<ServerVoice> = emptyList())

@Serializable
data class ServerVoice(val id: String, val label: String = "", val language: String = "")

@Serializable
private data class PairRequest(val code: String, val device: String)

@Serializable
private data class PairReply(val token: String)

@Serializable
data class JobRequest(
    val book: JobBook,
    val engine: JobEngine,
    val splitter: Int,
    val priority: String,
    val chapters: List<JobChapter>,
)

@Serializable
data class JobBook(val key: String, val source: String, val url: String, val title: String)

@Serializable
data class JobEngine(val model: String, val voice: String)

@Serializable
data class JobChapter(val url: String, val title: String, val paragraphs: List<JobParagraph>)

@Serializable
data class JobParagraph(val index: Int, val key: String, val text: String)

@Serializable
data class JobStatus(
    val job: String,
    val chapters: List<ChapterStatus> = emptyList(),
)

@Serializable
data class ChapterStatus(
    val url: String = "",
    val state: String,
    val done: Int = 0,
    val total: Int = 0,
) {
    val isFinished: Boolean get() = state == "done" || state == "failed" || state == "cancelled"
}

/** The job priorities the contract names. */
object JobPriority {
    const val WINDOW = "window"
    const val MANUAL = "manual"
}
