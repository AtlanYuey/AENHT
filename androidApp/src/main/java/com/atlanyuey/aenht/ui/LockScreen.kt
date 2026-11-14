package com.atlanyuey.aenht.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atlanyuey.aenht.core.tr
import com.atlanyuey.aenht.ui.components.AppIcon
import com.atlanyuey.aenht.ui.components.TextButton
import com.atlanyuey.aenht.ui.components.Txt
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.Type

/** Covers the app while it is locked (App Lock). */
@Composable
fun LockScreen(showsButton: Boolean = true, onUnlock: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            // Touches don't reach the app underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIcon(Icons.EyeOff, null, tint = AppTheme.colors.secondaryLabel, size = 44.dp)
            Txt("AENHT", style = Type.title2)
            if (showsButton) TextButton(tr("Unlock"), onUnlock, bold = true)
        }
    }
}
