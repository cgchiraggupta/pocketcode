package com.remotedev.pocketcode.persistence

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcript")
data class Transcript(@PrimaryKey(autoGenerate = true) val id: Long = 0, val session: String, val blob: ByteArray, val ts: Long)
@Entity(tableName = "agent_event")
data class StoredEvent(@PrimaryKey(autoGenerate = true) val id: Long = 0, val session: String, val kind: String, val summary: String, val ts: Long)

@Dao
interface Dao {
    @Insert suspend fun addTranscript(t: Transcript): Long
    @Query("SELECT * FROM transcript WHERE session = :s ORDER BY ts") fun streamTranscript(s: String): Flow<List<Transcript>>
    @Query("SELECT * FROM agent_event WHERE session = :s ORDER BY ts") fun events(s: String): Flow<List<StoredEvent>>
    @Insert suspend fun addEvent(e: StoredEvent)
}

@Database(entities = [Transcript::class, StoredEvent::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() { abstract fun dao(): Dao }
