package io.github.mhub.app

import android.app.Application
import io.github.mhub.app.playback.AudioCache
import io.github.mhub.app.playback.PlayerController

/** Process-wide objects. The player lives here so playback survives the activity. */
class MHubApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(val app: Application) {
    val settings = Settings(app)
    val library = LibraryStore(app)
    val model = AppModel(settings).also { it.attach(app) }
    val cache = AudioCache(app, settings.cacheLimit.value)
    val player = PlayerController(app, model, cache)
}

val android.content.Context.graph: AppGraph get() = (applicationContext as MHubApplication).graph

object BuildConfigCompat {
    fun versionName(context: android.content.Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}
