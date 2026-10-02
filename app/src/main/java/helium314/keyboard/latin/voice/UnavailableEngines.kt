// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.ComponentName

/**
 * Speech engines that are installed and advertise a RecognitionService but cannot serve a
 * keyboard. They stay in the engine picker, greyed out, so their absence is explained rather than
 * mysterious, and a saved choice of one falls back to the system default.
 *
 * Claude (checked 2026-10-02, Claude 1.260928.20): its service records under the Claude app's own
 * "while in use" microphone grant, and while someone types in another app Claude is in the
 * background, so Android rejects it ("Reject: [bg-up]" in appops) and the engine answers
 * ERROR_INSUFFICIENT_PERMISSIONS within ~20 ms. Nothing on the keyboard's side can change that.
 * Before that it also could not see this keyboard's package; a disabled RecognitionService stub in
 * the manifest fixed that layer, and was reverted because it is useless alone. Remove the entry
 * once the Claude app records from the foreground.
 */
object UnavailableEngines {
    private val packages = setOf("com.anthropic.claude")

    /** [service] is a flattened ComponentName, as stored in the engine preference. */
    fun isUnavailable(service: String?): Boolean =
        service?.let { ComponentName.unflattenFromString(it)?.packageName } in packages
}
