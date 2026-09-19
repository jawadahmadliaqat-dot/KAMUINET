package com.kamuinet.client.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UsageDao {
    @Insert
    suspend fun insertLog(log: LocalUsageLog)

    @Query("SELECT * FROM local_usage_logs ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentLogs(): List<LocalUsageLog>

    @Query("SELECT SUM(bytesSent + bytesReceived) FROM local_usage_logs")
    suspend fun getTotalBytesUsed(): Long?
}
