package app.dailycare.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import app.dailycare.core.ActionId
import app.dailycare.engine.EngineRunner
import app.dailycare.engine.UserActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quick-settings tile: one tap records an exercise round without opening the app. */
class RoundDoneTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val message = UserActions.perform(applicationContext, ActionId.EXERCISE_ROUND_DONE)
            runCatching { EngineRunner.tick(applicationContext, updatePlace = false) }
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, message ?: "Recorded", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }
    }

    private fun refresh() {
        scope.launch {
            val ex = runCatching { EngineRunner.summary(applicationContext).exercise }.getOrNull()
            withContext(Dispatchers.Main) {
                val tile = qsTile ?: return@withContext
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Round done"
                tile.subtitle = ex?.takeIf { it.enabled }?.let { "${it.fullRounds} of ${it.target} today" } ?: "Exercises"
                tile.updateTile()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
