package eu.kanade.presentation.more.settings.screen.novel

import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.LocalSettingsIndexing
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import reikai.data.novel.tts.FoundTtsServer
import reikai.data.novel.tts.ServerInfo
import reikai.data.novel.tts.TtsServerClient
import reikai.data.novel.tts.TtsServerDiscovery
import reikai.data.novel.tts.TtsServerException
import reikai.data.novel.tts.TtsServerFailure
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.tts.TtsAudioSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.runtime.collectAsState as collectFlowAsState

/**
 * Where read-aloud's audio comes from and how much of it is kept. Each source shows only its own rows:
 * the PC's server, model and voice, or the phone's speed test. The phone's voice stays with the other
 * voice settings in the novel reader screen, since live speech uses it too.
 */
object SettingsReadAloudAudioScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_tts_audio

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val preferences = remember { context.appGraph.novelPreferences }
        val source by preferences.readerTtsAudioSource().collectAsState()
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_tts_audio_source),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.readerTtsAudioSource(),
                        entries = TtsAudioSource.entries.associateWith { stringResource(it.titleRes) },
                        title = stringResource(MR.strings.pref_tts_audio_source),
                    ),
                ),
            ),
            pcGroup(preferences, visible = source == TtsAudioSource.PC),
            phoneGroup(visible = source == TtsAudioSource.PHONE),
            storageGroup(preferences, source),
        )
    }

    @Composable
    private fun pcGroup(preferences: NovelPreferences, visible: Boolean): Preference.PreferenceGroup {
        val context = LocalContext.current
        val client = remember { context.appGraph.ttsServerClient }
        val scope = rememberCoroutineScope()
        val url by preferences.readerTtsServerUrl().collectAsState()
        val awayUrl by preferences.readerTtsServerAwayUrl().collectAsState()
        val paired = preferences.readerTtsServerToken().collectAsState().value.isNotBlank()
        val model by preferences.readerTtsServerModel().collectAsState()
        var picking by remember { mutableStateOf(false) }
        var pairing by remember { mutableStateOf(false) }
        // Bumped to check the server again after a pairing, which may be what it was waiting for.
        var checks by remember { mutableIntStateOf(0) }
        val status = rememberServerStatus(client, url, awayUrl, checks, active = visible)

        if (picking) {
            ServerPickerDialog(
                discovery = remember { context.appGraph.ttsServerDiscovery },
                current = url,
                onDismiss = { picking = false },
                onPick = { address ->
                    picking = false
                    preferences.readerTtsServerUrl().set(address)
                    if (!paired) pairing = true
                },
            )
        }
        if (pairing) {
            PairingDialog(
                onDismiss = { pairing = false },
                onPair = { code ->
                    pairing = false
                    scope.launch {
                        try {
                            client.pair(code, "Reikai on ${Build.MODEL}")
                            context.toast(MR.strings.pref_tts_server_paired)
                            checks++
                        } catch (e: TtsServerException) {
                            context.toast(e.failure.messageRes)
                        }
                    }
                },
            )
        }

        val info = (status as? ServerStatus.Ready)?.info
        val models = info?.models.orEmpty()
        val voices = models.firstOrNull { it.id == model }?.voices.orEmpty()
            .sortedWith(compareBy({ it.language }, { it.label.ifBlank { it.id } }))
        val connectFirst = stringResource(MR.strings.tts_server_connect_first)
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_tts_pc),
            visible = visible,
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_tts_server),
                    subtitle = status.label(url),
                    onClick = { picking = true },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_tts_server_pair),
                    subtitle = stringResource(
                        if (paired) MR.strings.pref_tts_server_paired else MR.strings.pref_tts_server_pair_summary,
                    ),
                    onClick = { pairing = true },
                ),
                if (models.isEmpty()) {
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_tts_server_model),
                        subtitle = model.ifBlank { connectFirst },
                    )
                } else {
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.readerTtsServerModel(),
                        entries = models.associate { it.id to it.id },
                        title = stringResource(MR.strings.pref_tts_server_model),
                        // A voice belongs to its model, so a new model starts on its own first voice.
                        onValueChanged = { id ->
                            val voice = preferences.readerTtsServerVoice()
                            val offered = models.firstOrNull { it.id == id }?.voices.orEmpty()
                            if (offered.none { it.id == voice.get() }) voice.set(offered.firstOrNull()?.id.orEmpty())
                            true
                        },
                    )
                },
                if (voices.isEmpty()) {
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_tts_voice),
                        subtitle = preferences.readerTtsServerVoice().get().ifBlank { connectFirst },
                    )
                } else {
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.readerTtsServerVoice(),
                        entries = voices.associate { voice ->
                            val name = voice.label.ifBlank { voice.id }
                            voice.id to if (voice.language.isBlank()) name else "$name · ${voice.language}"
                        },
                        title = stringResource(MR.strings.pref_tts_voice),
                    )
                },
                Preference.PreferenceItem.EditTextPreference(
                    preference = preferences.readerTtsServerAwayUrl(),
                    title = stringResource(MR.strings.pref_tts_server_away),
                    subtitle = awayUrl.ifBlank { stringResource(MR.strings.pref_tts_server_away_summary) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = preferences.readerTtsServerMobileData(),
                    title = stringResource(MR.strings.pref_tts_server_mobile_data),
                ),
            ),
        )
    }

    @Composable
    private fun phoneGroup(visible: Boolean): Preference.PreferenceGroup {
        val context = LocalContext.current
        val speedTest = remember { context.appGraph.ttsRenderSpeedTest }
        val scope = rememberCoroutineScope()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_tts_phone),
            visible = visible,
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_tts_render_speed),
                    subtitle = stringResource(MR.strings.pref_tts_render_speed_summary),
                    onClick = {
                        context.toast(MR.strings.tts_render_speed_running)
                        scope.launch {
                            val result = speedTest.run()
                            val message = if (result == null) {
                                context.stringResource(MR.strings.tts_render_speed_failed)
                            } else {
                                context.stringResource(
                                    MR.strings.tts_render_speed_result,
                                    "%.1f".format(result.speed),
                                    "%.1f".format(result.audioMs / 1000.0),
                                    "%.1f".format(result.synthMs / 1000.0),
                                    "%.1f".format(result.encodeMs / 1000.0),
                                )
                            }
                            context.toast(message, Toast.LENGTH_LONG)
                        }
                    },
                ),
            ),
        )
    }

    @Composable
    private fun storageGroup(preferences: NovelPreferences, source: TtsAudioSource): Preference.PreferenceGroup {
        val context = LocalContext.current
        val renderedAudio = remember { context.appGraph.renderedTtsAudio }
        val scope = rememberCoroutineScope()
        var renderedBytes by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { renderedBytes = renderedAudio.usedBytes() }
        val rendering = source != TtsAudioSource.LIVE
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_tts_storage),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.readerTtsRenderAhead(),
                    entries = RENDER_AHEAD_CHAPTERS.associateWith { chaptersLabel(it) },
                    title = stringResource(MR.strings.pref_tts_render_ahead),
                    visible = rendering,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.readerTtsKeepBehind(),
                    entries = KEEP_BEHIND_CHAPTERS.associateWith { chaptersLabel(it) },
                    title = stringResource(MR.strings.pref_tts_keep_behind),
                    visible = rendering,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.readerTtsRenderCapMb(),
                    entries = RENDER_CAPS_MB.associateWith {
                        Formatter.formatShortFileSize(context, it * 1024L * 1024L)
                    },
                    title = stringResource(MR.strings.pref_tts_render_cap),
                    visible = rendering,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_tts_render_clear),
                    subtitle = stringResource(
                        MR.strings.pref_tts_render_used,
                        Formatter.formatShortFileSize(context, renderedBytes),
                    ),
                    onClick = {
                        scope.launch {
                            renderedAudio.clear()
                            renderedBytes = 0L
                            context.toast(MR.strings.tts_render_cleared)
                        }
                    },
                ),
            ),
        )
    }
}

private sealed interface ServerStatus {
    data object NotSet : ServerStatus
    data object Checking : ServerStatus
    data class Ready(val info: ServerInfo) : ServerStatus
    data class Failed(val failure: TtsServerFailure) : ServerStatus
}

/** Asks the server for its name, models and voices whenever its address changes; idle while [active] is off. */
@Composable
private fun rememberServerStatus(
    client: TtsServerClient,
    url: String,
    awayUrl: String,
    checks: Int,
    active: Boolean,
): ServerStatus {
    // Settings search composes this screen per query, so it must not reach out to the network.
    val indexing = LocalSettingsIndexing.current
    var status by remember { mutableStateOf<ServerStatus>(ServerStatus.NotSet) }
    LaunchedEffect(url, awayUrl, checks, active, indexing) {
        if (!active || indexing) return@LaunchedEffect
        if (url.isBlank() && awayUrl.isBlank()) {
            status = ServerStatus.NotSet
            return@LaunchedEffect
        }
        status = ServerStatus.Checking
        status = try {
            ServerStatus.Ready(client.info())
        } catch (e: TtsServerException) {
            ServerStatus.Failed(e.failure)
        }
    }
    return status
}

@Composable
private fun ServerStatus.label(url: String): String {
    val where = url.removePrefix("http://").removePrefix("https://")
    return when (this) {
        ServerStatus.NotSet -> stringResource(MR.strings.tts_server_not_set)
        ServerStatus.Checking -> stringResource(MR.strings.tts_server_checking, where)
        is ServerStatus.Ready -> stringResource(MR.strings.tts_server_found_at, info.name.ifBlank { where }, where)
        is ServerStatus.Failed -> stringResource(failure.messageRes)
    }
}

/** The render servers found on this network, plus a field for an address typed by hand. */
@Composable
private fun ServerPickerDialog(
    discovery: TtsServerDiscovery,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val servers by remember { discovery.servers() }.collectFlowAsState(initial = emptyList<FoundTtsServer>())
    var address by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.tts_server_find)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(MR.strings.tts_server_searching),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                servers.forEach { server ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(server.address) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(server.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "${server.host}:${server.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.trim() },
                    label = { Text(stringResource(MR.strings.pref_tts_server_url)) },
                    placeholder = { Text("192.168.1.20:8765") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { TtsServerClient.normalizedBase(address)?.let(onPick) },
                enabled = address.isNotBlank(),
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

/** Asks for the code the PC render server shows, to trade it for a token. */
@Composable
private fun PairingDialog(onDismiss: () -> Unit, onPair: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.pref_tts_server_pair)) },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(PAIRING_CODE_LENGTH) },
                label = { Text(stringResource(MR.strings.tts_server_pairing_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { onPair(code) }, enabled = code.length == PAIRING_CODE_LENGTH) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

@Composable
private fun chaptersLabel(count: Int): String =
    if (count == 0) stringResource(MR.strings.off) else stringResource(MR.strings.tts_chapters_count, count)

private val TtsServerFailure.messageRes
    get() = when (this) {
        TtsServerFailure.UNREACHABLE -> MR.strings.tts_server_unreachable
        TtsServerFailure.UNAUTHORIZED -> MR.strings.tts_server_unauthorized
        TtsServerFailure.UNSUPPORTED -> MR.strings.tts_server_unsupported
    }

/** The pre-rendered audio limits offered, in MB. */
private val RENDER_CAPS_MB = listOf(256, 512, 1024, 2048, 4096, 8192)
private val RENDER_AHEAD_CHAPTERS = listOf(0, 1, 2, 3, 5, 10)
private val KEEP_BEHIND_CHAPTERS = listOf(0, 1, 2, 3, 4, 5)
private const val PAIRING_CODE_LENGTH = 6
