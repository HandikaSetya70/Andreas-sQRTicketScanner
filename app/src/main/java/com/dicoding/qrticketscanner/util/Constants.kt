package com.dicoding.qrticketscanner.util

object Constants {
    // Scanner configuration
    const val SCAN_COOLDOWN_MS = 3000L // 3 seconds between scans
    const val AUTO_RESET_DELAY_MS = 5000L // 5 seconds auto-reset

    // Admin configuration
    const val DEFAULT_ADMIN_ID = "admin-scanner-001" // 🔧 Replace with actual admin ID
    const val DEFAULT_LOCATION = "Main Gate" // 🔧 Replace with actual location

    // Audio feedback durations
    const val SCAN_FEEDBACK_DURATION = 100L
    const val SUCCESS_FEEDBACK_DURATION = 200L
    const val ERROR_FEEDBACK_DURATION = 300L

    // Validation results
    const val RESULT_VALID = "valid"
    const val RESULT_INVALID = "invalid"
    const val RESULT_REVOKED = "revoked"
    const val RESULT_ERROR = "error"
}