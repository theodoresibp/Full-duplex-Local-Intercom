package com.localintercom.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.localintercom.app.presentation.RoomApp
import com.localintercom.app.presentation.RoomViewModel
import com.localintercom.app.presentation.RoomViewModelFactory

class MainActivity : ComponentActivity() {
    private lateinit var roomViewModel: RoomViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as IntercomApplication).roomRepository
        roomViewModel = ViewModelProvider(this, RoomViewModelFactory(repository))[RoomViewModel::class.java]
        handleRoomInvite(intent)
        setContent {
            RoomApp(roomViewModel)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRoomInvite(intent)
    }

    private fun handleRoomInvite(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "localintercom" || uri.host != "join") return
        val host = uri.getQueryParameter("host").orEmpty()
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: -1
        val token = uri.getQueryParameter("token").orEmpty()
        roomViewModel.applyRoomInvite(host, port, token)
    }
}
