package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentDependency
import dev.molifox.core.ContentHit

@Composable
internal fun ContentDependencyRow(
    dependency: ContentDependency,
    hit: ContentHit?,
    onOpenProject: (ContentHit) -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = hit != null) { hit?.let(onOpenProject) }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DependencyLabels(dependency, hit, Modifier.weight(1f))
        Text(
            strings.t("dialogs.content.dependencies.${dependency.type}"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        if (hit != null) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DependencyLabels(
    dependency: ContentDependency,
    hit: ContentHit?,
    modifier: Modifier = Modifier,
) {
    val title = hit?.title?.takeIf { it.isNotBlank() } ?: dependency.projectId.ifBlank { dependency.fileName }
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = if (hit != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        val subtitle = hit?.description?.takeIf { it.isNotBlank() }
        if (subtitle != null) {
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
