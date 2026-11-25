package com.localintercom.app.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localintercom.app.domain.model.DiscoveredRoom
import com.localintercom.app.domain.model.IntercomChannels
import com.localintercom.app.domain.model.RoomConnectionStatus
import com.localintercom.app.domain.model.RoomMember
import com.localintercom.app.domain.model.RoomSession
import com.localintercom.app.domain.model.RoomStatus
import com.localintercom.app.service.IntercomAudioService

@Composable
fun RoomApp(viewModel: RoomViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingMicrophoneAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val action = pendingMicrophoneAction
        pendingMicrophoneAction = null
        val microphoneGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (microphoneGranted) action?.invoke()
        else viewModel.showMessage("Microphone permission is required for intercom audio.")
    }
    val runWithMicrophonePermission: (() -> Unit) -> Unit = { action ->
        val missingPermissions = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (missingPermissions.isEmpty()) {
            action()
        } else {
            pendingMicrophoneAction = action
            microphonePermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { contentPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            val room = state.activeRoom
            if (room == null) {
                RoomSetupScreen(
                    state = state,
                    viewModel = viewModel,
                    onCreateRoom = { runWithMicrophonePermission(viewModel::createRoom) },
                    onJoinSelectedRoom = { runWithMicrophonePermission(viewModel::joinSelectedRoom) },
                    onJoinManualRoom = { runWithMicrophonePermission(viewModel::joinManualRoom) }
                )
            } else {
                ActiveRoomScreen(room, state, viewModel)
            }
        }
    }
}

@Composable
private fun RoomSetupScreen(
    state: RoomScreenState,
    viewModel: RoomViewModel,
    onCreateRoom: () -> Unit,
    onJoinSelectedRoom: () -> Unit,
    onJoinManualRoom: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Local Intercom", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Create a room or join a room on your local Wi-Fi network.")

        OutlinedTextField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayNameChanged,
            label = { Text("Your display name") },
            singleLine = true,
            isError = state.displayNameError != null,
            supportingText = state.displayNameError?.let { error -> { Text(error) } },
            modifier = Modifier.fillMaxWidth()
        )

        Text("Create a room", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = state.roomName,
            onValueChange = viewModel::onRoomNameChanged,
            label = { Text("Room name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onCreateRoom,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create room")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Rooms nearby", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = viewModel::refreshDiscovery, enabled = !state.discoveryState.isScanning) {
                Text(if (state.discoveryState.isScanning) "Searching…" else "Refresh")
            }
        }
        Text(state.discoveryState.message, style = MaterialTheme.typography.bodySmall)
        state.discoveredRooms.forEach { room ->
            DiscoveredRoomCard(
                room = room,
                selected = state.selectedRoom?.roomId == room.roomId,
                onClick = { viewModel.selectRoom(room) }
            )
        }
        if (state.discoveredRooms.isNotEmpty()) {
            OutlinedTextField(
                value = state.sessionToken,
                onValueChange = viewModel::onSessionTokenChanged,
                label = { Text("Room session token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onJoinSelectedRoom,
                enabled = !state.isBusy && state.selectedRoom != null && state.selectedRoom.status != RoomStatus.FULL,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Join selected room")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text("Join by IP address", style = MaterialTheme.typography.titleLarge)
        Text("Use the host IP, port, and session token shown on the host device.")
        OutlinedTextField(
            value = state.manualHostAddress,
            onValueChange = viewModel::onManualHostChanged,
            label = { Text("Host IP address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.manualPort,
            onValueChange = viewModel::onManualPortChanged,
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.sessionToken,
            onValueChange = viewModel::onSessionTokenChanged,
            label = { Text("Room session token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onJoinManualRoom,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Join using IP")
        }

        state.message?.let { MessageText(it) }
        if (state.isBusy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DiscoveredRoomCard(room: DiscoveredRoom, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(room.roomName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Host: ${room.hostName}", style = MaterialTheme.typography.bodyMedium)
            Text("Members: ${room.memberCount}/${room.maxMembers} · ${room.status.label()}")
            Text(room.hostAddress.endpoint(room.port), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ActiveRoomScreen(room: RoomSession, state: RoomScreenState, viewModel: RoomViewModel) {
    val context = LocalContext.current
    var isPttPressed by remember(room.localMemberId) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(room.roomName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Connection: ${room.connectionStatus.label()}", style = MaterialTheme.typography.titleMedium)
        Text("Host: ${room.hostName}")
        BluetoothPttModeSettings()
        Text("Members: ${room.members.size}/6 · ${room.status.label()}")
        Text("Host address: ${room.hostAddress.endpoint(room.port)}", style = MaterialTheme.typography.bodyMedium)
        Text(state.discoveryState.message, style = MaterialTheme.typography.bodySmall)

        if (room.isHost) {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Invite people", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Share these local network details with people you want to invite.")
                    Text("Session token", style = MaterialTheme.typography.labelMedium)
                    SelectableToken(room.joinToken.orEmpty())
                    Button(
                        onClick = {
                            val invite = """
                                You are invited to join "${room.roomName}" on Local Intercom.

                                Host: ${room.hostName}
                                Address: ${room.hostAddress.endpoint(room.port)}
                                Session token: ${room.joinToken.orEmpty()}

                                Connect your phone to the same Wi-Fi, open Local Intercom, enter your display name, then join using the host IP, port and session token.
                            """.trimIndent()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Local Intercom invitation: ${room.roomName}")
                                putExtra(Intent.EXTRA_TEXT, invite)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share room invitation"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Share invitation") }
                }
            }
        }

        Text("Channel", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IntercomChannels.all.forEach { channel ->
                FilterChip(
                    selected = room.selectedChannelId == channel.id,
                    onClick = { viewModel.selectChannel(channel.id) },
                    label = { Text(channel.name) }
                )
            }
        }
        Text("Hold the button to transmit on ${IntercomChannels.all.firstOrNull { it.id == room.selectedChannelId }?.name ?: "General"}. Release to mute.")

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .pointerInput(room.localMemberId) {
                    detectTapGestures(onPress = {
                        isPttPressed = true
                        viewModel.setPttActive(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            isPttPressed = false
                            viewModel.setPttActive(false)
                        }
                    })
                },
            shape = RoundedCornerShape(18.dp),
            color = if (isPttPressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Text(
                    if (isPttPressed) "TRANSMITTING" else "HOLD TO TALK",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isPttPressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Text("Members", style = MaterialTheme.typography.titleLarge)
        room.members.forEach { member -> MemberRow(member) }

        state.message?.let { MessageText(it) }
        Button(onClick = viewModel::leaveRoom, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
            Text(if (room.isHost) "Leave and close room" else "Leave room")
        }
        if (state.isBusy) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}


@Composable
private fun BluetoothPttModeSettings() {
    val context = LocalContext.current
    var selectedMode by remember {
        mutableStateOf(IntercomAudioService.getBluetoothPttMode(context))
    }
    var captureAction by remember { mutableStateOf<String?>(null) }
    var capturedMessage by remember { mutableStateOf<String?>(null) }
    var mappingVersion by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        IntercomAudioService.onButtonCaptured = { action, keyCode ->
            capturedMessage = "${actionLabel(action)} mapped to ${IntercomAudioService.keyCodeLabel(keyCode)}"
            captureAction = null
            mappingVersion++
        }
        IntercomAudioService.onButtonCaptureRejected = { action, keyCode ->
            capturedMessage = "${IntercomAudioService.keyCodeLabel(keyCode)} is already assigned. Choose a different button for ${actionLabel(action)}."
            captureAction = null
        }
        onDispose {
            IntercomAudioService.cancelButtonCapture()
            IntercomAudioService.onButtonCaptured = null
            IntercomAudioService.onButtonCaptureRejected = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Headset physical button", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedMode == IntercomAudioService.BLUETOOTH_PTT_MODE_PRESS_HOLD,
                onClick = {
                    selectedMode = IntercomAudioService.BLUETOOTH_PTT_MODE_PRESS_HOLD
                    IntercomAudioService.setBluetoothPttMode(context, selectedMode)
                },
                label = { Text("Press & Hold") }
            )
            FilterChip(
                selected = selectedMode == IntercomAudioService.BLUETOOTH_PTT_MODE_TOGGLE,
                onClick = {
                    selectedMode = IntercomAudioService.BLUETOOTH_PTT_MODE_TOGGLE
                    IntercomAudioService.setBluetoothPttMode(context, selectedMode)
                },
                label = { Text("Toggle") }
            )
        }
        Text(
            if (selectedMode == IntercomAudioService.BLUETOOTH_PTT_MODE_PRESS_HOLD) {
                "Hold the headset button to talk; release it to mute."
            } else {
                "Press once to talk; press again to mute."
            },
            style = MaterialTheme.typography.bodySmall
        )

        Text("Button mapping", style = MaterialTheme.typography.titleSmall)
        Text("Mapped keys are saved on this device.", style = MaterialTheme.typography.bodySmall)
        MappingRow(context, IntercomAudioService.ACTION_PTT, "PTT (Transmit)", mappingVersion) { action -> captureAction = action; IntercomAudioService.beginButtonCapture(action) }
        MappingRow(context, IntercomAudioService.ACTION_NEXT_CHANNEL, "Next channel", mappingVersion) { action -> captureAction = action; IntercomAudioService.beginButtonCapture(action) }
        MappingRow(context, IntercomAudioService.ACTION_PREVIOUS_CHANNEL, "Previous channel", mappingVersion) { action -> captureAction = action; IntercomAudioService.beginButtonCapture(action) }
        TextButton(onClick = {
            IntercomAudioService.resetMappedKeyCodes(context)
            mappingVersion++
            capturedMessage = "Default button mappings restored."
        }) { Text("Reset mappings") }
        capturedMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }

    if (captureAction != null) {
        AlertDialog(
            onDismissRequest = {
                IntercomAudioService.cancelButtonCapture()
                captureAction = null
            },
            title = { Text("Listen for button") },
            text = { Text("Press the physical button on your wired or Bluetooth headset now. Only buttons forwarded to Android can be detected.") },
            confirmButton = {
                TextButton(onClick = {
                    IntercomAudioService.cancelButtonCapture()
                    captureAction = null
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MappingRow(context: android.content.Context, action: String, label: String, @Suppress("UNUSED_PARAMETER") version: Int, onMap: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                IntercomAudioService.keyCodeLabel(
                    IntercomAudioService.getMappedKeyCode(context, action)
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
        TextButton(onClick = { onMap(action) }) { Text("Map") }
    }
}


private fun actionLabel(action: String): String = when (action) {
    IntercomAudioService.ACTION_PTT -> "PTT"
    IntercomAudioService.ACTION_NEXT_CHANNEL -> "Next channel"
    else -> "Previous channel"
}

@Composable
private fun SelectableToken(token: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text = token,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun MemberRow(member: RoomMember) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(member.displayName, fontWeight = FontWeight.Medium)
                Text(
                    IntercomChannels.all.firstOrNull { it.id == member.channelId }?.name ?: "General",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (member.isHost) Text("Host", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MessageText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

private fun RoomStatus.label(): String = when (this) {
    RoomStatus.OPEN -> "Open"
    RoomStatus.FULL -> "Full"
    RoomStatus.CLOSED -> "Closed"
}

private fun String.endpoint(port: Int): String =
    if (contains(":")) "[$this]:$port" else "$this:$port"

private fun RoomConnectionStatus.label(): String = when (this) {
    RoomConnectionStatus.CONNECTING -> "Connecting"
    RoomConnectionStatus.CONNECTED -> "Connected"
    RoomConnectionStatus.DISCONNECTED -> "Disconnected"
}
