package com.localintercom.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.localintercom.app.domain.repository.RoomRepository

class RoomViewModelFactory(
    private val repository: RoomRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RoomViewModel::class.java)) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        return RoomViewModel(repository) as T
    }
}
