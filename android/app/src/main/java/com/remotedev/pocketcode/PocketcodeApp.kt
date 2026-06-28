package com.remotedev.pocketcode

import android.app.Application
import com.remotedev.pocketcode.connection.ConnectionManager

class PocketcodeApp : Application() {
    val connection by lazy { ConnectionManager(this) }
    val machines by lazy { com.remotedev.pocketcode.pairing.PairedMachinesStore(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    companion object { lateinit var instance: PocketcodeApp }
}
