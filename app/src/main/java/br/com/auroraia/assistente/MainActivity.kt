package br.com.auroraia.assistente

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private var speechSessionId = 0
    private var tts: TextToSpeech? = null
    private lateinit var statusText: TextView
    private lateinit var commandText: TextView
    private lateinit var listenButton: Button
    private lateinit var keyguardManager: KeyguardManager
    private var awaitingWhatsAppProfileChoice = false
    private var whatsappProfileChoiceRetries = 0
    private var pendingDialContactName: String? = null

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
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
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
        val settings = getSharedPreferences("aurora_settings", MODE_PRIVATE)
        if (!settings.getBoolean("voice_commands_enabled", true)) {
            updateStatus("Comandos por voz desativados nas configurações.")
            return
        }
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

        // Cada escuta recebe um identificador. Resultados atrasados de uma sessão
        // anterior não podem sobrescrever a escolha atual nem disparar comandos.
        speechSessionId++
        val activeSessionId = speechSessionId
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { updateStatus("Estou ouvindo…") }
            override fun onBeginningOfSpeech() { updateStatus("Pode continuar falando.") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { updateStatus("Processando seu comando…") }
            override fun onError(error: Int) {
                if (activeSessionId != speechSessionId) return
                if (awaitingWhatsAppProfileChoice) {
                    // O reconhecimento pode encerrar cedo ou falhar por silêncio/ruído.
                    // Dar várias janelas de escuta sem anunciar falha após apenas duas tentativas.
                    when {
                        whatsappProfileChoiceRetries < 3 -> {
                            whatsappProfileChoiceRetries++
                            updateStatus("Aguardando sua resposta. Diga perfil 1 ou perfil 2; você pode falar novamente.")
                            window.decorView.postDelayed({
                                if (awaitingWhatsAppProfileChoice) startListening()
                            }, 900L)
                        }
                        whatsappProfileChoiceRetries == 3 -> {
                            whatsappProfileChoiceRetries++
                            updateStatus("Ainda aguardando. Vou repetir a pergunta e ouvir novamente.")
                            speakAndListenForProfile("Qual perfil você deseja abrir? Diga perfil um ou perfil dois.")
                        }
                        whatsappProfileChoiceRetries < 6 -> {
                            whatsappProfileChoiceRetries++
                            updateStatus("Não captei sua resposta. Tente dizer apenas um ou dois.")
                            window.decorView.postDelayed({
                                if (awaitingWhatsAppProfileChoice) startListening()
                            }, 1200L)
                        }
                        else -> {
                            awaitingWhatsAppProfileChoice = false
                            whatsappProfileChoiceRetries = 0
                            updateStatus("Não consegui ouvir a escolha após várias tentativas. Toque no microfone para tentar novamente.")
                            speak("Não consegui ouvir sua escolha. Toque no microfone quando estiver pronto e diga perfil um ou perfil dois.")
                        }
                    }
                } else {
                    updateStatus("Não consegui reconhecer a fala. Toque no microfone e tente novamente.")
                }
            }
            override fun onResults(results: Bundle?) {
                if (activeSessionId != speechSessionId) return
                val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val spoken = if (awaitingWhatsAppProfileChoice) {
                    candidates.firstOrNull { isProfileOne(it) || isProfileTwo(it) }
                        ?: candidates.firstOrNull().orEmpty()
                } else candidates.firstOrNull().orEmpty()
                commandText.text = "Você disse: $spoken"
                handleCommand(spoken)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (awaitingWhatsAppProfileChoice) "Diga primeiro, segundo, pessoal, profissional, Vivo ou TIM" else "Fale seu comando para a AURORA IA")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
            // A escolha de perfil precisa de uma janela maior: respostas curtas como
            // "Vivo", "TIM" e "primeiro" não podem ser cortadas por silêncio precoce.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, if (awaitingWhatsAppProfileChoice) 2500L else 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (awaitingWhatsAppProfileChoice) 8000L else 3500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (awaitingWhatsAppProfileChoice) 12000L else 6000L)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            updateStatus("Não foi possível iniciar o reconhecimento de fala.")
        }
    }

    private fun normalizeProfileChoice(raw: String): String =
        raw.lowercase(Locale("pt", "BR"))
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isProfileOne(raw: String): Boolean {
        val value = normalizeProfileChoice(raw)
        return value.matches(Regex("^(perfil )?(1|um|uma|primeiro|primeira|primeiro perfil|primeira opção|primeira opcao|perfil primeiro|primeiro whatsapp|primeira conta|número um|numero um|opção um|opcao um|opção 1|opcao 1|principal|pessoal)$")) ||
            value.matches(Regex(".*\\b(1|um|uma|primeiro|primeira)\\b.*")) ||
            value.contains("perfil principal") || value.contains("whatsapp pessoal") ||
            value.contains("primeiro whatsapp") || value.contains("primeira opção") || value.contains("primeira opcao") ||
            value == "vivo" || value.contains("whatsapp vivo") || value == "pessoal" || value.contains("perfil pessoal")
    }

    private fun isProfileTwo(raw: String): Boolean {
        val value = normalizeProfileChoice(raw)
        return value.matches(Regex("^(perfil )?(2|dois|duas|segundo|segunda|segundo perfil|segunda opção|segunda opcao|perfil segundo|segundo whatsapp|segunda conta|número dois|numero dois|opção dois|opcao dois|opção 2|opcao 2)$")) ||
            value.matches(Regex(".*\\b(2|dois|duas|segundo|segunda)\\b.*")) ||
            value.contains("segundo whatsapp") || value.contains("segunda opção") || value.contains("segunda opcao") ||
            value == "tim" || value.contains("whatsapp tim") || value == "profissional" ||
            value == "trabalho" || value.contains("perfil profissional") || value.contains("whatsapp profissional") ||
            value.contains("whatsapp comercial") || value.contains("whatsapp trabalho")
    }

    private fun handleCommand(raw: String) {
    if (keyguardManager.isKeyguardLocked) {
        updateStatus("Ação bloqueada porque o aparelho está bloqueado.")
        return
    }

    val command = raw.trim()
    val lower = command.lowercase(Locale("pt", "BR"))

    if (awaitingWhatsAppProfileChoice) {
        val choice = normalizeProfileChoice(lower)
        when {
            isProfileOne(choice) -> {
                awaitingWhatsAppProfileChoice = false
                whatsappProfileChoiceRetries = 0
                updateStatus("Ok, perfil 1.")
                speak("Ok, perfil um.")
                openWhatsApp()
            }
            isProfileTwo(choice) -> {
                awaitingWhatsAppProfileChoice = false
                whatsappProfileChoiceRetries = 0
                updateStatus("Ok, perfil 2.")
                speak("Ok, perfil dois.")
                openWhatsAppProfile2()
            }
            else -> {
                // Não encerrar a seleção após uma transcrição ambígua: a opção 1
                // costuma ser transcrita de forma variável pelo reconhecedor.
                whatsappProfileChoiceRetries++
                updateStatus("Não identifiquei a opção. Diga PRIMEIRO, PESSOAL ou VIVO para o WhatsApp principal; ou SEGUNDO, PROFISSIONAL ou TIM para o outro WhatsApp.")
                if (whatsappProfileChoiceRetries >= 3) {
                    whatsappProfileChoiceRetries = 0
                    speakAndListenForProfile("Diga UM para o WhatsApp principal ou DOIS para o segundo WhatsApp.")
                } else {
                    speakAndListenForProfile("Não entendi. Diga PRIMEIRO ou SEGUNDO.")
                }
            }
        }
        return
    }

    val settings = getSharedPreferences("aurora_settings", MODE_PRIVATE)
    val asksWaze = lower.contains("waze") || lower.contains("rota para") ||
        lower.contains("rota até") || lower.contains("navegar para") || lower.contains("navegar até") ||
        lower.contains("ir para casa")
    val asksWhatsApp = lower.contains("whatsapp") || lower == "vivo" || lower == "tim" ||
        lower == "pessoal" || lower == "profissional" || lower == "trabalho" ||
        lower == "primeiro" || lower == "segundo" || lower.contains("perfil 1") || lower.contains("perfil 2")
    val asksMessage = lower.startsWith("preparar mensagem:") || lower.startsWith("corrigir mensagem:")
    val asksDialer = lower.startsWith("ligar para ") || lower.startsWith("ligue para ")

    if (asksWaze && !settings.getBoolean("waze_enabled", true)) {
        updateStatus("A integração com o Waze está desativada nas configurações.")
        speak("A integração com o Waze está desativada.")
        return
    }
    if (asksWhatsApp && !settings.getBoolean("whatsapp_commands_enabled", true)) {
        updateStatus("Os comandos do WhatsApp estão desativados nas configurações.")
        speak("Os comandos do WhatsApp estão desativados.")
        return
    }
    if (asksMessage && !settings.getBoolean("message_review_enabled", true)) {
        updateStatus("A preparação de mensagens está desativada nas configurações.")
        speak("A preparação de mensagens está desativada.")
        return
    }
    if (asksDialer && !settings.getBoolean("dialer_enabled", true)) {
        updateStatus("A abertura do discador está desativada nas configurações.")
        speak("A abertura do discador está desativada.")
        return
    }

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

        (lower.contains("whatsapp") && (lower.contains("tim") || lower.contains("perfil 2") || lower.contains("perfil dois"))) ||
        lower.contains("whatsapp 2") ||
        lower.contains("whatsapp dois") ||
        lower.contains("segundo whatsapp") ||
        lower.contains("whatsapp secundário") ||
        lower.contains("whatsapp secundario") ||
        lower.contains("whatsapp profissional") ||
        lower.contains("whatsapp comercial") ||
        (lower.contains("whatsapp") && lower.contains("trabalho")) -> {
            openWhatsAppProfile2()
        }

        (lower.contains("whatsapp") && (lower.contains("vivo") || lower.contains("perfil 1") || lower.contains("perfil um"))) ||
        lower.contains("whatsapp pessoal") ||
        lower.contains("whatsapp principal") ||
        lower.contains("whatsapp 1") ||
        lower.contains("whatsapp um") -> {
            openWhatsApp()
        }

        lower.contains("abrir whatsapp") || lower == "whatsapp" -> {
            val prefs = getSharedPreferences("aurora_settings", MODE_PRIVATE)
            val profile1Enabled = prefs.getBoolean("profile_1_enabled", true)
            val profile2Enabled = prefs.getBoolean("profile_2_enabled", false)
            if (profile1Enabled && profile2Enabled) {
                awaitingWhatsAppProfileChoice = true
                whatsappProfileChoiceRetries = 0
                updateStatus("Aguardando escolha: diga UM para o WhatsApp principal ou DOIS para o segundo WhatsApp.")
                speakAndListenForProfile("Diga UM para o WhatsApp principal ou DOIS para o segundo WhatsApp.")
            } else if (profile2Enabled && !profile1Enabled) {
                openWhatsAppProfile2()
            } else {
                openWhatsApp()
            }
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

            if (contactName.isBlank()) {
                updateStatus("Diga o nome do contato, por exemplo: ligar para João.")
                speak("Qual contato você deseja localizar?")
            } else {
                openContactDialer(contactName)
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

    private fun speakAndListenForProfile(message: String) {
        // Invalida a escuta anterior antes da fala, evitando que um callback antigo
        // interprete o silêncio durante a pergunta como uma nova falha.
        speechSessionId++
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        val engine = tts
        if (engine == null) {
            updateStatus("Não foi possível falar a pergunta. Toque no microfone e diga 1 ou 2.")
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    if (awaitingWhatsAppProfileChoice) {
                        window.decorView.postDelayed({ if (awaitingWhatsAppProfileChoice) startListening() }, 500L)
                    }
                }
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "whatsapp-profile-question") {
                    runOnUiThread {
                        window.decorView.postDelayed({ if (awaitingWhatsAppProfileChoice) startListening() }, 500L)
                    }
                }
            }
        })
        engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "whatsapp-profile-question")
    }

    private fun openWhatsAppProfile2() {
        val prefs = getSharedPreferences("aurora_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("profile_2_enabled", false)) {
            updateStatus("O perfil 2 está desativado. Ative-o nas configurações e salve.")
            speak("O perfil dois está desativado. Ative-o nas configurações.")
            return
        }

        val savedComponent = prefs.getString("profile_2_component", null)
        val savedUser = prefs.getString("profile_2_user_handle", null)
        if (savedComponent.isNullOrBlank() || savedUser.isNullOrBlank()) {
            updateStatus("WhatsApp 2 ainda não foi vinculado. Abra as configurações e localize o Dual Messenger.")
            speak("O WhatsApp dois ainda não foi vinculado. Verifique as configurações.")
            return
        }

        val component = android.content.ComponentName.unflattenFromString(savedComponent)
        if (component == null) {
            updateStatus("A seleção do WhatsApp 2 está inválida. Localize-o novamente nas configurações.")
            return
        }

        try {
            val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
            val targetUser = launcherApps.profiles.firstOrNull { it.toString() == savedUser }
            if (targetUser == null) {
                updateStatus("O perfil do WhatsApp 2 não está acessível. Localize-o novamente nas configurações.")
                speak("Não consegui acessar o perfil dois. Localize-o novamente nas configurações.")
                return
            }
            launcherApps.startMainActivity(component, targetUser, null, null)
            updateStatus("Comando de voz: abrindo a instalação selecionada do WhatsApp 2.")
        } catch (_: SecurityException) {
            updateStatus("O Android/Samsung bloqueou a abertura por voz do WhatsApp 2. Use TESTAR PERFIL 2 nas configurações.")
            speak("O Android bloqueou a abertura do WhatsApp dois por voz.")
        } catch (_: Exception) {
            updateStatus("Não foi possível abrir o WhatsApp 2 por voz. Localize-o novamente nas configurações.")
        }
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

    private fun openContactDialer(contactName: String) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingDialContactName = contactName
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1002)
            updateStatus("Preciso de permissão para procurar esse contato na sua agenda.")
            return
        }

        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$contactName%")
            val matches = mutableListOf<Pair<String, String>>()
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                args,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && matches.size < 10) {
                    val name = cursor.getString(nameIndex).orEmpty()
                    val number = cursor.getString(numberIndex).orEmpty()
                    if (number.isNotBlank()) matches.add(name to number)
                }
            }

            when (matches.size) {
                0 -> {
                    updateStatus("Não encontrei telefone para '$contactName'. Confira o nome ou abra o discador manualmente.")
                    speak("Não encontrei esse contato com número de telefone.")
                }
                1 -> {
                    val (name, number) = matches.first()
                    val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                    startActivity(intent)
                    updateStatus("Contato localizado: $name. O discador está aberto; confira o número e toque para ligar quando quiser.")
                    speak("Encontrei $name. Confira o número no discador antes de ligar.")
                }
                else -> {
                    val names = matches.map { it.first }.distinct().toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Qual contato você deseja ligar?")
                        .setItems(names) { _, which ->
                            val selectedName = names[which]
                            val selected = matches.first { it.first == selectedName }
                            try {
                                startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", selected.second, null)))
                                updateStatus("Contato localizado: ${selected.first}. Confirme o número antes de ligar.")
                            } catch (_: ActivityNotFoundException) {
                                updateStatus("Não encontrei um aplicativo de telefone disponível.")
                            }
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
        } catch (_: SecurityException) {
            updateStatus("O Android não autorizou a leitura dos contatos. Verifique a permissão nas configurações.")
        } catch (_: ActivityNotFoundException) {
            updateStatus("Não encontrei um aplicativo de telefone disponível.")
        } catch (_: Exception) {
            updateStatus("Não consegui consultar a agenda. Tente novamente.")
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
        } else if (requestCode == 1002) {
            val contactName = pendingDialContactName
            pendingDialContactName = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && !contactName.isNullOrBlank()) {
                openContactDialer(contactName)
            } else {
                updateStatus("Permissão de contatos negada. Não consigo localizar o contato sem acesso à agenda.")
            }
        }
    }
}
