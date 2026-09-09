package moe.evil.hwhh

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.hook.factory.prefs
import kotlinx.coroutines.delay
import moe.evil.hwhh.analysis.AnalysisController
import moe.evil.hwhh.analysis.AnalysisNeed
import moe.evil.hwhh.analysis.AnalysisService
import moe.evil.hwhh.analysis.AnalysisState
import moe.evil.hwhh.analysis.HealthIdAnalysis
import moe.evil.hwhh.shared.DebugPrefs
import moe.evil.hwhh.shared.DebugToggle
import moe.evil.hwhh.shared.HookFeature
import moe.evil.hwhh.shared.PREFS_NAME
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.LogLevel
import moe.evil.hwhh.ui.theme.Dimensions
import moe.evil.hwhh.ui.theme.HWHealthHookerTheme
import moe.evil.hwhh.ui.widget.AboutDialog
import moe.evil.hwhh.ui.widget.AnalysisClearDialog
import moe.evil.hwhh.ui.widget.AnalysisProgressDialog
import moe.evil.hwhh.ui.widget.AnalysisRequiredDialog
import moe.evil.hwhh.ui.widget.AnalysisResultDialog
import moe.evil.hwhh.ui.widget.ModuleStatusCard
import moe.evil.hwhh.ui.widget.SwitchGroup
import moe.evil.hwhh.ui.widget.SwitchItem
import kotlin.time.Duration.Companion.milliseconds

private val log = HLog.of<MainActivity>()
private const val CONFIRM_WINDOW_MS = 10 * 60 * 1000L

private fun HookFeature.presentation() = when (this) {
    HookFeature.HOME -> Triple(
        Icons.Outlined.Home,
        R.string.hook_home_title,
        R.string.hook_home_subtitle
    )

    HookFeature.MESSAGE_CENTER -> Triple(
        Icons.Outlined.Email,
        R.string.hook_message_center_title,
        R.string.hook_message_center_subtitle
    )

    HookFeature.PERSONAL_CENTER -> Triple(
        Icons.Outlined.Person,
        R.string.hook_personal_center_title,
        R.string.hook_personal_center_subtitle
    )

    HookFeature.SPORT_DATA_EXPORT -> Triple(
        Icons.Outlined.FileDownload,
        R.string.hook_sport_data_export_title,
        R.string.hook_sport_data_export_subtitle
    )

    HookFeature.SPORT_HISTORY_EXPORT -> Triple(
        Icons.Outlined.DownloadForOffline,
        R.string.hook_sport_history_export_title,
        R.string.hook_sport_history_export_subtitle
    )

    HookFeature.HEALTH_EXPORT -> Triple(
        Icons.Outlined.MonitorHeart,
        R.string.hook_health_export_title,
        R.string.hook_health_export_subtitle
    )

    HookFeature.HEALTH_INSIGHTS -> Triple(
        Icons.Outlined.Insights,
        R.string.hook_health_insights_title,
        R.string.hook_health_insights_subtitle
    )

    HookFeature.MODULE_CACHE -> Triple(
        Icons.Outlined.CleaningServices,
        R.string.hook_module_cache_title,
        R.string.hook_module_cache_subtitle
    )
}

private fun DebugToggle.presentation() = when (this) {
    DebugToggle.RED_DOT -> Triple(
        Icons.Outlined.BugReport,
        R.string.debug_red_dot_title,
        R.string.debug_red_dot_subtitle
    )

    DebugToggle.HIDE_LAUNCHER_ICON -> Triple(
        Icons.Outlined.VisibilityOff,
        R.string.debug_hide_icon_title,
        R.string.debug_hide_icon_subtitle
    )

    DebugToggle.VERBOSE_EXPORT -> Triple(
        Icons.Outlined.DataObject,
        R.string.debug_verbose_export_title,
        R.string.debug_verbose_export_subtitle
    )

    DebugToggle.HEALTH_QUERY -> Triple(
        Icons.Outlined.Terminal,
        R.string.debug_health_query_title,
        R.string.debug_health_query_subtitle
    )
}

class MainActivity : ComponentActivity() {
    private val config get() = prefs(PREFS_NAME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HWHealthHookerTheme {
                MainScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val isModuleActive = remember { YukiHookAPI.Status.isXposedModuleActive }
        var showAbout by remember { mutableStateOf(false) }
        // Hoisted so the debug section can gate on it: turning a hook off must grey out
        // the debug toggles that DebugToggle.requiredBy attributes to it.
        val hookEnabled = remember {
            mutableStateMapOf<HookFeature, Boolean>().apply {
                HookFeature.entries.forEach { put(it, config.getBoolean(it.key, true)) }
            }
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = { MainTopAppBar(scrollBehavior, onTitleClick = { showAbout = true }) }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(Dimensions.SpaceL),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceL)
            ) {
                item { ModuleStatusCard(isModuleActive) }
                item { HookSettingsSection(hookEnabled, isModuleActive) }
                item { DebugSettingsSection(hookEnabled, isModuleActive) }
            }
        }

        if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainTopAppBar(
        scrollBehavior: TopAppBarScrollBehavior,
        onTitleClick: () -> Unit
    ) {
        LargeTopAppBar(
            title = {
                val title = stringResource(R.string.app_name)
                val titleModifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onTitleClick)
                if (BuildConfig.DEBUG) {
                    Row(
                        modifier = titleModifier,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(text = title)
                        Badge(
                            modifier = Modifier.padding(start = Dimensions.SpaceXS),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.debug_build_badge),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                } else {
                    Text(text = title, modifier = titleModifier)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface
            ),
            scrollBehavior = scrollBehavior
        )
    }

    @Suppress("RedundantValueArgument")
    @Composable
    private fun HookSettingsSection(
        enabled: MutableMap<HookFeature, Boolean>,
        moduleActive: Boolean
    ) {
        var analysisRevision by remember { mutableIntStateOf(0) }
        var showClearDialog by remember { mutableStateOf(false) }

        val items = HookFeature.entries.map { feature ->
            val (icon, title, subtitle) = feature.presentation()
            SwitchItem(
                icon = icon,
                title = stringResource(title),
                subtitle = stringResource(subtitle),
                isChecked = enabled.getValue(feature),
                enabled = moduleActive,
                onCheckedChange = { checked ->
                    saveHookEnabled(feature.key, checked)
                    enabled[feature] = checked
                },
                onLongClick = { showClearDialog = true }
                    .takeIf { feature == HookFeature.HEALTH_EXPORT }
            )
        }

        SwitchGroup(
            title = stringResource(R.string.hook_settings_title),
            items = items
        )

        if (showClearDialog) {
            AnalysisClearDialog(
                onDismiss = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    HealthIdAnalysis.clearDigests(this)
                    analysisRevision++
                    Toast.makeText(this, R.string.analysis_clear_done, Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (moduleActive) HealthExportAnalysisGate(
            enabled = enabled.getValue(HookFeature.HEALTH_EXPORT),
            revision = analysisRevision,
            onRevert = {
                enabled[HookFeature.HEALTH_EXPORT] = false
                saveHookEnabled(HookFeature.HEALTH_EXPORT.key, false)
            }
        )
    }

    @Composable
    private fun HealthExportAnalysisGate(
        enabled: Boolean,
        revision: Int,
        onRevert: () -> Unit
    ) {
        val context = LocalContext.current
        val state by AnalysisController.state.collectAsState()
        var requiredNeed by remember { mutableStateOf<AnalysisNeed?>(null) }
        var background by remember { mutableStateOf(false) }

        fun startAnalysis() {
            background = false
            context.startForegroundService(Intent(context, AnalysisService::class.java))
        }

        val requestNotifications = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { startAnalysis() }

        LaunchedEffect(enabled, revision) {
            requiredNeed =
                if (enabled && AnalysisController.state.value !is AnalysisState.Running) {
                    HealthIdAnalysis.analysisNeed(context)
                } else {
                    null
                }
        }

        val aborted = state is AnalysisState.Cancelled || state is AnalysisState.Failed
        LaunchedEffect(aborted) {
            if (!aborted) return@LaunchedEffect
            onRevert()
            if (state is AnalysisState.Cancelled) AnalysisController.consume()
        }

        requiredNeed?.let { reason ->
            AnalysisRequiredDialog(
                reason = reason,
                onCancel = {
                    requiredNeed = null
                    onRevert()
                },
                onConfirm = {
                    requiredNeed = null
                    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        startAnalysis()
                    } else {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
        }

        when (val current = state) {
            is AnalysisState.Running -> if (!background) AnalysisProgressDialog(
                state = current,
                onBackground = { background = true },
                onCancel = AnalysisController::cancel
            )

            is AnalysisState.Succeeded -> AnalysisResultDialog(
                success = true,
                title = stringResource(R.string.analysis_result_success_title),
                message = stringResource(
                    R.string.analysis_result_success_message,
                    current.names,
                    current.elapsedMs / 1000
                ),
                onDismiss = AnalysisController::consume
            )

            is AnalysisState.Failed -> AnalysisResultDialog(
                success = false,
                title = stringResource(R.string.analysis_result_failed_title),
                message = current.reason,
                onDismiss = AnalysisController::consume
            )

            else -> Unit
        }
    }

    @Composable
    private fun DebugSettingsSection(
        hookEnabled: Map<HookFeature, Boolean>,
        moduleActive: Boolean
    ) {
        val checked = remember {
            mutableStateMapOf<DebugToggle, Boolean>().apply {
                DebugToggle.entries.forEach { put(it, config.get(it.pref)) }
            }
        }
        var logLevel by remember { mutableStateOf(LogLevel.of(config.get(DebugPrefs.LOG_LEVEL))) }
        var showDialog by remember { mutableStateOf(false) }
        var lastConfirmedAt by remember { mutableLongStateOf(0L) }
        var pendingToggle by remember { mutableStateOf<(() -> Unit)?>(null) }

        fun requireConfirmation(enabled: Boolean, apply: (Boolean) -> Unit) = when {
            !enabled -> apply(false)
            System.currentTimeMillis() - lastConfirmedAt < CONFIRM_WINDOW_MS -> apply(true)
            else -> {
                pendingToggle = { apply(true) }
                showDialog = true
            }
        }

        fun apply(toggle: DebugToggle, value: Boolean) {
            val applied = when (toggle) {
                DebugToggle.HIDE_LAUNCHER_ICON -> runCatching {
                    packageManager.setComponentEnabledSetting(
                        ComponentName(this, "$packageName.Home"),
                        if (value) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }.onFailure {
                    log.error(it) { "Launcher icon toggle failed" }
                    Toast.makeText(this, R.string.setting_apply_failed, Toast.LENGTH_LONG).show()
                }.isSuccess

                else -> true
            }
            if (!applied) return
            config.edit { put(toggle.pref, value) }
            checked[toggle] = value
        }

        val items = DebugToggle.entries.map { toggle ->
            val (icon, title, subtitle) = toggle.presentation()
            // Stored value is kept while locked, matching Preference.setDependency; the
            // hook side re-checks the same gate, so a stale `true` stays inert.
            val lockedBy = toggle.requiredBy.takeUnless {
                toggle.isUnlocked { feature -> hookEnabled[feature] != false }
            }
            val isHostSide = toggle != DebugToggle.HIDE_LAUNCHER_ICON
            SwitchItem(
                icon = icon,
                title = stringResource(title),
                subtitle = lockedBy?.map { stringResource(it.presentation().second) }
                    ?.joinToString()
                    ?.let { stringResource(R.string.debug_requires_hook, it) }
                    ?: stringResource(subtitle),
                isChecked = checked.getValue(toggle),
                enabled = lockedBy == null && (moduleActive || !isHostSide),
                onCheckedChange = { enabled ->
                    requireConfirmation(enabled) { apply(toggle, it) }
                }
            )
        }

        SwitchGroup(
            title = stringResource(R.string.debug_settings_title),
            items = items,
            extraContent = {
                LogLevelRow(
                    current = logLevel,
                    enabled = moduleActive,
                    onSelect = { level ->
                        config.edit { put(DebugPrefs.LOG_LEVEL, level.name) }
                        logLevel = level
                        HLog.globalMinLevel = level
                    }
                )
            }
        )

        if (showDialog) {
            DebugConfirmDialog(
                onDismiss = {
                    showDialog = false
                    pendingToggle = null
                },
                onConfirm = {
                    lastConfirmedAt = System.currentTimeMillis()
                    showDialog = false
                    pendingToggle?.invoke()
                    pendingToggle = null
                }
            )
        }
    }

    @Composable
    private fun LogLevelRow(
        current: LogLevel,
        enabled: Boolean,
        onSelect: (LogLevel) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .heightIn(min = Dimensions.ListItem.M)
                .alpha(if (enabled) 1f else 0.38f)
                .clickable(enabled = enabled) { expanded = true }
                .padding(Dimensions.SpaceXL),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(Dimensions.IconSize.L + Dimensions.SpaceXS)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.small
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.FilterList,
                    contentDescription = null,
                    modifier = Modifier.size(Dimensions.IconSize.S),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(Dimensions.SpaceL))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.debug_log_level_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.debug_log_level_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimensions.SpaceXXS)
                )
            }

            Spacer(modifier = Modifier.width(Dimensions.SpaceM))

            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = current.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    LogLevel.entries.forEach { level ->
                        DropdownMenuItem(
                            text = { Text(level.name) },
                            onClick = {
                                expanded = false
                                onSelect(level)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun DebugConfirmDialog(
        onDismiss: () -> Unit,
        onConfirm: () -> Unit
    ) {
        var countdown by remember { mutableIntStateOf(5) }

        LaunchedEffect(Unit) {
            while (countdown > 0) {
                delay(1000.milliseconds)
                countdown--
            }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.debug_confirm_title)) },
            text = { Text(stringResource(R.string.debug_confirm_message)) },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.debug_confirm_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirm,
                    enabled = countdown == 0
                ) {
                    Text(
                        if (countdown > 0) stringResource(R.string.debug_confirm_ok, countdown)
                        else stringResource(R.string.debug_confirm_ok_ready)
                    )
                }
            }
        )
    }

    private fun saveHookEnabled(key: String, enabled: Boolean) =
        config.edit { putBoolean(key, enabled) }
}
