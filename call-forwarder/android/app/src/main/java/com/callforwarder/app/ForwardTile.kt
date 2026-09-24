package com.callforwarder.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile to switch call forwarding on/off (e.g. when carrying both phones). */
class ForwardTile : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        Prefs.setEnabled(this, !Prefs.enabled(this))
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = Prefs.enabled(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Call forwarding"
        tile.updateTile()
    }
}
