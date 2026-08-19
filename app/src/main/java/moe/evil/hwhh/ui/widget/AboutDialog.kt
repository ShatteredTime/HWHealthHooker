package moe.evil.hwhh.ui.widget

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import moe.evil.hwhh.BuildConfig
import moe.evil.hwhh.R
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.ui.theme.Dimensions

private val log = HLog("AboutDialog")
private const val SILENT_TAPS = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lyrics = stringArrayResource(R.array.easter_lyrics)
    var taps by remember { mutableIntStateOf(0) }
    var puzzle by remember { mutableStateOf(false) }
    var lyricToast by remember { mutableStateOf<Toast?>(null) }

    if (puzzle) {
        LogoPuzzleDialog(onDismiss = { puzzle = false })
        return
    }

    val uriHandler = LocalUriHandler.current
    val url = stringResource(R.string.about_source_url)
    val label = stringResource(R.string.about_source_label)
    val template = stringResource(R.string.about_view_source, label)
    val linkColor = MaterialTheme.colorScheme.primary

    val source = remember(template, label, url, linkColor, uriHandler) {
        val link = LinkAnnotation.Url(
            url = url,
            styles = TextLinkStyles(
                SpanStyle(
                    color = linkColor,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline
                )
            )
        ) {
            runCatching { uriHandler.openUri(url) }
                .onFailure { log.warn(it) { "Could not open source url" } }
        }
        buildAnnotatedString {
            when (val start = template.indexOf(label)) {
                -1 -> withLink(link) { append(template) }
                else -> {
                    append(template, 0, start)
                    withLink(link) { append(label) }
                    append(template, start + label.length, template.length)
                }
            }
        }
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Row(modifier = Modifier.padding(Dimensions.SpaceXXL)) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier
                        .size(Dimensions.IconSize.XL)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            taps++
                            val line = taps - SILENT_TAPS - 1
                            if (line in lyrics.indices) {
                                lyricToast?.cancel()
                                lyricToast = Toast
                                    .makeText(context, lyrics[line], Toast.LENGTH_SHORT)
                                    .also(Toast::show)
                            }
                            if (line >= lyrics.lastIndex) puzzle = true
                        }
                )

                Spacer(modifier = Modifier.width(Dimensions.SpaceL))

                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.about_version,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.GIT_COMMIT
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = source,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Dimensions.SpaceXXL)
                    )
                }
            }
        }
    }
}
