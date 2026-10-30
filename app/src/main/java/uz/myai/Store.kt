package uz.myai

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Msg(val role: String, val text: String)

const val DEFAULT_MODEL_URL =
    "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"

object Store {
    lateinit var app: Context
    lateinit var sp: SharedPreferences
    val msgs = mutableStateListOf<Msg>()
    val busy = mutableStateOf(false)
    val dlStatus = mutableStateOf("")
    val modelReady = mutableStateOf(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var llm: LlmInference? = null
    @Volatile private var onResult: ((String, Boolean) -> Unit)? = null

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c
        sp = c.getSharedPreferences("cfg", 0)
        modelReady.value = modelFile().exists()
        try {
            val a = JSONArray(sp.getString("chat", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                msgs.add(Msg(o.getString("r"), o.getString("t")))
            }
        } catch (_: Exception) {
        }
    }

    fun get(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun modelFile() = File(app.filesDir, "model.task")

    fun save() {
        val a = JSONArray()
        msgs.forEach { a.put(JSONObject().put("r", it.role).put("t", it.text)) }
        sp.edit().putString("chat", a.toString()).apply()
    }

    fun clearChat() {
        if (busy.value) return
        msgs.clear()
        save()
    }

    @Synchronized
    private fun engine(): LlmInference {
        llm?.let { return it }
        val o = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile().absolutePath)
            .setMaxTokens(1024)
            .setResultListener { partial, done -> onResult?.invoke(partial, done) }
            .build()
        return LlmInference.createFromOptions(app, o).also { llm = it }
    }

    @Synchronized
    private fun closeEngine() {
        try { llm?.close() } catch (_: Throwable) {}
        llm = null
    }

    private fun buildPrompt(idx: Int): String {
        val parts = ArrayList<String>()
        var len = 0
        for (i in idx - 1 downTo 0) {
            val m = msgs[i]
            val who = if (m.role == "user") "user" else "model"
            val s = "<start_of_turn>$who\n${m.text}<end_of_turn>\n"
            if (len + s.length > 3000 && parts.isNotEmpty()) break
            len += s.length
            parts.add(0, s)
        }
        while (parts.isNotEmpty() && parts[0].startsWith("<start_of_turn>model")) parts.removeAt(0)
        return parts.joinToString("") + "<start_of_turn>model\n"
    }

    fun sendLocal(text: String) {
        if (busy.value || text.isBlank()) return
        msgs.add(Msg("user", text.trim()))
        msgs.add(Msg("ai", ""))
        val idx = msgs.lastIndex
        busy.value = true
        val prompt = buildPrompt(idx)
        scope.launch {
            val acc = StringBuilder()
            try {
                val e = engine()
                onResult = { partial, done ->
                    acc.append(partial)
                    val t = acc.toString()
                    main.post {
                        msgs[idx] = Msg("ai", t)
                        if (done) { busy.value = false; save() }
                    }
                }
                e.generateResponseAsync(prompt)
            } catch (e: Throwable) {
                main.post {
                    msgs[idx] = Msg("ai", "Xatolik: ${e.message}")
                    busy.value = false
                    save()
                }
            }
        }
    }

    private fun status(s: String) { main.post { dlStatus.value = s } }

    fun download(url: String, token: String) {
        scope.launch(Dispatchers.IO) {
            try {
                status("Ulanmoqda...")
                var u = url
                var hops = 0
                var c: HttpURLConnection
                while (true) {
                    c = URL(u).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 20000
                    c.readTimeout = 30000
                    if (hops == 0 && token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
                    if (c.responseCode in 300..399 && hops < 5) {
                        u = URL(URL(u), c.getHeaderField("Location")).toString()
                        c.disconnect()
                        hops++
                        continue
                    }
                    break
                }
                if (c.responseCode !in 200..299) {
                    status("Xato ${c.responseCode}. Havola yoki token noto'g'ri bo'lishi mumkin.")
                    return@launch
                }
                val total = c.contentLengthLong
                val tmp = File(app.filesDir, "model.tmp")
                c.inputStream.use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(65536)
                        var got = 0L
                        var last = 0L
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            got += n
                            if (got - last > 3_000_000) {
                                last = got
                                val mb = got / 1_000_000
                                status(if (total > 0) "Yuklanmoqda: $mb / ${total / 1_000_000} MB" else "Yuklanmoqda: $mb MB")
                            }
                        }
                    }
                }
                finishModel(tmp)
            } catch (e: Throwable) {
                status("Yuklashda xato: ${e.message}")
            }
        }
    }

    fun importModel(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                status("Fayl nusxalanmoqda...")
                val tmp = File(app.filesDir, "model.tmp")
                app.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                finishModel(tmp)
            } catch (e: Throwable) {
                status("Xato: ${e.message}")
            }
        }
    }

    private fun finishModel(tmp: File) {
        closeEngine()
        val f = modelFile()
        if (f.exists()) f.delete()
        tmp.renameTo(f)
        main.post { modelReady.value = true }
        status("Model tayyor. Chat oynasida yozishingiz mumkin.")
    }

    fun gemini(history: List<Msg>, system: String): String {
        val key = get("key")
        if (key.isBlank()) return "API kalit kiritilmagan. Sozlamalardan kiriting."
        val model = get("gmodel", "gemini-2.5-flash")
        val contents = JSONArray()
        history.takeLast(12).forEach {
            contents.put(
                JSONObject().put("role", if (it.role == "user") "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.text)))
            )
        }
        val body = JSONObject().put("contents", contents).put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
        )
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        if (code !in 200..299) return "Xato $code: " + txt.take(200)
        return JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }
}
