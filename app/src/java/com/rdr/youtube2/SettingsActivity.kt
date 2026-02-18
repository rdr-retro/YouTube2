package com.rdr.youtube2

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit

class SettingsActivity : AppCompatActivity() {

    private lateinit var backButton: Button
    private lateinit var hideShortsSwitch: SwitchCompat
    private lateinit var subscriptionsFeedSwitch: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        backButton = findViewById(R.id.settings_screen_back_button)
        hideShortsSwitch = findViewById(R.id.settings_screen_hide_shorts_switch)
        subscriptionsFeedSwitch = findViewById(R.id.settings_screen_subscriptions_feed_switch)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        hideShortsSwitch.isChecked = prefs.getBoolean(KEY_HIDE_SHORTS, false)
        subscriptionsFeedSwitch.isChecked = prefs.getBoolean(KEY_SUBSCRIPTIONS_FEED_ONLY, false)

        hideShortsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit {
                putBoolean(KEY_HIDE_SHORTS, isChecked)
            }
        }
        subscriptionsFeedSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit {
                putBoolean(KEY_SUBSCRIPTIONS_FEED_ONLY, isChecked)
            }
        }

        backButton.setOnClickListener {
            finish()
        }
    }

    companion object {
        private const val PREFS_NAME = "main_feed_prefs"
        private const val KEY_HIDE_SHORTS = "hide_shorts_enabled"
        private const val KEY_SUBSCRIPTIONS_FEED_ONLY = "subscriptions_feed_only"
    }
}
