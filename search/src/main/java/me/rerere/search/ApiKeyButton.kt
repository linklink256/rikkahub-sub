package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource

@Composable
fun ApiKeyButton(url: String) {
    val urlHandler = LocalUriHandler.current
    TextButton(
        onClick = {
            urlHandler.openUri(url)
        }
    ) {
        Text(stringResource(R.string.click_to_get_api_key))
    }
}