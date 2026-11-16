package com.example.minicpm_v_demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.minicpm_v_demo.harness.HarnessFacade
import com.example.minicpm_v_demo.harness.attribution.AttributionFeatureFlags
import com.example.minicpm_v_demo.harness.attribution.AttributionModelRegistry
import com.example.minicpm_v_demo.harness.attribution.AttributionModelStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 提供主模型的选择、下载、加载和删除入口，同时管理独立的归因模型配置。 */
class ModelManagerActivity : AppCompatActivity() {

    private class ModelControls(root: View) {
        val status: TextView = root.findViewById(R.id.tv_model_status)
        val download: MaterialButton = root.findViewById(R.id.btn_download)
        val load: MaterialButton = root.findViewById(R.id.btn_load_model)
        val delete: MaterialButton = root.findViewById(R.id.btn_delete_model)
        val progress: LinearProgressIndicator = root.findViewById(R.id.progress_download)
    }

    private lateinit var agentA: ModelControls
    private lateinit var agentB: ModelControls
    private lateinit var tabs: TabLayout
    private lateinit var tvLanguageValue: TextView
    private lateinit var switchDoubleAgent: MaterialSwitch

    private lateinit var harness: HarnessFacade
    private lateinit var attributionEngine: AttributionLlamaEngine
    private lateinit var attributionModelStore: AttributionModelStore
    private lateinit var attributionFeatureFlags: AttributionFeatureFlags
    private var updatingAttributionControls = false
    private var fileActionRunning = false
    private var pendingDownloadId: String? = null
    private var startingDownloadId: String? = null

    // Android 13+: POST_NOTIFICATIONS is a runtime permission. We need it
    // for the foreground download service's progress notification (without
    // a notification the OS will outright kill the foreground service).
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // We start the download regardless of grant: the service still
            // posts a notification, the user just won't see it. Foreground
            // service itself is allowed without the permission.
            if (!granted) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_notification_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
            pendingDownloadId?.let(::startDownload)
            pendingDownloadId = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_manager)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val rootContent = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootContent) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = sysBars.left,
                top = sysBars.top,
                right = sysBars.right,
                bottom = sysBars.bottom
            )
            insets
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        agentB = ModelControls(findViewById(R.id.agent_b_controls))
        agentA = ModelControls(findViewById(R.id.agent_a_controls))
        tvLanguageValue = findViewById(R.id.tv_language_value)
        switchDoubleAgent = findViewById(R.id.switch_double_agent)

        harness = HarnessFacade.getInstance(applicationContext)
        attributionEngine = AttributionLlamaEngine.getInstance(applicationContext)
        attributionModelStore = AttributionModelStore(applicationContext)
        attributionFeatureFlags = AttributionFeatureFlags(applicationContext)
        pendingDownloadId = savedInstanceState?.getString(PENDING_DOWNLOAD_ID)

        setupTabs(savedInstanceState?.getInt(SELECTED_TAB) ?: 0)
        setupModelList()
        setupAttributionControls()
        updateLoadButtonState()
        observeEngineState()
        observeDownloadStatus()
        updateLanguageDisplay()

        agentB.download.setOnClickListener { onDownloadClicked(harness.getSelectedModel()) }
        agentB.load.setOnClickListener { loadSelectedModel() }
        agentB.delete.setOnClickListener { confirmDeleteModel(harness.getSelectedModel(), isAgentA = false) }
        agentA.download.setOnClickListener { onDownloadClicked(attributionModelStore.getSelectedEntry().model) }
        agentA.load.setOnClickListener { loadAttributionModel() }
        agentA.delete.setOnClickListener {
            confirmDeleteModel(attributionModelStore.getSelectedEntry().model, isAgentA = true)
        }
        findViewById<View>(R.id.btn_language).setOnClickListener { showLanguagePicker() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(SELECTED_TAB, tabs.selectedTabPosition)
        outState.putString(PENDING_DOWNLOAD_ID, pendingDownloadId)
        super.onSaveInstanceState(outState)
    }

    private fun setupTabs(initialTab: Int) {
        tabs = findViewById(R.id.model_tabs)
        val agentBSection = findViewById<View>(R.id.agent_b_section)
        val agentASection = findViewById<View>(R.id.agent_a_section)
        val scroll = findViewById<ScrollView>(R.id.model_manager_scroll)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                agentBSection.isVisible = tab.position == 0
                agentASection.isVisible = tab.position == 1
                scroll.scrollTo(0, 0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        tabs.getTabAt(initialTab)?.select()
    }

    private fun setupAttributionControls() {
        val models = AttributionModelRegistry.eligibleEntries().map { it.model }
        val list = findViewById<RecyclerView>(R.id.recycler_attribution_models)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ModelAdapter(models, attributionModelStore.getSelectedEntry().model.id) { model ->
            attributionModelStore.setSelectedModel(model.id)
            updateAttributionControls()
        }.apply { canSelect = { !fileActionRunning } }

        switchDoubleAgent.setOnCheckedChangeListener { _, enabled ->
            if (updatingAttributionControls) return@setOnCheckedChangeListener
            if (enabled && !attributionModelStore.isSelectedModelDownloaded()) {
                Toast.makeText(this, R.string.attribution_enable_requires_model, Toast.LENGTH_LONG).show()
            } else {
                attributionFeatureFlags.setLocalAgentAEnabled(enabled)
            }
            updateAttributionControls()
        }
        lifecycleScope.launch {
            attributionEngine.loadedModelPath.collect { updateAttributionControls() }
        }
    }

    private fun loadAttributionModel() {
        val files = attributionModelStore.getSelectedModelFiles()
        if (!files.llmFile.isFile || fileActionRunning) return
        runFileAction(agentA, R.string.status_loading, R.string.toast_load_failed) {
            attributionEngine.loadTextModel(files.entry.model, files.llmFile)
            Toast.makeText(this, R.string.toast_model_loaded, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAttributionControls() {
        val files = attributionModelStore.getSelectedModelFiles()
        val exists = files.llmFile.isFile
        val loaded = exists && attributionEngine.loadedModelPath.value == files.llmFile.absolutePath
        if (!fileActionRunning) {
            agentA.status.text = when {
                loaded -> getString(R.string.status_ready)
                exists -> getString(R.string.attribution_status_downloaded, files.entry.model.displayName)
                else -> getString(R.string.attribution_status_missing, files.entry.model.displayName)
            }
        }
        updateButtons(agentA, exists, loaded, files.entry.model.id, fileActionRunning)
        showDownloadProgress(agentA, files.entry.model.id)

        if (!exists && attributionFeatureFlags.localAgentAEnabled) {
            attributionFeatureFlags.setLocalAgentAEnabled(false)
        }
        switchDoubleAgent.isEnabled = exists && !fileActionRunning
        updatingAttributionControls = true
        switchDoubleAgent.isChecked = exists && attributionFeatureFlags.localAgentAEnabled
        updatingAttributionControls = false
    }

    private fun setupModelList() {
        val selectedModel = harness.getSelectedModel()
        val modelAdapter = ModelAdapter(
            models = harness.availableModels(),
            selectedModelId = selectedModel.id,
            onModelSelected = { model ->
                val previousModelId = harness.getSelectedModel().id
                harness.setSelectedModel(model.id)
                updateLoadButtonState()

                if (previousModelId != model.id) {
                    val wasLoaded = harness.state.value is LlamaState.ModelReady
                    if (wasLoaded) {
                        reloadSelectedModel()
                    } else {
                        agentB.status.text = getString(R.string.switched_to_load, model.displayName)
                    }
                }
            }
        )

        modelAdapter.canSelect = { !fileActionRunning && !isAgentBBusy() }
        val recyclerModels = findViewById<RecyclerView>(R.id.recycler_models)
        recyclerModels.layoutManager = LinearLayoutManager(this)
        recyclerModels.adapter = modelAdapter
    }

    private fun reloadSelectedModel() {
        val model = harness.getSelectedModel()

        if (!harness.isSelectedModelDownloaded()) {
            agentB.status.text = getString(R.string.switched_to_download, model.displayName)
            lifecycleScope.launch(Dispatchers.IO) {
                try { harness.unloadModel() } catch (_: Exception) {}
                withContext(Dispatchers.Main) { updateLoadButtonState() }
            }
            return
        }

        agentB.load.isEnabled = false
        agentB.download.isEnabled = false
        agentB.status.text = getString(R.string.switching_to, model.displayName)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                harness.unloadModel()
                harness.loadSelectedModel()
                harness.markModelSwitched()
                withContext(Dispatchers.Main) {
                    updateLoadButtonState()
                    Toast.makeText(this@ModelManagerActivity, getString(R.string.toast_load_success, model.displayName), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading model", e)
                harness.resetToInitialized()
                withContext(Dispatchers.Main) {
                    agentB.status.text = getString(R.string.toast_load_failed, e.message)
                    updateLoadButtonState()
                }
            }
        }
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            harness.state.collect { state ->
                when (state) {
                    is LlamaState.Uninitialized -> {
                        agentB.status.text = getString(R.string.status_uninitialized)
                    }
                    is LlamaState.Initializing -> {
                        agentB.status.text = getString(R.string.status_initializing)
                    }
                    is LlamaState.Initialized -> {
                        agentB.status.text = getString(R.string.status_initialized)
                    }
                    is LlamaState.LoadingModel -> {
                        agentB.status.text = getString(R.string.status_loading)
                    }
                    is LlamaState.ModelReady -> {
                        agentB.status.text = getString(R.string.status_ready)
                    }
                    is LlamaState.ProcessingSystemPrompt,
                    is LlamaState.ProcessingUserPrompt -> {
                        agentB.status.text = getString(R.string.status_generating)
                    }
                    is LlamaState.PrefillingImage -> {
                        agentB.status.text = getString(R.string.status_prefilling_image)
                    }
                    is LlamaState.Generating -> {
                        agentB.status.text = getString(R.string.status_generating)
                    }
                    is LlamaState.UnloadingModel -> {
                        agentB.status.text = getString(R.string.status_unloading)
                    }
                    is LlamaState.Error -> {
                        agentB.status.text = getString(R.string.status_error, state.exception.message)
                    }
                }
                updateLoadButtonState()
                updateAttributionControls()
            }
        }
    }

    private fun isAgentBBusy(): Boolean = when (harness.state.value) {
        LlamaState.Uninitialized, LlamaState.Initializing, LlamaState.LoadingModel, LlamaState.UnloadingModel,
        LlamaState.ProcessingSystemPrompt, LlamaState.ProcessingUserPrompt,
        LlamaState.PrefillingImage, LlamaState.Generating -> true
        else -> false
    }

    private fun updateLoadButtonState() {
        val model = harness.getSelectedModel()
        updateButtons(
            agentB,
            harness.isSelectedModelDownloaded(),
            harness.state.value is LlamaState.ModelReady,
            model.id,
            fileActionRunning || isAgentBBusy(),
        )
        showDownloadProgress(agentB, model.id)
    }

    private fun updateButtons(controls: ModelControls, exists: Boolean, loaded: Boolean, modelId: String, busy: Boolean) {
        val downloadInProgress = startingDownloadId != null || ModelDownloadController.isRunning
        val downloadingModel = startingDownloadId == modelId ||
            (ModelDownloadController.status.value as? ModelDownloadController.Status.Running)?.modelId == modelId
        controls.download.isEnabled = !busy && !downloadInProgress
        controls.load.isEnabled = exists && !busy && !downloadingModel
        controls.delete.isVisible = exists
        controls.delete.isEnabled = !busy && !downloadingModel
        controls.load.setText(when {
            exists && loaded -> R.string.reload_model
            exists -> R.string.load_model
            else -> R.string.no_model_file
        })
    }

    private fun onDownloadClicked(model: ModelInfo) {
        if (ModelDownloadController.isRunning || startingDownloadId != null) {
            Toast.makeText(this, R.string.toast_downloading, Toast.LENGTH_SHORT).show()
            return
        }
        val downloaded = if (model.id == harness.getSelectedModel().id) harness.isSelectedModelDownloaded()
            else attributionModelStore.isSelectedModelDownloaded()
        if (downloaded) {
            Toast.makeText(this, R.string.toast_already_downloaded, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadId = model.id
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startDownload(model.id)
        }
    }

    private fun startDownload(modelId: String) {
        if (ModelDownloadController.isRunning || startingDownloadId != null) return
        startingDownloadId = modelId
        updateLoadButtonState()
        updateAttributionControls()
        try {
            harness.startDownloadService(modelId)
        } catch (error: Exception) {
            startingDownloadId = null
            updateLoadButtonState()
            updateAttributionControls()
            Toast.makeText(this, getString(R.string.download_failed, error.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun showDownloadProgress(controls: ModelControls, modelId: String) {
        val running = ModelDownloadController.status.value as? ModelDownloadController.Status.Running
        val downloading = startingDownloadId == modelId || running?.modelId == modelId
        controls.progress.isVisible = downloading
        if (downloading) {
            controls.status.text = running?.message?.ifBlank { null } ?: getString(R.string.status_downloading)
        }
    }

    private fun observeDownloadStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloadController.status.collect { status ->
                    if (status !is ModelDownloadController.Status.Idle) startingDownloadId = null
                    val matchingControls = buildList {
                        if (status.modelId == harness.getSelectedModel().id) add(agentB)
                        if (status.modelId == attributionModelStore.getSelectedEntry().model.id) add(agentA)
                    }
                    updateLoadButtonState()
                    updateAttributionControls()
                    val message = when (status) {
                        is ModelDownloadController.Status.Completed -> getString(R.string.download_complete_status)
                        is ModelDownloadController.Status.Cancelled -> getString(R.string.download_cancelled)
                        is ModelDownloadController.Status.Failed -> getString(R.string.download_failed, status.message)
                        else -> null
                    }
                    if (message != null) {
                        matchingControls.forEach { it.status.text = message }
                        Toast.makeText(this@ModelManagerActivity, message, Toast.LENGTH_SHORT).show()
                        ModelDownloadController.acknowledge()
                    }
                }
            }
        }
    }

    /** 聊天模型在这里卸载旧模型并加载所选文件。语音模型则由语音合成页面按需加载。 */
    private fun loadSelectedModel() {
        val model = harness.getSelectedModel()
        // TTS models are loaded on-demand by TtsActivity, not via LlamaEngine.
        if (model.isTts) {
            harness.markModelSwitched()
            finish() // return to parent; MainActivity will redirect
            return
        }

        val currentState = harness.state.value
        if (currentState is LlamaState.LoadingModel) {
            Toast.makeText(this, R.string.toast_already_loading, Toast.LENGTH_SHORT).show()
            return
        }

        if (!harness.isSelectedModelDownloaded()) {
            Toast.makeText(this, R.string.toast_model_not_found, Toast.LENGTH_LONG).show()
            return
        }

        val isReload = currentState is LlamaState.ModelReady

        agentB.load.isEnabled = false
        agentB.download.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isReload) {
                    Log.i(TAG, "Unloading model for reload...")
                    harness.unloadModel()
                }

                harness.loadSelectedModel()
                // No default system prompt: aligned with iOS opt-r1. See
                // MainActivity.clearChat() for the rationale.

                withContext(Dispatchers.Main) {
                    agentB.load.isEnabled = true
                    agentB.download.isEnabled = true
                    updateLoadButtonState()
                    Toast.makeText(this@ModelManagerActivity, R.string.toast_model_loaded, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                harness.resetToInitialized()
                withContext(Dispatchers.Main) {
                    agentB.status.text = getString(R.string.toast_load_failed, e.message)
                    agentB.load.isEnabled = true
                    agentB.download.isEnabled = true
                    updateLoadButtonState()
                }
            }
        }
    }

    private fun confirmDeleteModel(model: ModelInfo, isAgentA: Boolean) {
        val shared = model.isTextOnly && AttributionModelRegistry.findEligibleEntry(model.id) != null &&
            harness.availableModels().any { it.id == model.id }
        val names = if (isAgentA) listOf(model.ggufFileName) else harness.selectedModelArtifactNames()
        val fileList = names.joinToString(separator = "\n") { "• $it" }
        val message = getString(R.string.delete_dialog_message, model.displayName, fileList) +
            if (shared) "\n\n" + getString(R.string.shared_model_delete_note) else ""
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ -> deleteModelFiles(model, isAgentA, shared) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteModelFiles(model: ModelInfo, isAgentA: Boolean, shared: Boolean) {
        val controls = if (isAgentA) agentA else agentB
        if (fileActionRunning || (shared && harness.getSelectedModel().id == model.id && isAgentBBusy())) return
        runFileAction(controls, R.string.status_unloading, R.string.toast_delete_failed) {
            val deleted = withContext(Dispatchers.IO) {
                if (shared && harness.getSelectedModel().id == model.id) harness.unloadModel()
                if (isAgentA || shared) {
                    val file = File(LlamaEngine.modelDirFor(applicationContext, model), model.ggufFileName)
                    attributionEngine.deleteModelFile(file)
                } else {
                    harness.deleteSelectedModelFiles()
                }
            }
            val result = if (deleted) R.string.model_files_deleted else R.string.toast_no_files_to_delete
            if (deleted && shared && harness.getSelectedModel().id == model.id) agentB.status.setText(result)
            Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
        }
    }

    private fun runFileAction(controls: ModelControls, status: Int, errorMessage: Int, action: suspend () -> Unit) {
        fileActionRunning = true
        updateLoadButtonState()
        updateAttributionControls()
        controls.status.setText(status)
        lifecycleScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Model file operation failed", error)
                Toast.makeText(this@ModelManagerActivity, getString(errorMessage, error.message), Toast.LENGTH_LONG).show()
            } finally {
                fileActionRunning = false
                updateLoadButtonState()
                updateAttributionControls()
            }
        }
    }

    private fun updateLanguageDisplay() {
        tvLanguageValue.text = LocaleManager.currentLanguage(this).displayName
    }

    private fun showLanguagePicker() {
        val current = LocaleManager.currentLanguage(this)
        val options = LocaleManager.AppLanguage.entries.toTypedArray()
        val items = options.map {
            if (it == current) "${it.displayName}  \u2713" else it.displayName
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.language_picker_title)
            .setItems(items) { _, which ->
                val picked = options[which]
                if (picked != current) {
                    LocaleManager.setLanguageAndRestart(this, picked)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val PENDING_DOWNLOAD_ID = "pending_download_id"
        private const val SELECTED_TAB = "selected_tab"
        private val TAG = ModelManagerActivity::class.java.simpleName
    }
}
