package com.example.audiotomidi

import com.google.gson.annotations.SerializedName

data class TaskResponse(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String? = null
)
