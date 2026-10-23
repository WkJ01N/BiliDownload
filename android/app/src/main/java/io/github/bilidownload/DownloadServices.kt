package io.github.bilidownload

import android.app.*
import android.app.job.*
import android.content.*
import android.os.Build
import android.os.PersistableBundle
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

object DownloadActions {
    fun schedule(context: Context, entry: DownloadEntry) {
        BiliApplication.store.put(entry.copy(state = "Queued", message = "等待下载", failureKind = null))
        if(Build.VERSION.SDK_INT >= 34) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            var jobId = entry.id.hashCode() and Int.MAX_VALUE
            while(scheduler.allPendingJobs.any { it.id == jobId && it.extras.getString("entry") != entry.id }) jobId++
            val info = JobInfo.Builder(jobId, ComponentName(context, DownloadJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setUserInitiated(true)
                .setExtras(PersistableBundle().apply { putString("entry", entry.id) }).build()
            if(scheduler.schedule(info) != JobScheduler.RESULT_SUCCESS) throw BiliException("系统未接受下载任务，请保持应用可见后重试。")
        } else context.startForegroundService(Intent(context, DownloadForegroundService::class.java).putExtra("entry", entry.id))
    }
    suspend fun pause(context: Context, id: String, canceled: Boolean = false) {
        if(Build.VERSION.SDK_INT >= 34) context.getSystemService(JobScheduler::class.java).allPendingJobs
            .filter { it.extras.getString("entry") == id }.forEach { context.getSystemService(JobScheduler::class.java).cancel(it.id) }
        BiliApplication.engine.pause(id, canceled)
    }
    fun notification(context: Context, entry: DownloadEntry): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getBroadcast(context, entry.id.hashCode(), Intent(context, PauseReceiver::class.java).putExtra("entry", entry.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, "downloads").setSmallIcon(io.github.bilidownload.R.drawable.ic_download)
            .setContentTitle(entry.item.title).setContentText(entry.message).setContentIntent(open).setOnlyAlertOnce(true)
            .setProgress(100, (entry.progress * 100).toInt(), false).setOngoing(entry.state !in listOf("Completed", "Failed", "Paused", "Canceled"))
            .addAction(0, "暂停", pause).build()
    }
}
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("entry") ?: return; val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { DownloadActions.pause(context, id) } finally { pending.finish() } }
    }
}
class DownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString("entry") ?: return false
        val entry = BiliApplication.store.get(id) ?: return false
        if(Build.VERSION.SDK_INT >= 34) setNotification(params, params.jobId, DownloadActions.notification(this, entry), JOB_END_NOTIFICATION_POLICY_REMOVE)
        jobs[params.jobId] = scope.launch {
            try { BiliApplication.engine.run(id) { if(Build.VERSION.SDK_INT >= 34) setNotification(params, params.jobId, DownloadActions.notification(this@DownloadJobService, it), JOB_END_NOTIFICATION_POLICY_REMOVE) } }
            finally { jobs.remove(params.jobId); jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
class DownloadForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("entry") ?: return START_NOT_STICKY
        val entry = BiliApplication.store.get(id) ?: return START_NOT_STICKY
        startForeground(100, DownloadActions.notification(this, entry))
        if(jobs.containsKey(id)) return START_NOT_STICKY
        jobs[id] = scope.launch {
            try { BiliApplication.engine.run(id) { getSystemService(NotificationManager::class.java).notify(100, DownloadActions.notification(this@DownloadForegroundService, it)) } }
            finally { jobs.remove(id); if(jobs.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
class ClipboardTileService : TileService() {
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // API 29–33 has no PendingIntent overload; the branch below is version gated.
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).setAction("io.github.bilidownload.PASTE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 12, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}
