package moe.evil.hwhh.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import moe.evil.hwhh.R
import moe.evil.hwhh.analysis.AnalysisNeed
import moe.evil.hwhh.analysis.AnalysisState
import moe.evil.hwhh.ui.theme.Dimensions

@Composable
fun AnalysisRequiredDialog(reason: AnalysisNeed, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(imageVector = Icons.Outlined.Insights, contentDescription = null) },
        title = {
            Text(
                stringResource(
                    when (reason) {
                        is AnalysisNeed.Missing -> R.string.analysis_required_title
                        is AnalysisNeed.Stale -> R.string.analysis_required_stale_title
                    }
                )
            )
        },
        text = {
            Text(
                when (reason) {
                    is AnalysisNeed.Missing -> stringResource(
                        R.string.analysis_required_message,
                        reason.tag
                    )

                    is AnalysisNeed.Stale -> stringResource(
                        R.string.analysis_required_stale_message,
                        reason.last,
                        reason.tag
                    )
                }
            )
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.analysis_required_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.analysis_required_confirm))
            }
        }
    )
}

@Composable
fun AnalysisClearDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Outlined.DeleteOutline, contentDescription = null) },
        title = { Text(stringResource(R.string.analysis_clear_title)) },
        text = { Text(stringResource(R.string.analysis_clear_message)) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.analysis_clear_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.analysis_clear_confirm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

@Composable
fun AnalysisProgressDialog(
    state: AnalysisState.Running,
    onBackground: () -> Unit,
    onCancel: () -> Unit
) {
    val logState = rememberLazyListState()
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) logState.animateScrollToItem(state.lines.lastIndex)
    }

    AlertDialog(
        onDismissRequest = onBackground,
        icon = { Icon(imageVector = Icons.Outlined.Insights, contentDescription = null) },
        title = { Text(stringResource(R.string.analysis_progress_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceM)) {
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (state.cancelling) {
                        stringResource(R.string.analysis_progress_cancelling)
                    } else {
                        stringResource(R.string.analysis_progress_percent, state.percent)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                LazyColumn(
                    state = logState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimensions.LogPane.M)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(Dimensions.SpaceM),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceXXS)
                ) {
                    items(state.lines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !state.cancelling) {
                Text(stringResource(R.string.analysis_progress_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onBackground) {
                Text(stringResource(R.string.analysis_progress_background))
            }
        }
    )
}

@Composable
fun AnalysisResultDialog(
    success: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (success) Icons.Outlined.CheckCircle
                else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (success) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.analysis_result_ok))
            }
        }
    )
}
