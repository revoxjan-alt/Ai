package uz.myai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class Voice(private val ctx: Context) {
    val state = mutableStateOf("To'xtagan")
    val running = mutableStateOf(false)
    val lines = mutableStateListOf<Msg>()
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val system = "Sen ovozli yordamchisan. Qisqa, 1-3 gapli, og'zaki uslubda javob ber. " +
        "Markdown, emoji, ro'yxat va belgilar ishlatma. Foydalanuvchi qaysi tilda gapirsa, shu tilda javob ber."

    private fun lang() = Store.get("lang", "uz-UZ")

    fun start() {
        running.value = true
        if (tts == null) {
            state.value = "Tayyorlanmoqda..."
            tts = TextToSpeech(ctx) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    val l = lang()
                    val r = tts?.setLanguage(Locale.forLanguageTag(l))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.forLanguageTag(if (l.startsWith("uz")) "tr-TR" else "en-US"))
                    }
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { main.post { listen() } }
                        @Deprecated("old")
                        override fun onError(id: String?) { main.post { listen() } }
                    })
                    ttsReady = true
                }
                main.post { listen() }
            }
        } else listen()
    }

    private fun listen() {
        if (!running.value) return
        state.value = "Tinglayapman..."
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) listen() else think(t)
            }
            override fun onError(e: Int) {
                if (!running.value) return
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stop(); state.value = "Mikrofon ruxsati yo'q"; return }
                if (e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) state.value = "Internet yo'q..."
                main.postDelayed({ listen() }, 1200)
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang())
        r.startListening(i)
    }

    private fun think(text: String) {
        lines.add(Msg("user", text))
        state.value = "O'ylayapman..."
        val hist = lines.toList()
        Store.scope.launch(Dispatchers.IO) {
            val ans = try { Store.gemini(hist, system) } catch (e: Throwable) { "Internet bilan muammo bor." }
            main.post {
                if (!running.value) return@post
                lines.add(Msg("ai", ans))
                speak(ans)
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) { listen(); return }
        state.value = "Gapiryapman..."
        val clean = text.replace(Regex("[*_#`]"), "")
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "u1")
    }

    fun stop() {
        running.value = false
        sr?.destroy(); sr = null
        tts?.stop()
        state.value = "To'xtagan"
    }

    fun release() {
        stop()
        tts?.shutdown(); tts = null
        ttsReady = false
    }
}
