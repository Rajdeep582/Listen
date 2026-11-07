package com.rajdeep.listen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import com.rajdeep.listen.data.AccountUser
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import com.rajdeep.listen.video.PillButton
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(vm: AppViewModel, onAuthenticated: () -> Unit, onSignOut: () -> Unit) {
    var create by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val accent = LocalAccent.current.primary
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val usernameValid = username.matches(Regex("^[A-Za-z0-9_]{3,24}$"))
    val passwordValid = password.length >= 6

    fun submit() {
        if (busy) return
        if (username.isBlank()) { message = "Enter your username."; return }
        if (create && !usernameValid) { message = "Use 3–24 letters, numbers, or underscores for your username."; return }
        if (password.isBlank()) { message = "Enter your password."; return }
        if (create && !passwordValid) { message = "Your password needs at least 6 characters."; return }
        busy = true
        message = null
        keyboard?.hide()
        focusManager.clearFocus()
        scope.launch {
            vm.authenticate(username.trim(), password, create).fold(
                onSuccess = { message = "Account ready."; onAuthenticated() },
                onFailure = { message = it.message ?: "Couldn't connect. Check your connection and try again." },
            )
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("listen", style = MaterialTheme.typography.displaySmall, color = Palette.Text, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp))
        Spacer(Modifier.height(4.dp))
        Text("Sign in to sync your favourites and account.", style = MaterialTheme.typography.titleMedium, color = Palette.TextDim, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp))
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth().widthIn(max = 480.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Surface.copy(alpha = 0.96f)).padding(20.dp),
        ) {
            Text(if (create) "Create your account" else "Welcome back", style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
            Text(if (create) "Save favourites and keep your music account in sync." else "Enter your Listen username and password.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(top = 5.dp, bottom = 14.dp))
            AccountField(
                "Username", username, { username = it.take(24); message = null }, password = false,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                isError = create && username.isNotEmpty() && !usernameValid, enabled = !busy,
            )
            if (create) Text(
                when { username.isEmpty() -> "3–24 characters: letters, numbers, or underscores."; usernameValid -> "Username is ready."; else -> "Use 3–24 letters, numbers, or underscores." },
                style = MaterialTheme.typography.bodySmall,
                color = if (username.isNotEmpty() && !usernameValid) Palette.Danger else if (usernameValid) accent else Palette.TextDim,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            AccountField(
                "Password", password, { password = it.take(128); message = null }, password = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                isError = create && password.isNotEmpty() && !passwordValid, enabled = !busy,
            )
            if (create) {
                Text(
                    when { passwordValid -> "Password is long enough."; password.isEmpty() -> "Use at least 6 characters."; else -> "Add ${6 - password.length} more characters. (${password.length}/6)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (passwordValid) accent else if (password.isEmpty()) Palette.TextDim else Palette.Danger,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(message!!, style = MaterialTheme.typography.bodySmall, color = if (message!!.startsWith("Account ready")) accent else Palette.Danger)
            }
            Spacer(Modifier.height(16.dp))
            PillButton(if (busy) "Connecting…" else if (create) "Create account" else "Sign in", onClick = { submit() })
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (create) "Already have an account?" else "New to Listen?", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                androidx.compose.material3.TextButton(enabled = !busy, onClick = { create = !create; message = null }) {
                    Text(if (create) "Sign in" else "Create account", color = accent)
                }
            }
            if (busy) Text("Connecting to your account. The first connection may take a little longer.", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        }
    }
}

@Composable
private fun AccountField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    var showPassword by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = if (password && !showPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailingIcon = if (password) ({
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPassword) "Hide password" else "Show password", tint = Palette.TextDim)
            }
        }) else null,
        modifier = Modifier.fillMaxWidth(),
        isError = isError,
        shape = RoundedCornerShape(15.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LocalAccent.current.primary, unfocusedBorderColor = Palette.Line,
            focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text, focusedLabelColor = LocalAccent.current.primary,
        ),
    )
}

@Composable
fun ProfileScreen(user: AccountUser?, onFavorites: () -> Unit, onSettings: () -> Unit, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Your profile", style = MaterialTheme.typography.displaySmall, color = Palette.Text)
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Surface).padding(20.dp)) {
            Text("USERNAME", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
            Text(user?.username ?: "Guest", style = MaterialTheme.typography.headlineSmall, color = Palette.Text, modifier = Modifier.padding(top = 4.dp))
            if (!user?.createdAt.isNullOrBlank()) Text("Member since ${user?.createdAt?.take(10)}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
        ProfileOptionRow(Icons.Rounded.Favorite, "Favourites", "Your saved music", onFavorites)
        Spacer(Modifier.height(10.dp))
        ProfileOptionRow(Icons.Rounded.Settings, "Settings", "Music, playback, appearance, and app options", onSettings)
        Spacer(Modifier.height(18.dp))
        PillButton("Sign out", filled = false, onClick = onSignOut)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ProfileOptionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Surface).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = LocalAccent.current.primary)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open $title", tint = Palette.TextFaint)
    }
}
