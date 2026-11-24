package com.example.minicpm_v_demo

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.textfield.TextInputEditText
import com.example.minicpm_v_demo.harness.HarnessChatRequest
import com.example.minicpm_v_demo.harness.HarnessFacade
import com.example.minicpm_v_demo.harness.HarnessModelFamily
import com.example.minicpm_v_demo.harness.character.CharacterCard
import com.example.minicpm_v_demo.harness.character.CharacterPromptDebug
import com.example.minicpm_v_demo.harness.character.CharacterFollowUpResolver
import com.example.minicpm_v_demo.harness.character.RoleplayObservationContext
import com.example.minicpm_v_demo.harness.character.RoleplayAnswerabilityDecision
import com.example.minicpm_v_demo.harness.character.RoleplayAnswerabilityGate
import com.example.minicpm_v_demo.harness.character.RoleplayOutputObserver
import com.example.minicpm_v_demo.harness.character.RoleplayLeakDiagnostics
import com.example.minicpm_v_demo.harness.character.RoleplayGenerationAction
import com.example.minicpm_v_demo.harness.character.RoleplayCharacterIdentity
import com.example.minicpm_v_demo.harness.character.RoleplayGenerationCoordinator
import com.example.minicpm_v_demo.harness.character.RoleplayOutputDecision
import com.example.minicpm_v_demo.harness.character.RoleplayVisibleText
import com.example.minicpm_v_demo.harness.rag.AndroidRagOrchestrator
import com.example.minicpm_v_demo.harness.rag.CompiledAndroidPrompt
import com.example.minicpm_v_demo.harness.rag.RagMode
import com.example.minicpm_v_demo.harness.rag.RagPromptLogcatPrinter
import com.example.minicpm_v_demo.harness.rag.RagSource
import com.example.minicpm_v_demo.harness.rag.RagTurnTraceWriter
import com.example.minicpm_v_demo.harness.data.HarnessDataPaths
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper
import com.example.minicpm_v_demo.harness.data.database.DatabaseRuntime
import com.example.minicpm_v_demo.harness.data.database.DatabaseInstallResult
import com.example.minicpm_v_demo.harness.data.database.DatabaseIdentity
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleHandle
import com.example.minicpm_v_demo.harness.attribution.LocalAttributionRewriteService
import com.example.minicpm_v_demo.harness.attribution.AgentBPromptFormat
import com.example.minicpm_v_demo.harness.attribution.AttributionFeatureFlags
import com.example.minicpm_v_demo.harness.attribution.AttributionModelStore
import com.example.minicpm_v_demo.harness.data.database.DatabaseTurnLease
import com.example.minicpm_v_demo.harness.session.ChatSession
import com.example.minicpm_v_demo.harness.session.ChatSessionStore
import com.example.minicpm_v_demo.harness.session.ChatTurn
import com.example.minicpm_v_demo.harness.session.MemoryStore
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** 聊天页入口，协调媒体输入、模型生成和会话恢复。角色回合用绑定的数据包、角色和剧情截止点贯穿检索、生成与保存。 */
class MainActivity : AppCompatActivity() {

    private lateinit var recyclerChat: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var etInput: TextInputEditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnCharacter: ImageButton
    private lateinit var btnRagMode: ImageButton
    private lateinit var btnClearChat: ImageButton
    private lateinit var btnModelManager: ImageButton
    private lateinit var cardInputBar: View
    private lateinit var appBarLayout: AppBarLayout

    private lateinit var harness: HarnessFacade
    private lateinit var sessionStore: ChatSessionStore
    private lateinit var memoryStore: MemoryStore
    private var ragOrchestrator: AndroidRagOrchestrator? = null
    private var ragTurnTraceWriter: RagTurnTraceWriter? = null
    private var boundIdentity: DatabaseIdentity? = null
    private var databaseRebinding = false
    private var currentSession: ChatSession? = null
    private var currentRagMode: RagMode = RagMode.OFF
    /** Session-only developer A/B switch; it never changes character data. */
    private var characterKnowledgeGraphEnabled: Boolean = true
    private var generationJob: Job? = null
    private var attributionWarmupJob: Job? = null
    private var isModelReady = false
    private var hasAutoLoaded = false
    private var loadedModelId: String? = null
    private var messageIdCounter = 1L
    private val messages = mutableListOf<ChatMessage>()
    private var createdWithLocale: String? = null
    private var isLocaleRestart = false
    private var currentCharacterId = ""
    private var currentStoryCutoff: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdWithLocale = LocaleManager.currentLanguage(this).tag
        harness = HarnessFacade.getInstance(applicationContext)
        val harnessPaths = HarnessDataPaths.from(applicationContext)
        harnessPaths.ensureRuntimeDirs()
        if (isDebuggableBuild()) {
            ragTurnTraceWriter = RagTurnTraceWriter(harnessPaths.ragTurnTraceFile)
        }
        val initialBundle = (DatabaseRuntime.installation as? DatabaseInstallResult.Ready)?.bundle
        if (initialBundle == null) databaseRebinding = true
        boundIdentity = initialBundle?.let(DatabaseIdentity::from)
        sessionStore = sessionStoreFor(initialBundle)
        memoryStore = MemoryStore(harnessPaths)
        currentSession = if (initialBundle != null) sessionStore.loadRecentSession() else null
        initialBundle?.manifest?.let { manifest ->
            currentCharacterId = currentSession?.characterId ?: manifest.defaultCharacterId
            currentStoryCutoff = currentSession?.storyCutoff ?: manifest.defaultStoryCutoff
        }
        ragOrchestrator = initialBundle?.let { runCatching { AndroidRagOrchestrator.fromContext(applicationContext) }
            .onFailure { Log.e(TAG, "Failed to initialize Android RAG orchestrator", it) }
            .getOrNull() }
        if (currentSession?.storyCutoff == null && currentSession != null) {
            currentStoryCutoff = ragOrchestrator?.availableCharacters()
                ?.firstOrNull { it.npcId == currentCharacterId }?.knowledgeScope?.storyCutoff
                ?: initialBundle?.manifest?.defaultStoryCutoff
        }

        setContentView(R.layout.activity_main)

        // Edge-to-edge: pad the root content for status/nav bars and the IME
        // so the bottom input bar follows the soft keyboard up. Without this,
        // targetSdk=35+ draws content behind the IME and the input bar gets
        // covered.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val rootContent = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootContent) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = sysBars.left,
                top = sysBars.top,
                right = sysBars.right,
                bottom = maxOf(sysBars.bottom, ime.bottom)
            )
            insets
        }

        harness.migrateLegacyLayoutIfNeeded()

        initViews()
        setupRecyclerView()
        setupClickListeners()
        updateRagModeButton()
        updateCharacterButton()
        observeEngineState()
        if (initialBundle == null) {
            enableInput(false)
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    while (!AssetBootstrapper.awaitStartup(1_000)) currentCoroutineContext().ensureActive()
                }
                val lease = withContext(Dispatchers.IO) { DatabaseRuntime.beginTurn() }
                databaseRebinding = false
                if (lease != null) rebindDatabase(lease, retryInput = false, showDialogOnSuccess = false)
                else enableInput(isModelReady)
            }
        }
    }

    private fun initViews() {
        recyclerChat = findViewById(R.id.recycler_chat)
        etInput = findViewById(R.id.et_input)
        btnSend = findViewById(R.id.btn_send)
        btnCharacter = findViewById(R.id.btn_character)
        btnRagMode = findViewById(R.id.btn_rag_mode)
        btnClearChat = findViewById(R.id.btn_clear_chat)
        btnModelManager = findViewById(R.id.btn_model_manager)
        cardInputBar = findViewById(R.id.card_input_bar)
        appBarLayout = findViewById(R.id.appBarLayout)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(Markwon.create(this))
        chatAdapter.setOnStopClick {
            harness.cancelGeneration()
        }
        chatAdapter.setOnSourcesClick { message ->
            showSourcesDialog(message.sources)
        }
        chatAdapter.setOnPromptClick { message ->
            showPromptDialog(message.debugPrompt.orEmpty(), message.debugTrace)
        }
        chatAdapter.setOnSuggestionClick { suggestion ->
            if (isModelReady) {
                etInput.setText(suggestion)
                handleUserInput()
            } else {
                Toast.makeText(this, R.string.toast_load_model_first, Toast.LENGTH_SHORT).show()
            }
        }

        recyclerChat.layoutManager = LinearLayoutManager(this)
        recyclerChat.adapter = chatAdapter

        cardInputBar.viewTreeObserver.addOnGlobalLayoutListener {
            recyclerChat.setPadding(
                recyclerChat.paddingLeft,
                recyclerChat.paddingTop,
                recyclerChat.paddingRight,
                cardInputBar.height
            )
        }

        messages.add(ChatMessage.WelcomeCard())
        restoreRecentSessionMessages()
        chatAdapter.submitList(messages.toList())
    }

    private fun restoreRecentSessionMessages() {
        val restoredTurns = currentSession?.turns.orEmpty().takeLast(20)
        if (restoredTurns.isEmpty()) return
        var nextId = messageIdCounter
        restoredTurns.forEach { turn ->
            messages.add(
                ChatMessage.UserMessage(
                    id = nextId++,
                    text = turn.userText,
                ),
            )
            messages.add(
                ChatMessage.AiMessage(
                    id = nextId++,
                    text = turn.assistantText,
                    isGenerating = false,
                    ragMode = runCatching { RagMode.valueOf(turn.ragMode) }.getOrNull(),
                    sources = turn.sources,
                    debugPrompt = turn.renderedPrompt,
                ),
            )
        }
        messageIdCounter = nextId
    }

    private fun setupClickListeners() {
        btnSend.setOnClickListener { handleUserInput() }
        btnCharacter.setOnClickListener { showCharacterDialog() }
        btnRagMode.setOnClickListener { showRagModeDialog() }
        btnClearChat.setOnClickListener { showClearChatDialog() }
        btnModelManager.setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }

        etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                collapseAppBar()
                scrollToBottom()
            }
        }
    }

    private fun collapseAppBar() {
        appBarLayout.setExpanded(false, true)
    }

    private fun scrollToBottom() {
        recyclerChat.post {
            val adapterCount = chatAdapter.itemCount
            if (adapterCount == 0) return@post
            val layoutManager = recyclerChat.layoutManager as? LinearLayoutManager ?: return@post
            val lastView = layoutManager.findViewByPosition(adapterCount - 1)
            if (lastView != null) {
                val offset = recyclerChat.height - recyclerChat.paddingBottom - lastView.height
                layoutManager.scrollToPositionWithOffset(adapterCount - 1, offset.coerceAtMost(0))
            } else {
                recyclerChat.scrollToPosition(adapterCount - 1)
            }
        }
    }

    private fun showClearChatDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_chat)
            .setMessage(R.string.clear_chat_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                clearChat()
            }
            .setNeutralButton(R.string.history_view) { _, _ -> showReadOnlyHistory() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sessionStoreFor(bundle: DatabaseBundleHandle?): ChatSessionStore = ChatSessionStore(
        paths = HarnessDataPaths.from(applicationContext),
        datasetIdentity = bundle?.let(DatabaseIdentity::from),
        allowedCharacterIds = bundle?.validated?.characterIds,
        allowedCutoffs = bundle?.validated?.cutoffs?.keys,
    )

    private fun showReadOnlyHistory() {
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) { sessionStore.listHistory() }
            if (sessions.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.history_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = sessions.map { session ->
                val dataset = session.datasetId ?: getString(R.string.history_legacy)
                val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(session.updatedAt))
                "${session.characterId} · $dataset · $date"
            }.toTypedArray()
            AlertDialog.Builder(this@MainActivity).setTitle(R.string.history_view)
                .setItems(labels) { _, which ->
                    val session = sessions[which]
                    val content = session.turns.joinToString("\n\n") { turn ->
                        "${getString(R.string.history_player)}：${turn.userText}\n${getString(R.string.history_character)}：${turn.assistantText}"
                    }.ifBlank { getString(R.string.history_empty) }
                    AlertDialog.Builder(this@MainActivity).setTitle(labels[which]).setMessage(content)
                        .setPositiveButton(android.R.string.ok, null).show()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun clearChatUI() {
        messages.clear()
        messages.add(ChatMessage.WelcomeCard())
        messageIdCounter = 1L
        chatAdapter.submitList(messages.toList())
    }

    private fun clearChat() {
        if (databaseRebinding) return
        databaseRebinding = true
        enableInput(false)
        if (generationJob?.isActive == true) harness.cancelGeneration()
        lifecycleScope.launch {
            try {
                generationJob?.cancelAndJoin()
                withContext(Dispatchers.IO) {
                    if (harness.state.value is LlamaState.ModelReady) harness.clearContext()
                    if (boundIdentity != null) sessionStore.clearRecentSession()
                }
                currentSession = null
                clearChatUI()
                Toast.makeText(this@MainActivity, R.string.clear_chat_toast, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing context", e)
                Toast.makeText(this@MainActivity, getString(R.string.toast_clear_chat_failed, e.message), Toast.LENGTH_SHORT).show()
            } finally {
                databaseRebinding = false
                enableInput(isModelReady)
            }
        }
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            harness.state.collect { state ->
                when (state) {
                    is LlamaState.Uninitialized,
                    is LlamaState.Initializing -> {
                        enableInput(false)
                    }
                    is LlamaState.Initialized -> {
                        enableInput(false)
                        if (!hasAutoLoaded) {
                            hasAutoLoaded = true
                            loadDefaultModel()
                        }
                    }
                    is LlamaState.LoadingModel -> {
                        enableInput(false)
                    }
                    is LlamaState.ModelReady -> {
                        isModelReady = true
                        loadedModelId = harness.getSelectedModel().id
                        enableInput(true)
                        warmAttributionModelIfNeeded()
                    }
                    is LlamaState.ProcessingSystemPrompt,
                    is LlamaState.ProcessingUserPrompt,
                    is LlamaState.Generating -> {
                        enableInput(false)
                    }
                    is LlamaState.UnloadingModel -> {
                        enableInput(false)
                    }
                    is LlamaState.Error -> {
                        enableInput(false)
                    }
                }
            }
        }
    }

    private fun enableInput(enable: Boolean) {
        val allowed = enable && !databaseRebinding
        etInput.isEnabled = allowed
        btnSend.isEnabled = allowed
    }

    private fun loadDefaultModel() {
        val availability = harness.getSelectedModelAvailability()
        val model = availability.model

        if (availability.ggufMissing) {
            promptDownloadModel()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                harness.loadSelectedModel()
                loadedModelId = model.id
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                harness.resetToInitialized()
                hasAutoLoaded = false
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_model_load_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Warm an enabled Agent A after B is ready, including after process restart or direct chat launch. */
    private fun warmAttributionModelIfNeeded() {
        if (attributionWarmupJob?.isActive == true) return
        val flags = AttributionFeatureFlags(applicationContext)
        if (!flags.localAgentAEnabled) return
        val files = AttributionModelStore(applicationContext).getSelectedModelFiles()
        if (!files.llmFile.isFile) return
        val engine = AttributionLlamaEngine.getInstance(applicationContext)
        if (engine.loadedModelPath.value == files.llmFile.absolutePath) return
        attributionWarmupJob = lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                engine.loadTextModel(files.entry.model, files.llmFile)
            }.onSuccess {
                Log.i(TAG, "Agent A warmup complete: ${files.entry.model.id}")
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Agent A warmup failed; turn-time fallback remains available", error)
            }
        }
    }

    private fun promptDownloadModel() {
        AlertDialog.Builder(this)
            .setTitle(R.string.download_prompt_title)
            .setMessage(R.string.download_prompt_all_missing)
            .setCancelable(false)
            .setPositiveButton(R.string.go_download) { _, _ ->
                startActivity(Intent(this, ModelManagerActivity::class.java))
            }
            .setNegativeButton(R.string.later) { _, _ ->
                Toast.makeText(
                    this,
                    R.string.download_prompt_hint,
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    /** 提交输入前获取数据包租约，把本轮使用的角色、模型和会话固定到生成任务中，任务结束后释放租约。 */
    private fun handleUserInput() {
        if (databaseRebinding) return
        val userMsg = etInput.text.toString().trim()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, R.string.toast_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        val lease = DatabaseRuntime.beginTurn()
        if (lease?.identity != boundIdentity) {
            rebindDatabase(lease, retryInput = true)
            return
        }
        if (currentRagMode != RagMode.OFF && (lease == null || ragOrchestrator == null)) {
            lease?.close()
            Toast.makeText(this, databaseUnavailableMessage(), Toast.LENGTH_LONG).show()
            return
        }
        val binding = TurnBinding(
            identity = lease?.identity, mode = currentRagMode, characterId = currentCharacterId,
            storyCutoff = currentStoryCutoff, session = currentSession, store = sessionStore,
            orchestrator = ragOrchestrator, modelId = harness.getSelectedModel().id,
            modelFamily = harness.getSelectedModelSpec().family,
        )

        etInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etInput.windowToken, 0)

        etInput.text = null
        enableInput(false)

        collapseAppBar()

        val msgId = messageIdCounter++
        val userMessage = ChatMessage.UserMessage(id = msgId, text = userMsg)
        messages.add(userMessage)
        chatAdapter.submitList(messages.toList()) {
            scrollToBottom()
        }

        val aiMsgId = messageIdCounter++
        val aiMessage = ChatMessage.AiMessage(id = aiMsgId, text = "", isGenerating = true)
        messages.add(aiMessage)
        chatAdapter.setActiveAiMessage(aiMsgId)
        chatAdapter.submitList(messages.toList()) {
            scrollToBottom()
        }

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            val promptInput = compilePromptForHarness(userMsg, binding)
            val outcome = if (promptInput.isCharacterRoleplay) {
                generateBufferedRoleplay(promptInput, userMsg)
            } else {
                generateStreamingResponse(promptInput, aiMsgId)
            }
            if (outcome.persistedAssistantText != null && (binding.mode == RagMode.OFF || promptInput.compiled != null)) {
                saveCompletedHarnessTurn(
                    userMsg = userMsg,
                    assistantText = outcome.persistedAssistantText,
                    promptInput = promptInput,
                    aiMsgId = aiMsgId,
                    binding = binding,
                )
            }
            withContext(Dispatchers.Main) {
                val index = messages.indexOfFirst { it.id == aiMsgId }
                if (index >= 0) {
                    messages[index] = (messages[index] as ChatMessage.AiMessage).copy(
                        text = outcome.visibleText,
                        isGenerating = false,
                        ragMode = promptInput.compiled?.mode,
                        sources = promptInput.compiled?.sources.orEmpty(),
                        debugPrompt = promptInput.debugPrompt,
                        debugTrace = promptInput.debugTrace,
                    )
                }
                chatAdapter.setGeneratingDone(aiMsgId)
                chatAdapter.clearActiveAiMessage()
                chatAdapter.submitList(messages.toList())
                enableInput(true)
                scrollToBottom()
            }
        }
        generationJob?.invokeOnCompletion { lease?.close() }
    }

    /** Rebuild every consumer and clear native context before accepting a new bundle's turn. */
    private fun rebindDatabase(lease: DatabaseTurnLease?, retryInput: Boolean, showDialogOnSuccess: Boolean = true) {
        if (databaseRebinding) { lease?.close(); return }
        databaseRebinding = true
        enableInput(false)
        if (generationJob?.isActive == true) harness.cancelGeneration()
        val rebindJob = lifecycleScope.launch {
            var success = false
            try {
                generationJob?.cancelAndJoin()
                val bundle = lease?.bundle
                val rebound = withContext(Dispatchers.IO) {
                    if (harness.state.value is LlamaState.ModelReady) harness.clearContext()
                    ragOrchestrator?.close()
                    val store = sessionStoreFor(bundle)
                    val orchestrator = bundle?.let {
                        AndroidRagOrchestrator.fromBundle(it, File(filesDir, "program/prompts/roleplay_system.prompt"),
                            com.example.minicpm_v_demo.harness.rag.VectorRetrievalFeatureFlags.from(applicationContext),
                            LocalAttributionRewriteService(applicationContext, isForegroundRuntimeReady = {
                                harness.state.value is LlamaState.ModelReady
                            }))
                    }
                    Triple(store, orchestrator, if (bundle == null) null else store.loadRecentSession())
                }
                sessionStore = rebound.first
                ragOrchestrator = rebound.second
                currentSession = rebound.third
                boundIdentity = lease?.identity
                currentCharacterId = currentSession?.characterId ?: bundle?.manifest?.defaultCharacterId.orEmpty()
                currentStoryCutoff = currentSession?.storyCutoff ?: bundle?.manifest?.defaultStoryCutoff
                clearChatUI()
                restoreRecentSessionMessages()
                chatAdapter.submitList(messages.toList())
                updateCharacterButton()
                success = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Database rebind failed", e)
                ragOrchestrator = null
                currentSession = null
                Toast.makeText(this@MainActivity, R.string.database_unavailable, Toast.LENGTH_LONG).show()
            } finally {
                lease?.close()
                databaseRebinding = false
                enableInput(isModelReady)
            }
            if (success && retryInput) handleUserInput()
            else if (success && lease != null && showDialogOnSuccess) showCharacterDialog()
            else if (success && lease == null) Toast.makeText(this@MainActivity, R.string.database_unavailable, Toast.LENGTH_LONG).show()
        }
        rebindJob.invokeOnCompletion { lease?.close() }
    }

    /** CHARACTER_RAG never streams raw model tokens into the UI before the guard accepts them. */
    private suspend fun generateBufferedRoleplay(
        promptInput: HarnessPromptInput,
        userMsg: String,
    ): GenerationOutcome {
        val coordinator = RoleplayGenerationCoordinator()
        var request = promptInput.request
        var useFixedResponse = promptInput.fixedResponse != null
        val identity = promptInput.roleplayIdentity ?: RoleplayCharacterIdentity("")
        for (attempt in 0..RoleplayGenerationCoordinator.MAX_REPAIR_ATTEMPTS) {
            when (val rawResult = collectRawResponse(request, promptInput.predictLength, promptInput.fixedResponse, useFixedResponse)) {
                is RawGenerationResult.Failed -> {
                    observeRoleplayAttempt(promptInput, userMsg, rawResult.partialOutput, attempt, null, "failed")
                    Log.e(TAG, "Roleplay generation failed", rawResult.error)
                    return GenerationOutcome(
                        visibleText = getString(
                            R.string.generation_failed,
                            rawResult.error.message ?: rawResult.error::class.java.simpleName,
                        ),
                    )
                }
                is RawGenerationResult.Completed -> {
                    val action = coordinator.resolve(attempt, rawResult.rawOutput, identity.name, userMsg,
                        identity.aliases, fixedResponse = useFixedResponse,
                        publicProfileReply = promptInput.compiled?.publicProfileReply)
                    observeRoleplayAttempt(promptInput, userMsg, rawResult.rawOutput, attempt, action, "completed")
                    when (action) {
                        is RoleplayGenerationAction.Deliver -> return GenerationOutcome(
                            visibleText = action.visibleText,
                            persistedAssistantText = action.visibleText.takeIf { action.persistVisibleText },
                        )
                        is RoleplayGenerationAction.Fallback -> return GenerationOutcome(
                            visibleText = action.visibleText,
                            persistedAssistantText = action.visibleText.takeIf { action.persistVisibleText },
                        )
                        is RoleplayGenerationAction.Retry -> {
                            // Do not include the rejected output in this request.  The native
                            // backend starts a fresh context for each structured prompt.
                            request = request.withRoleplayRepairInstruction(action.ruleIds)
                            useFixedResponse = false
                            Log.i(TAG, "Retrying roleplay generation after rules=${action.ruleIds.joinToString(",")}")
                        }
                    }
                }
            }
        }
        return GenerationOutcome(visibleText = RoleplayGenerationCoordinator.DEFAULT_SAFE_FALLBACK)
    }

    /** 把普通聊天的累计输出增量刷新到当前气泡，并在本轮结束后返回可展示和可保存的结果。 */
    private suspend fun generateStreamingResponse(
        promptInput: HarnessPromptInput,
        aiMsgId: Long,
    ): GenerationOutcome {
        val rawResult = collectRawResponse(
            request = promptInput.request,
            predictLength = promptInput.predictLength,
            fixedResponse = promptInput.fixedResponse,
            useFixedResponse = promptInput.fixedResponse != null,
        ) { currentText ->
            withContext(Dispatchers.Main) {
                val index = messages.indexOfFirst { it.id == aiMsgId }
                if (index >= 0) {
                    messages[index] = ChatMessage.AiMessage(
                        id = aiMsgId,
                        text = currentText,
                        isGenerating = true,
                        ragMode = promptInput.compiled?.mode,
                        sources = promptInput.compiled?.sources.orEmpty(),
                        debugPrompt = promptInput.debugPrompt,
                        debugTrace = promptInput.debugTrace,
                    )
                }
                chatAdapter.updateStreamingText(aiMsgId, currentText)
                scrollToBottom()
            }
        }
        return when (rawResult) {
            is RawGenerationResult.Failed -> {
                Log.e(TAG, "Generation failed", rawResult.error)
                GenerationOutcome(
                    visibleText = getString(
                        R.string.generation_failed,
                        rawResult.error.message ?: rawResult.error::class.java.simpleName,
                    ),
                )
            }
            is RawGenerationResult.Completed -> {
                val visible = RoleplayVisibleText.extract(rawResult.rawOutput).visibleText
                if (visible.isBlank()) {
                    GenerationOutcome(visibleText = getString(R.string.generation_empty_response))
                } else {
                    GenerationOutcome(
                        visibleText = rawResult.rawOutput,
                        persistedAssistantText = rawResult.rawOutput,
                    )
                }
            }
        }
    }

    /** 统一收集模型流和固定回应。协程取消向上透传，生成异常则连同已收集文本返回。 */
    private suspend fun collectRawResponse(
        request: HarnessChatRequest,
        predictLength: Int,
        fixedResponse: String?,
        useFixedResponse: Boolean,
        onCurrentText: suspend (String) -> Unit = {},
    ): RawGenerationResult {
        val rawResponse = StringBuilder()
        val responseFlow: Flow<String> = if (useFixedResponse && fixedResponse != null) {
            flowOf(fixedResponse)
        } else {
            harness.sendChatPrompt(request, predictLength)
        }
        return try {
            responseFlow.collect { token ->
                rawResponse.append(token)
                onCurrentText(rawResponse.toString())
            }
            RawGenerationResult.Completed(rawResponse.toString())
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            RawGenerationResult.Failed(error, rawResponse.toString())
        }
    }

    private fun observeRoleplayAttempt(
        promptInput: HarnessPromptInput,
        userInput: String,
        rawOutput: String,
        attempt: Int,
        action: RoleplayGenerationAction?,
        completion: String,
    ) {
        val observationContext = promptInput.roleplayObservationContext ?: return
        val visibleOutput = RoleplayVisibleText.extract(rawOutput).visibleText
        val decision = action?.toGuardDecision()
        val includeRawRoleplayOutput = RoleplayLeakDiagnostics
            .isRawOutputLoggingEnabled(applicationContext)
        val observation = RoleplayOutputObserver.observe(
            context = observationContext,
            rawOutput = rawOutput,
            visibleOutput = visibleOutput,
            completion = completion,
            includeRawText = includeRawRoleplayOutput,
            attempt = attempt,
            decision = decision,
            userInput = userInput,
        )
        Log.i(TAG, observation.toLogLine(includeRawText = includeRawRoleplayOutput))
    }

    /** 依据回合绑定选择普通聊天或 RAG 路径，并将编译结果转换为后端请求。 */
    private fun compilePromptForHarness(userMsg: String, binding: TurnBinding): HarnessPromptInput {
        if (binding.mode == RagMode.OFF) {
            return HarnessPromptInput(HarnessChatRequest(userPrompt = userMsg), null)
        }
        val orchestrator = binding.orchestrator
            ?: return HarnessPromptInput(HarnessChatRequest(userPrompt = userMsg), null,
                fixedResponse = getString(R.string.database_compile_failed))
        val compiled = runCatching {
            val followUpTarget = CharacterFollowUpResolver.resolve(
                session = binding.session,
                userInput = userMsg,
                characterId = binding.characterId,
                storyCutoff = binding.storyCutoff,
                knownCharacterNames = orchestrator.availableCharacters().flatMap { character ->
                    listOf(character.identity.name) + character.identity.aliases
                },
                datasetIdentity = requireNotNull(binding.identity),
            )
            val compiled = orchestrator.compile(
                userInput = userMsg,
                mode = binding.mode,
                characterId = binding.characterId,
                storyCutoff = binding.storyCutoff,
                conversationSession = binding.session?.takeIf {
                    it.datasetId == binding.identity?.datasetId && it.datasetFingerprint == binding.identity?.fingerprint &&
                        it.characterPackId == binding.identity?.datasetId && it.characterId == binding.characterId &&
                        it.storyCutoff == binding.storyCutoff
                },
                knowledgeGraphEnabled = characterKnowledgeGraphEnabled,
                followUpTarget = followUpTarget,
                modelFamily = binding.modelFamily,
                agentBPromptFormat = if (isDebuggableBuild()) AttributionFeatureFlags(applicationContext).agentBPromptFormat
                    else AgentBPromptFormat.TEXT,
            )
            val characterDebug = compiled.characterDebug as? CharacterPromptDebug
            Log.d(
                TAG,
                "Compiled ${compiled.mode} prompt: sources=${compiled.sources.size}, " +
                    "contextChars=${compiled.contextText.length}, renderedChars=${compiled.renderedPrompt.length}, " +
                    "systemChars=${compiled.rendered.splitPrompt.systemPrompt.length}, " +
                    "userChars=${compiled.rendered.splitPrompt.userPrompt.length}, " +
                    "graphEnabled=$characterKnowledgeGraphEnabled, " +
                    "knowledgeIntent=${characterDebug?.knowledgeQueryIntent}, " +
                    "followUp=${characterDebug?.followUpRoute}, " +
                    "v2=${characterDebug?.knowledgeGraphStatus}/${characterDebug?.knowledgeGraphInjectedChars}, " +
                    "v1Claims=${characterDebug?.v1ClaimGraphStatus}/${characterDebug?.v1ClaimGraphInjectedChars}, " +
                    "v1Relations=${characterDebug?.v1RelationshipGraphStatus}/${characterDebug?.v1RelationshipGraphInjectedChars}, " +
                    "profile=${characterDebug?.profileRoute}/${characterDebug?.profileCandidateCount}/" +
                        "${characterDebug?.profileSelectedIds?.size}/${characterDebug?.profileBudgetDroppedCount}/" +
                        "${characterDebug?.profileInjectedChars}, " +
                    "state=${characterDebug?.stateRoute}/${characterDebug?.stateCandidateCount}/" +
                        "${characterDebug?.stateSelectedIds?.size}/${characterDebug?.stateBudgetDroppedCount}/" +
                        "${characterDebug?.stateInjectedChars}, " +
                    "possession=${characterDebug?.possessionRoute}/${characterDebug?.possessionCandidateCount}/" +
                        "${characterDebug?.possessionSelectedCount}/${characterDebug?.possessionBudgetDroppedCount}/" +
                        "${characterDebug?.possessionInjectedChars}, " +
                    "legacyStory=${characterDebug?.legacyStoryRetrievalExecuted}, " +
                    "legacyMemory=${characterDebug?.legacyMemoryRetrievalExecuted}, " +
                    "estimatedSystemTokens=${characterDebug?.estimatedPromptTokens}, " +
                    "estimatedUserTokens=${characterDebug?.estimatedUserTokens}",
            )
            Log.d(TAG, compiled.turnTrace.toLogLine())
            val attributionTrace = compiled.attributionTrace
            Log.i(
                TAG,
                "ATTRIBUTION_TRACE " +
                    "status=${attributionTrace?.status ?: "not_applicable"} " +
                    "reason=${attributionTrace?.reason ?: "none"} " +
                    "model=${attributionTrace?.modelId ?: "none"} " +
                    "references=${attributionTrace?.referenceCount ?: 0} " +
                    "format=${attributionTrace?.finalPromptFormat ?: "none"} " +
                    "schema=${attributionTrace?.finalPromptSchemaVersion ?: "none"} " +
                    "formatFallback=${attributionTrace?.formatFallbackReason ?: "none"} " +
                    "rawSha=${attributionTrace?.rawPromptFingerprint ?: "none"} " +
                    "finalSha=${attributionTrace?.finalPromptFingerprint ?: "none"}",
            )
            if (isDebuggableBuild() && getSharedPreferences("rag_debug", MODE_PRIVATE)
                    .getBoolean("raw_prompt_logcat", false) && compiled.stageLogs.isNotEmpty()) {
                runCatching {
                    RagPromptLogcatPrinter.print(UUID.randomUUID().toString(), compiled.stageLogs)
                }.onFailure { error ->
                    // Raw prompt diagnostics must never change compile or generation behavior.
                    Log.w(TAG, "Failed to print transient RAG prompt diagnostics", error)
                }
            }
            runCatching { ragTurnTraceWriter?.append(compiled.turnTrace) }
                .onFailure { error -> Log.w(TAG, "Failed to append content-free RAG trace", error) }
            compiled
        }.getOrElse { error ->
            Log.e(TAG, "Database prompt compilation failed", error)
            return HarnessPromptInput(HarnessChatRequest(userPrompt = userMsg), null,
                fixedResponse = getString(R.string.database_compile_failed))
        }
        val roleplayIdentity = if (compiled.mode == RagMode.CHARACTER_RAG) {
            RoleplayCharacterIdentity.fromCards(binding.characterId, orchestrator.availableCharacters())
        } else {
            null
        }
        val roleplayObservationContext = if (compiled.mode == RagMode.CHARACTER_RAG) {
            runCatching {
                val characterDebug = compiled.characterDebug as? CharacterPromptDebug
                RoleplayOutputObserver.createContext(
                    observationId = UUID.randomUUID().toString(),
                    characterId = binding.characterId,
                    characterName = roleplayIdentity?.name.orEmpty(),
                    characterAliases = roleplayIdentity?.aliases.orEmpty(),
                    storyCutoff = binding.storyCutoff,
                    modelId = binding.modelId,
                    ragMode = compiled.mode.name,
                    systemPrompt = compiled.rendered.splitPrompt.systemPrompt,
                    userPrompt = compiled.rendered.splitPrompt.userPrompt,
                    renderedPrompt = compiled.renderedPrompt,
                    estimatedSystemTokens = characterDebug?.estimatedPromptTokens,
                    estimatedUserTokens = characterDebug?.estimatedUserTokens,
                    contextChars = compiled.contextText.length,
                    sourceCount = compiled.sources.size,
                    predictLength = ROLEPLAY_PREDICT_LENGTH,
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to create roleplay observation; continuing generation", error)
            }.getOrNull()
        } else {
            null
        }
        val answerabilityDecision = if (compiled.mode == RagMode.CHARACTER_RAG) {
            RoleplayAnswerabilityGate.evaluate(compiled.characterDebug as? CharacterPromptDebug, userMsg)
        } else {
            RoleplayAnswerabilityDecision.Allow
        }
        val fixedResponse = when (answerabilityDecision) {
            is RoleplayAnswerabilityDecision.BlockFutureFact -> answerabilityDecision.visibleResponse
            is RoleplayAnswerabilityDecision.BlockUnknownFact -> answerabilityDecision.visibleResponse
            RoleplayAnswerabilityDecision.Allow -> null
        }
        if (compiled.mode == RagMode.CHARACTER_RAG) {
            Log.i(TAG, "RAG_GATE querySha=${compiled.turnTrace.queryFingerprint} " +
                "outcome=${answerabilityDecision.javaClass.simpleName} character=${binding.characterId}")
        }
        if (fixedResponse != null) {
            Log.i(TAG, "Character answerability gate=${answerabilityDecision.javaClass.simpleName} character=${binding.characterId}")
        }
        return HarnessPromptInput(
            request = HarnessChatRequest(
                systemPrompt = compiled.rendered.splitPrompt.systemPrompt,
                userPrompt = compiled.rendered.splitPrompt.userPrompt,
                observationId = roleplayObservationContext?.observationId,
            ),
            compiled = compiled,
            roleplayObservationContext = roleplayObservationContext,
            roleplayIdentity = roleplayIdentity,
            fixedResponse = fixedResponse,
            debugTrace = if (isDebuggableBuild()) compiled.turnTrace.toDiagnosticText() else null,
        )
    }

    private fun isDebuggableBuild(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun HarnessChatRequest.withRoleplayRepairInstruction(ruleIds: List<String>): HarnessChatRequest {
        val repairedSystemPrompt = listOfNotNull(systemPrompt?.trim()?.takeIf(String::isNotEmpty))
            .plus(RoleplayGenerationCoordinator.repairInstruction(ruleIds))
            .joinToString("\n\n")
        return copy(
            systemPrompt = repairedSystemPrompt,
            observationId = observationId?.let { "$it-retry" },
        )
    }

    private fun RoleplayGenerationAction.toGuardDecision(): RoleplayOutputDecision = when (this) {
        is RoleplayGenerationAction.Deliver -> RoleplayOutputDecision.Accept(visibleText)
        is RoleplayGenerationAction.Retry -> RoleplayOutputDecision.RetryWithRepair(ruleIds)
        is RoleplayGenerationAction.Fallback -> RoleplayOutputDecision.SafeFallback(ruleIds)
    }

    /** 把已通过输出流程的回复和本轮元数据写入回合开始时绑定的会话存储。 */
    private fun saveCompletedHarnessTurn(
        userMsg: String,
        assistantText: String,
        promptInput: HarnessPromptInput,
        aiMsgId: Long,
        binding: TurnBinding,
    ) {
        runCatching {
            val identity = binding.identity ?: return
            check(identity == boundIdentity) { "database_changed_during_turn" }
            val modelId = binding.modelId
            val session = binding.session?.takeIf {
                it.datasetId == identity.datasetId && it.datasetFingerprint == identity.fingerprint &&
                    it.characterId == binding.characterId && it.storyCutoff == binding.storyCutoff
            } ?: binding.store.create(
                characterId = binding.characterId,
                storyCutoff = binding.storyCutoff,
                selectedModelId = modelId,
            )
            val turn = ChatTurn(
                turnId = "turn-${System.currentTimeMillis()}-$aiMsgId",
                userText = userMsg,
                assistantText = assistantText,
                ragMode = promptInput.compiled?.mode?.name ?: RagMode.OFF.name,
                sources = promptInput.compiled?.sources.orEmpty(),
                characterContextTargets = CharacterFollowUpResolver.persistableTargets(
                    promptInput.compiled?.characterDebug as? CharacterPromptDebug,
                ),
                renderedPrompt = promptInput.debugPrompt,
                characterId = session.characterId,
                modelId = modelId,
            )
            val updatedSession = binding.store.appendTurn(session, turn)
            memoryStore.observeTurn(updatedSession, turn)
            currentSession = updatedSession
        }.onFailure { error ->
            Log.e(TAG, "Failed to persist harness session turn", error)
        }
    }

    private fun showRagModeDialog() {
        val labels = arrayOf(
            getString(R.string.rag_mode_off),
            getString(R.string.rag_mode_rag),
            getString(R.string.rag_mode_character_rag),
        )
        val modes = arrayOf(RagMode.OFF, RagMode.RAG, RagMode.CHARACTER_RAG)
        val checked = modes.indexOf(currentRagMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.rag_mode_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                if (modes[which] != RagMode.OFF && ragOrchestrator == null) {
                    Toast.makeText(this, databaseUnavailableMessage(), Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }
                currentRagMode = modes[which]
                updateRagModeButton()
                Toast.makeText(
                    this,
                    getString(R.string.rag_mode_changed, labels[which]),
                    Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            }
            .setNeutralButton(
                getString(
                    if (characterKnowledgeGraphEnabled) R.string.character_graph_disable
                    else R.string.character_graph_enable,
                ),
            ) { _, _ ->
                characterKnowledgeGraphEnabled = !characterKnowledgeGraphEnabled
                Toast.makeText(
                    this,
                    getString(
                        if (characterKnowledgeGraphEnabled) R.string.character_graph_enabled
                        else R.string.character_graph_disabled,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateRagModeButton() {
        btnRagMode.alpha = if (currentRagMode == RagMode.OFF) 0.45f else 1.0f
        btnRagMode.contentDescription = "${getString(R.string.rag_mode)}: ${ragModeLabel(currentRagMode)}"
    }

    private fun showCharacterDialog() {
        if (databaseRebinding) return
        val lease = DatabaseRuntime.beginTurn()
        if (lease?.identity != boundIdentity) {
            rebindDatabase(lease, retryInput = false)
            return
        }
        lease?.close()
        val orchestrator = ragOrchestrator
        if (orchestrator == null) {
            Toast.makeText(this, databaseUnavailableMessage(), Toast.LENGTH_LONG).show()
            return
        }
        val characters = runCatching { orchestrator.availableCharacters() }
            .onFailure { Log.e(TAG, "Failed to list characters", it) }
            .getOrDefault(emptyList())
        if (characters.isEmpty()) {
            Toast.makeText(this, R.string.character_list_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = characters.map { it.displayLabel() }.toTypedArray()
        val checked = characters.indexOfFirst { it.npcId == currentCharacterId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.character_select_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                switchCharacter(characters[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun databaseUnavailableMessage(): String = getString(
        if (DatabaseRuntime.installation is DatabaseInstallResult.Ready) R.string.database_retrieval_pending
        else R.string.database_unavailable,
    )

    private fun switchCharacter(character: CharacterCard) {
        if (character.npcId == currentCharacterId || databaseRebinding) return
        databaseRebinding = true
        enableInput(false)
        if (generationJob?.isActive == true) harness.cancelGeneration()
        lifecycleScope.launch {
            try {
                generationJob?.cancelAndJoin()
                withContext(Dispatchers.IO) {
                    if (harness.state.value is LlamaState.ModelReady) harness.clearContext()
                    if (boundIdentity != null) sessionStore.clearRecentSession()
                }
                currentCharacterId = character.npcId
                currentStoryCutoff = character.knowledgeScope.storyCutoff
                currentSession = null
                clearChatUI()
                updateCharacterButton(character)
                Toast.makeText(this@MainActivity, getString(R.string.character_changed, character.identity.name), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error switching character", e)
                Toast.makeText(this@MainActivity, getString(R.string.character_switch_failed, e.message), Toast.LENGTH_SHORT).show()
            } finally {
                databaseRebinding = false
                enableInput(isModelReady)
            }
        }
    }

    private fun updateCharacterButton(character: CharacterCard? = null) {
        val label = character?.identity?.name
            ?: runCatching {
                ragOrchestrator?.availableCharacters()
                    ?.firstOrNull { it.npcId == currentCharacterId }
                    ?.identity
                    ?.name
            }.getOrNull()
            ?: currentCharacterId
        btnCharacter.contentDescription = "${getString(R.string.character_select_title)}: $label"
    }

    private fun CharacterCard.displayLabel(): String {
        return "${identity.name} (${npcId})"
    }

    private fun showSourcesDialog(sources: List<RagSource>) {
        val text = if (sources.isEmpty()) {
            getString(R.string.rag_no_sources)
        } else {
            sources.mapIndexed { index, source ->
                "${index + 1}. ${source.source}\nspan=${source.start}:${source.end}  score=${"%.4f".format(source.score)}"
            }.joinToString("\n\n")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rag_sources_title)
            .setMessage(text)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun showPromptDialog(prompt: String, trace: String?) {
        val content = if (trace.isNullOrBlank()) prompt else "$prompt\n\n---\n$trace"
        AlertDialog.Builder(this)
            .setTitle(R.string.rag_prompt_title)
            .setMessage(content)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun ragModeLabel(mode: RagMode): String {
        return when (mode) {
            RagMode.OFF -> getString(R.string.rag_mode_off)
            RagMode.RAG -> getString(R.string.rag_mode_rag)
            RagMode.CHARACTER_RAG -> getString(R.string.rag_mode_character_rag)
        }
    }

    private data class HarnessPromptInput(
        val request: HarnessChatRequest,
        val compiled: CompiledAndroidPrompt?,
        val roleplayObservationContext: RoleplayObservationContext? = null,
        val roleplayIdentity: RoleplayCharacterIdentity? = null,
        /** Non-null only for a matched graph fact unavailable at this cutoff. */
        val fixedResponse: String? = null,
        val debugTrace: String? = null,
    ) {
        val predictLength: Int
            get() = when (compiled?.mode) {
                RagMode.CHARACTER_RAG -> ROLEPLAY_PREDICT_LENGTH
                RagMode.RAG -> RAG_PREDICT_LENGTH
                else -> CHAT_PREDICT_LENGTH
            }

        val debugPrompt: String?
            get() = compiled?.renderedPrompt

        val isCharacterRoleplay: Boolean
            get() = compiled?.mode == RagMode.CHARACTER_RAG
    }

    /** 记录一轮聊天所使用的模型、数据包、角色、截止点和会话实例，供异步流程共享。 */
    private data class TurnBinding(
        val identity: DatabaseIdentity?,
        val mode: RagMode,
        val characterId: String,
        val storyCutoff: String?,
        val session: ChatSession?,
        val store: ChatSessionStore,
        val orchestrator: AndroidRagOrchestrator?,
        val modelId: String,
        val modelFamily: HarnessModelFamily,
    )

    private data class GenerationOutcome(
        val visibleText: String,
        /** Null means this rendered text must not affect sessions, summaries, or memory. */
        val persistedAssistantText: String? = null,
    )

    private sealed interface RawGenerationResult {
        data class Completed(val rawOutput: String) : RawGenerationResult
        data class Failed(val error: Throwable, val partialOutput: String) : RawGenerationResult
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is TextInputEditText) {
                val barRect = android.graphics.Rect()
                cardInputBar.getGlobalVisibleRect(barRect)
                if (!barRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        val currentTag = LocaleManager.currentLanguage(this).tag
        if (createdWithLocale != null && createdWithLocale != currentTag) {
            isLocaleRestart = true
            LocaleManager.recreateSeamlessly(this)
            return
        }
        val selectedId = harness.getSelectedModel().id

        if (loadedModelId != null && loadedModelId != selectedId) {
            loadedModelId = null
            hasAutoLoaded = false
            reloadAfterModelSwitch()
        } else if (harness.consumeModelSwitched()) {
            loadedModelId = selectedId
            clearChatUI()
            restoreRecentSessionMessages()
            chatAdapter.submitList(messages.toList())
        }
    }

    private fun reloadAfterModelSwitch() {
        enableInput(false)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (harness.state.value is LlamaState.ModelReady) {
                    harness.unloadModel()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error unloading during model switch", e)
            }
            withContext(Dispatchers.Main) {
                clearChatUI()
                restoreRecentSessionMessages()
                chatAdapter.submitList(messages.toList())
                loadDefaultModel()
            }
        }
    }

    override fun onStop() {
        if (generationJob?.isActive == true) harness.cancelGeneration()
        generationJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        ragOrchestrator?.let { previous -> Thread({ previous.close() }, "rag-encoder-close").start() }
        ragOrchestrator = null
        if (isFinishing && !isLocaleRestart) {
            harness.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val ROLEPLAY_PREDICT_LENGTH = 256
        private const val RAG_PREDICT_LENGTH = 384
        private const val CHAT_PREDICT_LENGTH = 512
    }
}
