package com.dicoding.qrticketscanner.data

data class ValidationRequest(
    val qr_data: String,
    val scanner_info: ScannerInfo
)

data class ScannerInfo(
    val admin_id: String,
    val location: String,
    val device_id: String
)

// ValidationResponse.kt
data class ValidationResponse(
    val status: String,
    val validation_result: String,
    val message: String? = null,
    val ticket_info: TicketInfo? = null,
    val blockchain_status: BlockchainStatus? = null,
    val ui_feedback: UIFeedback? = null
)

data class TicketInfo(
    val ticket_number: Int,
    val event_name: String,
    val holder_name: String,
    val entry_type: String
)

data class BlockchainStatus(
    val is_revoked: Boolean,
    val contract_status: Int,
    val last_checked: String,
    val contract_verified: Boolean
)

data class UIFeedback(
    val color: String,
    val message: String,
    val sound: String
)