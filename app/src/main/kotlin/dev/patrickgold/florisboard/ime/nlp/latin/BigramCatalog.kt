/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

/**
 * One downloadable per-language bigram file (`<lang>_bigrams.txt`) for the autocorrect Tier 2 context
 * model: a `"w1 w2" -> count` table used to re-rank corrections by the previous word. Downloaded and
 * removed alongside the glide-typing dictionary (same trigger: adding/removing an input language), so the
 * device never holds bigram data for languages the user doesn't use.
 *
 * English ships bundled in the APK ([ime/dict/en_bigrams.txt]); every other language is downloaded on
 * demand. Data is generated from the Leipzig Corpora Collection (wortschatz-leipzig.de, CC BY) by
 * `tools/glide-dict/generate_bigrams.py`; paste the script-printed catalog line here after uploading the
 * `<lang>_bigrams.txt` files as assets of the release named below.
 */
data class BigramDict(
    val lang: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

object BigramCatalog {
    const val REL = "https://github.com/DevEmperor/DictateKeyboard/releases/download/bigram-dicts-v1"

    /** Languages whose bigram file ships in the APK ([ime/dict/<lang>_bigrams.txt]) — never downloaded/deleted. */
    val BUNDLED = setOf("en")

    val all: List<BigramDict> = listOf(
        BigramDict("de", "$REL/de_bigrams.txt", 989807, "52d906c15bab021d386e6bf2537a2090c321959f16f814de3b64357dffb33c67"),
    )

    private val byLang = all.associateBy { it.lang }

    fun forLang(lang: String): BigramDict? = byLang[LatinLanguageProvider.normalizeLang(lang)]
}
