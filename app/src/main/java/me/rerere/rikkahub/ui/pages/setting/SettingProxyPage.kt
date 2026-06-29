package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.applyGlobalProxy
import me.rerere.rikkahub.data.datastore.ProxyConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingProxyPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current

    var enabled by remember(settings) { mutableStateOf(settings.proxyConfig.enabled) }
    var host by remember(settings) { mutableStateOf(settings.proxyConfig.host) }
    var port by remember(settings) { mutableStateOf(if (settings.proxyConfig.port > 0) settings.proxyConfig.port.toString() else "") }
    var username by remember(settings) { mutableStateOf(settings.proxyConfig.username) }
    var password by remember(settings) { mutableStateOf(settings.proxyConfig.password) }

    val savedMessage = stringResource(R.string.setting_proxy_saved)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.extensions_page_proxy)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_proxy_enabled)) },
                    trailingContent = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it }
                        )
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_proxy_host)) },
                    supportingContent = {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_proxy_port)) },
                    supportingContent = {
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_proxy_username)) },
                    supportingContent = {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_proxy_password)) },
                    supportingContent = {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                )
            }

            Button(
                onClick = {
                    val proxyConfig = ProxyConfig(
                        enabled = enabled,
                        host = host,
                        port = port.toIntOrNull() ?: 0,
                        username = username,
                        password = password
                    )
                    vm.updateSettings(settings.copy(proxyConfig = proxyConfig))
                    applyGlobalProxy(proxyConfig)
                    toaster.show(
                        message = savedMessage,
                        type = ToastType.Success
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.setting_proxy_save))
            }
        }
    }
}
