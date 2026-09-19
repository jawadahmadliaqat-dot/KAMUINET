package com.kamuinet.client.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_usage_logs")
data class LocalUsageLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String, // "host" or "client"
    val timestamp: Long = System.currentTimeMillis(),
    val bytesSent: Long,
    val bytesReceived: Long,
    val targetUid: String?
)
