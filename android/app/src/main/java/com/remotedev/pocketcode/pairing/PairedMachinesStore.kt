package com.remotedev.pocketcode.pairing

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

// Multi-machine store. EncryptedSharedPreferences keeps tokens at rest AES-256-GCM.
class PairedMachinesStore(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "pocketcode-machines",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _flow = MutableStateFlow(load())
    val machines get() = _flow

    fun add(qr: PairingQR, name: String? = null): PairedMachine {
        val m = PairedMachine(
            id = UUID.randomUUID().toString(),
            name = name ?: qr.url.substringAfter("://").substringBefore('.'),
            url = qr.url, token = qr.token, fingerprint = qr.fp, pairedAtMs = System.currentTimeMillis(),
        )
        save(_flow.value + m); _flow.value = _flow.value + m
        return m
    }

    fun remove(id: String) { val next = _flow.value.filter { it.id != id }; save(next); _flow.value = next }
    fun get(id: String): PairedMachine? = _flow.value.firstOrNull { it.id == id }

    /** Silently update the persisted token for a machine after a server-side token refresh. */
    fun updateToken(id: String, newToken: String) {
        val next = _flow.value.map { if (it.id == id) it.copy(token = newToken) else it }
        save(next)
        _flow.value = next
    }

    private fun load(): List<PairedMachine> {
        val raw = prefs.getString("machines", "[]") ?: "[]"
        return runCatching { Json.decodeFromString<List<PairedMachine>>(raw) }.getOrDefault(emptyList())
    }
    private fun save(list: List<PairedMachine>) {
        prefs.edit().putString("machines", Json.encodeToString(list)).apply()
    }
}
