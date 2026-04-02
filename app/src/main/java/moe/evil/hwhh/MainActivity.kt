package moe.evil.hwhh

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
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.hook.factory.prefs
import moe.evil.hwhh.ui.theme.Dimensions
import moe.evil.hwhh.ui.theme.HWHealthHookerTheme
import moe.evil.hwhh.ui.widget.ModuleStatusCard
import moe.evil.hwhh.ui.widget.SwitchGroup
import moe.evil.hwhh.ui.widget.SwitchItem
import moe.evil.hwhh.xposed.hooks.HomeHooker
import moe.evil.hwhh.xposed.hooks.MessageCenterHooker
import moe.evil.hwhh.xposed.hooks.PersonalCenterHooker
import moe.evil.hwhh.xposed.utils.TmbDexKitScope

class MainActivity : ComponentActivity() {
    companion object {
        private const val PREFS_NAME = TmbDexKitScope.PREFS_NAME
    }

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
                onCheckedChange = { enabled -> saveHookEnabled(homeKey, enabled) }
            ),
            SwitchItem(
                icon = Icons.Outlined.Email,
                title = stringResource(R.string.hook_message_center_title),
                subtitle = stringResource(R.string.hook_message_center_subtitle),
                isChecked = messageCenterEnabled,
                onCheckedChange = { enabled -> saveHookEnabled(msgKey, enabled) }
            ),
            SwitchItem(
                icon = Icons.Outlined.Person,
                title = stringResource(R.string.hook_personal_center_title),
                subtitle = stringResource(R.string.hook_personal_center_subtitle),
                isChecked = personalCenterEnabled,
                onCheckedChange = { enabled -> saveHookEnabled(personalKey, enabled) }
            )
        )

        SwitchGroup(
            title = stringResource(R.string.hook_settings_title),
            items = items
        )
    }

    private fun getHookEnabled(key: String): Boolean {
        return try {
            prefs(PREFS_NAME).getBoolean(key, true)
        } catch (_: Exception) {
            true
        }
    }

    private fun saveHookEnabled(key: String, enabled: Boolean) {
        try {
            prefs(PREFS_NAME).edit {
                putBoolean(key, enabled)
            }
        } catch (_: Exception) {
        }
    }
}
