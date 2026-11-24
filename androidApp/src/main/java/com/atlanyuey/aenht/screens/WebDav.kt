package com.atlanyuey.aenht.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.atlanyuey.aenht.core.*
import com.atlanyuey.aenht.ui.components.*
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Type
import kotlinx.coroutines.launch

/** A labeled text field row for grouped lists. */
@Composable
fun FieldRow(label: String, value: String, last: Boolean = false, secret: Boolean = false, url: Boolean = false, onChange: (String) -> Unit) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, style = Type.footnote, color = colors.secondaryLabel)
        BasicTextField(
            value, onChange,
            singleLine = true,
            textStyle = Type.body.copy(color = colors.label),
            cursorBrush = SolidColor(AppTheme.tint),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = if (secret) KeyboardType.Password else if (url) KeyboardType.Uri else KeyboardType.Text,
            ),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.tertiaryFill).padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    if (!last) RowSeparator()
}

/** Settings › WebDAV Sync. */
class WebDavScreen : Screen() {
    private var url by mutableStateOf(WebDavService.url)
    private var user by mutableStateOf(WebDavService.user)
    private var password by mutableStateOf("")
    private var checking by mutableStateOf(false)

    @Composable
    override fun Content() {
        val overlays = LocalOverlays.current
        val scope = com.atlanyuey.aenht.core.rememberSafeScope()
        val service = WebDavService
        val title = tr("WebDAV Sync")
        val connected = tr("Connected. The app keeps its file in the AENHT folder.")
        GroupedListScreen(title) {
            section("Server", footer = {
                FooterText(tr("For example Nextcloud, a NAS, or Jianguoyun (use an app password). The password stays on this device and isn't exported with the settings."))
            }) {
                FieldRow(tr("Address"), url, url = true) { url = it }
                FieldRow(tr("User Name"), user) { user = it }
                FieldRow(tr("Password"), password, secret = true) { password = it }
                ButtonRow(if (checking) tr("Connecting…") else tr("Connect"), {
                    checking = true
                    scope.launch {
                        val error = service.test(url, user, password)
                        checking = false
                        if (error == null) {
                            service.save(url, user, password)
                            password = ""
                            overlays.alert(title, connected)
                            service.syncNow()
                        } else {
                            overlays.alert(title, error)
                        }
                    }
                }, enabled = !checking && url.isNotBlank() && user.isNotBlank() && (password.isNotEmpty() || service.isConfigured), last = true)
            }
            if (service.isConfigured) {
                section(footer = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FooterText(tr("Syncs the settings, the reading history and the favorites copy with your other devices, Android or iPhone. Automatically: when the app opens and when it goes to the background."))
                        service.error?.let { FooterText(it, color = AppTheme.colors.red) }
                        service.lastSync?.let {
                            FooterText(tr("Last synced: %@", DateUtils.getRelativeTimeSpanString(it).toString()))
                        }
                    }
                }) {
                    ToggleRow(tr("Sync Automatically"), service.auto, { service.changeAuto(it) })
                    ButtonRow(if (service.syncing) tr("Syncing…") else tr("Sync Now"), { service.syncNow() }, enabled = !service.syncing)
                    ButtonRow(tr("Disconnect"), { service.disconnect(); url = ""; user = "" }, destructive = true, last = true)
                }
            }
        }
    }
}
