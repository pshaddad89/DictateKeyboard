/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches and caches the community prompt library (issue #105).
 *
 * The library JSON is downloaded from the project's own repository ([PromptLibraryCatalog.LIBRARY_URL])
 * the first time the user opens the browser, then cached on disk so subsequent opens work offline and
 * instantly. A pull-to-refresh forces a re-fetch. Nothing is ever uploaded — this is a plain read of a
 * static file.
 *
 * Mirrors the download/parse/cache shape of [dev.patrickgold.florisboard.ime.nlp.latin.GlideDictionaryManager],
 * but far lighter: the payload is a few KB of text, so there is no progress bar, checksum or staging.
 */
object PromptLibraryManager {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Where the last successfully fetched library body is cached. */
    private fun cacheFile(context: Context): File =
        File(context.filesDir, "prompt-library.json")

    /** The outcome of a [load]: the parsed entries, whether they came from network, and any error. */
    data class Result(
        val entries: List<PromptLibraryEntry>,
        val fromCache: Boolean,
        val error: Throwable? = null,
    )

    /**
     * Returns the on-disk cached library without touching the network, or null if there is no (valid)
     * cache yet. Used to paint the browser instantly on open before the background refresh arrives
     * (stale-while-revalidate), so re-opening never flashes an empty screen.
     */
    suspend fun cachedOnly(context: Context): List<PromptLibraryEntry>? = withContext(Dispatchers.IO) {
        val cache = cacheFile(context)
        if (cache.isFile && cache.length() > 0) {
            runCatching { parse(cache.readText()) }.getOrNull()
        } else null
    }

    /**
     * The snapshot bundled inside the APK (`assets/prompt-library.json`), or null if unreadable. Used
     * as the deepest fallback so the browser shows a useful library even on a first, offline open — it
     * is overwritten by the live fetch as soon as the network is available.
     */
    suspend fun bundled(context: Context): List<PromptLibraryEntry>? = withContext(Dispatchers.IO) {
        runCatching {
            context.applicationContext.assets.open("prompt-library.json").use { it.readBytes() }
                .toString(Charsets.UTF_8)
        }.mapCatching { parse(it) }.getOrNull()
    }

    /**
     * Loads the library. When [forceRefresh] is false, a fresh-enough network fetch is attempted and,
     * on any failure, falls back to the on-disk cache (if present). When true, always hits the network
     * and only falls back to cache on failure. The returned [Result] is never null: on a cold cache and
     * a failed fetch it carries an empty list plus the [Result.error] so the UI can show a retry.
     */
    suspend fun load(context: Context, forceRefresh: Boolean = false): Result = withContext(Dispatchers.IO) {
        val cache = cacheFile(context)
        // Without a forced refresh, a warm cache short-circuits the network so re-opening is instant.
        if (!forceRefresh && cache.isFile && cache.length() > 0) {
            val cached = runCatching { parse(cache.readText()) }.getOrNull()
            if (cached != null) return@withContext Result(cached, fromCache = true)
        }
        val fetched = runCatching {
            val request = Request.Builder().url(PromptLibraryCatalog.LIBRARY_URL).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                response.body?.string() ?: error("empty body")
            }
        }
        fetched.fold(
            onSuccess = { body ->
                val parsed = runCatching { parse(body) }
                parsed.fold(
                    onSuccess = { entries ->
                        runCatching { cache.writeText(body) } // best-effort cache update
                        Result(entries, fromCache = false)
                    },
                    onFailure = { err -> fallback(context, cache, err) },
                )
            },
            onFailure = { err -> fallback(context, cache, err) },
        )
    }

    // Network/parse failed: try the on-disk cache, then the APK-bundled snapshot, then give up (empty).
    private fun fallback(context: Context, cache: File, error: Throwable): Result {
        val cached = if (cache.isFile && cache.length() > 0) {
            runCatching { parse(cache.readText()) }.getOrNull()
        } else null
        if (cached != null) return Result(cached, fromCache = true, error = error)
        val bundled = runCatching {
            context.applicationContext.assets.open("prompt-library.json").use { it.readBytes() }
                .toString(Charsets.UTF_8).let { parse(it) }
        }.getOrNull()
        return if (bundled != null) {
            Result(bundled, fromCache = true, error = error)
        } else {
            Result(emptyList(), fromCache = false, error = error)
        }
    }

    private fun parse(body: String): List<PromptLibraryEntry> {
        val doc = json.decodeFromString<PromptLibraryDocument>(body)
        // Guard against blank/duplicate entries so the browser stays clean regardless of the source.
        return doc.prompts
            .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.prompt.isNotBlank() }
            .distinctBy { it.id }
    }
}
