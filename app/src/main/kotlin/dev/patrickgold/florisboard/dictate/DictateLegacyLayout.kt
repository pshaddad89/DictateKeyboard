/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

/**
 * Whether the keyboard opens into the classic, keyboard-less "legacy" dictation layout (issue #125) –
 * the compact record-first UI from Dictate 3.x that several dictation-only users asked to have back.
 *
 *  - [OFF]: the modern FlorisBoard-style keyboard (default, unchanged behaviour).
 *  - [LOCKED]: only the legacy dictation UI. The typing keyboard is never shown; users who need to type
 *    switch to another IME via the switch key.
 *  - [SWIPE]: the legacy dictation UI is the home view, but a horizontal swipe flips to the modern
 *    typing keyboard and back – for users who mainly dictate but occasionally need the full keyboard.
 *
 * Both [LOCKED] and [SWIPE] render [LegacyDictateLayout] in place of the regular typing keyboard (the
 * `ImeUiMode.TEXT` branch in `ImeWindow`); [SWIPE] additionally wires the swipe-to-modern gesture.
 */
enum class DictateLegacyLayout {
    OFF,
    LOCKED,
    SWIPE;

    /** True when the legacy dictation UI should be shown as the keyboard home view. */
    val isEnabled: Boolean
        get() = this != OFF
}
