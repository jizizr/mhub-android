package io.github.mhub.app

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.mhub.app.playback.PlaybackService
import io.github.mhub.app.ui.AppRoot
import io.github.mhub.app.ui.MHubTheme

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val appearance by graph.settings.appearance.state.collectAsStateWithLifecycle()
            MHubTheme(appearance) { AppRoot() }
        }
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller creates the session service, which then keeps
        // itself in the foreground while music plays.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        graph.player.persistNow()
        super.onStop()
    }
}
