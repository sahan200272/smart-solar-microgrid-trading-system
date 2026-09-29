// File:        OpsUi.kt
// Component:   Booking Views & Grid Operator Verification
// Description: Shared UI helpers for the booking view and Grid Operator screens: status
//              colours (same palette as the web operator console), badges, edge-to-edge
//              insets, session handling, and the reusable state and stepper views.
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.ui.operator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.example.microgridsystem.LoginActivity
import com.example.microgridsystem.R
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.max

// Colour set for a status or message tone. Values match the web console's --ops-* tokens.
enum class OpsTone(
    @param:ColorRes val foreground: Int,
    @param:ColorRes val background: Int,
    @param:ColorRes val border: Int
) {
    PENDING(R.color.ops_pending, R.color.ops_pending_bg, R.color.ops_pending_border),
    APPROVED(R.color.ops_approved, R.color.ops_approved_bg, R.color.ops_approved_border),
    COMPLETED(R.color.ops_completed, R.color.ops_completed_bg, R.color.ops_completed_border),
    NEUTRAL(R.color.ops_neutral, R.color.ops_neutral_bg, R.color.ops_neutral_border),
    DANGER(R.color.ops_danger, R.color.ops_danger_bg, R.color.ops_danger_border);

    companion object {
        // Maps a reservation status from the API to its tone.
        fun forStatus(status: String?): OpsTone {
            return when (status?.trim()?.lowercase()) {
                "pending" -> PENDING
                "approved" -> APPROVED
                "completed" -> COMPLETED
                "rejected" -> DANGER
                else -> NEUTRAL
            }
        }
    }
}

object OpsUi {

    // Converts dp to pixels.
    fun dp(context: Context, value: Float): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    // Draws edge to edge with transparent system bars. lightBars = dark icons on a light screen.
    fun enableEdgeToEdge(activity: ComponentActivity, lightBars: Boolean = true) {
        val style = if (lightBars) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }

        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    // Pads a view so its content stays clear of the status bar, navigation bar and keyboard.
    // The insets are consumed so nested views don't pad themselves a second time.
    fun applySystemBarPadding(view: View, top: Boolean = true, bottom: Boolean = true) {
        val initial = Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)

        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())

            target.setPadding(
                initial.left + bars.left,
                initial.top + if (top) bars.top else 0,
                initial.right + bars.right,
                initial.bottom + if (bottom) max(bars.bottom, keyboard.bottom) else 0
            )

            WindowInsetsCompat.CONSUMED
        }
    }

    // Grid Operators verify and finalise transfers; the API rejects other roles.
    fun isGridOperator(role: String?): Boolean {
        return role.equals("GridOperator", ignoreCase = true) || role.equals("Grid Operator", ignoreCase = true)
    }

    // The operator dashboard API allows Grid Operator and Backoffice accounts.
    fun canViewConsole(role: String?): Boolean {
        return isGridOperator(role) || role.equals("Backoffice", ignoreCase = true)
    }

    fun isProsumer(role: String?): Boolean = role.equals("Prosumer", ignoreCase = true)

    // Pill background with a 1dp border in the tone's colours.
    fun pill(context: Context, tone: OpsTone): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 999f).toFloat()
            setColor(ContextCompat.getColor(context, tone.background))
            setStroke(dp(context, 1f), ContextCompat.getColor(context, tone.border))
        }
    }

    // Styles a TextView as a coloured badge.
    fun badge(view: TextView, text: CharSequence, tone: OpsTone) {
        view.text = text
        view.setTextColor(ContextCompat.getColor(view.context, tone.foreground))
        view.background = pill(view.context, tone)
    }

    // Badge for a reservation status such as "Approved".
    fun statusBadge(view: TextView, status: String?) {
        badge(view, OpsFormat.statusLabel(status), OpsTone.forStatus(status))
    }

    // Badge for a slot phase: green while in progress, grey otherwise.
    fun phaseBadge(view: TextView, phase: OpsFormat.Phase?) {
        if (phase == null) {
            view.isVisible = false
            return
        }

        view.isVisible = true
        badge(view, phase.label, if (phase.key == OpsFormat.PhaseKey.LIVE) OpsTone.APPROVED else OpsTone.NEUTRAL)
    }

    // Tints a card's background and border with a tone (used for result banners).
    fun tintCard(card: MaterialCardView, tone: OpsTone) {
        val context = card.context
        card.setCardBackgroundColor(ContextCompat.getColor(context, tone.background))
        card.strokeColor = ContextCompat.getColor(context, tone.border)
    }

    // Adds a "label ... value" row to a details list.
    fun addDetailRow(container: LinearLayout, label: CharSequence, value: CharSequence, monospace: Boolean = false) {
        val row = LayoutInflater.from(container.context).inflate(R.layout.item_ops_detail_row, container, false)
        row.findViewById<TextView>(R.id.tvDetailLabel).text = label

        val valueView = row.findViewById<TextView>(R.id.tvDetailValue)
        valueView.text = value
        if (monospace) {
            valueView.typeface = android.graphics.Typeface.MONOSPACE
        }

        container.addView(row)
    }

    // Sends the user to the login screen and clears the back stack.
    fun goToLogin(activity: Activity) {
        activity.startActivity(
            Intent(activity, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }

    // Clears the saved session and returns to login.
    fun signOut(activity: Activity) {
        SessionManager(activity).logout()
        goToLogin(activity)
    }

    // Tells the user their session has expired. Shown once per screen even if several calls fail.
    fun showSessionExpired(activity: Activity) {
        val decor = activity.window.decorView
        if (activity.isFinishing || decor.getTag(R.id.ops_tag_session_dialog) == true) {
            return
        }

        decor.setTag(R.id.ops_tag_session_dialog, true)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ops_session_expired_title)
            .setMessage(R.string.ops_session_expired_message)
            .setCancelable(false)
            .setPositiveButton(R.string.ops_action_sign_in) { _, _ -> signOut(activity) }
            .show()
    }
}

// Centered empty / error / restricted message with an optional action (layout_ops_state.xml).
class OpsStateView(private val root: View) {

    private val iconFrame: View = root.findViewById(R.id.opsStateIconFrame)
    private val icon: ImageView = root.findViewById(R.id.opsStateIcon)
    private val title: TextView = root.findViewById(R.id.opsStateTitle)
    private val message: TextView = root.findViewById(R.id.opsStateMessage)
    private val action: MaterialButton = root.findViewById(R.id.opsStateAction)

    val isShown: Boolean
        get() = root.isVisible

    // Shows the state with the given icon, tone, text and optional action button.
    fun show(
        @DrawableRes iconRes: Int,
        tone: OpsTone,
        titleText: CharSequence,
        messageText: CharSequence,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null
    ) {
        val context = root.context

        icon.setImageResource(iconRes)
        icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, tone.foreground))
        iconFrame.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, tone.background))

        title.text = titleText
        message.text = messageText
        message.isVisible = messageText.isNotEmpty()

        if (actionLabel != null && onAction != null) {
            action.text = actionLabel
            action.setOnClickListener { onAction() }
            action.isVisible = true
        } else {
            action.setOnClickListener(null)
            action.isVisible = false
        }

        root.isVisible = true
    }

    fun hide() {
        root.isVisible = false
    }
}

// Three-step progress indicator: Scan -> Review -> Complete (layout_ops_stepper.xml).
class OpsStepper(root: View) {

    private val dots = listOf<View>(
        root.findViewById(R.id.stepDot1), root.findViewById(R.id.stepDot2), root.findViewById(R.id.stepDot3)
    )
    private val numbers = listOf<TextView>(
        root.findViewById(R.id.stepNum1), root.findViewById(R.id.stepNum2), root.findViewById(R.id.stepNum3)
    )
    private val checks = listOf<ImageView>(
        root.findViewById(R.id.stepCheck1), root.findViewById(R.id.stepCheck2), root.findViewById(R.id.stepCheck3)
    )
    private val labels = listOf<TextView>(
        root.findViewById(R.id.stepLabel1), root.findViewById(R.id.stepLabel2), root.findViewById(R.id.stepLabel3)
    )
    private val connectors = listOf<View>(root.findViewById(R.id.stepLine1), root.findViewById(R.id.stepLine2))

    // Marks earlier steps done and highlights the current one. 4 shows every step as done.
    fun setStep(current: Int) {
        val context = dots.first().context
        val primary = ContextCompat.getColor(context, R.color.primary)
        val primaryDark = ContextCompat.getColor(context, R.color.primary_dark)
        val muted = ContextCompat.getColor(context, R.color.text_muted)
        val secondary = ContextCompat.getColor(context, R.color.text_secondary)
        val border = ContextCompat.getColor(context, R.color.border_light)

        for (index in dots.indices) {
            val step = index + 1

            when {
                step < current -> {
                    dots[index].setBackgroundResource(R.drawable.bg_ops_step_done)
                    numbers[index].isVisible = false
                    checks[index].isVisible = true
                    labels[index].setTextColor(secondary)
                }
                step == current -> {
                    dots[index].setBackgroundResource(R.drawable.bg_ops_step_active)
                    numbers[index].isVisible = true
                    numbers[index].setTextColor(primaryDark)
                    checks[index].isVisible = false
                    labels[index].setTextColor(primaryDark)
                }
                else -> {
                    dots[index].setBackgroundResource(R.drawable.bg_ops_step_todo)
                    numbers[index].isVisible = true
                    numbers[index].setTextColor(muted)
                    checks[index].isVisible = false
                    labels[index].setTextColor(muted)
                }
            }
        }

        connectors.forEachIndexed { index, line ->
            line.setBackgroundColor(if (index + 1 < current) primary else border)
        }
    }
}
