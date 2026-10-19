package com.example.mouseonlytouch

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView
    private lateinit var blockSwitch: Switch

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { updateUi() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(MouseOnlyService.PREFS, MODE_PRIVATE)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        updateUi()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        fun label(textRes: Int, sizeSp: Float, bold: Boolean = false) = TextView(this).apply {
            setText(textRes)
            textSize = sizeSp
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        }

        fun button(textRes: Int, onClick: () -> Unit) = Button(this).apply {
            setText(textRes)
            setOnClickListener { onClick() }
        }

        root.addView(label(R.string.app_name, 26f, bold = true))

        statusView = label(R.string.status_unknown, 16f)
        root.addView(statusView)

        blockSwitch = Switch(this).apply {
            setText(R.string.switch_label)
            textSize = 16f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(blockSwitch)

        root.addView(button(R.string.btn_accessibility) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button(R.string.btn_appinfo) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        })

        root.addView(label(R.string.steps, 14f))

        return ScrollView(this).apply { addView(root) }
    }

    private fun updateUi() {
        blockSwitch.setOnCheckedChangeListener(null)
        blockSwitch.isChecked = prefs.getBoolean(MouseOnlyService.KEY_ENABLED, true)
        blockSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(MouseOnlyService.KEY_ENABLED, checked).apply()
        }

        val statusRes = when {
            !isServiceEnabled() -> R.string.status_service_off
            else -> when (prefs.getString(MouseOnlyService.KEY_STATUS, "")) {
                MouseOnlyService.STATUS_BLOCKING -> R.string.status_blocking
                MouseOnlyService.STATUS_NO_MOUSE -> R.string.status_no_mouse
                MouseOnlyService.STATUS_DISABLED -> R.string.status_disabled
                MouseOnlyService.STATUS_PAUSED -> R.string.status_paused
                else -> R.string.status_unknown
            }
        }
        statusView.setText(statusRes)
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, MouseOnlyService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
