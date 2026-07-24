package com.dl.m3u8recorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dl.m3u8recorder.manager.DownloadManager
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.record.LiveStreamRecorder
import com.dl.m3u8recorder.ui.TaskActivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap // 导入 ConcurrentHashMap

class LiveRecordingService : Service() {

    private val TAG = "LiveRecordingService"
    private val SERVICE_NOTIFICATION_ID = 101
    private val NOTIFICATION_CHANNEL_ID = "live_recording_silent_channel"
    private val NOTIFICATION_CHANNEL_NAME = "后台录制"

    private lateinit var liveStreamRecorder: LiveStreamRecorder
    // 关键改变：服务现在维护一个所有活跃直播任务的映射
    private val activeLiveRecordingTasks = ConcurrentHashMap<String, DownloadTask>()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 唯一的监听器实例，用于服务本身接收 DownloadManager 的更新
    private val serviceTaskListener = object : DownloadManager.TaskListener {
        override fun onTaskUpdated(task: DownloadTask) {
            // 服务端监听器只关注直播任务完成或取消时，服务自身是否需要停止
            // 注意：这里监听的是 DownloadManager 更新后的任务状态
            if (task.isLive) {
                if (task.progress == 100 || task.isCancelled || task.isPaused) { // 暂停也视为一种结束状态
                    Log.d(TAG, "服务监听器：直播任务 ${task.id} 状态更新：完成/取消/暂停。")
                    if (activeLiveRecordingTasks.containsKey(task.id)) {
                        activeLiveRecordingTasks.remove(task.id)
                        Log.d(TAG, "任务 ${task.id} 从活跃列表中移除。当前活跃任务数: ${activeLiveRecordingTasks.size}")
                    }
                    // 如果所有直播任务都已完成或停止，则停止服务
                    if (activeLiveRecordingTasks.isEmpty()) {
                        Log.d(TAG, "所有直播任务已完成/停止，停止 LiveRecordingService。")
                        stopForeground(true) // 移除通知
                        stopSelf()
                    }
                }
            }
        }

        override fun onQueueChanged(tasks: List<DownloadTask>) {
            Log.d(TAG, "LiveRecordingService received onQueueChanged. Total tasks: ${tasks.size}")
            // 在此服务中，我们不依赖 onQueueChanged 来管理 activeLiveRecordingTasks，
            // 主要通过 onStartCommand 添加和 onTaskUpdated 移除。
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LiveRecordingService onCreate")
        DownloadManager.init(applicationContext)
        liveStreamRecorder = DownloadManager.liveStreamRecorder
        // 在服务创建时将自己注册为监听器
        DownloadManager.addListener(serviceTaskListener)

        // 创建通知渠道 (在 Android O 及以上版本必须)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "LiveRecordingService onStartCommand, action: ${intent?.action}")

        val action = intent?.action
        when (action) {
            ACTION_START_RECORDING -> {
                val task = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DOWNLOAD_TASK, DownloadTask::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DOWNLOAD_TASK)
                }
                if (task != null) {
                    // 如果任务已在活跃列表中，检查是否需要重新启动
                    if (activeLiveRecordingTasks.containsKey(task.id)) {
                        val existingTask = activeLiveRecordingTasks[task.id]
                        // 如果任务已完成或已取消，允许重新启动
                        if (existingTask?.progress == 100 || existingTask?.isCancelled == true) {
                            Log.d(TAG, "任务 ${task.id} 已完成或已取消，重新启动。")
                            activeLiveRecordingTasks[task.id] = task
                            startForeground(SERVICE_NOTIFICATION_ID, createSilentNotification())
                            liveStreamRecorder.startRecording(task)
                        } else {
                            Log.w(TAG, "任务 ${task.id} 已经在活跃列表中且正在运行，不重复启动。")
                        }
                    } else {
                        activeLiveRecordingTasks[task.id] = task
                        startForeground(SERVICE_NOTIFICATION_ID, createSilentNotification())
                        liveStreamRecorder.startRecording(task)
                        Log.d(TAG, "启动录制任务: ${task.id}")
                    }
                } else {
                    Log.e(TAG, "未收到下载任务，无法启动录制服务。")
                    // 如果没有任务且没有其他活跃任务，可以考虑停止自身
                    if (activeLiveRecordingTasks.isEmpty()) {
                        stopSelf()
                    }
                }
            }
            ACTION_STOP_RECORDING -> {
                // 关键改变：停止指令现在需要知道停止哪个任务
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                Log.d(TAG, "接收到停止录制指令，任务ID: $taskId")
                if (taskId != null) {
                    val taskToStop = activeLiveRecordingTasks[taskId]
                    if (taskToStop != null) {
                        liveStreamRecorder.stopRecording(taskToStop.id) // 停止 FFmpeg 进程并触发转换
                        // 这里不需要从 activeLiveRecordingTasks 移除，因为 LiveStreamRecorder.stopRecording
                        // 会导致任务状态变为完成，然后 serviceTaskListener 会处理移除逻辑。
                        // 也可以选择在这里立即移除，取决于你希望的移除时机。
                        // activeLiveRecordingTasks.remove(taskId) // 立即移除
                    } else {
                        Log.w(TAG, "停止指令收到，但任务 $taskId 不在活跃列表中。")
                        // 如果任务不在活跃列表中，尝试从 DownloadManager 获取并更新其状态
                        DownloadManager.getTasks().find { it.id == taskId }?.let {
                            it.statusMessage = "已停止 (服务已重启或任务已完成)"
                            it.isCancelled = true
                            DownloadManager.notifyTaskUpdated(it)
                        }
                    }
                } else {
                    Log.w(TAG, "停止指令缺少任务ID。")
                }
                // 服务不立即停止，除非所有任务都完成了（由 serviceTaskListener 处理）
            }

            ACTION_PAUSE_RECORDING -> {
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                Log.d(TAG, "接收到暂停录制指令，任务ID: $taskId")
                if (taskId != null) {
                    // 尝试从活跃任务或DownloadManager中获取任务
                    val taskToPause = activeLiveRecordingTasks[taskId] ?: DownloadManager.getTasks().find { it.id == taskId }
                    if (taskToPause != null) {
                        liveStreamRecorder.pauseRecording(taskToPause.id)
                        // 状态更新应该由 LiveStreamRecorder 内部回调和 DownloadManager.notifyTaskUpdated 驱动
                        // 但为了即时反馈，这里可以先设置
                        taskToPause.isPaused = true
                        taskToPause.statusMessage = "已暂停"
                        DownloadManager.notifyTaskUpdated(taskToPause) // 通知 UI 状态更新
                    } else {
                        Log.w(TAG, "暂停指令收到，但任务 $taskId 不在活跃列表中或未找到。")
                    }
                }
            }
            ACTION_RESUME_RECORDING -> {
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                Log.d(TAG, "接收到恢复录制指令，任务ID: $taskId")
                if (taskId != null) {
                    // 尝试从活跃任务或DownloadManager中获取任务
                    val taskToResume = activeLiveRecordingTasks[taskId] ?: DownloadManager.getTasks().find { it.id == taskId }
                    if (taskToResume != null) {
                        liveStreamRecorder.resumeRecording(taskToResume)
                        // 状态更新
                        taskToResume.isPaused = false
                        taskToResume.statusMessage = "恢复中"
                        DownloadManager.notifyTaskUpdated(taskToResume) // 通知 UI 状态更新
                    } else {
                        Log.w(TAG, "恢复指令收到，但任务 $taskId 不在活跃列表中或未找到。")
                    }
                }
            }
            else -> Log.w(TAG, "未知服务动作: $action")
        }
        return START_STICKY // 服务应该保持运行，直到所有任务都停止
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "LiveRecordingService onDestroy")
        serviceScope.cancel()

        DownloadManager.removeListener(serviceTaskListener)

        // 关键：在服务销毁时，停止所有剩余的活跃直播任务
        // 遍历 activeLiveRecordingTasks 的副本，避免 ConcurrentModificationException
        for (task in activeLiveRecordingTasks.values.toList()) {
            if (task.progress < 100 && !task.isCancelled) {
                Log.d(TAG, "onDestroy: 任务 ${task.id} 未完成/未取消，强制停止录制。")
                liveStreamRecorder.stopRecording(task.id) // 确保 FFmpeg 进程停止并触发 MP4 转换
            } else {
                Log.d(TAG, "onDestroy: 任务 ${task.id} 已完成或已取消，无需额外停止。")
            }
        }
        activeLiveRecordingTasks.clear() // 清空列表
        Log.d(TAG, "LiveRecordingService fully destroyed.")
    }

    // 创建静默通知渠道
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN // 关键：最低优先级
            ).apply {
                description = "确保后台任务运行"
                setSound(null, null) // 无声音
                enableLights(false) // 无灯光
                enableVibration(false) // 无震动
                setShowBadge(false) // 不在应用图标上显示徽章
            }
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    // 创建静默通知
    private fun createSilentNotification(): Notification {
        val notificationIntent = Intent(this, TaskActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("后台录制正在运行") // 简单标题
            .setContentText("点击查看任务进度") // 简单文本
            .setSmallIcon(android.R.drawable.ic_media_play) // 保持一个图标，这是前台服务必须的
            .setContentIntent(pendingIntent)
            .setOngoing(true) // 持续显示
            .setSilent(true) // 关键：静音
            .setOnlyAlertOnce(true) // 关键：只通知一次，避免后续更新产生提示
            .build()
    }

    companion object {
        const val ACTION_START_RECORDING = "com.dl.m3u8recorder.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.dl.m3u8recorder.action.STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.dl.m3u8recorder.action.PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.dl.m3u8recorder.action.RESUME_RECORDING"
        const val EXTRA_DOWNLOAD_TASK = "extra_download_task"
        const val EXTRA_TASK_ID = "extra_task_id" // 新增：用于传递任务ID

        /**
         * 启动录制服务
         * @param context Context
         * @param task DownloadTask
         */
        fun startService(context: Context, task: DownloadTask) {
            val serviceIntent = Intent(context, LiveRecordingService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_DOWNLOAD_TASK, task)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        /**
         * 停止指定任务的录制。
         * 这将发送一个 Intent 到服务，由服务内部处理 FFmpeg 的停止。
         * @param context Context
         * @param taskId 要停止的任务的ID
         */
        fun stopService(context: Context, taskId: String) {
            val serviceIntent = Intent(context, LiveRecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
                putExtra(EXTRA_TASK_ID, taskId) // 传递任务ID
            }
            context.startService(serviceIntent) // 仍然使用 startService 来触发 onStartCommand
        }

        /**
         * 向服务发送暂停指令
         * @param context Context
         * @param taskId 要暂停的任务的ID
         */
        fun pauseService(context: Context, taskId: String) {
            val serviceIntent = Intent(context, LiveRecordingService::class.java).apply {
                action = ACTION_PAUSE_RECORDING
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(serviceIntent)
        }

        /**
         * 向服务发送恢复指令
         * @param context Context
         * @param taskId 要恢复的任务的ID
         */
        fun resumeService(context: Context, taskId: String) {
            val serviceIntent = Intent(context, LiveRecordingService::class.java).apply {
                action = ACTION_RESUME_RECORDING
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(serviceIntent)
        }
    }
}