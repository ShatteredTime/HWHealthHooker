package moe.evil.hwhh

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
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

        var homeEnabled by remember { mutableStateOf(getHookEnabled(homeKey)) }
        var messageCenterEnabled by remember { mutableStateOf(getHookEnabled(msgKey)) }
        var personalCenterEnabled by remember { mutableStateOf(getHookEnabled(personalKey)) }

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
            )
        )

        SwitchGroup(
            title = stringResource(R.string.debug_settings_title),
            items = items
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
}
