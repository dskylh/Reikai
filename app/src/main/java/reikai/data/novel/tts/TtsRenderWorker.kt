package reikai.data.novel.tts

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.tts.TtsAudioRepository
import reikai.domain.novel.tts.TtsAudioSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.util.concurrent.TimeUnit

/**
 * Works through the read-aloud render queue one chapter at a time, in the foreground so a long batch is
 * not killed when the screen goes off. With the PC as the source it runs only on an unmetered network; a
 * server that cannot be reached leaves the chapter queued and retries later.
 */
class TtsRenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph: AppGraph = context.metroGraph()

    init {
        graph.inject(this)
    }

    @Inject private lateinit var store: TtsAudioRepository

    @Inject private lateinit var preferences: NovelPreferences

    @Inject private lateinit var rendererFactory: () -> TtsChapterRenderer

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("")

    override suspend fun doWork(): Result {
        setForegroundSafely()
        val renderer = rendererFactory()
        try {
            while (true) {
                val (chapterId, priority) = store.nextQueued() ?: break
                setForegroundSafely(foregroundInfo(renderer.nameOf(chapterId)))
                val rendered = try {
                    renderer.render(chapterId, priority) { store.isQueued(chapterId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TtsServerException) {
                    logcat(LogPriority.WARN, e) { "PC render server failed: ${e.failure}" }
                    return Result.retry()
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Pre-rendering chapter $chapterId failed" }
                    false
                }
                if (store.isQueued(chapterId)) store.finish(chapterId, rendered)
            }
            return Result.success()
        } finally {
            renderer.shutdown()
        }
    }

    // A refusal (the app in the background on Android 12+) leaves the queue running without the notice.
    private suspend fun setForegroundSafely(info: ForegroundInfo) {
        runCatching { setForeground(info) }
    }

    private fun foregroundInfo(chapterName: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_NOVEL_TTS)
            .setSmallIcon(R.drawable.ic_reikai)
            .setContentTitle(applicationContext.stringResource(MR.strings.tts_pre_rendering))
            .setContentText(chapterName)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "TtsRender"

        // Beside the read-aloud notification's -801, on the channel it already owns.
        private const val NOTIFICATION_ID = -802

        /** Starts the queue, or leaves a running one to pick up what was just queued. */
        fun start(context: Context, source: TtsAudioSource) {
            val network = if (source == TtsAudioSource.PC) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED
            val request = OneTimeWorkRequestBuilder<TtsRenderWorker>()
                .addTag(TAG)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }
    }
}
