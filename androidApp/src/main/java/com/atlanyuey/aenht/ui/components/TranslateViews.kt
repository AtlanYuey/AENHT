package com.atlanyuey.aenht.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atlanyuey.aenht.core.TranslationService
import com.atlanyuey.aenht.core.tr
import com.atlanyuey.aenht.shared.translate.Translation
import com.atlanyuey.aenht.shared.translate.TranslationKind
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * "Translate" under a title or comment; tapped, the translation shows in
 * its place with who made it. Hidden until a translator is set up.
 */
@Composable
fun TranslateLine(text: String, kind: TranslationKind, modifier: Modifier = Modifier) {
    if (!TranslationService.isAvailable) return
    val scope = rememberCoroutineScope()
    var result by remember(text) { mutableStateOf<Translation?>(null) }
    var error by remember(text) { mutableStateOf<String?>(null) }
    var working by remember(text) { mutableStateOf(false) }
    val colors = AppTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val r = result
        if (r != null) {
            Txt(r.text, style = if (kind == TranslationKind.TITLE) Type.subheadline.copy(fontWeight = FontWeight.Medium) else Type.subheadline)
            Txt(
                if (r.fellBack) tr("Translated by %@ (switched)", r.provider) else tr("Translated by %@", r.provider),
                style = Type.caption2, color = colors.tertiaryLabel,
            )
        } else {
            Row(
                Modifier.clickable(enabled = !working) {
                    working = true
                    error = null
                    scope.launch {
                        try {
                            result = TranslationService.translate(text, kind)
                        } catch (e: Exception) {
                            error = e.localizedMessage
                        }
                        working = false
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (working) ActivityIndicator(size = 12.dp) else AppIcon(Icons.Language, null, tint = AppTheme.tint, size = 13.dp)
                Txt(tr("Translate"), style = Type.caption1.copy(fontWeight = FontWeight.Medium), color = AppTheme.tint)
            }
            error?.let { Txt(it, style = Type.caption2, color = colors.red, maxLines = 2) }
        }
    }
}
