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

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.patrickgold.florisboard.R

/**
 * FORK ADDITION (pshaddad89/DictateKeyboard): a Quick Settings tile that shows or hides the floating
 * button anywhere, text field or not ([BubbleSummon]).
 *
 * The button lives in the accessibility service, so the tile can only call it while that service is on.
 * When it is off the tile says so, and a tap opens the system Accessibility settings to turn it on.
 */
class DictateBubbleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (!DictateAccessibilityService.isRunning) {
            openAccessibilitySettings()
            return
        }
        BubbleSummon.toggle(this)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = DictateAccessibilityService.isRunning
        val on = running && BubbleSummon.summoned.value
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when {
                    !running -> R.string.dictate__tile_subtitle_service_off
                    on -> R.string.dictate__tile_subtitle_shown
                    else -> R.string.dictate__tile_subtitle_hidden
                },
            )
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}
