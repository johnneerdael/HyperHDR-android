package eu.hyperhdr.android.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class HyperHdrTileService : TileService() {

    override fun onClick() {
        // We can't show the MediaProjection consent dialog from a tile — open the main activity instead,
        // which will trigger the projection flow.
        val launch = packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(launch)
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "HyperHDR"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
