// SyncMessage.kt
package com.example.sensor_recorder.sync

import org.json.JSONObject

/**
 * A sealed class representing all possible messages exchanged between devices
 * for synchronization and control.
 */
sealed class SyncMessage {
    abstract fun toJson(): String

    // Controller -> Worker messages
    data class Ping(val tNs: Long) : SyncMessage() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "PING")
            put("t_ns", tNs)
        }.toString()
    }

    data class Command(val commandType: String, val sessionId: String) : SyncMessage() {
        override fun toJson(): String = JSONObject().apply {
            put("type", commandType) // e.g., CMD_START, CMD_STOP
            put("session_id", sessionId)
        }.toString()
    }

    // Worker -> Controller messages
    data class Pong(val tSendNs: Long, val tRecvNs: Long, val tReplyNs: Long) : SyncMessage() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "PONG")
            put("t_send_ns", tSendNs)
            put("t_recv_ns", tRecvNs)
            put("t_reply_ns", tReplyNs)
        }.toString()
    }

    data class Ack(val cmd: String, val sessionId: String, val tNs: Long) : SyncMessage() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "ACK")
            put("cmd", cmd)
            put("session_id", sessionId)
            put("t_ns", tNs)
        }.toString()
    }

    data class Status(val recording: Boolean, val accelCount: Long, val gyroCount: Long) : SyncMessage() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "STATUS")
            put("recording", recording)
            put("accel_count", accelCount)
            put("gyro_count", gyroCount)
        }.toString()
    }

    object SyncDone : SyncMessage() {
        override fun toJson(): String = JSONObject().apply { put("type", "SYNC_DONE") }.toString()
    }

    companion object {
        /**
         * Parses a JSON string into a SyncMessage object.
         * Returns null if the JSON is malformed or represents an unknown type.
         */
        fun fromJson(line: String): SyncMessage? {
            return try {
                val json = JSONObject(line)
                when (json.getString("type")) {
                    "PING" -> Ping(json.getLong("t_ns"))
                    "PONG" -> Pong(
                        json.getLong("t_send_ns"),
                        json.getLong("t_recv_ns"),
                        json.getLong("t_reply_ns")
                    )
                    "CMD_START", "CMD_STOP", "CMD_SYNC_TAP" -> Command(
                        json.getString("type"),
                        json.getString("session_id")
                    )
                    "ACK" -> Ack(
                        json.getString("cmd"),
                        json.getString("session_id"),
                        json.getLong("t_ns")
                    )
                    "STATUS" -> Status(
                        json.getBoolean("recording"),
                        json.getLong("accel_count"),
                        json.getLong("gyro_count")
                    )
                    "SYNC_DONE" -> SyncDone
                    else -> null
                }
            } catch (e: Exception) {
                null // Ignore malformed JSON
            }
        }
    }
}
