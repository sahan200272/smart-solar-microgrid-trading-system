// File:        OpsApiError.kt
// Component:   Booking Views & Grid Operator Verification
// Description: Turns failed API calls into readable messages. Reads the "reason" code the
//              QR endpoints return (e.g. EXPIRED, WRONG_NODE) and maps it to the same titles
//              and next steps the web verification page shows.
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.ui.operator

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.microgridsystem.R
import com.example.microgridsystem.models.OpsFailureBody
import com.google.gson.Gson
import com.google.gson.JsonParseException
import retrofit2.Response
import java.io.IOException

// A failed request. status is 0 when the server could not be reached.
data class OpsFailure(val status: Int, val reason: String?, val message: String) {

    // Network and server errors are worth retrying; business rule failures are not.
    val isRetryable: Boolean
        get() = status == 0 || status >= 500
}

// How a failure is presented: colour, icon, title and an optional next step.
data class OpsFailureDetails(
    val tone: OpsTone,
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val hint: Int?
)

object OpsApiError {

    private val gson = Gson()

    // Builds a failure from an unsuccessful response. The error body can only be read once.
    fun fromResponse(context: Context, response: Response<*>): OpsFailure {
        val rawBody = try {
            response.errorBody()?.string()
        } catch (e: IOException) {
            null
        }

        val body = try {
            if (rawBody.isNullOrBlank()) null else gson.fromJson(rawBody, OpsFailureBody::class.java)
        } catch (e: JsonParseException) {
            null
        }

        val message = body?.message?.takeIf { it.isNotBlank() }
            ?: body?.title?.takeIf { it.isNotBlank() }
            ?: defaultMessage(context, response.code())

        return OpsFailure(response.code(), body?.reason?.takeIf { it.isNotBlank() }, message)
    }

    // Builds a failure for a request that never reached the server.
    fun fromThrowable(context: Context, error: Throwable): OpsFailure {
        return OpsFailure(0, null, context.getString(R.string.ops_error_network))
    }

    // Fallback message when the API sends no body (e.g. 401/403 from the auth middleware).
    private fun defaultMessage(context: Context, status: Int): String {
        return when {
            status == 401 -> context.getString(R.string.ops_error_session)
            status == 403 -> context.getString(R.string.ops_error_forbidden)
            status == 404 -> context.getString(R.string.ops_error_not_found)
            status >= 500 -> context.getString(R.string.ops_error_server)
            else -> context.getString(R.string.ops_error_http, status)
        }
    }

    // Presentation for a failure: known API reason codes first, then HTTP status fallbacks.
    fun describe(failure: OpsFailure): OpsFailureDetails {
        return when (failure.reason) {
            "MISSING_TOKEN" -> OpsFailureDetails(
                OpsTone.PENDING, R.drawable.ic_ops_keyboard, R.string.ops_fail_missing_title, R.string.ops_fail_missing_hint
            )
            "MALFORMED_TOKEN" -> OpsFailureDetails(
                OpsTone.DANGER, R.drawable.ic_qr, R.string.ops_fail_malformed_title, R.string.ops_fail_malformed_hint
            )
            "INVALID_TOKEN" -> OpsFailureDetails(
                OpsTone.DANGER, R.drawable.ic_ops_error, R.string.ops_fail_invalid_title, R.string.ops_fail_invalid_hint
            )
            "ALREADY_USED", "ALREADY_COMPLETED" -> OpsFailureDetails(
                OpsTone.DANGER, R.drawable.ic_check_circle, R.string.ops_fail_used_title, R.string.ops_fail_used_hint
            )
            "EXPIRED" -> OpsFailureDetails(
                OpsTone.DANGER, R.drawable.ic_hourglass, R.string.ops_fail_expired_title, R.string.ops_fail_expired_hint
            )
            "NOT_APPROVED" -> OpsFailureDetails(
                OpsTone.PENDING, R.drawable.ic_ops_error, R.string.ops_fail_not_approved_title, R.string.ops_fail_not_approved_hint
            )
            "TOO_EARLY" -> OpsFailureDetails(
                OpsTone.PENDING, R.drawable.ic_clock, R.string.ops_fail_too_early_title, R.string.ops_fail_too_early_hint
            )
            "WRONG_NODE" -> OpsFailureDetails(
                OpsTone.PENDING, R.drawable.ic_map_node, R.string.ops_fail_wrong_node_title, R.string.ops_fail_wrong_node_hint
            )
            "PROSUMER_NOT_FOUND" -> OpsFailureDetails(
                OpsTone.DANGER, R.drawable.ic_person, R.string.ops_fail_prosumer_missing_title, R.string.ops_fail_prosumer_missing_hint
            )
            "PROSUMER_INACTIVE" -> OpsFailureDetails(
                OpsTone.DANGER, R.drawable.ic_lock, R.string.ops_fail_prosumer_inactive_title, R.string.ops_fail_prosumer_inactive_hint
            )
            "TOKEN_MISMATCH" -> OpsFailureDetails(
                OpsTone.DANGER, R.drawable.ic_qr, R.string.ops_fail_mismatch_title, R.string.ops_fail_mismatch_hint
            )
            else -> describeStatus(failure.status)
        }
    }

    // Presentation when the API gave no reason code (network, auth or server problems).
    private fun describeStatus(status: Int): OpsFailureDetails {
        return when {
            status == 0 -> OpsFailureDetails(
                OpsTone.DANGER, R.drawable.ic_ops_cloud_off, R.string.ops_fail_network_title, R.string.ops_fail_network_hint
            )
            status == 401 -> OpsFailureDetails(OpsTone.PENDING, R.drawable.ic_lock, R.string.ops_fail_session_title, null)
            status == 403 -> OpsFailureDetails(
                OpsTone.PENDING, R.drawable.ic_lock, R.string.ops_fail_forbidden_title, R.string.ops_fail_forbidden_hint
            )
            status == 404 -> OpsFailureDetails(OpsTone.DANGER, R.drawable.ic_search, R.string.ops_fail_not_found_title, null)
            else -> OpsFailureDetails(OpsTone.DANGER, R.drawable.ic_ops_error, R.string.ops_fail_generic_title, null)
        }
    }
}
