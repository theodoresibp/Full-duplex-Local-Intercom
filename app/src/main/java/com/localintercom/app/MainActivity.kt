package com.localintercom.app

import android.os.Bundle
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
        setContent {
            RoomApp(roomViewModel)
        }
    }
}
