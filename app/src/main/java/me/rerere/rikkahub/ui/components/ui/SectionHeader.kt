package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.ui.theme.Spacing

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
        ProvideTextStyle(MaterialTheme.typography.titleSmallEmphasized) {
            Box(modifier.padding(start = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm)) {
                Text(text)
            }
        }
    }
}
