package com.example.audiotomidi

import com.google.gson.annotations.SerializedName

data class TaskStatus(
    @SerializedName("task_id") val taskId: String?,
    @SerializedName("status") val status: String,
    @SerializedName("download_url") val downloadUrl: String?,
    @SerializedName("error") val error: String?,
    @SerializedName("processing_time") val processingTime: Double?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("updated_at") val updatedAt: String?,
    @SerializedName("position_in_queue") val positionInQueue: Int?,
    @SerializedName("filename") val filename: String?,
    @SerializedName("message") val message: String?
)
