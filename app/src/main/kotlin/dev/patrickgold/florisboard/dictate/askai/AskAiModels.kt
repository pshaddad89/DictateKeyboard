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

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.dictate.provider.ModelCatalog
import dev.patrickgold.florisboard.dictate.provider.ModelRole
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry

/**
 * FORK ADDITION (pshaddad89/DictateKeyboard): which models "Ask AI" may send a screenshot to.
 *
 * Only providers the user has entered an API key for, and only chat models that can look at an image as
 * far as anyone knows: Groq's gpt-oss models are text-only, DeepSeek's API takes no images, and a
 * speech model is no use here. A custom endpoint offers the one model the user chose for it.
 */
data class AskAiModel(
    val providerId: String,
    val providerName: String,
    val model: String,
) {
    /** How the choice is remembered in [FlorisPreferenceModel.Dictate.floatingButtonAskAiModel]. */
    val key: String get() = "$providerId|$model"
}

object AskAiModels {

    /** Providers whose chat API is known to take no images at all. */
    private val TEXT_ONLY_PROVIDERS = setOf("deepseek", ProviderRegistry.CLOUD.id)

    fun presetFor(account: ProviderAccount): ProviderPreset = when {
        account.isCustom -> ProviderRegistry.custom(account.customBaseUrl, realtime = account.customRealtime)
        else -> ProviderRegistry.byId(account.providerId) ?: ProviderRegistry.OPENAI
    }

    private fun looksVisionCapable(model: String): Boolean {
        val id = model.lowercase()
        return id.isNotBlank() &&
            "gpt-oss" !in id &&
            "whisper" !in id &&
            "transcribe" !in id &&
            "realtime" !in id &&
            "tts" !in id &&
            "embed" !in id
    }

    /** Every model on offer, the rewording provider's first. */
    fun available(prefs: FlorisPreferenceModel): List<AskAiModel> {
        val rewordingId = prefs.dictate.rewordingProviderId.get()
        val accounts = prefs.dictate.providerAccounts.get().accounts.values
            .filter { it.apiKey.isNotBlank() && it.providerId !in TEXT_ONLY_PROVIDERS }
            .sortedBy { if (it.providerId == rewordingId) 0 else 1 }
        return accounts.flatMap { account ->
            val preset = presetFor(account)
            if (!preset.capabilities.chat) return@flatMap emptyList()
            val name = account.displayName.ifBlank { preset.displayName }
            val ids = if (account.isCustom) {
                listOf(account.chatModel)
            } else {
                // The model the user picked for rewording first, then the ones the app curates.
                listOf(account.chatModel, preset.defaultChatModel.orEmpty()) +
                    ModelCatalog.idsOf(preset.id, ModelRole.CHAT)
            }
            ids.filter { looksVisionCapable(it) }.distinct().map { AskAiModel(account.providerId, name, it) }
        }
    }

    /** The remembered choice when it is still on offer; otherwise the rewording provider's first. */
    fun defaultChoice(prefs: FlorisPreferenceModel, available: List<AskAiModel>): AskAiModel? {
        val saved = prefs.dictate.floatingButtonAskAiModel.get()
        return available.firstOrNull { it.key == saved } ?: available.firstOrNull()
    }

    suspend fun remember(prefs: FlorisPreferenceModel, choice: AskAiModel) {
        prefs.dictate.floatingButtonAskAiModel.set(choice.key)
    }
}
