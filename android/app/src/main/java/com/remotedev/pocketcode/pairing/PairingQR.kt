package com.remotedev.pocketcode.pairing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PairingQR(
    val v: Int,
    val url: String,
    val token: String,
    val fp: String,
    val exp: Long,
)

object QrParser {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(raw: String): PairingQR? = runCatching { json.decodeFromString<PairingQR>(raw.trim()) }.getOrNull()
}

@Serializable
data class PairedMachine(
    val id: String,            // stable uuid
    val name: String,
    val url: String,
    val token: String,
    val fingerprint: String,
    val pairedAtMs: Long,
)
