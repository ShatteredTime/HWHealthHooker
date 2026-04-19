package moe.evil.hwhh

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.hook.factory.prefs
import kotlinx.coroutines.delay
import moe.evil.hwhh.ui.theme.Dimensions
import moe.evil.hwhh.ui.theme.HWHealthHookerTheme
import moe.evil.hwhh.ui.widget.ModuleStatusCard
import moe.evil.hwhh.ui.widget.SwitchGroup
import moe.evil.hwhh.ui.widget.SwitchItem
import moe.evil.hwhh.xposed.DebugPrefs
import moe.evil.hwhh.xposed.PREFS_NAME
import moe.evil.hwhh.xposed.hooks.HomeHooker
import moe.evil.hwhh.xposed.hooks.MessageCenterHooker
import moe.evil.hwhh.xposed.hooks.PersonalCenterHooker
import moe.evil.hwhh.xposed.hooks.SportDataExportHooker
import moe.evil.hwhh.xposed.hooks.SportHistoryExportHooker
import moe.evil.hwhh.xposed.utils.HLog

class MainActivity : ComponentActivity() {
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
        val isModuleActive = runCatching {
            YukiHookAPI.Status.isXposedModuleActive
        }.getOrDefault(false)

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = { MainTopAppBar(scrollBehavior) }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(Dimensions.SpaceL),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceL)
            ) {
                item { ModuleStatusCard(isActive = isModuleActive) }
                item { HookSettingsSection() }
                item { DebugSettingsSection() }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainTopAppBar(scrollBehavior: TopAppBarScrollBehavior) {
        LargeTopAppBar(
            title = {
                Text(text = stringResource(R.string.app_name))
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
    private fun HookSettingsSection() {
        val homeKey = HomeHooker.javaClass.simpleName
        val msgKey = MessageCenterHooker.javaClass.simpleName
        val personalKey = PersonalCenterHooker.javaClass.simpleName
        val sportDataKey = SportDataExportHooker.javaClass.simpleName
        val sportHistoryKey = SportHistoryExportHooker.javaClass.simpleName

        var homeEnabled by remember { mutableStateOf(getHookEnabled(homeKey)) }
        var messageCenterEnabled by remember { mutableStateOf(getHookEnabled(msgKey)) }
        var personalCenterEnabled by remember { mutableStateOf(getHookEnabled(personalKey)) }
        var sportDataExportEnabled by remember { mutableStateOf(getHookEnabled(sportDataKey)) }
        var sportHistoryExportEnabled by remember { mutableStateOf(getHookEnabled(sportHistoryKey)) }

        val items = listOf(
            SwitchItem(
                icon = Icons.Outlined.Home,
                title = stringResource(R.string.hook_home_title),
                subtitle = stringResource(R.string.hook_home_subtitle),
                isChecked = homeEnabled,
                onCheckedChange = { enabled ->
                    homeEnabled = enabled
                    saveHookEnabled(homeKey, enabled)
                }
            ),
            SwitchItem(
                icon = Icons.Outlined.Email,
                title = stringResource(R.string.hook_message_center_title),
                subtitle = stringResource(R.string.hook_message_center_subtitle),
                isChecked = messageCenterEnabled,
                onCheckedChange = { enabled ->
                    messageCenterEnabled = enabled
                    saveHookEnabled(msgKey, enabled)
                }
            ),
            SwitchItem(
                icon = Icons.Outlined.Person,
                title = stringResource(R.string.hook_personal_center_title),
                subtitle = stringResource(R.string.hook_personal_center_subtitle),
                isChecked = personalCenterEnabled,
                onCheckedChange = { enabled ->
                    personalCenterEnabled = enabled
                    saveHookEnabled(personalKey, enabled)
                }
            ),
            SwitchItem(
                icon = Icons.Outlined.FileDownload,
                title = stringResource(R.string.hook_sport_data_export_title),
                subtitle = stringResource(R.string.hook_sport_data_export_subtitle),
                isChecked = sportDataExportEnabled,
                onCheckedChange = { enabled ->
                    sportDataExportEnabled = enabled
                    saveHookEnabled(sportDataKey, enabled)
                }
            ),
            SwitchItem(
                icon = Icons.Outlined.DownloadForOffline,
                title = stringResource(R.string.hook_sport_history_export_title),
                subtitle = stringResource(R.string.hook_sport_history_export_subtitle),
                isChecked = sportHistoryExportEnabled,
                onCheckedChange = { enabled ->
                    sportHistoryExportEnabled = enabled
                    saveHookEnabled(sportHistoryKey, enabled)
                }
            )
        )

        SwitchGroup(
            title = stringResource(R.string.hook_settings_title),
            items = items
        )
    }

    @Suppress("RedundantValueArgument")
    @Composable
    private fun DebugSettingsSection() {
        var redDotEnabled by remember {
            mutableStateOf(
                runCatching { prefs(PREFS_NAME).get(DebugPrefs.RED_DOT) }.getOrDefault(false)
            )
        }
        var hideIconEnabled by remember { mutableStateOf(getHideIconState()) }
        var verboseExportEnabled by remember {
            mutableStateOf(
                runCatching { prefs(PREFS_NAME).get(DebugPrefs.VERBOSE_EXPORT) }.getOrDefault(false)
            )
        }
        var logLevel by remember { mutableStateOf(getLogLevel()) }
        var showDialog by remember { mutableStateOf(false) }
        var lastConfirmedAt by remember { mutableLongStateOf(0L) }
        var pendingToggle by remember { mutableStateOf<(() -> Unit)?>(null) }

        fun requireConfirmation(
            onConfirmed: () -> Unit,
            onToggleOff: () -> Unit,
            enabled: Boolean
        ) {
            if (!enabled) {
                onToggleOff()
            } else if (System.currentTimeMillis() - lastConfirmedAt < 10 * 60 * 1000) {
                onConfirmed()
            } else {
                pendingToggle = onConfirmed
                showDialog = true
            }
        }

        val items = listOf(
            SwitchItem(
                icon = Icons.Outlined.BugReport,
                title = stringResource(R.string.debug_red_dot_title),
                subtitle = stringResource(R.string.debug_red_dot_subtitle),
                isChecked = redDotEnabled,
                onCheckedChange = { enabled ->
                    requireConfirmation(
                        onConfirmed = {
                            redDotEnabled = true
                            runCatching { prefs(PREFS_NAME).edit { put(DebugPrefs.RED_DOT, true) } }
                        },
                        onToggleOff = {
                            redDotEnabled = false
                            runCatching {
                                prefs(PREFS_NAME).edit {
                                    put(
                                        DebugPrefs.RED_DOT,
                                        false
                                    )
                                }
                            }
                        },
                        enabled = enabled
                    )
                }
            ),
            SwitchItem(
                icon = Icons.Outlined.VisibilityOff,
                title = stringResource(R.string.debug_hide_icon_title),
                subtitle = stringResource(R.string.debug_hide_icon_subtitle),
                isChecked = hideIconEnabled,
                onCheckedChange = { enabled ->
                    requireConfirmation(
                        onConfirmed = {
                            hideIconEnabled = true
                            saveHideIconState(true)
                        },
                        onToggleOff = {
                            hideIconEnabled = false
                            saveHideIconState(false)
                        },
                        enabled = enabled
                    )
                }
            ),
            SwitchItem(
                icon = Icons.Outlined.DataObject,
                title = stringResource(R.string.debug_verbose_export_title),
                subtitle = stringResource(R.string.debug_verbose_export_subtitle),
                isChecked = verboseExportEnabled,
                onCheckedChange = { enabled ->
                    requireConfirmation(
                        onConfirmed = {
                            verboseExportEnabled = true
                            saveVerboseExport(true)
                        },
                        onToggleOff = {
                            verboseExportEnabled = false
                            saveVerboseExport(false)
                        },
                        enabled = enabled
                    )
                }
            )
        )

        SwitchGroup(
            title = stringResource(R.string.debug_settings_title),
            items = items,
            extraContent = {
                LogLevelRow(
                    current = logLevel,
                    onSelect = { level ->
                        logLevel = level
                        saveLogLevel(level)
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
        current: HLog.Level,
        onSelect: (HLog.Level) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .heightIn(min = Dimensions.ListItem.M)
                .clickable { expanded = true }
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
                    HLog.Level.entries.forEach { level ->
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
                delay(1000)
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

    private fun getHookEnabled(key: String): Boolean =
        runCatching { prefs(PREFS_NAME).getBoolean(key, true) }.getOrDefault(true)

    private fun saveHookEnabled(key: String, enabled: Boolean) {
        runCatching { prefs(PREFS_NAME).edit { putBoolean(key, enabled) } }
    }

    private fun getHideIconState(): Boolean =
        runCatching { prefs(PREFS_NAME).get(DebugPrefs.HIDE_LAUNCHER_ICON, false) }.getOrDefault(
            false
        )

    private fun saveHideIconState(hide: Boolean) {
        runCatching { prefs(PREFS_NAME).edit { put(DebugPrefs.HIDE_LAUNCHER_ICON, hide) } }
        packageManager.setComponentEnabledSetting(
            ComponentName(this, "$packageName.Home"),
            if (!hide) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun getLogLevel(): HLog.Level = runCatching {
        HLog.Level.valueOf(prefs(PREFS_NAME).get(DebugPrefs.LOG_LEVEL))
    }.getOrDefault(HLog.Level.DEBUG)

    private fun saveLogLevel(level: HLog.Level) {
        runCatching { prefs(PREFS_NAME).edit { put(DebugPrefs.LOG_LEVEL, level.name) } }
    }

    private fun saveVerboseExport(enabled: Boolean) {
        runCatching { prefs(PREFS_NAME).edit { put(DebugPrefs.VERBOSE_EXPORT, enabled) } }
    }
}
