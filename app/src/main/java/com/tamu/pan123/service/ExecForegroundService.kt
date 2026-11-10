package com.tamu.pan123.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tamu.pan123.MainActivity
import com.tamu.pan123.data.exec.ExecutionEngine
import com.tamu.pan123.data.exec.TaskRun
import com.tamu.pan123.data.exec.TaskStore
import com.tamu.pan123.data.scrape.PlanStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 前台服务：执行刮削计划，切到后台也不会被立刻杀掉。
 */
class ExecForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var notifyJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("准备执行…", 0, 0))
        notifyJob = scope.launch {
            ExecutionEngine.live.collectLatest { run ->
                if (run == null) return@collectLatest
                val total = run.actionIds.size.coerceAtLeast(1)
                val done = run.ok + run.fail + run.skipped
                val title = when (run.status) {
                    TaskRun.STATUS_PAUSED -> "已暂停"
                    TaskRun.STATUS_STOPPING -> "正在结束…"
                    TaskRun.STATUS_DONE -> "任务完成"
                    else -> "正在执行"
                }
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification("$title · ${run.message}", done, total))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> ExecutionEngine.pause()
            ACTION_RESUME -> ExecutionEngine.resume()
            ACTION_STOP -> {
                ExecutionEngine.stop()
            }
            ACTION_START, null -> {
                if (job?.isActive == true) {
                    android.widget.Toast.makeText(this, "已有任务在执行，请到「任务」页查看", android.widget.Toast.LENGTH_SHORT).show()
                    return START_STICKY
                }
                val ids = intent?.getStringArrayListExtra(EXTRA_ACTION_IDS)
                val resume = intent?.getBooleanExtra(EXTRA_RESUME, false) ?: false
                job = scope.launch {
                    try {
                        val plan = PlanStore.load()
                        ExecutionEngine.run(plan, ids, resumeExisting = resume)
                    } catch (e: Exception) {
                        val msg = "执行失败：${e.message ?: e.javaClass.simpleName}"
                        com.tamu.pan123.data.net.NetLog.error("exec", msg)
                        val cur = TaskStore.load()
                        if (cur != null && cur.status != TaskRun.STATUS_DONE) {
                            val next = cur.copy(status = TaskRun.STATUS_DONE, message = msg, finishedAt = System.currentTimeMillis())
                            TaskStore.save(next)
                            ExecutionEngine.reportLive(next)
                        } else {
                            ExecutionEngine.reportStartError(msg)
                        }
                    } finally {
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        notifyJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "刮削任务", NotificationManager.IMPORTANCE_LOW)
        ch.description = "显示改名/上传进度"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(content: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, ExecForegroundService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resume = PendingIntent.getService(
            this, 2, Intent(this, ExecForegroundService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, ExecForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(com.tamu.pan123.AppInfo.NAME)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done.coerceAtMost(total), total == 0)
            .addAction(0, "暂停", pause)
            .addAction(0, "继续", resume)
            .addAction(0, "结束", stop)
            .build()
    }

    companion object {
        const val CHANNEL = "exec"
        const val NOTIF_ID = 42
        const val ACTION_START = "com.tamu.pan123.EXEC_START"
        const val ACTION_PAUSE = "com.tamu.pan123.EXEC_PAUSE"
        const val ACTION_RESUME = "com.tamu.pan123.EXEC_RESUME"
        const val ACTION_STOP = "com.tamu.pan123.EXEC_STOP"
        const val EXTRA_ACTION_IDS = "action_ids"
        const val EXTRA_RESUME = "resume"

        fun start(context: Context, actionIds: List<String>? = null, resume: Boolean = false) {
            val i = Intent(context, ExecForegroundService::class.java).setAction(ACTION_START)
            if (actionIds != null) i.putStringArrayListExtra(EXTRA_ACTION_IDS, ArrayList(actionIds))
            i.putExtra(EXTRA_RESUME, resume)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {
                ExecutionEngine.reportStartError("无法启动后台执行服务：${e.message ?: e.javaClass.simpleName}")
            }
        }

        fun pause(context: Context) {
            context.startService(Intent(context, ExecForegroundService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, ExecForegroundService::class.java).setAction(ACTION_RESUME))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ExecForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
