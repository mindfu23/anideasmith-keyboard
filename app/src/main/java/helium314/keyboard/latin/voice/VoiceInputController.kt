// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import helium314.keyboard.latin.utils.Log
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Owns a [SpeechRecognizer] and turns it into a simple start/stop/cancel surface.
 *
 * Everything here must run on the main thread — [SpeechRecognizer] throws otherwise. The
 * controller holds no view references; [listener] callbacks are where the IME decides what to
 * do with the text.
 *
 * Recognition happens in a separate process (the system's [RecognitionService]), which is why
 * HeliBoard needs no INTERNET permission for this.
 */
class VoiceInputController(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onVoiceInputStarted()
        fun onVoiceInputPartial(text: String)
        fun onVoiceInputFinal(text: String)
        fun onVoiceInputRms(rmsDb: Float)
        /** [error] is one of the SpeechRecognizer.ERROR_* constants. The session is over. */
        fun onVoiceInputError(error: Int)
        fun onVoiceInputStopped()
    }

    private var recognizer: SpeechRecognizer? = null

    /**
     * True while the user wants dictation, across utterances — not merely while the recognizer is
     * listening. Cleared by [stop] and [cancel], which is what ends the restart loop.
     */
    var isActive = false
        private set

    /** Set while tearing down deliberately, so the resulting error callback is not reported. */
    private var cancelling = false

    /** True when the running session was built with [SpeechRecognizer.createOnDeviceSpeechRecognizer]. */
    private var usingOnDevice = false

    /** Guards the one automatic retry, so a broken engine cannot loop. */
    private var retriedOnline = false

    /** Kept so the retry can rebuild the same request against the general recognizer. */
    private var currentLocale: Locale? = null

    /** Punctuation and capitalisation from the engine (API 33+). */
    private var autoPunctuation = true

    /** Empty means "whatever the system has chosen"; otherwise a flattened ComponentName. */
    private var serviceComponent: String? = null

    /** Kept so a restart can reissue the same request without rebuilding it. */
    private var currentIntent: Intent? = null

    /**
     * Consecutive restarts that produced no speech. Reset by any result. Caps the restart loop so
     * a broken engine cannot hold the microphone open forever.
     */
    private var consecutiveErrors = 0

    /** True from scheduling a restart until listening actually begins again. */
    private var restartPending = false

    private val handler = Handler(Looper.getMainLooper())

    fun start(locale: Locale?, preferOffline: Boolean, autoPunctuation: Boolean, service: String?) {
        retriedOnline = false
        consecutiveErrors = 0
        this.autoPunctuation = autoPunctuation
        this.serviceComponent = service?.takeIf { it.isNotEmpty() }
        startInternal(locale, preferOffline)
    }

    private fun startInternal(locale: Locale?, preferOffline: Boolean) {
        if (isActive) {
            Log.i(TAG, "start() while already active, ignoring")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "no recognition service available")
            listener.onVoiceInputError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        logResolvedService()

        // An explicitly chosen engine wins over the on-device preference: the on-device factory
        // takes no component, so honouring both is not possible.
        val chosen = serviceComponent?.let { ComponentName.unflattenFromString(it) }
        // createOnDeviceSpeechRecognizer exists from API 31, but isOnDeviceRecognitionAvailable
        // only from 33 — on 31/32 there is nothing to ask, so just try it.
        val onDevice = chosen == null && preferOffline && when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> true
            else -> false
        }
        val r = try {
            when {
                onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                chosen != null -> SpeechRecognizer.createSpeechRecognizer(context, chosen)
                else -> SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not create recognizer (onDevice=$onDevice)", e)
            listener.onVoiceInputError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        Log.i(TAG, "created recognizer, onDevice=$onDevice, service=${chosen?.flattenToShortString() ?: "system default"}")
        r.setRecognitionListener(recognitionListener)
        recognizer = r
        usingOnDevice = onDevice
        currentLocale = locale

        val intent = buildIntent(locale, preferOffline)
        currentIntent = intent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            logRecognitionSupport(r, intent)

        isActive = true
        cancelling = false
        listener.onVoiceInputStarted()
        r.startListening(intent)
    }

    /**
     * Android's recognizer is single-utterance: it stops on a silence timeout. Continuous
     * dictation is therefore a restart loop, with a backoff so a recognizer that errors
     * immediately cannot spin the microphone.
     *
     * API 33's EXTRA_SEGMENTED_SESSION would avoid the loop, but only on 33+, so the loop has to
     * exist regardless. Running both would double the paths through the trickiest part of this
     * class for no user-visible gain, so this is the single implementation.
     */
    private fun restartListening() {
        // The engine can report more than one final for a single utterance — saying a trailing
        // "period" is enough to produce a second one. Restarting for each would race two
        // startListening calls and the loser gets ERROR_RECOGNIZER_BUSY, which is terminal.
        if (!VoiceSessionPolicy.shouldRestartAfterResult(isActive, cancelling, restartPending)) {
            Log.i(TAG, "not restarting (pending=$restartPending, active=$isActive)")
            return
        }
        val r = recognizer ?: return
        val intent = currentIntent ?: return
        val delay = VoiceSessionPolicy.restartDelayMs(consecutiveErrors)
        restartPending = true
        handler.postDelayed({
            // stop() or cancel() may have landed while this was queued
            if (isActive && !cancelling) {
                Log.i(TAG, "restarting listening (consecutiveErrors=$consecutiveErrors)")
                r.startListening(intent)
            } else {
                restartPending = false
            }
        }, delay)
    }

    /** Stop listening but keep whatever has been recognised — final results still arrive. */
    fun stop() {
        if (!isActive) return
        Log.i(TAG, "stop()")
        // clearing isActive ends the restart loop; the in-flight utterance still reports
        isActive = false
        restartPending = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.stopListening()
    }

    /** Abandon the session. No further results are reported. */
    fun cancel() {
        if (!isActive && recognizer == null) return
        Log.i(TAG, "cancel()")
        cancelling = true
        isActive = false
        restartPending = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        release()
        listener.onVoiceInputStopped()
    }

    /**
     * Release the recognizer. A leaked instance keeps the service binding alive and the system
     * microphone indicator lit, so this must run from the IME's onDestroy too.
     */
    fun release() {
        restartPending = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        currentIntent = null
        isActive = false
    }

    private fun buildIntent(locale: Locale?, preferOffline: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (locale != null)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            // widely ignored by engines — createOnDeviceSpeechRecognizer is the real mechanism
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                if (autoPunctuation)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
        }

    /**
     * Which engine actually handles this matters on One UI, where the default may be Samsung's
     * rather than Google's. Without this every failure is ambiguous between "the IME cannot
     * record" and "there is no usable engine".
     */
    private fun logResolvedService() {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = context.packageManager.queryIntentServices(intent, 0)
        if (services.isEmpty()) {
            Log.w(TAG, "no RecognitionService found by queryIntentServices")
            return
        }
        services.forEach { Log.i(TAG, "RecognitionService available: ${it.serviceInfo.packageName}/${it.serviceInfo.name}") }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun logRecognitionSupport(r: SpeechRecognizer, intent: Intent) {
        try {
            r.checkRecognitionSupport(intent, Executors.newSingleThreadExecutor(), object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    Log.i(TAG, "recognition support: installedOnDevice=${recognitionSupport.installedOnDeviceLanguages}"
                            + ", pendingOnDevice=${recognitionSupport.pendingOnDeviceLanguages}"
                            + ", supportedOnDevice=${recognitionSupport.supportedOnDeviceLanguages}"
                            + ", online=${recognitionSupport.onlineLanguages}")
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "checkRecognitionSupport error $error")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "checkRecognitionSupport threw", e)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "onReadyForSpeech")
            restartPending = false // listening again, so a further restart is allowed
        }

        override fun onBeginningOfSpeech() {
            Log.i(TAG, "onBeginningOfSpeech")
        }

        // The Gate 2 instrument: if an IME is denied microphone access this stays flat at the
        // floor value while onReadyForSpeech still fires and ERROR_NO_MATCH arrives on a timer.
        // That silence, not a crash, is what "an IME cannot record" looks like.
        override fun onRmsChanged(rmsdB: Float) {
            listener.onVoiceInputRms(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) { }

        override fun onEndOfSpeech() {
            Log.i(TAG, "onEndOfSpeech")
        }

        override fun onError(error: Int) {
            Log.w(TAG, "onError ${errorName(error)}")
            restartPending = false // this attempt is over either way
            val action = VoiceSessionPolicy.onError(
                error, cancelling, isActive, consecutiveErrors, usingOnDevice, retriedOnline
            )
            if (action == VoiceSessionPolicy.ErrorAction.IGNORE) return
            if (action == VoiceSessionPolicy.ErrorAction.RESTART) {
                // A pause between sentences ends the utterance, not the dictation.
                consecutiveErrors++
                restartListening()
                return
            }

            val retryOnline = action == VoiceSessionPolicy.ErrorAction.RETRY_ONLINE
            isActive = false
            release()
            // isOnDeviceRecognitionAvailable() reports true whenever the engine exists, even with
            // no downloaded model, so the on-device recognizer can only ever fail here. Fall back
            // to the general one once rather than leaving the user with a mic that does nothing.
            if (retryOnline) {
                Log.i(TAG, "on-device recognition unavailable for the language, retrying online")
                retriedOnline = true
                startInternal(currentLocale, false)
                return
            }
            listener.onVoiceInputError(error)
            // every terminal path ends with onVoiceInputStopped, so the IME has exactly one place
            // to close the composing span
            listener.onVoiceInputStopped()
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            Log.i(TAG, "onResults, ${text?.length ?: -1} chars")
            if (cancelling) return
            consecutiveErrors = 0 // the engine is working; any earlier silence is forgiven
            if (text != null) listener.onVoiceInputFinal(text)
            if (isActive) {
                restartListening() // keep dictating until the user stops
            } else {
                release()
                listener.onVoiceInputStopped()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (cancelling) return
            val text = firstResult(partialResults) ?: return
            Log.i(TAG, "onPartialResults, ${text.length} chars")
            listener.onVoiceInputPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) { }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        private val TAG = VoiceInputController::class.simpleName

        fun errorName(error: Int) = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
            else -> "unknown error $error"
        }
    }
}
