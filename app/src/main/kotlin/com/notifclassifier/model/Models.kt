package com.notifclassifier.model

import java.util.UUID

/**
 * Represents a single intercepted notification with its classification state.
 */
data class NotificationItem(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long = System.currentTimeMillis(),

    // Classification result — null until server responds
    var decisionCode: Int? = null,       // 1 = Notify Instantly, 2 = Notify Later, 3 = Block
    var decisionLabel: String? = null,
    var confidence: Float? = null,

    // Pipeline log messages for this notification
    val pipelineLog: MutableList<PipelineStep> = mutableListOf(),

    // Feedback state
    var feedbackState: FeedbackState = FeedbackState.AWAITING_DECISION,
    var selectedRating: Int = 0
)

enum class FeedbackState {
    AWAITING_DECISION,  // Classification not yet returned
    PENDING_RATING,     // Decision arrived, waiting for user to rate
    SUBMITTING,         // Currently sending feedback to server
    SUBMITTED,          // Successfully submitted
    ERROR               // Submission failed
}

data class PipelineStep(
    val stepNumber: Int,
    val message: String,
    val isError: Boolean = false
)

// ─── API request/response models ──────────────────────────────────────────────

data class ClassifyRequest(
    val app_name: String,
    val user_name: String,
    val content: String
)

data class ClassifyResponse(
    val decision_code: Int,
    val decision_label: String,
    val confidence: Float
)

data class FeedbackRequest(
    val app_name: String,
    val user_name: String,
    val content: String,
    val decision_code: Int,
    val user_rating: Int
)

data class FeedbackResponse(
    val success: Boolean,
    val message: String,
    val log_id: Int
)
