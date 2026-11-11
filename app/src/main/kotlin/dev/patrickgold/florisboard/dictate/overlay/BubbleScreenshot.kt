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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Display
import android.widget.Toast
import androidx.annotation.RequiresApi
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * FORK ADDITION (pshaddad89/DictateKeyboard): the floating button menu's "Screenshot" entry.
 *
 * Kept in a file of its own so that merging upstream changes touches as little of the original code as
 * possible. The accessibility service captures the screen itself (Android 11+, `canTakeScreenshot` in
 * accessibility_service_config.xml), saves it to Pictures/Dictate and puts it on the clipboard. On
 * Android 9/10, where that API does not exist, it falls back to the system screenshot.
 */
internal object BubbleScreenshot {

    /** Folder under Pictures/ the screenshots are saved to. */
    private const val FOLDER = "Dictate"

    /**
     * Long enough for the menu window that was just removed and the hidden bubble to leave the frame the
     * capture reads, so neither shows up in the screenshot.
     */
    private const val SETTLE_MS = 300L

    /**
     * Takes one screenshot. [hideOverlay] is called first so the bubble is not in the picture, and
     * [restoreOverlay] always afterwards — straight after the capture, before the slower save.
     */
    suspend fun capture(
        service: AccessibilityService,
        hideOverlay: () -> Unit,
        restoreOverlay: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureWithService(service, hideOverlay, restoreOverlay)
        } else {
            captureWithSystem(service, hideOverlay, restoreOverlay)
        }
    }

    /** Android 11+: the accessibility service takes the picture itself. */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureWithService(
        service: AccessibilityService,
        hideOverlay: () -> Unit,
        restoreOverlay: () -> Unit,
    ) {
        hideOverlay()
        val result = try {
            delay(SETTLE_MS)
            grab(service)
        } finally {
            restoreOverlay()
        }
        val bitmap = result.getOrNull()
        if (bitmap != null) {
            saveAndCopy(service, bitmap)
        } else {
            val code = (result.exceptionOrNull() as? CaptureError)?.code ?: -1
            toast(service, failureMessage(service, code))
        }
    }

    /** Android 9/10: the same screenshot as Power + Volume down, saved by the system. */
    private suspend fun captureWithSystem(
        service: AccessibilityService,
        hideOverlay: () -> Unit,
        restoreOverlay: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            toast(service, service.getString(R.string.dictate__screenshot_unsupported))
            return
        }
        hideOverlay()
        try {
            delay(SETTLE_MS)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            delay(SETTLE_MS) // the system takes its picture asynchronously; keep the bubble out of it
        } finally {
            restoreOverlay()
        }
    }

    private class CaptureError(val code: Int) : Exception("takeScreenshot failed: $code")

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun grab(service: AccessibilityService): Result<Bitmap> =
        suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        // The wrapped bitmap lives in GPU memory and cannot be compressed; copy it out.
                        val bitmap = try {
                            val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            hw?.copy(Bitmap.Config.ARGB_8888, false).also { hw?.recycle() }
                        } catch (e: Exception) {
                            null
                        } finally {
                            buffer.close()
                        }
                        if (cont.isActive) {
                            cont.resume(
                                if (bitmap != null) Result.success(bitmap) else Result.failure(CaptureError(-1)),
                            )
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(Result.failure(CaptureError(errorCode)))
                    }
                },
            )
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun saveAndCopy(service: AccessibilityService, bitmap: Bitmap) {
        val uri = withContext(Dispatchers.IO) {
            try {
                save(service, bitmap)
            } finally {
                bitmap.recycle()
            }
        }
        if (uri == null) {
            toast(service, service.getString(R.string.dictate__screenshot_save_failed))
            return
        }
        runCatching {
            val clipboard = service.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                ClipData.newUri(
                    service.contentResolver,
                    service.getString(R.string.dictate__floating_button_menu_screenshot_title),
                    uri,
                ),
            )
        }
        toast(service, service.getString(R.string.dictate__screenshot_saved, "${Environment.DIRECTORY_PICTURES}/$FOLDER"))
    }

    /** Writes the PNG into the shared Pictures/Dictate folder, where the gallery picks it up. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun save(service: AccessibilityService, bitmap: Bitmap): Uri? {
        val resolver = service.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_$stamp.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return try {
            val written = resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
            check(written) { "compress failed" }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun failureMessage(service: AccessibilityService, code: Int): String = when (code) {
        // The capability is granted when the service is switched on, so an update that adds it needs one
        // off/on of the service in the system Accessibility settings.
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            service.getString(R.string.dictate__screenshot_no_access)
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            service.getString(R.string.dictate__screenshot_too_fast)
        else -> service.getString(R.string.dictate__screenshot_failed, code)
    }

    private fun toast(service: AccessibilityService, message: String) {
        Toast.makeText(service, message, Toast.LENGTH_LONG).show()
    }
}
