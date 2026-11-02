package com.sayang.translate

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class TranslateTileService : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        val shown = isReady() && !Prefs(this).buttonHidden
        tile.state = if (shown) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        val service = TranslateService.instance
        if (isReady() && service != null) {
            service.toggleButton()
            onStartListening()
        } else {
            openSetup()
        }
    }

    private fun isReady() = Prefs(this).myLanguage != null && TranslateService.instance != null

    private fun openSetup() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
