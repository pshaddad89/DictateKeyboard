/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import dev.patrickgold.florisboard.dictate.DictationSink

/**
 * [DictationSink] backed by [DictateAccessibilityService]. Used by the floating dictation button
 * (issue #88) to write the transcription into whichever app's text field is focused, where no
 * InputConnection is available. Every call is a no-op (returns gracefully) when the accessibility
 * service is not running or no editable field is focused.
 */
class AccessibilitySink : DictationSink {
    override fun commitText(text: String) {
        DictateAccessibilityService.injectText(text)
    }

    override fun selectedText(): String = DictateAccessibilityService.selectedText()

    override fun fullText(): String = DictateAccessibilityService.fullText()

    override fun selectAll() {
        DictateAccessibilityService.selectAll()
    }

    override fun performEnter() {
        DictateAccessibilityService.performEnter()
    }

    override fun deleteLastText(text: String): Boolean =
        DictateAccessibilityService.deleteLastText(text)

    // The overlay has no composing/underline concept over AccessibilityService, and re-setting the whole
    // field on every interim would be an accessibility-IPC flood (issue #147). So interim is skipped and
    // only the finished text is written on finish (issue #128).
    override fun setComposingText(text: String) { /* no interim over a11y */ }

    override fun finishComposing(text: String) {
        DictateAccessibilityService.injectText(text)
    }

    override fun clearComposing() { /* nothing was shown */ }
}
