package com.localintercom.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LocalIntercomApp() }
    }
}

@Composable
private fun LocalIntercomApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Local Intercom", style = MaterialTheme.typography.headlineMedium)
                Text("LAN-first full-duplex PTT", modifier = Modifier.padding(top = 8.dp))
                Text("Project foundation · v0.1.0", modifier = Modifier.padding(top = 4.dp))
                Button(onClick = { /* Room creation will be implemented in the networking milestone. */ },
                    modifier = Modifier.padding(top = 24.dp)) {
                    Text("Create room (coming soon)")
                }
            }
        }
    }
}
