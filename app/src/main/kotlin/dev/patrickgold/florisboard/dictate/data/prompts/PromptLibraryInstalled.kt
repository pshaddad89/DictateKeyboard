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

/**
 * Remembers which community-library prompts the user has imported, keyed by the library entry [id]
 * (issue #105).
 *
 * The user's own `prompts.db` deliberately does not store the library id (its schema is frozen for
 * backwards-compatibility), so the browser cannot tell "already added" from the database alone once a
 * prompt has been edited/renamed. This tiny side-store fixes that: it survives edits and is the source
 * of truth for the "Added" marker, with content matching kept only as a fallback for prompts imported
 * before this store existed. No prompt text is stored here — only opaque ids.
 */
object PromptLibraryInstalled {
    private const val PREFS = "prompt_library"
    private const val KEY_IDS = "installed_ids"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The set of library ids the user has imported. */
    fun all(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_IDS, emptySet()) ?: emptySet()

    /** Records [id] as imported. No-op for a blank id. */
    fun add(context: Context, id: String) {
        if (id.isBlank()) return
        // Copy the returned set — SharedPreferences must not be handed back its own mutated instance.
        val updated = HashSet(all(context)).apply { add(id) }
        prefs(context).edit().putStringSet(KEY_IDS, updated).apply()
    }
}
