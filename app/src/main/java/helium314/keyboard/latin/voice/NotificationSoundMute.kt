// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.media.AudioManager
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.utils.Log

/**
 * Silences the recognition service's listening pings for a long-form session.
 *
 * The pings are not ours to switch off: Google's service plays them itself, as an AudioTrack with
 * AUDIO_USAGE_NOTIFICATION_EVENT, on every startListening and at every end of utterance, and no
 * extra suppresses them. Long form reopens its session after every silence the engine calls final,
 * so a thinking pause in prose pings. Measured 2026-09-29: one reopen per 13–28s of silence, and the
 * user heard them in Obsidian. Muting the notification stream for the session is the only lever an
 * IME has, and it silences real notifications for as long as the session lasts — which is why it
 * is confined to long form and restored the moment the session ends.
 *
 * Whether we muted is kept on disk rather than in memory, so a keyboard process that dies
 * mid-session still gives the sound back the next time it starts. In a file of its own, not the
 * settings: a settings write mid-session makes the keyboard reload its settings, and device-protected
 * storage so it can be read before the phone is first unlocked. A stream the user had muted
 * themselves is left alone in both directions: never counted as ours, so never unmuted.
 */
internal class NotificationSoundMute(private val context: Context) {

    private val state: SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)

    fun mute() {
        if (state.getBoolean(MUTED_BY_US, false)) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audio.isStreamMute(AudioManager.STREAM_NOTIFICATION)) return
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            state.edit { putBoolean(MUTED_BY_US, true) }
            Log.i(TAG, "notification sounds muted for long-form dictation")
        } catch (e: SecurityException) {
            // Do Not Disturb can refuse volume changes; the pings are a nuisance, not a fault
            Log.w(TAG, "could not mute notification sounds: ${e.message}")
        }
    }

    fun restore() {
        if (!state.getBoolean(MUTED_BY_US, false)) return
        state.edit { putBoolean(MUTED_BY_US, false) }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
            Log.i(TAG, "notification sounds restored")
        } catch (e: SecurityException) {
            Log.w(TAG, "could not restore notification sounds: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NotificationSoundMute"
        private const val STATE_FILE = "voice_input_state"
        private const val MUTED_BY_US = "muted_notifications"
    }
}
