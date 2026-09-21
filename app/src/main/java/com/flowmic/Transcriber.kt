package com.flowmic

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*

/**
 * Double moteur : Google offline (gratuit) ou OpenAI Whisper API (clé).
 * Démarre sur appui micro, transcrit à l'arrêt.
 */
class Transcriber(
    private val ctx: Context,
    private val onResult: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var listening = false

    fun isListening() = listening

    fun start(mode: String, language: String, apiKey: String) {
        if (listening) return
        listening = true
        if (mode == "whisper_api" && apiKey.isNotBlank()) startRecording()
        else startGoogle(language)
    }

    fun stop() {
        if (!listening) return
        listening = false
        try { recognizer?.stopListening() } catch (_: Exception) {}
        if (recorder != null) stopRecordingAndTranscribe()
    }

    fun destroy() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        scope.cancel()
    }

    private fun startGoogle(language: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            listening = false
            onResult("")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) {
                    Log.e("Transcriber", "STT error $e")
                    listening = false
                }
                override fun onResults(r: Bundle?) {
                    val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim() ?: ""
                    listening = false
                    if (text.isNotBlank()) onResult(text)
                }
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
        val lang = when (language) {
            "en" -> "en-US"
            "fr" -> "fr-FR"
            else -> ""
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (lang.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { recognizer?.startListening(intent) }
        catch (e: Exception) { Log.e("Transcriber", "start failed", e); listening = false }
    }

    private fun startRecording() {
        try {
            audioFile = File(ctx.cacheDir, "flow_${System.currentTimeMillis()}.m4a")
            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx)
                        else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(16000)
                setOutputFile(audioFile!!.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("Transcriber", "recorder failed", e)
            startGoogle("fr")
        }
    }

    private fun stopRecordingAndTranscribe() {
        try { recorder?.apply { stop(); release() } } catch (_: Exception) {}
        recorder = null
        val f = audioFile ?: return
        if (!f.exists() || f.length() < 800) return
        scope.launch(Dispatchers.IO) {
            try {
                val prefs = kotlinx.coroutines.flow.first(ctx.prefsFlow())
                val text = transcribeWhisper(f, prefs.apiKey, prefs.language)
                withContext(Dispatchers.Main) { if (text.isNotBlank()) onResult(text) }
            } catch (e: Exception) {
                Log.e("Transcriber", "whisper failed", e)
            } finally {
                try { f.delete() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun transcribeWhisper(file: File, apiKey: String, language: String): String =
        withContext(Dispatchers.IO) {
            val boundary = "----FlowMic${System.currentTimeMillis()}"
            val conn = (java.net.URL("https://api.openai.com/v1/audio/transcriptions")
                .openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 25000
                readTimeout = 25000
            }
            java.io.DataOutputStream(conn.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.writeBytes("$value\r\n")
                }
                field("model", "whisper-1")
                if (language != "auto") field("language", language)
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\n")
                out.writeBytes("Content-Type: audio/m4a\r\n\r\n")
                FileInputStream(file).use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().readText()
            if (code !in 200..299) throw java.io.IOException("Whisper $code: $resp")
            JSONObject(resp).optString("text", "").trim()
        }
}
