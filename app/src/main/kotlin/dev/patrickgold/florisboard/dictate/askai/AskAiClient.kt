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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.dictate.dictateProxyConfig
import dev.patrickgold.florisboard.dictate.rewordingReasoningWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * FORK ADDITION (pshaddad89/DictateKeyboard): the one request "Ask AI" makes — a screenshot and a question
 * to an OpenAI-compatible `chat/completions` endpoint, which every provider in the app speaks (Anthropic
 * and Gemini through their compatibility layers).
 *
 * Kept apart from the app's own client on purpose: that one carries text only, and teaching it images
 * would mean editing the upstream code that every merge from DevEmperor touches.
 */
object AskAiClient {

    /** One exchange of the conversation on the card. */
    data class Turn(val question: String, val answer: String)

    class AskAiException(message: String) : Exception(message)

    private const val MAX_IMAGE_SIDE = 1568
    private const val JPEG_QUALITY = 85

    private val SYSTEM_PROMPT = """
        You are a helpful assistant built into the user's phone. The first message includes a screenshot of
        what is on their screen right now; questions refer to it unless they clearly do not. Answer directly
        and concisely in plain text that reads well on a small phone card: no Markdown headings, tables or
        bold markers; short dashes for lists are fine. When asked to write something the user will send or
        paste (a reply, a translation, extracted text), output only that text, without any preamble.
    """.trimIndent().replace("\n", " ")

    /**
     * Shrinks the screenshot so it uploads quickly and costs fewer tokens, and encodes it as a JPEG data URI.
     * Providers downscale large images themselves, so nothing a model could read is lost at this size.
     */
    fun encodeScreenshot(file: File): String? {
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val longest = maxOf(source.width, source.height)
        val scaled = if (longest > MAX_IMAGE_SIDE) {
            val factor = MAX_IMAGE_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                source, (source.width * factor).toInt(), (source.height * factor).toInt(), true,
            ).also { if (it !== source) source.recycle() }
        } else {
            source
        }
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        scaled.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Asks [question] about the screenshot ([imageDataUri], sent with the first question only) through
     * [choice], with the earlier [history] of this card as context. Returns the answer text.
     */
    suspend fun ask(
        prefs: FlorisPreferenceModel,
        choice: AskAiModel,
        imageDataUri: String?,
        history: List<Turn>,
        question: String,
    ): String = withContext(Dispatchers.IO) {
        val account = prefs.dictate.providerAccounts.get().getOrEmpty(choice.providerId)
        val preset = AskAiModels.presetFor(account)
        val baseUrl = (
            if (account.isCustom || preset.allowsCustomBaseUrl) account.customBaseUrl.takeIf { it.isNotBlank() } else null
            ) ?: preset.baseUrl
        val url = (if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/") + "chat/completions"

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        val turns = history + Turn(question, "")
        turns.forEachIndexed { index, turn ->
            val content: Any = if (index == 0 && imageDataUri != null) {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", turn.question))
                    .put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", imageDataUri)),
                    )
            } else {
                turn.question
            }
            messages.put(JSONObject().put("role", "user").put("content", content))
            if (turn.answer.isNotEmpty()) {
                messages.put(JSONObject().put("role", "assistant").put("content", turn.answer))
            }
        }
        val body = JSONObject()
            .put("model", choice.model)
            .put("messages", messages)
        // The thinking level the user set for this provider, when the catalog knows the model; otherwise
        // the provider's own default.
        rewordingReasoningWire(account, preset, choice.model)
            ?.takeIf { it.isNotBlank() }
            ?.let { body.put("reasoning_effort", it) }

        val request = Request.Builder()
            .url(url)
            .apply {
                if (account.apiKey.isNotBlank()) header("Authorization", "Bearer ${account.apiKey}")
                preset.extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val timeout = maxOf(prefs.dictate.requestTimeout.get().toLong(), 90L)
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS)
            .apply { prefs.dictate.dictateProxyConfig()?.let { proxy(it.toJavaProxy()) } }
            .build()

        val (code, text) = client.newCall(request).await()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (code !in 200..299 || json == null) {
            throw AskAiException(errorMessage(json) ?: "HTTP $code from ${choice.providerName}")
        }
        val answer = answerText(json)
        if (answer.isBlank()) {
            throw AskAiException(errorMessage(json) ?: "${choice.providerName} sent back an empty answer")
        }
        answer
    }

    private fun answerText(json: JSONObject): String {
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return ""
        val raw = when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text") append(part.optString("text"))
                }
            }
            else -> ""
        }
        // Some open models write their thinking into the answer itself.
        return raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    private fun errorMessage(json: JSONObject?): String? {
        val error = json?.opt("error") ?: return null
        return when (error) {
            is JSONObject -> error.optString("message").takeIf { it.isNotBlank() }
            is String -> error.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(AskAiException(e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use { it.code to (it.body?.string().orEmpty()) }
                if (cont.isActive) cont.resume(result)
            }
        })
    }
}
