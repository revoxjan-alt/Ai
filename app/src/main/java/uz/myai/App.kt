package uz.myai

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun App(voice: Voice) {
    var tab by remember { mutableIntStateOf(0) }
    val items = listOf("💬" to "Chat", "🎙" to "Ovoz", "⚙" to "Sozlamalar")
    Scaffold(bottomBar = {
        NavigationBar {
            items.forEachIndexed { i, (ic, label) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(ic) }, label = { Text(label) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ChatScreen()
                1 -> VoiceScreen(voice)
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
fun Bubble(m: Msg) {
    val user = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            SelectionContainer {
                Text(if (m.text.isEmpty()) "..." else m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun ChatScreen() {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val msgs = Store.msgs
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text) {
        if (msgs.isNotEmpty()) list.scrollToItem(msgs.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Mening AI", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { Store.clearChat() }) { Text("Yangi chat") }
        }
        if (!Store.modelReady.value) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text("Oflayn model yuklanmagan. Sozlamalar bo'limidan yuklab oling.", Modifier.padding(12.dp))
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) { items(msgs) { Bubble(it) } }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), placeholder = { Text("Xabar yozing") }, maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (input.isNotBlank() && Store.modelReady.value) { Store.sendLocal(input); input = "" } },
                enabled = !Store.busy.value && Store.modelReady.value
            ) { Text("↑") }
        }
    }
}

@Composable
fun VoiceScreen(v: Voice) {
    val ctx = LocalContext.current
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) v.start()
    }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Ovozli suhbat (internet kerak)", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(v.lines) { Bubble(it) }
        }
        Text(v.state.value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            if (v.running.value) v.stop()
            else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) v.start()
            else perm.launch(Manifest.permission.RECORD_AUDIO)
        }) { Text(if (v.running.value) "To'xtatish" else "Suhbatni boshlash") }
    }
}

@Composable
fun SettingsScreen() {
    var key by remember { mutableStateOf(Store.get("key")) }
    var gm by remember { mutableStateOf(Store.get("gmodel", "gemini-2.5-flash")) }
    var lang by remember { mutableStateOf(Store.get("lang", "uz-UZ")) }
    var url by remember { mutableStateOf(Store.get("url", DEFAULT_MODEL_URL)) }
    var tok by remember { mutableStateOf(Store.get("hf")) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Store.importModel(uri)
    }
    fun saveAll() {
        Store.put("key", key.trim()); Store.put("gmodel", gm.trim()); Store.put("lang", lang.trim())
        Store.put("url", url.trim()); Store.put("hf", tok.trim())
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Ovozli suhbat (internet)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(key, { key = it }, label = { Text("Gemini API kalit") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(gm, { gm = it }, label = { Text("Gemini model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(lang, { lang = it }, label = { Text("Til (uz-UZ, ru-RU, en-US)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Oflayn model (matnli chat)", style = MaterialTheme.typography.titleMedium)
        Text(if (Store.modelReady.value) "Model yuklangan" else "Model yo'q")
        OutlinedTextField(url, { url = it }, label = { Text("Model havolasi (.task)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tok, { tok = it }, label = { Text("HuggingFace token (kerak bo'lsa)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { saveAll(); Store.download(url.trim(), tok.trim()) }, modifier = Modifier.fillMaxWidth()) {
            Text("Internetdan yuklab olish")
        }
        OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Telefondagi fayldan tanlash")
        }
        if (Store.dlStatus.value.isNotEmpty()) Text(Store.dlStatus.value)
        Button(onClick = { saveAll() }, modifier = Modifier.fillMaxWidth()) { Text("Saqlash") }
    }
}
