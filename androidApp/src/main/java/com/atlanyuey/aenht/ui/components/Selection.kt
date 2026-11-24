package com.atlanyuey.aenht.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atlanyuey.aenht.core.tr
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Type

/**
 * The bar at the bottom while choosing galleries (Favorites, History): all
 * of them (or none), how many are chosen, and what's done to them.
 */
@Composable
fun GallerySelectionBar(
    selection: GallerySelection,
    items: List<GallerySummary>,
    action: String,
    onAction: () -> Unit,
) {
    val count = selection.keys.size
    val all = items.isNotEmpty() && items.all { it in selection }
    // It rises into place when choosing starts.
    val shown = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
      androidx.compose.animation.AnimatedVisibility(
          shown,
          enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it },
      ) {
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(26.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(if (all) tr("Deselect All") else tr("Select All"), { selection.toggleAll(items) }, enabled = items.isNotEmpty())
            Spacer(Modifier.weight(1f))
            Txt(tr("%lld Selected", count), style = Type.footnote, color = AppTheme.colors.secondaryLabel, maxLines = 1)
            Spacer(Modifier.weight(1f))
            TextButton(action, onAction, enabled = count > 0, bold = true)
        }
      }
    }
}
