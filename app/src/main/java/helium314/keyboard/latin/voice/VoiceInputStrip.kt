// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.databinding.VoiceInputSuggestionBinding
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey

/**
 * The row shown in place of the suggestion strip while dictating: a microphone that reacts to the
 * voice level, a "Listening…" label, and a stop button.
 *
 * The system microphone indicator already says the mic is open, but it says nothing about whether
 * this keyboard is hearing anything. That is what the reacting icon is for.
 */
class VoiceInputStrip private constructor(
    val root: View,
    private val icon: ImageView,
    private val preview: TextView
) {

    /**
     * Show what has been heard but not yet finalised. This is the only place provisional dictation
     * appears: writing it into the text field means a composing span the engine then revises, and
     * an editor that mishandles one shrinking leaves the discarded words behind as duplicates.
     */
    fun onPartial(text: String) {
        preview.text = text
    }

    /** Drop the preview once the words have been committed for real. */
    fun clearPartial() {
        preview.text = ""
    }

    /**
     * @param rmsDb as reported by the recognizer, roughly -2 (silence) to 10 (loud). The scale is
     *   not specified by the platform and varies by engine, so this only needs to look alive.
     */
    fun onRms(rmsDb: Float) {
        val level = ((rmsDb - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0f, 1f)
        icon.alpha = MIN_ALPHA + (1f - MIN_ALPHA) * level
    }

    companion object {
        private const val QUIET_DB = -2f
        private const val LOUD_DB = 10f
        private const val MIN_ALPHA = 0.35f

        fun create(context: Context, parent: ViewGroup, onStop: () -> Unit): VoiceInputStrip {
            val binding = VoiceInputSuggestionBinding.inflate(LayoutInflater.from(context), parent, false)
            val colors = Settings.getValues().mColors

            val icon = binding.voiceInputSuggestionIcon
            icon.setImageDrawable(KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.VOICE.name.lowercase()))
            colors.setColor(icon, ColorType.KEY_ICON)
            icon.alpha = MIN_ALPHA


            val stopButton = binding.voiceInputSuggestionStop
            stopButton.setImageDrawable(KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase()))
            colors.setColor(stopButton, ColorType.REMOVE_SUGGESTION_ICON)

            val preview = binding.voiceInputSuggestionText
            preview.setTextColor(colors.get(ColorType.KEY_TEXT))

            colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)

            // the whole row stops dictation, not just the button — it is the only thing here to tap
            binding.root.setOnClickListener { onStop() }
            stopButton.setOnClickListener { onStop() }

            return VoiceInputStrip(binding.root, icon, preview)
        }
    }
}
