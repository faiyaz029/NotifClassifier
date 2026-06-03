package com.notifclassifier

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notifclassifier.model.FeedbackState
import com.notifclassifier.model.NotificationItem
import com.notifclassifier.model.PipelineStep
import com.notifclassifier.service.NotifListenerService
import com.notifclassifier.service.NotificationEventListener
import com.notifclassifier.ui.NotificationRepository
import java.util.Date

/**
 * Single-Activity app.
 *
 * Shows either:
 *   A) Permission screen  — if NotificationListenerService access not granted
 *   B) Main feed screen   — scrollable list of intercepted notifications
 *
 * Uses a Handler (main thread) for all UI updates from the service callbacks.
 */
class MainActivity : AppCompatActivity(), NotificationEventListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Views — Permission screen
    private lateinit var permissionScreen: View
    private lateinit var permStatusIcon: TextView
    private lateinit var permStatusText: TextView
    private lateinit var btnOpenSettings: Button

    // Views — Main screen
    private lateinit var mainScreen: View
    private lateinit var rvNotifications: RecyclerView
    private lateinit var tvPipelineLog: TextView
    private lateinit var pipelinePanel: View
    private lateinit var btnTogglePipeline: Button
    private lateinit var btnExportCsv: Button
    private lateinit var tvEmpty: TextView

    private lateinit var adapter: NotificationAdapter
    private var isPipelineVisible = true

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupRecyclerView()
        setupClickListeners()

        // Register as the service's event listener
        NotifListenerService.notificationListener = this
    }

    override fun onResume() {
        super.onResume()
        // Re-check permission every time user returns from Settings
        updatePermissionState()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only clear if we're actually finishing, not just rotating
        if (!isChangingConfigurations) {
            NotifListenerService.notificationListener = null
        }
    }

    // ─── Permission check ──────────────────────────────────────────────────────

    private fun updatePermissionState() {
        if (isNotificationListenerEnabled()) {
            showMainScreen()
            startListenerService()
        } else {
            showPermissionScreen()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, NotifListenerService::class.java)
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun startListenerService() {
        val intent = Intent(this, NotifListenerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ─── Screen switching ──────────────────────────────────────────────────────

    private fun showPermissionScreen() {
        permissionScreen.visibility = View.VISIBLE
        mainScreen.visibility = View.GONE

        val granted = isNotificationListenerEnabled()
        if (granted) {
            permStatusIcon.text = "✅"
            permStatusText.text = "Permission granted"
            permStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            btnOpenSettings.text = "Go to App"
            btnOpenSettings.setOnClickListener { showMainScreen() }
        } else {
            permStatusIcon.text = "🔴"
            permStatusText.text = "Notification access not granted"
            permStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            btnOpenSettings.text = "Open Notification Settings"
            btnOpenSettings.setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }

    private fun showMainScreen() {
        permissionScreen.visibility = View.GONE
        mainScreen.visibility = View.VISIBLE
        refreshList()
    }

    // ─── Setup helpers ─────────────────────────────────────────────────────────

    private fun bindViews() {
        permissionScreen = findViewById(R.id.permissionScreen)
        permStatusIcon = findViewById(R.id.permStatusIcon)
        permStatusText = findViewById(R.id.permStatusText)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)

        mainScreen = findViewById(R.id.mainScreen)
        rvNotifications = findViewById(R.id.rvNotifications)
        tvPipelineLog = findViewById(R.id.tvPipelineLog)
        pipelinePanel = findViewById(R.id.pipelinePanel)
        btnTogglePipeline = findViewById(R.id.btnTogglePipeline)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        tvEmpty = findViewById(R.id.tvEmpty)
    }

    private fun setupRecyclerView() {
        adapter = NotificationAdapter { item, rating ->
            onStarRatingSelected(item, rating)
        }
        rvNotifications.layoutManager = LinearLayoutManager(this)
        rvNotifications.adapter = adapter
    }

    private fun setupClickListeners() {
        btnTogglePipeline.setOnClickListener {
            isPipelineVisible = !isPipelineVisible
            pipelinePanel.visibility = if (isPipelineVisible) View.VISIBLE else View.GONE
            btnTogglePipeline.text = if (isPipelineVisible) "Hide Pipeline Log ▲" else "Show Pipeline Log ▼"
        }

        btnExportCsv.setOnClickListener {
            val path = NotificationRepository.exportCsv(this)
            if (path != null) {
                Toast.makeText(this, "Exported to:\n$path", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── Star rating callback ──────────────────────────────────────────────────

    private fun onStarRatingSelected(item: NotificationItem, rating: Int) {
        item.selectedRating = rating
        item.feedbackState = FeedbackState.SUBMITTING
        adapter.notifyItemChanged(findItemPosition(item.id))

        // Service handles the network call; result comes back via onFeedbackResult
        val service = getListenerService()
        if (service != null) {
            service.submitFeedback(item, rating)
        } else {
            // Service not bound — call ApiClient directly on a background thread
            Thread {
                try {
                    com.notifclassifier.network.ApiClient.feedback(
                        com.notifclassifier.model.FeedbackRequest(
                            app_name = item.packageName,
                            user_name = item.title,
                            content = item.text,
                            decision_code = item.decisionCode ?: 1,
                            user_rating = rating
                        )
                    )
                    onFeedbackResult(item.id, success = true)
                } catch (e: Exception) {
                    onFeedbackResult(item.id, success = false)
                }
            }.start()
        }
    }

    private fun getListenerService(): NotifListenerService? {
        // NotificationListenerService can't be bound directly;
        // we communicate via the static listener pattern
        return null
    }

    // ─── NotificationEventListener callbacks (called from background thread) ───

    override fun onNotificationReceived(item: NotificationItem) {
        NotificationRepository.add(item)
        mainHandler.post {
            tvEmpty.visibility = View.GONE
            adapter.submitList(NotificationRepository.items.toMutableList())
            updatePipelineLog(item)
        }
    }

    override fun onPipelineUpdated(item: NotificationItem) {
        mainHandler.post { updatePipelineLog(item) }
    }

    override fun onClassificationResult(item: NotificationItem) {
        item.feedbackState = FeedbackState.PENDING_RATING
        mainHandler.post {
            val pos = findItemPosition(item.id)
            if (pos >= 0) adapter.notifyItemChanged(pos)
            updatePipelineLog(item)
        }
    }

    override fun onFeedbackResult(itemId: String, success: Boolean) {
        val item = NotificationRepository.findById(itemId) ?: return
        item.feedbackState = if (success) FeedbackState.SUBMITTED else FeedbackState.ERROR
        mainHandler.post {
            val pos = findItemPosition(item.id)
            if (pos >= 0) adapter.notifyItemChanged(pos)
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun refreshList() {
        val items = NotificationRepository.items
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(items.toMutableList())
    }

    private fun findItemPosition(itemId: String): Int {
        return NotificationRepository.items.indexOfFirst { it.id == itemId }
    }

    private fun updatePipelineLog(item: NotificationItem) {
        val sb = StringBuilder()
        item.pipelineLog.forEach { step ->
            sb.appendLine("Step ${step.stepNumber}: ${step.message}")
        }
        tvPipelineLog.text = sb.toString().trimEnd()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RecyclerView Adapter
// ═══════════════════════════════════════════════════════════════════════════════

class NotificationAdapter(
    private val onRatingSelected: (NotificationItem, Int) -> Unit
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    private val items = mutableListOf<NotificationItem>()

    fun submitList(newItems: MutableList<NotificationItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvAppLabel: TextView = view.findViewById(R.id.tvAppLabel)
        private val tvSender: TextView = view.findViewById(R.id.tvSender)
        private val tvBody: TextView = view.findViewById(R.id.tvBody)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val tvDecisionBadge: TextView = view.findViewById(R.id.tvDecisionBadge)
        private val tvConfidence: TextView = view.findViewById(R.id.tvConfidence)
        private val ratingBar: LinearLayout = view.findViewById(R.id.ratingBar)
        private val tvFeedbackStatus: TextView = view.findViewById(R.id.tvFeedbackStatus)
        private val stars = Array(5) { view.findViewById<TextView>(view.resources.getIdentifier("star${it + 1}", "id", view.context.packageName)) }

        fun bind(item: NotificationItem) {
            val ctx = itemView.context

            tvAppLabel.text = item.appLabel
            tvSender.text = item.title
            tvBody.text = item.text
            tvTime.text = DateFormat.format("HH:mm:ss", Date(item.postTime))

            // Decision badge
            when (item.decisionCode) {
                null -> {
                    tvDecisionBadge.text = "Classifying…"
                    tvDecisionBadge.setBackgroundColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
                    tvConfidence.visibility = View.GONE
                }
                1 -> {
                    tvDecisionBadge.text = "Notify Instantly"
                    tvDecisionBadge.setBackgroundColor(ContextCompat.getColor(ctx, android.R.color.holo_green_dark))
                    tvConfidence.visibility = View.VISIBLE
                }
                2 -> {
                    tvDecisionBadge.text = "Notify Later"
                    tvDecisionBadge.setBackgroundColor(0xFFFF8800.toInt()) // Orange
                    tvConfidence.visibility = View.VISIBLE
                }
                3 -> {
                    tvDecisionBadge.text = "Block"
                    tvDecisionBadge.setBackgroundColor(ContextCompat.getColor(ctx, android.R.color.holo_red_dark))
                    tvConfidence.visibility = View.VISIBLE
                }
                else -> {
                    tvDecisionBadge.text = "Unknown"
                    tvDecisionBadge.setBackgroundColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
                    tvConfidence.visibility = View.GONE
                }
            }

            item.confidence?.let { c ->
                tvConfidence.text = "${(c * 100).toInt()}% confident"
            }

            // Star rating / feedback status
            when (item.feedbackState) {
                FeedbackState.AWAITING_DECISION -> {
                    ratingBar.visibility = View.GONE
                    tvFeedbackStatus.visibility = View.GONE
                }
                FeedbackState.PENDING_RATING, FeedbackState.ERROR -> {
                    ratingBar.visibility = View.VISIBLE
                    tvFeedbackStatus.visibility = if (item.feedbackState == FeedbackState.ERROR) View.VISIBLE else View.GONE
                    if (item.feedbackState == FeedbackState.ERROR) {
                        tvFeedbackStatus.text = "⚠ Submission failed — tap a star to retry"
                        tvFeedbackStatus.setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_red_light))
                    }
                    // Render stars
                    for (i in 0..4) {
                        val star = stars[i]
                        star.text = if (i < item.selectedRating) "★" else "☆"
                        star.setTextColor(
                            if (i < item.selectedRating) 0xFFFFA500.toInt()
                            else ContextCompat.getColor(ctx, android.R.color.darker_gray)
                        )
                        val rating = i + 1
                        star.setOnClickListener { onRatingSelected(item, rating) }
                    }
                }
                FeedbackState.SUBMITTING -> {
                    ratingBar.visibility = View.GONE
                    tvFeedbackStatus.visibility = View.VISIBLE
                    tvFeedbackStatus.text = "Submitting…"
                    tvFeedbackStatus.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
                }
                FeedbackState.SUBMITTED -> {
                    ratingBar.visibility = View.GONE
                    tvFeedbackStatus.visibility = View.VISIBLE
                    tvFeedbackStatus.text = "✓ Feedback saved"
                    tvFeedbackStatus.setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_green_dark))
                }
            }
        }
    }
}
