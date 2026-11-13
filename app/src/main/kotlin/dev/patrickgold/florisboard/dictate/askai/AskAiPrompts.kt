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
import org.json.JSONArray
import org.json.JSONObject

/**
 * FORK ADDITION (pshaddad89/DictateKeyboard): the one-tap buttons on the "Ask AI" card.
 *
 * Stored as a small JSON list in one preference. Empty means "never edited", which reads as [DEFAULTS],
 * so improving a default reaches everyone who has not made the list their own.
 */
data class AskAiPrompt(val label: String, val prompt: String)

object AskAiPrompts {

    val DEFAULTS = listOf(
        AskAiPrompt(
            "Summarize",
            "Summarize what is on this screen in a few short points.",
        ),
        AskAiPrompt(
            "Extract text",
            "Extract all the text visible on this screen exactly as written, keeping line breaks where they " +
                "matter. Output only the text.",
        ),
        AskAiPrompt(
            "Draft reply",
            "Draft a short, natural reply to the message or email on this screen, in the same language. " +
                "Output only the reply.",
        ),
        AskAiPrompt(
            "Explain simply",
            "Explain what is on this screen in plain, simple language a patient without medical training " +
                "could understand.",
        ),
        AskAiPrompt(
            "Translate to Spanish",
            "Translate the text on this screen into clear, natural Spanish. Output only the translation.",
        ),
        AskAiPrompt(
            "ICD-10 codes",
            "List the ICD-10-CM codes for the problems or diagnoses on this screen, in the same order they " +
                "appear. One per line as: code - description. Flag any that need more specificity to code " +
                "accurately.",
        ),
    )

    fun load(prefs: FlorisPreferenceModel): List<AskAiPrompt> {
        val raw = prefs.dictate.floatingButtonAskAiPrompts.get()
        if (raw.isBlank()) return DEFAULTS
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val label = o.optString("label").trim()
                val prompt = o.optString("prompt").trim()
                if (label.isEmpty() || prompt.isEmpty()) null else AskAiPrompt(label, prompt)
            }
        }.getOrDefault(DEFAULTS)
    }

    suspend fun save(prefs: FlorisPreferenceModel, prompts: List<AskAiPrompt>) {
        val array = JSONArray()
        prompts.forEach { array.put(JSONObject().put("label", it.label).put("prompt", it.prompt)) }
        prefs.dictate.floatingButtonAskAiPrompts.set(array.toString())
    }
}
