package com.groq.voicetyper

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ImeSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_DEEP_LINK_SETTINGS, true)
        startActivity(intent)
        finish()
    }
}
