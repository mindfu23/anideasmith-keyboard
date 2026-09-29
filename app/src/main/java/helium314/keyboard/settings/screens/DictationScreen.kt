// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.SwitchPreference

/**
 * Everything about dictation in one place. Until 2026-09-29 these lived at the end of Preferences,
 * with the dictated-text log switch in About beside Save log; Save log is repeated here so that
 * switch still sits next to it.
 */
@Composable
fun DictationScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    // the rest only mean anything once dictation keeps the keyboard up
    val inlineVoiceInput = prefs.getBoolean(Settings.PREF_USE_INLINE_VOICE_INPUT, Defaults.PREF_USE_INLINE_VOICE_INPUT)
    val items = listOf(
        Settings.PREF_USE_INLINE_VOICE_INPUT,
        if (inlineVoiceInput) R.string.settings_category_voice_punctuation else null,
        if (inlineVoiceInput) Settings.PREF_VOICE_INPUT_AUTO_PUNCTUATION else null,
        if (inlineVoiceInput) Settings.PREF_VOICE_INPUT_SPOKEN_PUNCTUATION else null,
        if (inlineVoiceInput) Settings.PREF_VOICE_INPUT_SENTENCE_CAPS else null,
        if (inlineVoiceInput) R.string.voice_long_form else null,
        if (inlineVoiceInput) Settings.PREF_VOICE_INPUT_KEEP_TYPING else null,
        if (inlineVoiceInput) R.string.settings_category_voice_recognition else null,
        if (inlineVoiceInput && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Settings.PREF_VOICE_INPUT_PREFER_OFFLINE else null,
        if (inlineVoiceInput) Settings.PREF_VOICE_INPUT_SERVICE else null,
        R.string.settings_category_voice_troubleshooting,
        DebugSettings.PREF_LOG_DICTATED_TEXT,
        SettingsWithoutKey.SAVE_LOG,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_dictation),
        settings = items
    )
}

fun createDictationSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_USE_INLINE_VOICE_INPUT,
        R.string.use_inline_voice_input, R.string.use_inline_voice_input_summary
    ) { setting ->
        // mirrors the READ_CONTACTS switch in TextCorrectionScreen: the permission is only ever
        // requested from here, because an InputMethodService cannot show a permission dialog
        val activity = LocalContext.current.getActivity() ?: return@Setting
        var granted by remember {
            mutableStateOf(PermissionsUtil.checkAllPermissionsGranted(activity, Manifest.permission.RECORD_AUDIO))
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            if (granted)
                activity.prefs().edit { putBoolean(setting.key, true) }
            else
                Toast.makeText(activity, R.string.voice_input_no_permission, Toast.LENGTH_LONG).show()
        }
        SwitchPreference(setting, Defaults.PREF_USE_INLINE_VOICE_INPUT,
            allowCheckedChange = {
                if (!it) true
                else if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                    Toast.makeText(activity, R.string.voice_input_not_available, Toast.LENGTH_LONG).show()
                    false
                } else if (!granted) {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                    false
                } else true
            }
        )
    },
    Setting(context, Settings.PREF_VOICE_INPUT_KEEP_TYPING,
        R.string.voice_input_keep_typing, R.string.voice_input_keep_typing_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VOICE_INPUT_KEEP_TYPING)
    },
    // The two are opposite answers to the same question, so turning one on turns the other off.
    // Both off is a third answer -- no punctuation from anywhere -- and stays reachable.
    Setting(context, Settings.PREF_VOICE_INPUT_AUTO_PUNCTUATION,
        R.string.voice_input_auto_punctuation, R.string.voice_input_auto_punctuation_summary
    ) { setting ->
        val ctx = LocalContext.current
        SwitchPreference(setting, Defaults.PREF_VOICE_INPUT_AUTO_PUNCTUATION) { on ->
            if (on) ctx.prefs().edit { putBoolean(Settings.PREF_VOICE_INPUT_SPOKEN_PUNCTUATION, false) }
        }
    },
    Setting(context, Settings.PREF_VOICE_INPUT_SPOKEN_PUNCTUATION,
        R.string.voice_input_spoken_punctuation, R.string.voice_input_spoken_punctuation_summary
    ) { setting ->
        val ctx = LocalContext.current
        SwitchPreference(setting, Defaults.PREF_VOICE_INPUT_SPOKEN_PUNCTUATION) { on ->
            if (on) ctx.prefs().edit { putBoolean(Settings.PREF_VOICE_INPUT_AUTO_PUNCTUATION, false) }
        }
    },
    Setting(context, Settings.PREF_VOICE_INPUT_SENTENCE_CAPS,
        R.string.voice_input_sentence_caps, R.string.voice_input_sentence_caps_summary
    ) { SwitchPreference(it, Defaults.PREF_VOICE_INPUT_SENTENCE_CAPS) },
    Setting(context, Settings.PREF_VOICE_INPUT_PREFER_OFFLINE,
        R.string.voice_input_prefer_offline, R.string.voice_input_prefer_offline_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VOICE_INPUT_PREFER_OFFLINE)
    },
    Setting(context, Settings.PREF_VOICE_INPUT_SERVICE, R.string.voice_input_service) { setting ->
        // Also addresses upstream #1547, which asks to choose the engine. An empty value means
        // "whatever the system default is", so a user who never touches this is unaffected.
        val ctx = LocalContext.current
        val services = remember {
            val pm = ctx.packageManager
            listOf(ctx.getString(R.string.voice_input_service_default) to "") +
                pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map { info ->
                    val label = info.serviceInfo.applicationInfo.loadLabel(pm).toString()
                    label to ComponentName(info.serviceInfo.packageName, info.serviceInfo.name).flattenToString()
                }
        }
        ListPreference(setting, services, Defaults.PREF_VOICE_INPUT_SERVICE)
    },
    Setting(context, DebugSettings.PREF_LOG_DICTATED_TEXT,
        R.string.log_dictated_text, R.string.log_dictated_text_summary
    ) {
        SwitchPreference(it, Defaults.PREF_LOG_DICTATED_TEXT)
    },
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            DictationScreen { }
        }
    }
}
