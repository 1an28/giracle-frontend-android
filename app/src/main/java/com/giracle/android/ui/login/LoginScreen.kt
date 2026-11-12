package com.giracle.android.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.giracle.android.ui.common.TestTags
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.giracle.android.data.model.ServerConfig
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.userMessage
import kotlinx.coroutines.launch

/** サーバー指定 → ログイン / 新規登録 (Web 版 routes/auth.tsx 相当) */
@Composable
fun LoginScreen() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(graph.prefs.serverUrl ?: "") }
    var direct by rememberSaveable { mutableStateOf(graph.prefs.directBackend) }
    var server by androidx.compose.runtime.remember { mutableStateOf<ServerConfig?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }

    fun probe() {
        error = null
        if (!graph.setServer(url, direct)) { error = "URL の形式が正しくありません"; return }
        busy = true
        scope.launch {
            try {
                server = graph.probeServer()
            } catch (e: Exception) {
                server = null
                error = "サーバーに接続できません: ${e.userMessage()}"
            } finally { busy = false }
        }
    }

    // 保存済みの URL があれば自動で確認
    LaunchedEffect(Unit) { if (url.isNotBlank()) probe() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Giracle", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = url, onValueChange = { url = it; server = null },
                label = { Text("サーバーの URL") }, placeholder = { Text("https://chat.example.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_SERVER_URL),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { probe() }),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = direct, onCheckedChange = { direct = it; server = null })
                Text("バックエンドに直接接続する (/api を付けない・開発用)", style = MaterialTheme.typography.bodySmall)
            }
            if (server == null) {
                Button(onClick = ::probe, enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_CONNECT)) { Text("接続") }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val s = server
            if (s != null) {
                Text(s.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (s.introduction.isNotBlank()) Text(s.introduction, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("ログイン") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("新規登録") })
                }
                val canRegister = s.registerAvailable || s.isFirstUser
                if (tab == 1 && !canRegister) {
                    Text("このサーバーは新規登録を受け付けていません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedTextField(username, { username = it }, label = { Text("ユーザー名") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_USERNAME))
                    OutlinedTextField(
                        password, { password = it }, label = { Text("パスワード (4 文字以上)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_PASSWORD),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    val needInvite = tab == 1 && s.registerInviteOnly && !s.isFirstUser
                    if (needInvite) OutlinedTextField(invite, { invite = it }, label = { Text("招待コード") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (tab == 1 && s.isFirstUser) Text("最初のユーザーはサーバー管理者になります", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            error = null; busy = true
                            scope.launch {
                                try {
                                    if (tab == 0) graph.signIn(username.trim(), password)
                                    else graph.signUp(username.trim(), password, invite.trim().takeIf { needInvite })
                                } catch (e: Exception) {
                                    error = e.userMessage()
                                } finally { busy = false }
                            }
                        },
                        enabled = !busy && username.isNotBlank() && password.length >= 4,
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_SUBMIT),
                    ) { Text(if (tab == 0) "ログイン" else "登録してログイン") }
                }
                TextButton(onClick = { server = null }) { Text("別のサーバーに接続") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
