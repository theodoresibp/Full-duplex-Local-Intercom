package com.localintercom.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localintercom.app.domain.model.DiscoveredRoom
import com.localintercom.app.domain.model.DiscoveryState
import com.localintercom.app.domain.model.RoomSession
import com.localintercom.app.domain.repository.RoomRepository
import com.localintercom.app.domain.usecase.CreateRoomUseCase
import com.localintercom.app.domain.usecase.JoinDiscoveredRoomUseCase
import com.localintercom.app.domain.usecase.JoinManualRoomUseCase
import com.localintercom.app.domain.usecase.LeaveRoomUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomScreenState(
    val displayName: String = "",
    val roomName: String = "",
    val sessionToken: String = "",
    val manualHostAddress: String = "",
    val manualPort: String = "",
    val selectedRoom: DiscoveredRoom? = null,
    val discoveredRooms: List<DiscoveredRoom> = emptyList(),
    val discoveryState: DiscoveryState = DiscoveryState(),
    val activeRoom: RoomSession? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
    val displayNameError: String? = null
)

class RoomViewModel(private val repository: RoomRepository) : ViewModel() {
    private val createRoom = CreateRoomUseCase(repository)
    private val joinDiscoveredRoom = JoinDiscoveredRoomUseCase(repository)
    private val joinManualRoom = JoinManualRoomUseCase(repository)
    private val leaveRoomUseCase = LeaveRoomUseCase(repository)

    private val _state = MutableStateFlow(RoomScreenState())
    val state: StateFlow<RoomScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.activeRoom, repository.discoveredRooms, repository.discoveryState) { room, rooms, discovery ->
                Triple(room, rooms, discovery)
            }.collect { (room, rooms, discovery) ->
                _state.update {
                    it.copy(
                        activeRoom = room,
                        discoveredRooms = rooms,
                        selectedRoom = it.selectedRoom?.let { selected -> rooms.firstOrNull { room -> room.roomId == selected.roomId } },
                        discoveryState = discovery
                    )
                }
            }
        }
        repository.refreshDiscovery()
    }

    fun onDisplayNameChanged(value: String) = _state.update {
        it.copy(displayName = value, message = null, displayNameError = null)
    }

    fun validateDisplayName(): Boolean {
        val name = _state.value.displayName.trim()
        val error = when {
            name.isEmpty() -> "Please enter your name before continuing."
            name.length !in 2..24 -> "Your name must be 2–24 characters."
            else -> null
        }
        _state.update { it.copy(displayNameError = error) }
        return error == null
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
    fun onRoomNameChanged(value: String) = _state.update { it.copy(roomName = value, message = null) }
    fun onSessionTokenChanged(value: String) = _state.update { it.copy(sessionToken = value, message = null) }
    fun onManualHostChanged(value: String) = _state.update { it.copy(manualHostAddress = value, message = null) }
    fun onManualPortChanged(value: String) = _state.update { it.copy(manualPort = value, message = null) }
    fun selectRoom(room: DiscoveredRoom) = _state.update { it.copy(selectedRoom = room, message = null) }
    fun refreshDiscovery() = repository.refreshDiscovery()

    fun createRoom() {
        if (!validateDisplayName()) return
        launchOperation { createRoom(_state.value.roomName, _state.value.displayName) }
    }

    fun joinSelectedRoom() {
        if (!validateDisplayName()) return
        launchOperation {
            val snapshot = _state.value
            val room = snapshot.selectedRoom ?: throw IllegalArgumentException("Select a room from the discovery list first.")
            joinDiscoveredRoom(room, snapshot.displayName, snapshot.sessionToken)
        }
    }

    fun joinManualRoom() {
        if (!validateDisplayName()) return
        launchOperation {
            val snapshot = _state.value
            val port = snapshot.manualPort.toIntOrNull()
                ?: throw IllegalArgumentException("Enter the host port.")
            joinManualRoom(
                snapshot.manualHostAddress,
                port,
                snapshot.displayName,
                snapshot.sessionToken
            )
        }
    }

    fun selectChannel(channelId: String) = launchOperation {
        repository.selectChannel(channelId)
    }

    fun setPttActive(active: Boolean) = repository.setPttActive(active)

    fun showMessage(message: String) = _state.update { it.copy(message = message) }

    fun leaveRoom() = launchOperation {
        setPttActive(false)
        leaveRoomUseCase()
    }

    private fun launchOperation(block: suspend () -> Unit) {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(message = error.message ?: "The operation failed. Try again.") }
            } finally {
                _state.update { it.copy(isBusy = false) }
            }
        }
    }

    override fun onCleared() {
        repository.releaseWhenUiCloses()
        super.onCleared()
    }
}
