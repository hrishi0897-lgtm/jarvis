package com.jarvis.app

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

class JarvisTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val serviceIntent = Intent(this, BackgroundVoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
