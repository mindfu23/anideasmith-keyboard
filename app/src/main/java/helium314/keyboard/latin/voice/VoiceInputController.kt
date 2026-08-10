// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.utils.Log
import java.util.Locale
import java.util.concurrent.Executor

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
        /**
         * The engine is listening on a session that will send its text from the beginning. Fires
         * once per [SpeechRecognizer.startListening], not per utterance, so it marks the points
         * where nothing streamed so far will be extended or revised again.
         */
        fun onVoiceInputListening()
        fun onVoiceInputPartial(text: String)
        fun onVoiceInputFinal(text: String)
        fun onVoiceInputRms(rmsDb: Float)
        /** [error] is one of the SpeechRecognizer.ERROR_* constants. The session is over. */
        fun onVoiceInputError(error: Int)
        /**
         * The engine stopped listening. [recovering] is true while a new one is being built and
         * dictation carries on by itself, false once rebuilding has been tried enough times and
         * the session really is over.
         */
        fun onVoiceInputEngineWedged(recovering: Boolean)
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

    /** Runs until the user stops it, tolerating silences that would end an ordinary session. */
    var longForm = false
        private set

    /** Kept so a restart can reissue the same request without rebuilding it. */
    private var currentIntent: Intent? = null

    /**
     * Consecutive restarts that produced no speech. Reset by any result. Caps the restart loop so
     * a broken engine cannot hold the microphone open forever.
     */
    private var consecutiveErrors = 0

    /** ERROR_CLIENT failures since the last result, capped separately from silence. */
    private var clientErrors = 0

    /** True from scheduling a restart until listening actually begins again. */
    private var restartPending = false

    private val handler = Handler(Looper.getMainLooper())

    /** Uptime of the last recognised text, for the long-form idle ceiling. */
    private var lastResultAt = 0L

    /** Uptime of the last startListening, so an error can be timed against it. */
    private var listenStartedAt = 0L

    /** Errors that arrived too fast for the engine to have listened. Reset by any result. */
    private var instantErrors = 0

    /** Recognizer rebuilds this session, capped so a dead engine is not retried forever. */
    private var recoveries = 0

    /** True from scheduling a rebuild until it runs. The session stays active throughout. */
    private var recoveryPending = false

    /**
     * True once the engine has answered with a segment, which is the only proof that it honoured
     * the segmented-session request. Until then the restart loop is still the live mechanism.
     */
    private var segmented = false

    fun start(
        locale: Locale?, preferOffline: Boolean, autoPunctuation: Boolean, service: String?,
        longForm: Boolean = false
    ) {
        retriedOnline = false
        consecutiveErrors = 0
        clientErrors = 0
        instantErrors = 0
        recoveries = 0
        segmented = false
        this.autoPunctuation = autoPunctuation
        this.serviceComponent = service?.takeIf { it.isNotEmpty() }
        this.longForm = longForm
        lastResultAt = SystemClock.uptimeMillis()
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
        if (DebugFlags.DEBUG_ENABLED) logResolvedService()

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
        // What was asked for, so a log can be read against the request rather than guessed at.
        // EXTRA_ENABLE_FORMATTING is the one that decides whether the engine punctuates and
        // capitalises by itself, which is the difference between text to correct and text to leave.
        if (DebugFlags.DEBUG_ENABLED)
            Log.i(TAG, "asked for: formatting=${intent.hasExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING)}"
                    + ", preferOffline=$preferOffline, autoPunctuation=$autoPunctuation"
                    + ", longForm=$longForm, segmented=${intent.hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION)}")
        if (DebugFlags.DEBUG_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            logRecognitionSupport(r, intent)

        isActive = true
        cancelling = false
        listener.onVoiceInputStarted()
        listenStartedAt = SystemClock.uptimeMillis()
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
        val delay = VoiceSessionPolicy.restartDelayMs(consecutiveErrors, clientErrors)
        restartPending = true
        handler.postDelayed({
            // stop() or cancel() may have landed while this was queued
            if (isActive && !cancelling) {
                Log.i(TAG, "restarting listening (consecutiveErrors=$consecutiveErrors, clientErrors=$clientErrors)")
                listenStartedAt = SystemClock.uptimeMillis()
                r.startListening(intent)
            } else {
                restartPending = false
            }
        }, delay)
    }

    /**
     * Destroy the recognizer and build a new one after a pause, without ending the session. The
     * recognition service runs out of process and can stop listening while still accepting calls;
     * restarting it in place then just repeats the same instant failure, so the whole object goes.
     *
     * [isActive] deliberately stays true: from everywhere else this is one continuous session, so
     * the microphone key still toggles it off and a key press still cancels it during the pause.
     */
    private fun recover() {
        recoveries++
        Log.w(TAG, "engine stopped listening, rebuilding recognizer" +
                " (recovery $recoveries of ${VoiceSessionPolicy.MAX_RECOVERIES})")
        instantErrors = 0
        consecutiveErrors = 0
        clientErrors = 0
        restartPending = false
        recoveryPending = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        currentIntent = null
        listener.onVoiceInputEngineWedged(true)
        val locale = currentLocale
        // the resolved choice, not the original preference — whichever engine we were on is the
        // one to rebuild, and the on-device fallback has already been decided by this point
        val onDevice = usingOnDevice
        handler.postDelayed({
            if (!recoveryPending) return@postDelayed // stopped or cancelled during the pause
            recoveryPending = false
            isActive = false // startInternal refuses to run while a session is marked active
            startInternal(locale, onDevice)
        }, VoiceSessionPolicy.RECOVERY_COOL_OFF_MS)
    }

    /** Stop listening but keep whatever has been recognised — final results still arrive. */
    fun stop() {
        if (!isActive) return
        Log.i(TAG, "stop()")
        // clearing isActive ends the restart loop; the in-flight utterance still reports
        isActive = false
        restartPending = false
        recoveryPending = false
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
        recoveryPending = false
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
        recoveryPending = false
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
            if (longForm) {
                // widely ignored by engines - the restart loop is what actually makes long
                // silences survivable - but harmless to ask, and honoured by some
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, LONG_FORM_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, LONG_FORM_SILENCE_MS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                // Google's engine rejects this outright unless EXTRA_PREFER_OFFLINE is set
                // ("EXTRA_ENABLE_FORMATTING can't be used when EXTRA_PREFER_OFFLINE is false"),
                // so asking anyway only earns an error in its log.
                if (autoPunctuation && preferOffline)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                // One session that segments itself, rather than a session per utterance. See
                // VoiceSessionPolicy.useSegmentedSession: this is what silences the earcons.
                if (VoiceSessionPolicy.useSegmentedSession(Build.VERSION.SDK_INT)) {
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        VoiceSessionPolicy.SEGMENTED_SILENCE_MS)
                }
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
            r.checkRecognitionSupport(intent, DIRECT_EXECUTOR, object : RecognitionSupportCallback {
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
            listener.onVoiceInputListening()
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
            val sinceListen = SystemClock.uptimeMillis() - listenStartedAt
            Log.w(TAG, "onError ${errorName(error)} after ${sinceListen}ms")
            restartPending = false // this attempt is over either way
            // counted before the decision, so the third instant error is the one that acts on it
            if (VoiceSessionPolicy.isWedgedError(error, sinceListen)) instantErrors++ else instantErrors = 0
            val action = VoiceSessionPolicy.onError(
                error = error,
                cancelling = cancelling,
                isActive = isActive,
                consecutiveErrors = consecutiveErrors,
                usingOnDevice = usingOnDevice,
                retriedOnline = retriedOnline,
                maxConsecutiveErrors =
                    if (longForm) VoiceSessionPolicy.NO_ERROR_CAP else VoiceSessionPolicy.MAX_CONSECUTIVE_ERRORS,
                clientErrors = clientErrors,
                msSinceLastResult =
                    if (longForm) SystemClock.uptimeMillis() - lastResultAt else 0,
                instantErrors = instantErrors,
                recoveries = recoveries
            )
            if (action == VoiceSessionPolicy.ErrorAction.IGNORE) return
            if (action == VoiceSessionPolicy.ErrorAction.RESTART) {
                // A pause between sentences ends the utterance, not the dictation.
                if (error == SpeechRecognizer.ERROR_CLIENT) clientErrors++ else consecutiveErrors++
                restartListening()
                return
            }

            if (action == VoiceSessionPolicy.ErrorAction.RECOVER) {
                recover()
                return
            }

            val retryOnline = action == VoiceSessionPolicy.ErrorAction.RETRY_ONLINE
            // a wedged engine is not the same failure as the error code says it is, and deserves
            // its own message rather than "voice input unavailable"
            val wedged = instantErrors >= VoiceSessionPolicy.WEDGE_ERROR_COUNT
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
            if (wedged) listener.onVoiceInputEngineWedged(false) else listener.onVoiceInputError(error)
            // every terminal path ends with onVoiceInputStopped, so the IME has exactly one place
            // to close the composing span
            listener.onVoiceInputStopped()
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            Log.i(TAG, "onResults, ${text?.length ?: -1} chars")
            if (cancelling) return
            // In a segmented session every utterance has already arrived through onSegmentResults;
            // a trailing onResults would repeat the last one. Only the session end matters here,
            // and onEndOfSegmentedSession reports that.
            if (segmented) {
                Log.i(TAG, "ignoring onResults, segment results are driving this session")
                return
            }
            consecutiveErrors = 0 // the engine is working; any earlier silence is forgiven
            clientErrors = 0
            instantErrors = 0
            lastResultAt = SystemClock.uptimeMillis()
            if (text != null) listener.onVoiceInputFinal(text)
            if (isActive) {
                restartListening() // keep dictating until the user stops
            } else {
                release()
                listener.onVoiceInputStopped()
            }
        }

        /**
         * One utterance inside a continuous session. No restart follows: the engine is still
         * listening, which is the whole point — no teardown means no earcon.
         */
        override fun onSegmentResults(segmentResults: Bundle) {
            val text = firstResult(segmentResults)
            Log.i(TAG, "onSegmentResults, ${text?.length ?: -1} chars")
            if (cancelling) return
            if (!segmented) {
                segmented = true
                Log.i(TAG, "engine honoured the segmented session; restart loop stands down")
            }
            consecutiveErrors = 0
            clientErrors = 0
            instantErrors = 0
            lastResultAt = SystemClock.uptimeMillis()
            if (text != null) listener.onVoiceInputFinal(text)
        }

        /**
         * The continuous session ended by itself, after a silence the engine considers final.
         *
         * Long form opens another one, because prose has pauses longer than the engine's patience
         * and the session has to outlast them. Short form takes the engine at its word and stops:
         * one thought at a time is what it is for, and the engine deciding it has heard the end of
         * one is the same judgement the user would have made.
         */
        override fun onEndOfSegmentedSession() {
            val idle = SystemClock.uptimeMillis() - lastResultAt
            Log.i(TAG, "onEndOfSegmentedSession (active=$isActive, longForm=$longForm, idle=${idle}ms)")
            if (cancelling) return
            if (VoiceSessionPolicy.shouldReopenSegmentedSession(isActive, longForm, idle)) {
                // a session that ended in silence backs off the same way a silence timeout does
                consecutiveErrors++
                restartListening()
                return
            }
            isActive = false
            release()
            listener.onVoiceInputStopped()
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

        /** Runs the callback on the thread that delivers it; these callbacks only log. */
        private val DIRECT_EXECUTOR = Executor { it.run() }

        /**
         * What long-form dictation asks the engine to tolerate before ending an utterance. An Int
         * because the extras are read with getIntExtra: a Long is not a smaller ask, it is no ask
         * at all, silently answered with the engine's own default.
         */
        private const val LONG_FORM_SILENCE_MS = 30_000

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
