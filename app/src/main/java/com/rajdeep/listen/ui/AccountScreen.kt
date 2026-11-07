package com.rajdeep.listen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.rajdeep.listen.data.AccountUser
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import com.rajdeep.listen.video.PillButton
import kotlinx.coroutines.launch
import org.json.JSONArray

@Composable
fun AccountScreen(vm: AppViewModel, onAuthenticated: () -> Unit, onSignOut: () -> Unit) {
    var create by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val accent = LocalAccent.current.primary
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("listen", style = MaterialTheme.typography.displaySmall, color = Palette.Text)
        Spacer(Modifier.height(6.dp))
        Text("Your music, saved to your account.", style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Palette.Surface.copy(alpha = 0.94f)).padding(22.dp),
        ) {
            Text(if (create) "Create your account" else "Welcome back", style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
            Text(if (create) "Choose a username and password to keep your library with you." else "Sign in to continue to your music.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
            AccountField("Username", username, { username = it.take(24) }, false)
            Spacer(Modifier.height(10.dp))
            AccountField("Password", password, { password = it.take(128) }, true)
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(message!!, style = MaterialTheme.typography.bodySmall, color = if (message!!.startsWith("Account ready")) accent else Palette.Danger)
            }
            Spacer(Modifier.height(20.dp))
            PillButton(if (busy) "Please wait…" else if (create) "Create account" else "Sign in") {
                if (busy) return@PillButton
                if (username.isBlank() || password.isBlank()) { message = "Enter your username and password."; return@PillButton }
                busy = true; message = null
                scope.launch {
                    vm.authenticate(username.trim(), password, create).fold(
                        onSuccess = { message = "Account ready."; onAuthenticated() },
                        onFailure = { message = it.message ?: "Couldn't connect. Check your connection and try again." },
                    )
                    busy = false
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (create) "Already have an account?" else "New to Listen?", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                androidx.compose.material3.TextButton(onClick = { create = !create; message = null }) {
                    Text(if (create) "Sign in" else "Create account", color = accent)
                }
            }
            if (create) Text("Usernames: 3–24 letters, numbers, or underscores. Passwords: at least 10 characters.", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        }
    }
}

@Composable
private fun AccountField(label: String, value: String, onChange: (String) -> Unit, password: Boolean) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LocalAccent.current.primary, unfocusedBorderColor = Palette.Line,
            focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text, focusedLabelColor = LocalAccent.current.primary,
        ),
    )
}

@Composable
fun ProfileScreen(vm: AppViewModel, user: AccountUser?, onSignOut: () -> Unit) {
    var favorites by remember { mutableStateOf<JSONArray?>(null) }
    var history by remember { mutableStateOf<JSONArray?>(null) }
    LaunchedEffect(user?.id) {
        favorites = vm.cloudFavorites(); history = vm.cloudHistory()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Your profile", style = MaterialTheme.typography.displaySmall, color = Palette.Text)
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Surface).padding(20.dp)) {
            Text("USERNAME", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
            Text(user?.username ?: "Guest", style = MaterialTheme.typography.headlineSmall, color = Palette.Text, modifier = Modifier.padding(top = 4.dp))
            if (!user?.createdAt.isNullOrBlank()) Text("Member since ${user?.createdAt?.take(10)}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(14.dp))
        ProfileList("Cloud favourites", favorites, "No saved favourites yet.")
        Spacer(Modifier.height(14.dp))
        ProfileList("Recently played", history, "Your listening history will appear here.")
        Spacer(Modifier.height(22.dp))
        PillButton("Sign out", filled = false, onClick = onSignOut)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ProfileList(title: String, items: JSONArray?, empty: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Surface).padding(18.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
        Spacer(Modifier.height(8.dp))
        if (items == null) Text("Connect to load account data.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        else if (items.length() == 0) Text(empty, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        else repeat(items.length()) { i ->
            val item = items.optJSONObject(i) ?: return@repeat
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(item.optString("title"), style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                Text(item.optString("artist"), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
    }
}
