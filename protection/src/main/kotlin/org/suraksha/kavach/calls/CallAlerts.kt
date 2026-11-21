package org.suraksha.kavach.calls

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.suraksha.core.CallReason
import org.suraksha.core.CallRisk
import org.suraksha.core.CallRiskLevel
import org.suraksha.core.CallVerdict
import org.suraksha.kavach.KavachApplication
import org.suraksha.kavach.R
import org.suraksha.kavach.data.AppSettings
import java.util.concurrent.atomic.AtomicLong

/** Heads-up notification plus an optional banner above the incoming-call screen. */
object CallAlerts {
    const val CHANNEL = "call_alerts"
    const val ID = 403
    private const val BANNER_MILLIS = 30_000L
    const val REPORT_ACTION_MILLIS = 10 * 60_000L
    private var banner: View? = null
    private var bannerTimeout: Runnable? = null
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)

    fun reasonText(context: Context, reason: CallReason): String = context.getString(when (reason) {
        CallReason.HIDDEN_NUMBER -> R.string.sk_call_reason_hidden
        CallReason.REPORTED_NUMBER -> R.string.sk_call_reason_reported
        CallReason.INTERNATIONAL -> R.string.sk_call_reason_international
        CallReason.UNKNOWN_CALLER -> R.string.sk_call_reason_unknown
        CallReason.TELEMARKETING -> R.string.sk_call_reason_telemarketing
    })

    fun show(app: KavachApplication, number: String?, verdict: CallVerdict) {
        if (verdict.level == CallRiskLevel.NONE || !enabled(app)) return
        val epoch = app.settings.state.value.callActionEpoch
        val token = generation.get()
        val context = AppSettings.localized(app)
        val high = verdict.level == CallRiskLevel.HIGH
        val title = context.getString(if (high) R.string.sk_call_title_high else R.string.sk_call_title_caution)
        val body = (verdict.reasons.map { reasonText(context, it) } + context.getString(R.string.sk_call_voice_clone_advice)).joinToString("\n")
        main.post {
            if (generation.get() != token || app.settings.state.value.callActionEpoch != epoch || !enabled(app)) return@post
            notify(app, context, title, body, number, high, epoch)
            showBanner(app, context, title, body, high)
        }
    }

    private fun enabled(app: KavachApplication) = app.settings.state.value.callShieldEnabled &&
        !app.resetInProgress.value && app.canShowIntrusiveWarnings()

    private fun notify(app: KavachApplication, context: Context, title: String, body: String, number: String?, high: Boolean, epoch: Long) {
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.sk_call_channel),
            NotificationManager.IMPORTANCE_HIGH))
        val builder = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.sk_ic_notification)
            .setContentTitle(title)
            .setContentText(body.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setColor(if (high) Color.rgb(153, 27, 27) else Color.rgb(174, 102, 0))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setSilent(!high)
            .setTimeoutAfter(REPORT_ACTION_MILLIS)
        val reportable = CallRisk.reportKey(number, CallShieldPlatform.homeCountryCode(app))
        if (reportable != null && !high) {
            val report = PendingIntent.getBroadcast(app, number.hashCode(),
                Intent(app, ReportCallerReceiver::class.java)
                    .setAction("org.suraksha.kavach.REPORT_CALL.$epoch.${System.nanoTime()}")
                    .putExtra(ReportCallerReceiver.EXTRA_NUMBER, reportable)
                    .putExtra(ReportCallerReceiver.EXTRA_EPOCH, epoch)
                    .putExtra(ReportCallerReceiver.EXTRA_ISSUED_AT, System.currentTimeMillis()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(R.drawable.sk_ic_notification, context.getString(R.string.sk_call_mark_scam), report)
        }
        try { manager.notify(ID, builder.build()) } catch (_: SecurityException) { /* Permission revoked meanwhile. */ }
    }

    private fun showBanner(app: KavachApplication, context: Context, title: String, body: String, high: Boolean) {
        if (!enabled(app) || !app.settings.state.value.overlayEnabled || !Settings.canDrawOverlays(app) ||
            app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        dismissBanner(app)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (high) Color.rgb(153, 27, 27) else Color.rgb(255, 229, 163))
            }
            val text = if (high) Color.WHITE else Color.rgb(64, 39, 0)
            addView(TextView(context).apply {
                this.text = title; setTextColor(text); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                this.text = body; setTextColor(text); textSize = 15f; setPadding(0, dp(6), 0, dp(6))
            })
            addView(TextView(context).apply {
                this.text = context.getString(R.string.sk_call_banner_dismiss); setTextColor(text)
                textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END
            })
            setOnClickListener { dismissBanner(app) }
            contentDescription = title
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; x = 0; y = dp(36); horizontalMargin = 0.03f }
        runCatching { app.getSystemService(WindowManager::class.java).addView(card, params) }.onSuccess {
            banner = card
            bannerTimeout = Runnable { dismissBanner(app) }.also { main.postDelayed(it, BANNER_MILLIS) }
        }
    }

    fun dismissBanner(app: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { dismissBanner(app) }; return }
        bannerTimeout?.let(main::removeCallbacks)
        bannerTimeout = null
        banner?.let { view -> runCatching { app.getSystemService(WindowManager::class.java).removeView(view) } }
        banner = null
    }

    fun dismissAll(app: Context) {
        generation.incrementAndGet()
        NotificationManagerCompat.from(app).cancel(ID)
        dismissBanner(app)
    }
}

/** "Mark as scam" from the call warning: the number is stored on this phone only. */
class ReportCallerReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_EPOCH = "epoch"
        const val EXTRA_ISSUED_AT = "issued_at"
    }
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() } ?: return
        val app = context.applicationContext as? KavachApplication ?: return
        val settings = app.settings.state.value
        val issuedAt = intent.getLongExtra(EXTRA_ISSUED_AT, -1L)
        val now = System.currentTimeMillis()
        if (app.resetInProgress.value || !settings.callShieldEnabled || !CallShieldPlatform.roleHeld(app) ||
            intent.getLongExtra(EXTRA_EPOCH, -1L) != settings.callActionEpoch || issuedAt < 0L ||
            now < issuedAt || now - issuedAt > CallAlerts.REPORT_ACTION_MILLIS) return
        if (app.settings.reportNumber(number)) CallAlerts.dismissAll(app)
        else Toast.makeText(AppSettings.localized(app), R.string.sk_call_report_invalid_or_full, Toast.LENGTH_LONG).show()
    }
}
