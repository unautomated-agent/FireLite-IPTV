package dev.firetvlite.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.firetvlite.iptv.update.UpdateViewModel

/** Single activity of the app. All screens are Compose destinations hosted by [FireLiteApp]. */
class MainActivity : ComponentActivity() {

    private val updateViewModel: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FireLiteApp(updateViewModel) }
    }
}
