package com.remotedev.pocketcode

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.remotedev.pocketcode.commands.SavedCommandsStore
import com.remotedev.pocketcode.connection.ConnectionManager
import com.remotedev.pocketcode.persistence.Db

class PocketcodeApp : Application() {
    val connection by lazy { ConnectionManager(this) }
    val machines by lazy { com.remotedev.pocketcode.pairing.PairedMachinesStore(this) }
    val savedCommands by lazy { SavedCommandsStore(this) }
    val db by lazy {
        Room.databaseBuilder(this, Db::class.java, "pocketcode.db")
            .addMigrations(object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("CREATE TABLE IF NOT EXISTS note (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, content TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                }
            })
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
