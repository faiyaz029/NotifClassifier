package com.notifclassifier.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.notifclassifier.MainActivity
import com.notifclassifier.R
import com.notifclassifier.model.ClassifyRequest
import com.notifclassifier.model.FeedbackRequest
import com.notifclassifier.model.NotificationItem
import com.notifclassifier.model.PipelineStep
import com.notifclassifier.network.ApiClient
import com.notifclassifier.network.ApiException
import java.util.concurrent.Executors

/**
 * Native Android NotificationListenerService.
 *
 * Runs as a foreground service so MIUI/Android's battery optimization
 * cannot kill it silently. Uses a single-thread executor for all network
 * calls — no coroutine library required.
 *
 * Communication with MainActivity uses a static listener pattern
 * (safe because MainActivity is always the only consumer).
 */
class NotifListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListenerService"
        private const val FOREGROUND_CHANNEL_ID = "notif_classifier_fg"
        private const val FOREGROUND_NOTIF_ID = 1001

        /**
         * Packages to ignore — system noise that is never useful to classify.
         */
        private val IGNORED_PACKAGES = setOf(
            "com.notifclassifier",          // ourselves
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.miui.securitycenter",
            "com.miui.powerkeeper",
            "com.xiaomi.xmsf",
            "com.miui.notification"
        )

        /**
         * Callback registered by MainActivity to receive new notifications
         * and pipeline log updates on the main thread.
         */
        var notificationListener: NotificationEventListener? = null
    }

    // Single background thread for all HTTP calls
    private val executor = Executors.newSingleThreadExecutor()

    // ─── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        startForegroundSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: system will restart the service if it is killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        Log.d(TAG, "Service destroyed")
        // Schedule a restart via BootReceiver broadcast so MIUI can't keep it dead
        val restartIntent = Intent(this, BootReceiver::class.java).apply {
            action = "com.notifclassifier.RESTART_SERVICE"
        }
        sendBroadcast(restartIntent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    // ─── NotificationListenerService callbacks ────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName in IGNORED_PACKAGES) return
        // Skip ongoing notifications (e.g. media players, navigation)
        if (sbn.isOngoing) return

        val extras = sbn.notification?.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text: String = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return

        val pm = applicationContext.packageManager
        val appLabel = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val item = NotificationItem(
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            postTime = sbn.postTime
        )

        Log.d(TAG, "Notification intercepted: $appLabel | $title")

        // Step 1 — Notify UI
        addPipelineStep(item, 1, "📱 Received from $appLabel — \"$title\"")
        notificationListener?.onNotificationReceived(item)

        // Step 2 → 4 — Classify in background
        classifyInBackground(item)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed for this use case
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected to notification service")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Listener disconnected — requesting rebind")
        // Request system to reconnect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotifListenerService::class.java))
        }
    }

    // ─── Classification pipeline ───────────────────────────────────────────────

    private fun classifyInBackground(item: NotificationItem) {
        executor.submit {
            try {
                // Step 2
                addPipelineStep(item, 2, "🌐 Sending to server...")
                notificationListener?.onPipelineUpdated(item)

                val request = ClassifyRequest(
                    app_name = item.packageName,
                    user_name = item.title,
                    content = item.text
                )
                val response = ApiClient.classify(request)

                // Step 3
                addPipelineStep(item, 3, "✅ HTTP 200 received")
                notificationListener?.onPipelineUpdated(item)

                // Step 4
                item.decisionCode = response.decision_code
                item.decisionLabel = response.decision_label
                item.confidence = response.confidence
                val pct = (response.confidence * 100).toInt()
                addPipelineStep(
                    item, 4,
                    "🏷️ Decision: ${response.decision_label} (${pct}% confident)"
                )
                notificationListener?.onClassificationResult(item)

            } catch (e: ApiException) {
                addPipelineStep(
                    item, 3,
                    "❌ Server error HTTP ${e.httpCode}: ${e.message}",
                    isError = true
                )
                notificationListener?.onPipelineUpdated(item)
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("timeout", ignoreCase = true) == true ->
                        "❌ Request timed out after 15s"
                    e.message?.contains("Unable to resolve host") == true ->
                        "❌ Server unreachable — check internet/URL"
                    else -> "❌ Error: ${e.message}"
                }
                addPipelineStep(item, 3, msg, isError = true)
                notificationListener?.onPipelineUpdated(item)
            }
        }
    }

    /**
     * Called from MainActivity after user selects a star rating.
     */
    fun submitFeedback(item: NotificationItem, rating: Int) {
        executor.submit {
            try {
                val request = FeedbackRequest(
                    app_name = item.packageName,
                    user_name = item.title,
                    content = item.text,
                    decision_code = item.decisionCode ?: return@submit,
                    user_rating = rating
                )
                ApiClient.feedback(request)
                notificationListener?.onFeedbackResult(item.id, success = true)
            } catch (e: Exception) {
                Log.e(TAG, "Feedback submission failed: ${e.message}")
                notificationListener?.onFeedbackResult(item.id, success = false)
            }
        }
    }

    // ─── Foreground service notification ──────────────────────────────────────

    private fun startForegroundSelf() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Notification Classifier Service",
                NotificationManager.IMPORTANCE_MIN   // Silent, no pop-up
            ).apply {
                description = "Keeps the classifier running in the background"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("Notification Classifier")
            .setContentText("Monitoring notifications…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(FOREGROUND_NOTIF_ID, notification)
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun addPipelineStep(
        item: NotificationItem,
        step: Int,
        message: String,
        isError: Boolean = false
    ) {
        item.pipelineLog.add(PipelineStep(step, message, isError))
    }
}

/**
 * Callback interface so MainActivity can react to service events
 * on whatever thread it chooses (it will post to main thread itself).
 */
interface NotificationEventListener {
    fun onNotificationReceived(item: NotificationItem)
    fun onPipelineUpdated(item: NotificationItem)
    fun onClassificationResult(item: NotificationItem)
    fun onFeedbackResult(itemId: String, success: Boolean)
}
