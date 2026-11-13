/*
 * Copyright (C) 2026 pshaddad89 (fork addition to Dictate by DevEmperor)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.askai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.overlay.DictateAccessibilityService
import dev.patrickgold.florisboard.dictate.recognition.RecognitionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File

/**
 * FORK ADDITION (pshaddad89/DictateKeyboard): the "Ask AI" card the floating button opens.
 *
 * A translucent screen over whatever app was showing, holding the screenshot the button just took. It
 * starts listening straight away, so the user can simply speak a question; the quick-prompt buttons and
 * the text field work at the same time. Answers stay on the card with Copy and Insert, and anything said
 * or typed afterwards is a follow-up about the same screenshot. The picture lives in the app's private
 * cache and is deleted when the card closes; the question is kept out of the transcription history.
 */
class AskAiActivity : ComponentActivity() {

    private val prefs by FlorisPreferenceStore

    private var screenshotFile: File? = null
    private var fieldWasFocused = false
    private lateinit var image: Deferred<String?>
    private var thumbnail: Bitmap? = null

    private var models: List<AskAiModel> = emptyList()
    private val choice = mutableStateOf<AskAiModel?>(null)
    private val prompts = mutableStateListOf<AskAiPrompt>()
    private val turns = mutableStateListOf<AskAiClient.Turn>()
    private val pendingQuestion = mutableStateOf<String?>(null)
    private val errorText = mutableStateOf<String?>(null)
    private val input = mutableStateOf("")
    private val listening = mutableStateOf(false)
    private val transcribing = mutableStateOf(false)
    /** Index of the prompt being edited; [NEW_PROMPT] for a new one; null when no dialog is open. */
    private val editing = mutableStateOf<Int?>(null)

    private var session: RecognitionSession? = null
    private var askJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isOpen = true
        // Draw behind the system bars so the card can follow the keyboard with imePadding on every version.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        screenshotFile = intent.getStringExtra(EXTRA_SCREENSHOT)?.let { File(it) }?.takeIf { it.exists() }
        fieldWasFocused = intent.getBooleanExtra(EXTRA_FIELD_FOCUSED, false)
        image = lifecycleScope.async(Dispatchers.IO) {
            screenshotFile?.let { runCatching { AskAiClient.encodeScreenshot(it) }.getOrNull() }
        }
        thumbnail = screenshotFile?.let { file ->
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })
            }.getOrNull()
        }

        models = AskAiModels.available(prefs)
        choice.value = AskAiModels.defaultChoice(prefs, models)
        prompts.addAll(AskAiPrompts.load(prefs))

        setContent {
            val theme = remember { prefs.other.settingsTheme.get() }
            FlorisAppTheme(theme) {
                AskAiCard()
            }
        }

        if (choice.value != null && savedInstanceState == null) startListening()
    }

    // --- Voice -----------------------------------------------------------------------------------

    private val host = object : RecognitionSession.Host {
        override fun onEndOfSpeech() {
            if (listening.value) {
                listening.value = false
                transcribing.value = true
            }
        }

        override fun onResults(text: String) {
            session = null
            listening.value = false
            transcribing.value = false
            // Something typed already is part of the question: the spoken part is added to it.
            val typed = input.value.trim()
            send(if (typed.isEmpty()) text else "$typed $text")
        }

        override fun onError(code: Int) {
            session = null
            listening.value = false
            transcribing.value = false
            // Saying nothing is not an error: the buttons and the text field are still there.
            if (code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_CLIENT) {
                errorText.value = getString(R.string.dictate__ask_ai_voice_failed)
            }
        }
    }

    private fun startListening() {
        if (session != null || pendingQuestion.value != null) return
        errorText.value = null
        listening.value = true
        session = RecognitionSession(applicationContext, host, sensitiveField = true).also { it.start() }
    }

    /** Stops recording and transcribes what was said. */
    private fun finishListening() {
        session?.stop()
    }

    /** Drops the recording without transcribing it. */
    private fun cancelListening() {
        session?.cancel()
        session = null
        listening.value = false
        transcribing.value = false
    }

    // --- Asking ----------------------------------------------------------------------------------

    private fun send(question: String) {
        val q = question.trim()
        val model = choice.value
        if (q.isEmpty() || model == null || pendingQuestion.value != null) return
        cancelListening()
        input.value = ""
        errorText.value = null
        pendingQuestion.value = q
        askJob = lifecycleScope.launch {
            try {
                val answer = AskAiClient.ask(prefs, model, image.await(), turns.toList(), q)
                turns.add(AskAiClient.Turn(q, answer))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                errorText.value = getString(R.string.dictate__ask_ai_failed, t.message ?: t.javaClass.simpleName)
                // Give the question back, so asking again is one tap.
                if (input.value.isEmpty()) input.value = q
            } finally {
                pendingQuestion.value = null
            }
        }
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Ask AI", text))
        Toast.makeText(this, R.string.dictate__ask_ai_copied, Toast.LENGTH_SHORT).show()
    }

    /** Closes the card and types [text] into the field that had focus when the button was used. */
    private fun insert(text: String) {
        val app = applicationContext
        finish()
        // The app underneath gets its window focus back only after this one is gone.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!DictateAccessibilityService.injectText(text)) {
                app.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Ask AI", text))
                Toast.makeText(app, R.string.dictate__ask_ai_insert_failed, Toast.LENGTH_LONG).show()
            }
        }, INSERT_DELAY_MS)
    }

    private fun pickModel(model: AskAiModel) {
        choice.value = model
        lifecycleScope.launch { AskAiModels.remember(prefs, model) }
    }

    private fun savePrompts() {
        val snapshot = prompts.toList()
        lifecycleScope.launch { AskAiPrompts.save(prefs, snapshot) }
    }

    override fun onDestroy() {
        isOpen = false
        session?.cancel()
        session = null
        askJob?.cancel()
        thumbnail?.recycle()
        thumbnail = null
        if (isFinishing) screenshotFile?.delete()
        super.onDestroy()
    }

    // --- UI --------------------------------------------------------------------------------------

    @Composable
    private fun AskAiCard() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    finish()
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(8.dp)
                    .heightIn(max = 620.dp)
                    // Taps on the card must not reach the scrim, which closes it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                    Header()
                    Conversation(Modifier.weight(1f, fill = false))
                    Spacer(Modifier.height(8.dp))
                    PromptRow()
                    Spacer(Modifier.height(8.dp))
                    InputRow()
                }
            }
        }
        editing.value?.let { index -> PromptDialog(index) }
    }

    @Composable
    private fun Header() {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 30.dp, height = 52.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = stringResource(R.string.dictate__ask_ai_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            ModelPicker()
            IconButton(onClick = { finish() }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dictate__ask_ai_close))
            }
        }
    }

    @Composable
    private fun ModelPicker() {
        var open by remember { mutableStateOf(false) }
        val current by choice
        Box {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = models.isNotEmpty()) { open = true },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text(
                        text = current?.model ?: stringResource(R.string.dictate__ask_ai_no_model),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                var lastProvider: String? = null
                models.forEach { model ->
                    if (model.providerId != lastProvider) {
                        lastProvider = model.providerId
                        Text(
                            text = model.providerName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model.model,
                                fontWeight = if (model == current) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            open = false
                            pickModel(model)
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun Conversation(modifier: Modifier) {
        val scroll = rememberScrollState()
        val pending by pendingQuestion
        val error by errorText
        LaunchedEffect(turns.size, pending, error) { scroll.animateScrollTo(scroll.maxValue) }
        Column(modifier.verticalScroll(scroll)) {
            if (models.isEmpty()) {
                Text(
                    text = stringResource(R.string.dictate__ask_ai_no_keys),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            turns.forEach { turn ->
                QuestionBubble(turn.question)
                SelectionContainer {
                    Text(
                        text = turn.answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { copy(turn.answer) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dictate__ask_ai_copy))
                    }
                    if (fieldWasFocused) {
                        TextButton(onClick = { insert(turn.answer) }) {
                            Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.dictate__ask_ai_insert))
                        }
                    }
                }
            }
            pending?.let { question ->
                QuestionBubble(question)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.dictate__ask_ai_thinking, choice.value?.model.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun QuestionBubble(text: String) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PromptRow() {
        val busy = pendingQuestion.value != null
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(prompts) { index, prompt ->
                PromptChip(
                    label = prompt.label,
                    enabled = !busy && choice.value != null,
                    onClick = { send(prompt.prompt) },
                    onLongClick = { editing.value = index },
                )
            }
            item {
                PromptChip(
                    label = "+",
                    enabled = true,
                    onClick = { editing.value = NEW_PROMPT },
                    onLongClick = { editing.value = NEW_PROMPT },
                    icon = true,
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PromptChip(
        label: String,
        enabled: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        icon: Boolean = false,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        ) {
            if (icon) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.dictate__ask_ai_add_prompt),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).size(20.dp),
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun InputRow() {
        var text by input
        val isListening by listening
        val isTranscribing by transcribing
        val busy = pendingQuestion.value != null
        val level by DictateController.audioLevel.collectAsState()
        // Typed explicitly: a lambda in an if-expression is otherwise not inferred as @Composable.
        val sendButton: (@Composable () -> Unit)? = if (text.isNotBlank()) {
            {
                IconButton(onClick = { send(text) }, enabled = !busy) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.dictate__ask_ai_send),
                    )
                }
            }
        } else {
            null
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = choice.value != null,
                placeholder = {
                    Text(
                        stringResource(
                            when {
                                isListening -> R.string.dictate__ask_ai_listening
                                isTranscribing -> R.string.dictate__ask_ai_transcribing
                                turns.isEmpty() -> R.string.dictate__ask_ai_hint
                                else -> R.string.dictate__ask_ai_follow_up_hint
                            },
                        ),
                    )
                },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                trailingIcon = sendButton,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val micColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .scale(if (isListening) 1f + level.coerceIn(0f, 1f) * 0.25f else 1f)
                    .clip(CircleShape)
                    .background(micColor)
                    .clickable(enabled = !busy && !isTranscribing && choice.value != null) {
                        if (isListening) finishListening() else startListening()
                    },
            ) {
                if (isTranscribing) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Icon(
                        if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = stringResource(
                            if (isListening) R.string.dictate__ask_ai_stop else R.string.dictate__ask_ai_speak,
                        ),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }

    @Composable
    private fun PromptDialog(index: Int) {
        val existing = prompts.getOrNull(index)
        var label by remember(index) { mutableStateOf(existing?.label.orEmpty()) }
        var prompt by remember(index) { mutableStateOf(existing?.prompt.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing.value = null },
            title = {
                Text(
                    stringResource(
                        if (existing == null) R.string.dictate__ask_ai_add_prompt else R.string.dictate__ask_ai_edit_prompt,
                    ),
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(stringResource(R.string.dictate__ask_ai_prompt_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.dictate__ask_ai_prompt_text)) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = label.isNotBlank() && prompt.isNotBlank(),
                    onClick = {
                        val updated = AskAiPrompt(label.trim(), prompt.trim())
                        if (existing == null) prompts.add(updated) else prompts[index] = updated
                        savePrompts()
                        editing.value = null
                    },
                ) { Text(stringResource(R.string.dictate__ask_ai_save)) }
            },
            dismissButton = {
                Row {
                    if (existing != null) {
                        TextButton(onClick = {
                            prompts.removeAt(index)
                            savePrompts()
                            editing.value = null
                        }) { Text(stringResource(R.string.dictate__ask_ai_delete)) }
                    }
                    TextButton(onClick = { editing.value = null }) {
                        Text(stringResource(R.string.dictate__ask_ai_cancel))
                    }
                }
            },
        )
    }

    companion object {
        /** True while the card is up: the floating button stays off it, the card has its own mic. */
        @Volatile
        var isOpen = false
            private set

        private const val EXTRA_SCREENSHOT = "dev.patrickgold.florisboard.askai.SCREENSHOT"
        private const val EXTRA_FIELD_FOCUSED = "dev.patrickgold.florisboard.askai.FIELD_FOCUSED"
        private const val NEW_PROMPT = -1
        private const val INSERT_DELAY_MS = 450L

        fun intent(context: Context, screenshot: File, fieldFocused: Boolean): Intent =
            Intent(context, AskAiActivity::class.java)
                .putExtra(EXTRA_SCREENSHOT, screenshot.absolutePath)
                .putExtra(EXTRA_FIELD_FOCUSED, fieldFocused)
    }
}
