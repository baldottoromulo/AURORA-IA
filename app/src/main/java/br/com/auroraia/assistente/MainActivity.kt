package br.com.auroraia.assistente

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.os.Handler
import android.os.Looper
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
    private var awaitingMessageDictation = false
    private var pendingContactName: String = ""
    private var pendingMessageText: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val contactPickerRequest = 2401

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

        val whatsappSettingsButton = Button(this).apply {
            text = "⚙  ESCOLHER WHATSAPP"
            textSize = 15f
            setOnClickListener { selectWhatsApp() }
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
        outer.addView(whatsappSettingsButton, LinearLayout.LayoutParams(
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
                if (awaitingMessageDictation) {
                    updateStatus("Não consegui captar a mensagem inteira. Toque no microfone e dite novamente.")
                    speak("Não consegui entender a mensagem. Toque no microfone e tente novamente.")
                } else {
                    updateStatus("Não consegui reconhecer a fala. Tente novamente.")
                }
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                commandText.text = "Você disse: $spoken"
                if (awaitingMessageDictation) {
                    awaitingMessageDictation = false
                    if (spoken.isBlank()) {
                        updateStatus("Nenhum texto foi reconhecido. Toque no microfone para ditar novamente.")
                    } else {
                        pendingMessageText = organizeMessageLocally(spoken)
                        showMessageReview(pendingMessageText)
                    }
                } else {
                    handleCommand(spoken)
                }
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

    lower == "abrir waze" ||
    lower == "abrir o waze" ||
    lower == "abra o waze" ||
    lower == "abra waze" ||
    lower == "waze" -> {
    openWaze()
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

        lower.contains("mensagem para ") ||
        lower.contains("mensagem pro ") ||
        lower.contains("preparar mensagem para ") ||
        lower.contains("preparar mensagem pro ") ||
        lower.contains("enviar mensagem para ") ||
        lower.contains("enviar mensagem pro ") -> {
            val contact = command.substringAfter("mensagem", "")
                .replaceFirst(Regex("^(\\s*(para|pro)\\s*)", RegexOption.IGNORE_CASE), "")
                .trim()
                .trimEnd('.', ',', '!', '?')
            if (contact.isBlank()) {
                updateStatus("Não identifiquei o contato. Diga: mensagem para nome do contato.")
                speak("Para qual contato você deseja preparar a mensagem?")
            } else {
                startMessageDictation(contact)
            }
        }

        lower.contains("abrir whatsapp") || lower == "whatsapp" -> {
            openWhatsApp()
        }

        lower.startsWith("preparar mensagem:") ||
        lower.startsWith("corrigir mensagem:") -> {
            val message = command.substringAfter(":").trim()
            if (message.isBlank()) {
                updateStatus("Diga o texto da mensagem depois de tocar no microfone.")
            } else {
                pendingContactName = ""
                pendingMessageText = organizeMessageLocally(message)
                showMessageReview(pendingMessageText)
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
   private fun openWaze() {
    try {
        val launchIntent =
            packageManager.getLaunchIntentForPackage("com.waze")

        if (launchIntent != null) {
            startActivity(launchIntent)
            updateStatus("Waze aberto. Diga o destino quando quiser preparar uma rota.")
            speak("Waze aberto. Para onde você quer ir?")
        } else {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://waze.com/")
            )
            startActivity(intent)
            updateStatus("Abrindo a página do Waze.")
        }
    } catch (_: Exception) {
        updateStatus("Não consegui abrir o Waze. Verifique se ele está instalado.")
        speak("Não consegui abrir o Waze.")
    }
}
    private fun selectWhatsApp() {
        if (keyguardManager.isKeyguardLocked) {
            updateStatus("Ação bloqueada: desbloqueie o aparelho antes de alterar as configurações.")
            return
        }

        val options = arrayOf("WhatsApp comum", "WhatsApp Business")
        val packages = arrayOf("com.whatsapp", "com.whatsapp.w4b")
        val prefs = getSharedPreferences("aurora_settings", MODE_PRIVATE)
        val currentPackage = prefs.getString("whatsapp_package", "com.whatsapp")
        val checkedItem = packages.indexOf(currentPackage).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle("Escolha o WhatsApp preferido")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val selectedPackage = packages[which]
                prefs.edit().putString("whatsapp_package", selectedPackage).apply()
                val selectedName = options[which]
                updateStatus("Preferência salva: $selectedName.")
                Toast.makeText(this, "$selectedName será usado no comando 'abrir WhatsApp'.", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openWhatsApp() {
        val prefs = getSharedPreferences("aurora_settings", MODE_PRIVATE)
        val selectedPackage = prefs.getString("whatsapp_package", null)
        val packageToOpen = selectedPackage ?: run {
            val regularAvailable = packageManager.getLaunchIntentForPackage("com.whatsapp") != null
            if (regularAvailable) "com.whatsapp" else "com.whatsapp.w4b"
        }

        val launch = packageManager.getLaunchIntentForPackage(packageToOpen)
        if (launch == null) {
            val selectedName = if (packageToOpen == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp comum"
            updateStatus("$selectedName não foi encontrado. Toque em 'ESCOLHER WHATSAPP' e selecione um aplicativo instalado.")
            speak("O WhatsApp selecionado não foi encontrado. Verifique as configurações.")
            return
        }
        try {
            startActivity(launch)
            val selectedName = if (packageToOpen == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp comum"
            updateStatus("$selectedName aberto. Nenhuma mensagem foi enviada.")
        } catch (_: Exception) {
            updateStatus("Não consegui abrir o WhatsApp selecionado.")
        }
    }

    private fun startMessageDictation(contactName: String) {
        if (keyguardManager.isKeyguardLocked) {
            updateStatus("Ação bloqueada: desbloqueie o aparelho pelos meios normais do Android.")
            return
        }
        pendingContactName = contactName
        pendingMessageText = ""
        awaitingMessageDictation = true
        updateStatus("Contato solicitado: $contactName. Vou aguardar o texto da mensagem.")
        speak("Mensagem para $contactName. Dite o texto depois do sinal.")
        mainHandler.postDelayed({
            if (awaitingMessageDictation) startListening()
        }, 2500)
    }

    // Organização local básica: não substitui uma correção gramatical feita por IA.
    private fun organizeMessageLocally(raw: String): String {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return text
        val capitalized = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("pt", "BR")) else it.toString() }
        return if (capitalized.last() in ".!?") capitalized else "$capitalized."
    }

    private fun showMessageReview(message: String) {
        val preview = android.widget.EditText(this).apply {
            setText(message)
            minLines = 4
            textSize = 17f
            setSelection(text.length)
        }
        val destination = if (pendingContactName.isBlank()) {
            "Confira o texto. A organização aplicada é local e básica; nomes, sentido e gramática devem ser conferidos."
        } else {
            "Contato solicitado: $pendingContactName\\n\\nConfira o texto. A organização aplicada é local e básica; nomes, sentido e gramática devem ser conferidos."
        }

        AlertDialog.Builder(this)
            .setTitle("Revise sua mensagem")
            .setMessage(destination)
            .setView(preview)
            .setNegativeButton("Cancelar") { _, _ ->
                pendingMessageText = ""
                pendingContactName = ""
                updateStatus("Preparação cancelada. Nenhuma mensagem foi enviada.")
            }
            .setNeutralButton("Ditar novamente") { _, _ ->
                startMessageDictation(pendingContactName)
            }
            .setPositiveButton("Escolher contato") { _, _ ->
                pendingMessageText = preview.text.toString().trim()
                if (pendingMessageText.isBlank()) {
                    updateStatus("A mensagem está vazia. Nada foi enviado.")
                } else {
                    try {
                        val pick = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                        startActivityForResult(pick, contactPickerRequest)
                        updateStatus("Selecione na agenda o contato $pendingContactName. A mensagem não será enviada automaticamente.")
                    } catch (_: ActivityNotFoundException) {
                        updateStatus("Não foi possível abrir a agenda de contatos.")
                    }
                }
            }
            .show()
    }

    @Deprecated("Deprecated in Android API; retained for compatibility with the current project target")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != contactPickerRequest || resultCode != RESULT_OK || data?.data == null) return

        val contactUri = data.data!!
        val cursor = contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null
        )
        var phoneNumber: String? = null
        var displayName: String? = null
        cursor?.use {
            if (it.moveToFirst()) {
                phoneNumber = it.getString(0)
                displayName = it.getString(1)
            }
        }

        val digits = phoneNumber.orEmpty().filter { it.isDigit() }
        if (digits.isBlank()) {
            updateStatus("Não consegui obter o telefone do contato. Nenhuma mensagem foi enviada.")
            return
        }

        // O WhatsApp abre a conversa com o texto preenchido; o usuário ainda precisa tocar em Enviar.
        val normalizedNumber = if (digits.startsWith("55")) digits else "55$digits"
        val uri = Uri.parse("https://wa.me/$normalizedNumber?text=${Uri.encode(pendingMessageText)}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            updateStatus("Conversa de ${displayName ?: pendingContactName} aberta com o texto preenchido. Revise e toque em Enviar no WhatsApp.")
        } catch (_: Exception) {
            updateStatus("Não consegui abrir a conversa no WhatsApp. O texto continua preparado e não foi enviado.")
        }
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
