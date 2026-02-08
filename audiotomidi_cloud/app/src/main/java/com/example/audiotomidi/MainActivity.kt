package com.example.audiotomidi

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.audiotomidi.utils.FileUtil
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var apiService: ApiService
    private var audioRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    private var taskId: String? = null
    private var pollingJob: Job? = null
    private var isProcessing = false

    // UI 组件
    private lateinit var recordButton: Button
    private lateinit var uploadButton: Button
    private lateinit var selectFileButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var resultInfo: TextView
    private lateinit var serverUrl: EditText
    private lateinit var apiKeyField: EditText
    private lateinit var saveApiKeySwitch: Switch
    private lateinit var useHttpsSwitch: Switch

    // 配置
    private val API_KEY_PREFS_NAME = "api_key_prefs"
    private val SERVER_PREFS_NAME = "server_prefs"
    private var savedApiKey: String? = null
    private var savedServerUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化 UI
        initUiComponents()

        // 从共享偏好设置中加载配置
        loadPreferences()

        // 初始化 API 服务
        initApiService()

        // 设置事件监听
        setupEventListeners()

        // 请求必要权限
        requestRequiredPermissions()
    }

    private fun initUiComponents() {
        recordButton = findViewById(R.id.recordButton)
        uploadButton = findViewById(R.id.uploadButton)
        selectFileButton = findViewById(R.id.selectFileButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        downloadButton = findViewById(R.id.downloadButton)
        resultInfo = findViewById(R.id.resultInfo)
        serverUrl = findViewById(R.id.serverUrl)
        apiKeyField = findViewById(R.id.apiKeyField)
        saveApiKeySwitch = findViewById(R.id.saveApiKeySwitch)
        useHttpsSwitch = findViewById(R.id.useHttpsSwitch)
    }

    private fun setupEventListeners() {
        recordButton.setOnClickListener { toggleRecording() }
        uploadButton.setOnClickListener { uploadAudio() }
        selectFileButton.setOnClickListener { selectAudioFile() }
        downloadButton.setOnClickListener { downloadMidi() }
        useHttpsSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateServerUrlHint()
            saveServerPreferences()
        }

        saveApiKeySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                clearSavedApiKey()
            }
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(SERVER_PREFS_NAME, Context.MODE_PRIVATE)
        savedServerUrl = prefs.getString("server_url", null)

        val apiKeyPrefs = getSharedPreferences(API_KEY_PREFS_NAME, Context.MODE_PRIVATE)
        savedApiKey = if (apiKeyPrefs.getBoolean("save_key", false)) {
            apiKeyPrefs.getString("api_key", null)
        } else {
            null
        }

        applySavedSettings()
        updateServerUrlHint()
    }

    private fun applySavedSettings() {
        if (savedServerUrl != null) {
            serverUrl.setText(savedServerUrl)
            val isHttps = savedServerUrl?.startsWith("https://") ?: false
            useHttpsSwitch.isChecked = isHttps
        } else {
            serverUrl.setText("47.94.214.142") // Default to your server IP
            useHttpsSwitch.isChecked = true // Default to HTTPS
        }

        if (savedApiKey != null) {
            apiKeyField.setText(savedApiKey)
            saveApiKeySwitch.isChecked = true
        }
    }

    private fun saveServerPreferences() {
        val prefs = getSharedPreferences(SERVER_PREFS_NAME, Context.MODE_PRIVATE)
        with(prefs.edit()) {
            putString("server_url", serverUrl.text.toString())
            putBoolean("use_https", useHttpsSwitch.isChecked)
            apply()
        }
    }

    private fun saveApiKey() {
        if (saveApiKeySwitch.isChecked && apiKeyField.text.isNotBlank()) {
            val prefs = getSharedPreferences(API_KEY_PREFS_NAME, Context.MODE_PRIVATE)
            with(prefs.edit()) {
                putString("api_key", apiKeyField.text.toString())
                putBoolean("save_key", true)
                apply()
            }
        }
    }

    private fun clearSavedApiKey() {
        val prefs = getSharedPreferences(API_KEY_PREFS_NAME, Context.MODE_PRIVATE)
        with(prefs.edit()) {
            putString("api_key", null)
            putBoolean("save_key", false)
            apply()
        }
    }

    private fun initApiService() {
        val isDebugMode = BuildConfig.DEBUG

        // 处理服务器 URL
        var baseUrl = serverUrl.text.toString().trim()
        if (baseUrl.isEmpty()) {
            baseUrl = if (isDebugMode) "https://47.94.214.142" else "https://your-production-server.com"
        }

        // 确保 URL 有正确的协议
        if (!baseUrl.startsWith("http")) {
            val protocol = if (useHttpsSwitch.isChecked) "https://" else "http://"
            baseUrl = "$protocol$baseUrl"
        }

        // 去掉末尾的斜杠
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length - 1)
        }

        // 创建 API 服务
        apiService = ApiService.create(baseUrl, isDebugMode)

        Log.d("Network", "API 服务初始化完成")
        Log.d("Network", "调试模式: $isDebugMode")
        Log.d("Network", "服务器地址: $baseUrl")
    }

    private fun updateServerUrlHint() {
        val protocol = if (useHttpsSwitch.isChecked) "https://" else "http://"
        serverUrl.hint = "${protocol}your-server-ip-or-domain"
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupResources()
    }

    private fun cleanupResources() {
        // 释放录音资源
        stopRecording()
        // 停止轮询
        pollingJob?.cancel()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.INTERNET
        )

        val requiredPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (requiredPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!granted) {
                Toast.makeText(this, "需要权限才能使用录音和存储功能", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        if (isProcessing) {
            showToast("正在处理文件，请等待完成")
            return
        }

        if (isRecording) {
            stopRecording()
            updateRecordButtonState(false)
        } else {
            startRecording()
            updateRecordButtonState(true)
        }
        isRecording = !isRecording
    }

    private fun updateRecordButtonState(isRecording: Boolean) {
        runOnUiThread {
            recordButton.text = if (isRecording) "停止录音" else "开始录音"
            recordButton.setBackgroundColor(
                getColor(if (isRecording) R.color.stop_button else R.color.record_button)
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        try {
            // 创建输出文件
            val timestamp = System.currentTimeMillis()
            val audioDir = FileUtil.getAudioDirectory(this)
            outputFile = File(audioDir, "recording_$timestamp.wav")

            // 确保目录存在
            audioDir.mkdirs()

            // 配置录音器
            audioRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
                start()
            }

            updateStatus("正在录音...", R.color.recording)
            setInteractionEnabled(false)

        } catch (e: Exception) {
            handleRecordingError(e)
        }
    }

    private fun handleRecordingError(e: Exception) {
        Log.e("Recording", "录音失败: ${e.message}", e)
        showToast("录音失败: ${e.message ?: "未知错误"}")

        resetRecordingState()
        updateRecordButtonState(false)
    }

    private fun resetRecordingState() {
        isRecording = false
        setInteractionEnabled(true)
    }

    private fun stopRecording() {
        try {
            audioRecorder?.apply {
                stop()
                release()
            }
            audioRecorder = null

            if (outputFile?.exists() == true) {
                val fileSizeMb = (outputFile!!.length().toDouble() / (1024 * 1024)).toInt()
                updateStatus("录音已保存 (${fileSizeMb}MB)\n${outputFile!!.name}", R.color.success)
                setInteractionEnabled(true)

                checkFileSizeWarning(fileSizeMb)
            }
        } catch (e: Exception) {
            Log.e("Recording", "停止录音失败: ${e.message}", e)
            updateStatus("录音保存失败: ${e.message}", R.color.error)
        }
    }

    private fun checkFileSizeWarning(fileSizeMb: Int) {
        if (fileSizeMb > 95) {
            updateStatus("警告: 文件接近100MB限制 (${fileSizeMb}MB)", R.color.warning)
        }
    }

    private fun selectAudioFile() {
        if (isProcessing) {
            showToast("正在处理文件，请等待完成")
            return
        }

        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerLauncher.launch(intent)
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedAudioFile(uri)
            }
        }
    }

    @SuppressLint("Recycle")
    private fun handleSelectedAudioFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = processSelectedFile(uri)
                validateAndDisplayFile(file)
            } catch (e: Exception) {
                handleFileSelectionError(e)
            }
        }
    }

    private suspend fun processSelectedFile(uri: Uri): File {
        val fileName = FileUtil.getFileNameFromUri(this@MainActivity, uri) ?: "selected_audio.wav"
        val cleanFileName = sanitizeFileName(fileName)

        val audioDir = FileUtil.getAudioDirectory(this@MainActivity)
        val file = File(audioDir, cleanFileName)

        contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        return file
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private suspend fun validateAndDisplayFile(file: File) {
        if (file.exists() && file.length() > 0) {
            val fileSizeMb = (file.length().toDouble() / (1024 * 1024)).toInt()

            withContext(Dispatchers.Main) {
                if (fileSizeMb > 100) {
                    handleFileSizeError(fileSizeMb)
                } else {
                    outputFile = file
                    outputFile?.let { validateFileType(it) }

                    updateStatus("已选择文件: ${file.name}\n大小: ${fileSizeMb}MB", R.color.success)
                    uploadButton.isEnabled = true
                    recordButton.isEnabled = true
                }
            }
        } else {
            throw Exception("无法访问选定的音频文件")
        }
    }

    private fun validateFileType(file: File) {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        val allowedExtensions = listOf("wav", "mp3", "flac", "ogg", "m4a", "aac")

        if (!allowedExtensions.contains(extension)) {
            updateStatus("警告: 文件类型 .$extension 可能不受支持", R.color.warning)
        }
    }

    private fun handleFileSizeError(fileSizeMb: Int) {
        updateStatus("错误: 文件过大 (${fileSizeMb}MB)，最大支持100MB", R.color.error)
        showToast("文件过大，最大支持100MB")
    }

    private fun handleFileSelectionError(e: Exception) {
        Log.e("FileSelect", "文件选择失败: ${e.message}", e)
        lifecycleScope.launch(Dispatchers.Main) {
            updateStatus("文件选择失败: ${e.message}", R.color.error)
            showToast("文件选择失败: ${e.message}")
        }
    }

    private fun uploadAudio() {
        if (!validateUploadConditions()) return

        val apiKey = apiKeyField.text.toString().trim()
        saveUserPreferences(apiKey)

        // 重建API服务（应用新配置）
        initApiService()

        // 禁用操作按钮
        setInteractionEnabled(false)
        isProcessing = true

        lifecycleScope.launch {
            showLoading(true)
            try {
                uploadFileToServer(apiKey)
            } catch (e: Exception) {
                handleUploadError(e)
            } finally {
                showLoading(false)
            }
        }
    }

    private fun validateUploadConditions(): Boolean {
        if (outputFile == null || !outputFile!!.exists()) {
            showToast("没有可上传的音频文件")
            return false
        }

        if (outputFile!!.length() > 100 * 1024 * 1024) { // 100MB
            showToast("文件过大，最大支持100MB")
            return false
        }

        val apiKey = apiKeyField.text.toString().trim()
        if (apiKey.isEmpty()) {
            showToast("请输入API密钥")
            return false
        }

        return true
    }

    private fun saveUserPreferences(apiKey: String) {
        saveApiKey()
        saveServerPreferences()
    }

    private suspend fun uploadFileToServer(apiKey: String) {
        val file = outputFile!!
        val requestFile = file.asRequestBody("audio/*".toMediaType())
        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)

        updateStatus("正在上传文件...", R.color.processing)

        val response = withContext(Dispatchers.IO) {
            apiService.uploadAudio(apiKey, body)
        }

        handleUploadResponse(response, apiKey)
    }

    private fun handleUploadResponse(response: TaskResponse, apiKey: String) {
        taskId = response.taskId
        updateStatus("任务已提交\nID: ${response.taskId}\n状态: ${response.status}", R.color.processing)
        Log.d("Upload", "任务ID: ${response.taskId}")

        // 开始轮询任务状态
        startPollingTaskStatus(apiKey)
    }

    private fun handleUploadError(e: Exception) {
        var errorMessage = "未知错误"

        when (e) {
            is HttpException -> {
                errorMessage = when (e.code()) {
                    404 -> "服务器端点不存在，请检查服务器URL"
                    401 -> {
                        apiKeyField.error = "API密钥无效"
                        "API密钥无效或过期"
                    }
                    413 -> "文件过大 (最大100MB)"
                    429 -> "服务器繁忙，请稍后再试"
                    507 -> "服务器磁盘空间不足"
                    else -> "服务器错误 (${e.code()})"
                }
            }
            is IOException -> {
                errorMessage = if (e.message?.contains("SSL", ignoreCase = true) == true) {
                    "SSL证书错误。开发模式请使用IP地址，生产模式需有效证书"
                } else {
                    "网络连接失败: ${e.message}"
                }
            }
            else -> {
                errorMessage = e.message ?: "处理失败"
            }
        }

        // 404特殊处理
        if (e is HttpException && e.code() == 404) {
            showServerEndpointErrorDialog()
            resetProcessingState()
            return
        }

        // 显示错误
        lifecycleScope.launch(Dispatchers.Main) {
            statusText.text = "❌ 上传失败: $errorMessage"
            statusText.setTextColor(getColor(R.color.error))
            Toast.makeText(this@MainActivity, "上传失败: $errorMessage", Toast.LENGTH_LONG).show()
        }

        resetProcessingState()
    }

    private fun showServerEndpointErrorDialog() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("服务器端点错误 (404)")
                .setMessage("无法连接到服务器API端点。请检查:\n\n• 服务器URL是否正确\n• 服务是否正在运行\n• Nginx配置是否正确\n• 防火墙是否开放端口\n\n服务器地址: ${serverUrl.text}")
                .setPositiveButton("重试") { _, _ ->
                    initApiService()
                    if (outputFile?.exists() == true) {
                        uploadAudio()
                    }
                }
                .setNeutralButton("检查健康状态") { _, _ ->
                    checkServerHealth()
                }
                .setNegativeButton("取消") { _, _ ->
                    resetProcessingState()
                }
                .show()
        }
    }

    private fun checkServerHealth() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val baseUrl = serverUrl.text.toString().trim().let { url ->
                    if (!url.startsWith("http")) {
                        "http://$url"
                    } else {
                        url
                    }
                }
                val healthUrl = if (baseUrl.endsWith("/")) {
                    "${baseUrl}health"
                } else {
                    "$baseUrl/health"
                }

                val request = Request.Builder().url(healthUrl).build()
                val response = okhttp3.OkHttpClient().newCall(request).execute()

                var message = ""
                if (response.isSuccessful) {
                    message = response.body?.string() ?: "无响应内容"
                } else {
                    message = "健康检查失败: HTTP ${response.code}"
                }

                lifecycleScope.launch(Dispatchers.Main) {
                    showHealthStatusDialog(message)
                }
            } catch (e: Exception) {
                lifecycleScope.launch(Dispatchers.Main) {
                    showHealthStatusDialog("健康检查异常: ${e.message}")
                }
            }
        }
    }

    private fun showHealthStatusDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("服务器健康状态")
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> }
            .show()
    }

    private fun startPollingTaskStatus(apiKey: String) {
        pollingJob?.cancel() // 取消之前的轮询

        pollingJob = lifecycleScope.launch {
            var attempts = 0
            val maxAttempts = 120 // 最多轮询10分钟 (每5秒一次)

            while (attempts < maxAttempts && isActive) {
                delay(if (attempts == 0) 2000 else 5000) // 首次查询延迟2秒
                attempts++

                try {
                    pollTaskStatus(apiKey, attempts)
                } catch (e: Exception) {
                    handlePollingError(e, attempts, maxAttempts)
                    if (attempts >= 4) { // 连续4次错误后停止
                        break
                    }
                }
            }

            handlePollingTimeout(attempts, maxAttempts)
        }
    }

    private suspend fun pollTaskStatus(apiKey: String, attempts: Int) {
        val taskId = this@MainActivity.taskId ?: return

        withContext(Dispatchers.Main) {
            updateStatus("处理中... (${attempts * 5}秒)\nID: $taskId", R.color.processing)
        }

        val statusResponse = withContext(Dispatchers.IO) {
            apiService.getTaskStatus(apiKey, taskId)
        }

        Log.d("Polling", "状态: ${statusResponse.status}, 尝试: $attempts")
        processTaskStatus(statusResponse, attempts)
    }

    private fun processTaskStatus(statusResponse: TaskStatus, attempts: Int) {
        when (statusResponse.status) {
            "completed" -> handleTaskCompleted(statusResponse)
            "failed" -> handleTaskFailed(statusResponse)
            else -> continueProcessing(statusResponse, attempts)
        }
    }

    private fun handleTaskCompleted(statusResponse: TaskStatus) {
        lifecycleScope.launch(Dispatchers.Main) {
            val processingTime = statusResponse.processingTime?.let { "$it 秒" } ?: "未知"
            updateStatus("✅ 处理成功!\n耗时: $processingTime", R.color.success)

            setupDownloadResult(statusResponse)
            resetProcessingState()
        }
    }

    private fun setupDownloadResult(statusResponse: TaskStatus) {
        statusResponse.downloadUrl?.let { url ->
            val filename = Uri.parse(url).lastPathSegment ?: "result.mid"
            resultInfo.text = "📁 文件: $filename"
            resultInfo.visibility = View.VISIBLE
            downloadButton.isEnabled = true

            // 保存文件名用于下载
            val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            with(prefs.edit()) {
                putString("last_download_url", url)
                putString("last_filename", filename)
                apply()
            }
        }
    }

    private fun handleTaskFailed(statusResponse: TaskStatus) {
        lifecycleScope.launch(Dispatchers.Main) {
            val error = statusResponse.error ?: "未知错误"
            updateStatus("❌ 处理失败\n错误: $error", R.color.error)
            showRetryDialog(error)
            resetProcessingState()
        }
    }

    private fun continueProcessing(statusResponse: TaskStatus, attempts: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            updateStatus("⏳ 处理中... (${attempts * 5}秒)\n状态: ${statusResponse.status}", R.color.processing)
        }
    }

    private fun handlePollingError(e: Exception, attempts: Int, maxAttempts: Int) {
        Log.e("Polling", "轮询错误 (尝试 $attempts): ${e.message}", e)

        // 特殊处理404错误
        if (e is HttpException && e.code() == 404) {
            if (attempts == 1) {
                // 首次404可能是任务刚创建，稍等再试
                return
            }

            lifecycleScope.launch(Dispatchers.Main) {
                statusText.text = "⚠️ 任务状态异常 (404)\n可能服务器重启，尝试重新上传"
                statusText.setTextColor(getColor(R.color.warning))

                if (attempts >= 3) {
                    showTaskNotFoundErrorDialog()
                }
            }
            return
        }

        // 其他错误处理
        lifecycleScope.launch(Dispatchers.Main) {
            val errorMsg = when (e) {
                is HttpException -> "服务器错误 (${e.code()})"
                is IOException -> "网络连接失败"
                else -> e.message ?: "未知错误"
            }

            statusText.text = "❌ 轮询失败 ($attempts/$maxAttempts): $errorMsg"
            statusText.setTextColor(getColor(R.color.error))
        }
    }

    private fun showTaskNotFoundErrorDialog() {
        AlertDialog.Builder(this)
            .setTitle("任务不存在")
            .setMessage("服务器返回'任务不存在'错误。这通常发生在:\n\n• 服务器重启后任务丢失\n• 任务已过期(24小时)\n• 上传过程中断\n\n建议重新上传文件")
            .setPositiveButton("重新上传") { _, _ ->
                if (outputFile?.exists() == true) {
                    uploadAudio()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                resetProcessingState()
            }
            .show()
    }

    private fun handlePollingTimeout(attempts: Int, maxAttempts: Int) {
        if (attempts >= maxAttempts) {
            lifecycleScope.launch(Dispatchers.Main) {
                statusText.text = "❌ 处理超时 (10分钟)\n请尝试更短的音频片段"
                statusText.setTextColor(getColor(R.color.error))
                showRetryDialog("处理超时")
                resetProcessingState()
            }
        }
    }

    private fun resetProcessingState() {
        isProcessing = false
        setInteractionEnabled(true)
    }

    private fun setInteractionEnabled(enabled: Boolean) {
        runOnUiThread {
            recordButton.isEnabled = enabled && !isRecording
            uploadButton.isEnabled = enabled
            selectFileButton.isEnabled = enabled
            downloadButton.isEnabled = enabled
            progressBar.visibility = if (!enabled) View.VISIBLE else View.GONE
        }
    }

    private fun showRetryDialog(errorMessage: String?) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("处理失败")
                .setMessage("错误: ${errorMessage ?: "未知错误"}\n\n建议:\n• 尝试更短的音频片段\n• 检查网络连接\n• 确认API密钥正确")
                .setPositiveButton("重试") { _, _ ->
                    if (outputFile?.exists() == true) {
                        uploadAudio()
                    }
                }
                .setNegativeButton("取消") { _, _ -> }
                .show()
        }
    }

    private fun downloadMidi() {
        val apiKey = apiKeyField.text.toString().trim()
        if (apiKey.isEmpty()) {
            showToast("请输入API密钥")
            return
        }

        lifecycleScope.launch {
            showLoading(true)
            try {
                downloadFileFromServer(apiKey)
            } catch (e: Exception) {
                handleDownloadError(e)
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun downloadFileFromServer(apiKey: String) {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val downloadUrl = prefs.getString("last_download_url", null)
        val filename = prefs.getString("last_filename", "result.mid")

        if (downloadUrl.isNullOrEmpty() || filename.isNullOrEmpty()) {
            throw Exception("没有可用的下载信息")
        }

        val cleanFilename = sanitizeFileName(filename)
        val finalFilename = if (!cleanFilename.endsWith(".mid")) "$cleanFilename.mid" else cleanFilename

        updateStatus("正在下载: $finalFilename", R.color.processing)

        val response = withContext(Dispatchers.IO) {
            apiService.downloadMidiFile(apiKey, Uri.parse(downloadUrl).lastPathSegment ?: finalFilename)
        }

        handleDownloadResponse(response, finalFilename)
    }

    private fun handleDownloadResponse(response: Response<ResponseBody>, filename: String) {
        if (response.isSuccessful) {
            saveDownloadedFile(response.body()!!, filename)
        } else {
            throw Exception("下载失败: HTTP ${response.code()}")
        }
    }

    private fun saveDownloadedFile(fileBody: okhttp3.ResponseBody, filename: String) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, filename)

            FileOutputStream(file).use { fos ->
                fileBody.byteStream().copyTo(fos)
            }

            refreshMediaStore(file)
            showDownloadSuccess(file, filename)
        } catch (e: Exception) {
            handleFileSaveError(e)
        }
    }

    private fun refreshMediaStore(file: File) {
        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        mediaScanIntent.data = Uri.fromFile(file)
        sendBroadcast(mediaScanIntent)
    }

    private fun showDownloadSuccess(file: File, filename: String) {
        runOnUiThread {
            updateStatus("✅ 下载成功!\n保存至: ${file.absolutePath}", R.color.success)
            showToast("MIDI文件已保存至下载目录: $filename")
            showOpenFileOption(file)
        }
    }

    private fun handleFileSaveError(e: Exception) {
        Log.e("Download", "保存文件失败", e)
        runOnUiThread {
            updateStatus("❌ 保存失败: ${e.message}", R.color.error)
            showToast("文件保存失败: ${e.message}")
        }
    }

    private fun showOpenFileOption(file: File) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("下载完成")
                .setMessage("MIDI文件已保存。要打开文件吗？")
                .setPositiveButton("打开") { _, _ ->
                    openMidiFile(file)
                }
                .setNegativeButton("完成") { _, _ -> }
                .show()
        }
    }

    private fun openMidiFile(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )

            intent.setDataAndType(uri, "audio/midi")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            if (packageManager.queryIntentActivities(intent, 0).isNotEmpty()) {
                startActivity(intent)
            } else {
                showMidiAppsDialog()
            }
        } catch (e: Exception) {
            handleFileOpenError(e)
        }
    }

    private fun handleFileOpenError(e: Exception) {
        Log.e("OpenFile", "打开文件失败", e)
        showToast("打开文件失败: ${e.message}")
    }

    private fun showMidiAppsDialog() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("推荐MIDI应用")
                .setMessage("您的设备没有安装MIDI查看器。推荐安装以下应用：\n\n• MobileSheets (Android)\n• MuseScore (免费)\n• Perfect Piano")
                .setPositiveButton("打开Google Play") { _, _ ->
                    openGooglePlayStore()
                }
                .setNegativeButton("取消") { _, _ -> }
                .show()
        }
    }

    private fun openGooglePlayStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.musescore")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.musescore")))
        }
    }

    private fun handleDownloadError(e: Exception) {
        val errorMessage = when (e) {
            is HttpException -> when (e.code()) {
                401 -> "API密钥无效"
                404 -> "文件不存在或已过期"
                else -> "下载错误 (${e.code()})"
            }
            else -> e.message ?: "下载失败"
        }

        updateStatus("❌ $errorMessage", R.color.error)
        showToast("下载失败: $errorMessage")
    }

    private fun showLoading(show: Boolean) {
        runOnUiThread {
            progressBar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun updateStatus(message: String, colorRes: Int) {
        runOnUiThread {
            statusText.text = message
            statusText.setTextColor(getColor(colorRes))
        }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }
}
