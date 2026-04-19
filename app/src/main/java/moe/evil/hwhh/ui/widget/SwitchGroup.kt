package moe.evil.hwhh.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.evil.hwhh.ui.theme.Dimensions

data class SwitchItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String = "",
    val isChecked: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)

@Composable
fun SwitchGroup(
    title: String,
    items: List<SwitchItem>,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Dimensions.SpaceXS, bottom = Dimensions.SpaceM)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            items.forEachIndexed { index, item ->
                SettingsSwitchRow(item = item)
                if (index < items.size - 1) {
                    SettingsDivider()
                }
            }
            if (extraContent != null) {
                if (items.isNotEmpty()) SettingsDivider()
                extraContent()
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Dimensions.SpaceXL),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
        thickness = Dimensions.Divider.Thin
    )
}

@Composable
private fun SettingsSwitchRow(item: SwitchItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimensions.ListItem.M)
            .toggleable(
                value = item.isChecked,
                onValueChange = { item.onCheckedChange(it) },
                role = Role.Switch
            )
            .padding(Dimensions.SpaceXL),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left icon in rounded box
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
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.IconSize.S),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.width(Dimensions.SpaceL))

        // Center title + subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (item.subtitle.isNotEmpty()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimensions.SpaceXXS)
                )
            }
        }

        Spacer(modifier = Modifier.width(Dimensions.SpaceM))

        // Right switch
        Switch(
            checked = item.isChecked,
            onCheckedChange = { item.onCheckedChange(it) }
        )
    }
}
