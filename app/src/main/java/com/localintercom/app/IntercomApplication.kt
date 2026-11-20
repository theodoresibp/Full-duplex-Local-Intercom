package com.localintercom.app

import android.app.Application
import com.localintercom.app.data.repository.KtorRoomRepository
import com.localintercom.app.domain.repository.RoomRepository

class IntercomApplication : Application() {
    lateinit var roomRepository: RoomRepository
        private set

    override fun onCreate() {
        super.onCreate()
        roomRepository = KtorRoomRepository(this)
    }
}
