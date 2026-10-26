package br.com.auroraia.assistente

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var statusText: TextView
    private lateinit var commandText: TextView
    private lateinit var listenButton: Button
    private lateinit var keyguardManager: KeyguardManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        tts = TextToSpeech(this, this)
        buildInterface()
        updateStatus("Pronto. Toque no microfone e diga um comando.")
    }

    private fun buildInterface() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 34, 28, 24)
            setBackgroundColor(0xFF0B1020.toInt())
        }

        val title = TextView(this).apply {
            text = "AURORA IA"
            textSize = 30f
            setTextColor(0xFF77D8FF.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Seu assistente pessoal"
            textSize = 16f
            setTextColor(0xFFE4ECF7.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 28)
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFB9C8DD.toInt())
            gravity = Gravity.CENTER
            setPadding(12, 16, 12, 18)
        }

        commandText = TextView(this).apply {
            text = "Exemplos: “abrir Waze”, “abrir WhatsApp”, “preparar mensagem: ...”"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(14, 20, 14, 24)
        }

        listenButton = Button(this).apply {
            text = "🎙  FALAR COM A AURORA"
            textSize = 16f
            setOnClickListener { startListening() }
        }

        val help = TextView(this).apply {
            text = "SEGURANÇA ATIVA\nNão desbloqueia o aparelho. Não possui comando para abrir bancos. Mensagens não são enviadas automaticamente."
            textSize = 13f
            setTextColor(0xFF9FE7C2.toInt())
            gravity = Gravity.CENTER
            setPadding(8, 26, 8, 8)
        }

        outer.addView(title, matchWrap())
        outer.addView(subtitle, matchWrap())
        outer.addView(statusText, matchWrap())
        outer.addView(commandText, matchWrap())
        outer.addView(listenButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        outer.addView(help, matchWrap())

        val scroll = ScrollView(this).apply { addView(outer) }
        setContentView(scroll)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun startListening() {
        if (keyguardManager.isKeyguardLocked) {
            updateStatus("Ação bloqueada: desbloqueie o aparelho pelos meios normais do Android.")
            speak("Por segurança, desbloqueie o aparelho antes de usar a Aurora.")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus("O reconhecimento de fala não está disponível neste aparelho.")
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { updateStatus("Estou ouvindo…") }
            override fun onBeginningOfSpeech() { updateStatus("Pode continuar falando.") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { updateStatus("Processando seu comando…") }
            override fun onError(error: Int) {
                updateStatus("Não consegui reconhecer a fala. Tente novamente.")
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                commandText.text = "Você disse: $spoken"
                handleCommand(spoken)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale seu comando para a AURORA IA")
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            updateStatus("Não foi possível iniciar o reconhecimento de fala.")
        }
    }

    private fun handleCommand(raw: String) {
    if (keyguardManager.isKeyguardLocked) {
        updateStatus("Ação bloqueada porque o aparelho está bloqueado.")
        return
    }

    val command = raw.trim()
    val lower = command.lowercase(Locale("pt", "BR"))

    when {
        lower.contains("abrir banco") ||
        lower.contains("abre banco") ||
        lower.contains("aplicativo bancário") ||
        lower.contains("meu banco") -> {
            updateStatus("Bloqueado por segurança: a AURORA IA não abre aplicativos bancários.")
            speak("Por segurança, não posso abrir aplicativos bancários.")
        }

        lower.contains("waze") ||
        lower.contains("rota para casa") ||
        lower.contains("ir para casa") ||
        lower.contains("navegar para") ||
        lower.contains("navegar até") ||
        lower.contains("rota para") ||
        lower.contains("rota até") -> {

            val destination = extractWazeDestination(command)

            if (destination.isBlank() ||
                destination.equals("waze", ignoreCase = true)) {
                updateStatus("Diga, por exemplo: abrir Waze e ir para Praia da Costa, Vila Velha.")
                speak("Para onde você quer ir?")
            } else {
                openWazeDestination(destination)
            }
        }

        lower.contains("abrir whatsapp") || lower == "whatsapp" -> {
            openWhatsApp()
        }

        lower.startsWith("preparar mensagem:") ||
        lower.startsWith("corrigir mensagem:") -> {
            val message = command.substringAfter(":").trim()

            if (message.isBlank()) {
                updateStatus("Diga preparar mensagem e depois fale o texto.")
            } else {
                showMessageReview(message)
            }
        }

        lower.startsWith("ligar para ") ||
        lower.startsWith("ligue para ") -> {
            val contactName = command.substringAfter("para", "").trim()

            updateStatus("Vou abrir o discador. Confirme o contato e a ligação manualmente.")

            try {
                startActivity(Intent(Intent.ACTION_DIAL))
            } catch (_: ActivityNotFoundException) {
                updateStatus("Não encontrei um aplicativo de telefone disponível.")
            }

            if (contactName.isNotBlank()) {
                Toast.makeText(
                    this,
                    "Contato solicitado: $contactName. Selecione-o no telefone.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        else -> {
            updateStatus("Ainda não conheço esse comando. Nenhuma ação foi executada.")
            speak("Ainda não conheço esse comando.")
        }
    }
}

private fun extractWazeDestination(command: String): String {
    var destination = command.trim()

    val prefixes = listOf(
        "aurora ia",
        "aurora",
        "por favor",
        "você pode",
        "pode",
        "abra o",
        "abra",
        "abrir o",
        "abrir",
        "waze"
    )

    for (prefix in prefixes) {
        destination = destination.replaceFirst(
            Regex("^${Regex.escape(prefix)}\\b", RegexOption.IGNORE_CASE),
            ""
        ).trim()
    }

    val markers = listOf(
        "e me leve para",
        "e me leve até",
        "e trace uma rota para",
        "e trace uma rota até",
        "e coloque um endereço de",
        "e procure o endereço de",
        "e procure",
        "e pesquise",
        "e coloque",
        "navegar para",
        "navegar até",
        "ir para",
        "ir até",
        "rota para",
        "rota até",
        "endereço de",
        "endereço",
        "destino",
        "até",
        "para"
    )

    for (marker in markers) {
        val index = destination.indexOf(marker, ignoreCase = true)
        if (index >= 0) {
            destination = destination.substring(index + marker.length).trim()
            break
        }
    }

    destination = destination
        .replace(Regex("^[\\s,:-]+"), "")
        .replace(Regex("[.!?,]+$"), "")
        .trim()

    return destination
}

private fun openWazeDestination(destination: String) {
    val encodedDestination = Uri.encode(destination)
    val uri = Uri.parse(
        "https://waze.com/ul?q=$encodedDestination&navigate=no"
    )

    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        updateStatus(
            "Solicitei ao Waze a busca por: $destination. Confira o destino antes de iniciar a navegação."
        )
    } catch (_: Exception) {
        updateStatus("Não consegui abrir o Waze. Verifique se ele está instalado.")
    }
}
    private fun openWhatsApp() {
        val launch = packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
        if (launch == null) {
            updateStatus("Não encontrei o WhatsApp instalado.")
            return
        }
        try {
            startActivity(launch)
            updateStatus("WhatsApp aberto. Nenhuma mensagem foi enviada.")
        } catch (_: Exception) {
            updateStatus("Não consegui abrir o WhatsApp.")
        }
    }

    private fun showMessageReview(message: String) {
        // Este protótipo ainda não chama uma IA para correção gramatical.
        // A mensagem é mostrada para revisão e só pode ser compartilhada após toque explícito.
        val preview = android.widget.EditText(this).apply {
            setText(message)
            minLines = 3
            textSize = 17f
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Revise sua mensagem")
            .setMessage("Confira o texto antes de continuar. Nesta versão, a correção automática por IA ainda não está conectada.")
            .setView(preview)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Copiar") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Mensagem revisada", preview.text.toString()))
                updateStatus("Texto copiado. Nenhuma mensagem foi enviada.")
            }
            .setPositiveButton("Preparar envio") { _, _ ->
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, preview.text.toString())
                }
                try {
                    startActivity(Intent.createChooser(sendIntent, "Escolha o aplicativo para continuar"))
                    updateStatus("Texto preparado para revisão no aplicativo escolhido. Envio não automático.")
                } catch (_: Exception) {
                    updateStatus("Não foi possível abrir o menu de compartilhamento.")
                }
            }
            .show()
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    private fun speak(message: String) {
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "aurora-response")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("pt", "BR")
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (requestCode == 1001) {
            updateStatus("Permissão de microfone negada. O comando por voz não pode funcionar.")
        }
    }
}
