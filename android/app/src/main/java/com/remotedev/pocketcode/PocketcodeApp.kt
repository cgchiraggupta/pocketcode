package com.remotedev.pocketcode

import android.app.Application
import androidx.room.Room
import com.remotedev.pocketcode.connection.ConnectionManager
import com.remotedev.pocketcode.persistence.Db

class PocketcodeApp : Application() {
    val connection by lazy { ConnectionManager(this) }
    val machines by lazy { com.remotedev.pocketcode.pairing.PairedMachinesStore(this) }
    val db by lazy {
        Room.databaseBuilder(this, Db::class.java, "pocketcode.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // ponytail: create the notification channel here -- without it,
        // Android 8+ drops every notification posted by Notifier.show().
        com.remotedev.pocketcode.notifications.Notifier.ensure(this)
    }
    companion object { lateinit var instance: PocketcodeApp }
}
