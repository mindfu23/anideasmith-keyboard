// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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

    /** True between [start] and the terminal callback for that session. */
    var isActive = false
        private set

    /** Set while tearing down deliberately, so the resulting error callback is not reported. */
    private var cancelling = false

    fun start(locale: Locale?, preferOffline: Boolean) {
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

        // createOnDeviceSpeechRecognizer exists from API 31, but isOnDeviceRecognitionAvailable
        // only from 33 — on 31/32 there is nothing to ask, so just try it.
        val onDevice = preferOffline && when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> true
            else -> false
        }
        val r = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.e(TAG, "could not create recognizer (onDevice=$onDevice)", e)
            listener.onVoiceInputError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        Log.i(TAG, "created recognizer, onDevice=$onDevice")
        r.setRecognitionListener(recognitionListener)
        recognizer = r

        val intent = buildIntent(locale, preferOffline)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            logRecognitionSupport(r, intent)

        isActive = true
        cancelling = false
        listener.onVoiceInputStarted()
        r.startListening(intent)
    }

    /** Stop listening but keep whatever has been recognised — final results still arrive. */
    fun stop() {
        if (!isActive) return
        Log.i(TAG, "stop()")
        recognizer?.stopListening()
    }

    /** Abandon the session. No further results are reported. */
    fun cancel() {
        if (!isActive) return
        Log.i(TAG, "cancel()")
        cancelling = true
        isActive = false
        recognizer?.cancel()
        release()
        listener.onVoiceInputStopped()
    }

    /**
     * Release the recognizer. A leaked instance keeps the service binding alive and the system
     * microphone indicator lit, so this must run from the IME's onDestroy too.
     */
    fun release() {
        recognizer?.destroy()
        recognizer = null
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
            if (cancelling) return
            isActive = false
            release()
            listener.onVoiceInputError(error)
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            Log.i(TAG, "onResults, ${text?.length ?: -1} chars")
            if (cancelling) return
            isActive = false
            release()
            if (text != null) listener.onVoiceInputFinal(text)
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
