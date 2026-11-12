/*
 * Copyright (C) 2026 pshaddad89 (fork addition to Dictate by DevEmperor)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * FORK ADDITION (pshaddad89/DictateKeyboard): the floating button "summoned" by the Quick Settings tile
 * ([DictateBubbleTileService]), so it can be used as a small assistant — screenshot, menu, a dictation
 * that lands on the clipboard — where no text field is focused.
 *
 * While summoned the button stays up everywhere, past the focus rule, the per-app filter and the Dictate
 * keyboard. It still goes when the screen goes off (and that ends the summon, so a phone picked up later
 * does not greet its owner with it) and while another app runs a system voice-input session.
 */
object BubbleSummon {

    private val _summoned = MutableStateFlow(false)
    val summoned: StateFlow<Boolean> get() = _summoned

    fun set(context: Context, on: Boolean) {
        if (_summoned.value == on) return
        _summoned.value = on
        refreshTile(context)
    }

    fun toggle(context: Context) = set(context, !_summoned.value)

    /** Whether the summon alone keeps the button on screen. */
    fun shows(summoned: Boolean, screenOn: Boolean, recognitionActive: Boolean): Boolean =
        summoned && screenOn && !recognitionActive

    /** Asks the system to redraw the tile, which may not be listening while the shade is closed. */
    fun refreshTile(context: Context) {
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, DictateBubbleTileService::class.java))
        }
    }

    /** Pulls the shade up after a summon, so the button the tile just called is not hidden behind it. */
    fun closeShade(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
    }
}
