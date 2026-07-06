/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.ime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import net.devemperor.dictate.wear.sync.WearSettingsStore

/**
 * Watch-side mirror of the phone's dictation haptics (issue #166): a short buzz on record start/stop, a
 * double when the transcription is ready, a longer one when a rewording is applied — so the wrist tells
 * the user when a step finished. Gated on the phone-synced `hapticFeedback` flag.
 */
object WearHaptics {
    private const val SHORT_MS = 25L
    private const val MEDIUM_MS = 90L
    private const val GAP_MS = 90L

    /** Record start/stop. */
    fun short(context: Context) = vibrate(context, VibrationEffect.createOneShot(SHORT_MS, VibrationEffect.DEFAULT_AMPLITUDE))

    /** Transcription ready. */
    fun double(context: Context) =
        vibrate(context, VibrationEffect.createWaveform(longArrayOf(0L, SHORT_MS, GAP_MS, SHORT_MS), -1))

    /** Rewording / LLM prompt applied. */
    fun medium(context: Context) = vibrate(context, VibrationEffect.createOneShot(MEDIUM_MS, VibrationEffect.DEFAULT_AMPLITUDE))

    private fun vibrate(context: Context, effect: VibrationEffect) {
        if (!WearSettingsStore.current().hapticFeedback) return
        runCatching {
            val vibrator = vibratorOf(context)
            if (vibrator.hasVibrator()) vibrator.vibrate(effect)
        }
    }

    private fun vibratorOf(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
