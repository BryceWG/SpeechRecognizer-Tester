package com.example.speechrecognizertester

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 简单的 SpeechRecognizer 测试工具：
 * - 枚举当前系统已开放的 RecognitionService
 * - 允许选择一个具体服务并通过 SpeechRecognizer 调用
 */
class MainActivity : AppCompatActivity(), RecognitionListener {

    companion object {
        private const val MAX_LOG_CHARS = 8_000
        private const val PREFS_NAME = "sr_tester_prefs"
        private const val PREF_KEY_LAST_SERVICE = "last_service"
    }

    // 简单使用 findViewById，避免引入 ViewBinding 生成依赖
    private lateinit var spinnerServices: Spinner
    private lateinit var btnRefresh: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnCancel: Button
    private lateinit var chkPartial: CheckBox
    private lateinit var editLanguage: EditText
    private lateinit var txtServiceInfo: TextView
    private lateinit var txtPartial: TextView
    private lateinit var txtFinal: TextView
    private lateinit var txtLog: TextView
    private lateinit var txtRms: TextView

    private data class ServiceEntry(
        val displayName: String,
        val componentName: ComponentName,
        val requiredPermission: String?,
        val exported: Boolean,
        val enabled: Boolean
    ) {
        override fun toString(): String = displayName
    }

    private val services = mutableListOf<ServiceEntry>()
    private var speechRecognizer: SpeechRecognizer? = null

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private var suppressSpinnerCallback: Boolean = false

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    getString(R.string.msg_record_permission_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerServices = findViewById(R.id.spinner_services)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnCancel = findViewById(R.id.btn_cancel)
        chkPartial = findViewById(R.id.chk_partial)
        editLanguage = findViewById(R.id.edit_language)
        txtServiceInfo = findViewById(R.id.txt_service_info)
        txtPartial = findViewById(R.id.txt_partial)
        txtFinal = findViewById(R.id.txt_final)
        txtLog = findViewById(R.id.txt_log)
        txtRms = findViewById(R.id.txt_rms)

        setupUi()
        checkAndRequestAudioPermission()
        loadRecognitionServices()
    }

    private fun setupUi() {
        btnRefresh.setOnClickListener {
            loadRecognitionServices()
        }

        btnStart.setOnClickListener {
            startRecognition()
        }

        btnStop.setOnClickListener {
            speechRecognizer?.stopListening()
        }

        btnCancel.setOnClickListener {
            speechRecognizer?.cancel()
        }
    }

    private fun checkAndRequestAudioPermission() {
        if (!hasAudioPermission()) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasAudioPermission(): Boolean {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        appendLog("hasAudioPermission() = $granted")
        return granted
    }

    private fun loadRecognitionServices() {
        val pm = packageManager
        val resolveInfos = queryRecognitionServices()

        services.clear()

        resolveInfos.forEach { ri ->
            val si = ri.serviceInfo ?: return@forEach
            val appLabel = pm.getApplicationLabel(si.applicationInfo)?.toString() ?: si.packageName
            val serviceLabel = ri.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                ?: si.name.substringAfterLast('.')
            val displayName = buildString {
                append(appLabel)
                append(" / ")
                append(serviceLabel)
                append("\n")
                append(si.packageName)
                append("/")
                append(si.name.substringAfterLast('.'))
            }
            val component = ComponentName(si.packageName, si.name)
            services += ServiceEntry(
                displayName = displayName,
                componentName = component,
                requiredPermission = si.permission,
                exported = si.exported,
                enabled = si.enabled
            )
        }

        if (services.isEmpty()) {
            Toast.makeText(this, R.string.msg_no_services, Toast.LENGTH_LONG).show()
            txtServiceInfo.text = getString(R.string.label_service_info_placeholder)
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            services
        )
        spinnerServices.adapter = adapter

        // 切换服务时重建 SpeechRecognizer
        spinnerServices.setOnItemSelectedListenerCompat { position ->
            if (suppressSpinnerCallback) return@setOnItemSelectedListenerCompat
            onServiceSelected(position)
        }

        val lastComponent = prefs.getString(PREF_KEY_LAST_SERVICE, null)
        val initialIndex = services.indexOfFirst { it.componentName.flattenToString() == lastComponent }
            .takeIf { it >= 0 }
            ?: 0

        if (services.isNotEmpty()) {
            suppressSpinnerCallback = true
            spinnerServices.setSelection(initialIndex)
            suppressSpinnerCallback = false
            onServiceSelected(initialIndex)
        } else {
            releaseSpeechRecognizer()
        }
    }

    private fun queryRecognitionServices(): List<ResolveInfo> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val pm = packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.queryIntentServices(pm, intent)
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        }
    }

    private object Api33 {
        fun queryIntentServices(pm: PackageManager, intent: Intent): List<ResolveInfo> {
            return pm.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        }
    }

    private fun onServiceSelected(position: Int) {
        val entry = services.getOrNull(position)
        releaseSpeechRecognizer()
        if (entry == null) {
            txtServiceInfo.text = getString(R.string.label_service_info_placeholder)
            return
        }

        prefs.edit()
            .putString(PREF_KEY_LAST_SERVICE, entry.componentName.flattenToString())
            .apply()

        createSpeechRecognizer(entry.componentName)

        val permissionText = entry.requiredPermission?.takeIf { it.isNotBlank() }
            ?: getString(R.string.label_permission_none)
        txtServiceInfo.text = getString(
            R.string.label_service_info,
            entry.displayName,
            entry.componentName.flattenToShortString(),
            permissionText,
            entry.exported.toString(),
            entry.enabled.toString()
        )
    }

    private fun createSpeechRecognizer(componentName: ComponentName) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.msg_speech_not_available, Toast.LENGTH_LONG).show()
            return
        }
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this, componentName).apply {
                setRecognitionListener(this@MainActivity)
            }
            appendLog(getString(R.string.msg_bind_service, componentName.flattenToShortString()))
        } catch (t: Throwable) {
            speechRecognizer = null
            appendLog(
                getString(
                    R.string.msg_bind_service_failed,
                    componentName.flattenToShortString(),
                    t.message ?: "unknown"
                )
            )
            Toast.makeText(this, R.string.msg_bind_service_failed_short, Toast.LENGTH_LONG).show()
        }
    }

    private fun releaseSpeechRecognizer() {
        val sr = speechRecognizer ?: return
        speechRecognizer = null
        try {
            sr.cancel()
        } catch (_: Throwable) {
        }
        try {
            sr.destroy()
        } catch (_: Throwable) {
        }
    }

    private fun startRecognition() {
        if (!hasAudioPermission()) {
            Toast.makeText(
                this,
                getString(R.string.msg_record_permission_denied),
                Toast.LENGTH_LONG
            ).show()
            checkAndRequestAudioPermission()
            return
        }

        val entry = services.getOrNull(spinnerServices.selectedItemPosition)
        if (entry == null) {
            Toast.makeText(this, R.string.msg_select_service_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (speechRecognizer == null) {
            createSpeechRecognizer(entry.componentName)
        }
        val sr = speechRecognizer
        if (sr == null) {
            Toast.makeText(this, R.string.msg_recognizer_not_ready, Toast.LENGTH_LONG).show()
            return
        }

        txtPartial.text = ""
        txtFinal.text = ""
        appendLog(getString(R.string.msg_start_recognition, entry.componentName.flattenToShortString()))

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, chkPartial.isChecked)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            val langTag = editLanguage.text.toString().trim()
            if (langTag.isNotEmpty()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
            }
        }
        try {
            sr.startListening(intent)
        } catch (t: Throwable) {
            appendLog(getString(R.string.msg_start_failed, t.message ?: "unknown"))
        }
    }

    private fun runOnUi(block: () -> Unit) {
        if (Looper.getMainLooper().thread === Thread.currentThread()) {
            block()
        } else {
            runOnUiThread(block)
        }
    }

    private fun appendLog(msg: String) {
        runOnUi {
            val stamped = "[${SystemClock.elapsedRealtime()}] $msg"
            val old = txtLog.text?.toString().orEmpty()
            val text = buildString {
                appendLine(stamped)
                if (old.isNotBlank()) append(old)
            }
            txtLog.text = if (text.length > MAX_LOG_CHARS) text.substring(0, MAX_LOG_CHARS) else text
        }
    }

    // RecognitionListener 实现

    override fun onReadyForSpeech(params: Bundle?) {
        appendLog("onReadyForSpeech")
    }

    override fun onBeginningOfSpeech() {
        appendLog("onBeginningOfSpeech")
    }

    override fun onRmsChanged(rmsdB: Float) {
        // 简单显示当前振幅
        runOnUi {
            txtRms.text = getString(R.string.label_rms_value, rmsdB)
        }
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        // 不特别处理
    }

    override fun onEndOfSpeech() {
        appendLog("onEndOfSpeech")
    }

    override fun onError(error: Int) {
        val label = speechErrorToString(error)
        appendLog("onError: $label ($error)")
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            Toast.makeText(
                this,
                getString(R.string.msg_error_insufficient_permissions_detailed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResults(results: Bundle) {
        val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = list?.firstOrNull().orEmpty()
        runOnUi { txtFinal.text = text }
        appendLog("onResults: $text")
    }

    override fun onPartialResults(partialResults: Bundle) {
        val list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = list?.firstOrNull().orEmpty()
        runOnUi { txtPartial.text = text }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
        appendLog("onEvent: type=$eventType")
    }

    override fun onDestroy() {
        releaseSpeechRecognizer()
        super.onDestroy()
    }

    override fun onStop() {
        releaseSpeechRecognizer()
        super.onStop()
    }

    private fun speechErrorToString(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            else -> "ERROR_UNKNOWN"
        }
    }
}

// Spinner 的简单扩展：兼容旧 API 的 OnItemSelectedListener 写法
private inline fun android.widget.Spinner.setOnItemSelectedListenerCompat(
    crossinline onSelected: (position: Int) -> Unit
) {
    this.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>?,
            view: android.view.View?,
            position: Int,
            id: Long
        ) {
            onSelected(position)
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
            // ignore
        }
    }
}
