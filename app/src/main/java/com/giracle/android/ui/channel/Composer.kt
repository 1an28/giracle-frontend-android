package com.giracle.android.ui.channel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.data.model.Message
import com.giracle.android.data.model.User
import com.giracle.android.data.net.UploadFile
import com.giracle.android.ui.common.AppIcons
import com.giracle.android.ui.common.Avatar
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.fileSizeOf
import com.giracle.android.ui.common.humanSize
import com.giracle.android.ui.common.rememberUser
import com.giracle.android.ui.common.toast
import com.giracle.android.ui.common.uploadFileFrom
import com.giracle.android.ui.common.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/** 添付ファイル 1 つのアップロード状況 */
@Stable
class Attachment(val uri: Uri, val name: String, val size: Long) {
    var progress by mutableStateOf(0f)
    var fileId by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var job: Job? = null
}

/** チャンネルごとの下書き (アプリ起動中のみ保持) */
object Drafts {
    val text = HashMap<String, String>()
}

@Composable
fun Composer(
    channelId: String,
    replyTo: Message?,
    onCancelReply: () -> Unit,
    editing: Message?,
    onCancelEdit: () -> Unit,
) {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val server by store.serverConfig.collectAsStateWithLifecycle()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    var value by remember(channelId) { mutableStateOf(TextFieldValue(Drafts.text[channelId] ?: "")) }
    val attachments = remember(channelId) { mutableStateListOf<Attachment>() }
    var sending by remember { mutableStateOf(false) }

    // 編集モードに入ったら本文を差し替える
    LaunchedEffect(editing?.id) {
        if (editing != null) value = TextFieldValue(editing.content, TextRange(editing.content.length))
    }
    LaunchedEffect(value.text, editing) { if (editing == null) Drafts.text[channelId] = value.text }

    // ---- メンション候補 ----
    val mentionQuery = remember(value) { mentionQueryAt(value) }
    var candidates by remember { mutableStateOf<List<User>>(emptyList()) }
    LaunchedEffect(mentionQuery) {
        val q = mentionQuery?.second
        if (q == null) { candidates = emptyList(); return@LaunchedEffect }
        delay(200) // 入力ごとの API 呼び出しを避ける (Web 版と同じ 200ms)
        candidates = runCatching { graph.api.userList(length = 20, username = q, joinedChannel = channelId) }.getOrDefault(emptyList())
        candidates.forEach(store::putUser)
    }

    fun insertMention(u: User) {
        val (start, _) = mentionQuery ?: return
        val end = value.selection.start
        val token = "@<${u.id}> "
        val t = value.text.replaceRange(start, end, token)
        value = TextFieldValue(t, TextRange(start + token.length))
        candidates = emptyList()
    }

    // ---- 添付 ----
    fun upload(a: Attachment) {
        a.job = scope.launch {
            try {
                val base = ctx.uploadFileFrom(a.uri)
                val counting = UploadFile(base.name, base.mime, ProgressBody(base.body) { a.progress = it })
                a.fileId = graph.api.uploadFile(channelId, counting)
                a.progress = 1f
            } catch (e: Exception) {
                a.error = e.userMessage()
            }
        }
    }

    fun addUris(uris: List<Uri>) {
        for (u in uris) {
            val size = ctx.fileSizeOf(u)
            val f = ctx.uploadFileFrom(u)
            val a = Attachment(u, f.name, size)
            if (size >= 0 && size >= server.messageMaxFileSize) a.error = "ファイルが大きすぎます (上限 ${humanSize(server.messageMaxFileSize)})"
            else if (!com.giracle.android.ui.common.isUploadable(f.mime)) a.error = "この形式 (${f.mime}) はサーバーが受け付けません"
            attachments += a
            if (a.error == null) upload(a)
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { addUris(it) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addUris(it) }
    var attachMenu by remember { mutableStateOf(false) }

    val uploading = attachments.any { it.fileId == null && it.error == null }
    val text = value.text
    val tooLong = text.length > server.messageMaxLength
    val canSend = !sending && !uploading && !tooLong && (text.isNotBlank() || attachments.any { it.fileId != null })

    fun submit() {
        if (!canSend) return
        val body = text
        if (editing != null) {
            sending = true
            scope.launch {
                try {
                    graph.api.edit(editing.id, body)
                    value = TextFieldValue(Drafts.text[channelId] ?: "")
                    onCancelEdit()
                } catch (e: Exception) { ctx.toast(e.userMessage()) } finally { sending = false }
            }
            return
        }
        val ids = attachments.mapNotNull { it.fileId }
        val reply = replyTo?.id
        // 送信前に入力欄を空にして、すぐ次を打てるようにする (失敗したら戻す)
        val keep = value
        val keepAttach = attachments.toList()
        value = TextFieldValue("")
        attachments.clear()
        onCancelReply()
        sending = true
        scope.launch {
            try {
                store.send(channelId, body, ids, reply)
                Drafts.text.remove(channelId)
            } catch (e: Exception) {
                value = keep
                attachments.addAll(keepAttach)
                ctx.toast("送信できませんでした: ${e.userMessage()}")
            } finally { sending = false }
        }
    }

    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()) {
            if (candidates.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(candidates, key = { it.id }) { u ->
                        Row(Modifier.fillMaxWidth().clickable { insertMention(u) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Avatar(u.id, 28.dp)
                            Text(u.displayName)
                        }
                    }
                }
                HorizontalDivider()
            }
            if (editing != null) Banner(Icons.Default.Edit, "メッセージを編集中", onCancelEdit)
            replyTo?.let { r ->
                val u = rememberUser(r.userId)
                Banner(AppIcons.Reply, "${u?.name ?: ""} へ返信: ${store.plainText(r.content).replace('\n', ' ')}", onCancelReply)
            }
            if (attachments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in attachments) AttachmentChip(a) {
                        a.job?.cancel()
                        attachments.remove(a)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.Bottom) {
                if (editing == null) {
                    Column {
                        IconButton(onClick = { attachMenu = !attachMenu }) { Icon(AppIcons.AttachFile, "ファイルを添付") }
                        androidx.compose.material3.DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("写真・動画") }, leadingIcon = { Icon(AppIcons.Image, null) }, onClick = {
                                attachMenu = false
                                pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("ファイル") }, leadingIcon = { Icon(AppIcons.File, null) }, onClick = {
                                attachMenu = false
                                pickFile.launch(arrayOf("*/*"))
                            })
                        }
                    }
                }
                TextField(
                    value = value, onValueChange = { value = it },
                    modifier = Modifier.weight(1f).heightIn(max = 180.dp).onPreviewKeyEvent { e ->
                        // ハードウェアキーボード: Enter で送信 (設定 ON 時)、Shift+Enter で改行
                        if (settings.sendWithEnter && e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) { submit(); true } else false
                    },
                    placeholder = { Text("メッセージを入力") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    supportingText = if (tooLong) ({ Text("${text.length} / ${server.messageMaxLength} 文字", color = MaterialTheme.colorScheme.error) }) else null,
                )
                IconButton(onClick = ::submit, enabled = canSend) {
                    if (sending || uploading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(if (editing != null) Icons.Default.Edit else Icons.Default.Send, if (editing != null) "保存" else "送信")
                }
            }
        }
    }
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(16.dp))
        Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "取り消す") }
    }
}

@Composable
private fun AttachmentChip(a: Attachment, onRemove: () -> Unit) {
    Row(
        Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            a.error != null -> Icon(Icons.Default.Close, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            a.fileId == null -> CircularProgressIndicator(progress = { a.progress }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else -> Icon(AppIcons.File, null, Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f, fill = false).padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            Text(a.error ?: if (a.size >= 0) humanSize(a.size) else "", style = MaterialTheme.typography.labelSmall, color = if (a.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "外す", Modifier.size(16.dp)) }
    }
}

/** カーソル直前の "@xxx" を探す。(開始位置, 検索語) を返す */
internal fun mentionQueryAt(v: TextFieldValue): Pair<Int, String>? {
    val cursor = v.selection.start
    if (cursor <= 0 || v.selection.start != v.selection.end) return null
    val before = v.text.substring(0, cursor)
    val at = before.lastIndexOf('@')
    if (at < 0) return null
    val q = before.substring(at + 1)
    if (q.isEmpty() || q.any { it.isWhitespace() } || q.startsWith("<")) return null
    if (at > 0 && !before[at - 1].isWhitespace()) return null
    return at to q
}

/** アップロードの進み具合を数える RequestBody */
private class ProgressBody(private val inner: okhttp3.RequestBody, private val onProgress: (Float) -> Unit) : okhttp3.RequestBody() {
    override fun contentType() = inner.contentType()
    override fun contentLength() = inner.contentLength()
    override fun writeTo(sink: BufferedSink) {
        val total = contentLength().toFloat()
        var written = 0L
        val fs = object : ForwardingSink(sink) {
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (total > 0) onProgress((written / total).coerceAtMost(0.99f))
            }
        }.buffer()
        inner.writeTo(fs)
        fs.flush()
    }
}
