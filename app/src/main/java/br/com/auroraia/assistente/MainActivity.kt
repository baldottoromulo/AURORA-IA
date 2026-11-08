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

private data class ContactPhone(val number: String, val label: String, val isPrimary: Boolean)
private data class ContactChoice(val name: String, val phones: List<ContactPhone>)

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
    private var awaitingCallConfirmation = false
    private var pendingCallNumber: String? = null
    private var pendingCallName: String? = null
    private var awaitingCallChoice = false
    private var callChoices: List<ContactChoice> = emptyList()
    private var awaitingPhoneChoice = false
    private var pendingPhoneChoices: List<ContactPhone> = emptyList()
    private var awaitingMessageContactChoice = false
    private var awaitingMessageRecipientRetry = false
    private var messageContactChoices: List<ContactChoice> = emptyList()
    private var awaitingMessageDictation = false
    private var pendingMessageDraft = StringBuilder()
    private var pendingMessageRecipientName: String? = null
    private var pendingMessageRecipientNumber: String? = null
    private var pendingMessageWhatsAppPackage: String? = null

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
                            updateStatus("Aguardando sua resposta. Perfil 1: Vivo, principal ou primeiro. Perfil 2: Tim, secundário ou segundo.")
                            window.decorView.postDelayed({
                                if (awaitingWhatsAppProfileChoice) startListening()
                            }, 900L)
                        }
                        whatsappProfileChoiceRetries == 3 -> {
                            whatsappProfileChoiceRetries++
                            updateStatus("Ainda aguardando. Vou repetir a pergunta e ouvir novamente.")
                            speakAndListenForProfile("Qual perfil deseja usar? Diga perfil um, Vivo, principal ou primeiro; ou perfil dois, Tim, secundário ou segundo.")
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
                } else if (awaitingCallChoice || awaitingPhoneChoice || awaitingMessageContactChoice || awaitingMessageRecipientRetry || awaitingMessageDictation || awaitingCallConfirmation) {
                    // Não cancela a etapa por silêncio ou falha momentânea do reconhecedor.
                    updateStatus("Continuo aguardando sua resposta. Pode falar novamente.")
                    window.decorView.postDelayed({
                        if (awaitingCallChoice || awaitingPhoneChoice || awaitingMessageContactChoice || awaitingMessageRecipientRetry || awaitingMessageDictation || awaitingCallConfirmation) startListening()
                    }, 650L)
                } else {
                    updateStatus("Não consegui reconhecer a fala. Toque no microfone e tente novamente.")
                }
            }
            override fun onResults(results: Bundle?) {
                if (activeSessionId != speechSessionId) return
                val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val spoken = when {
                    awaitingWhatsAppProfileChoice ->
                        candidates.firstOrNull { isProfileOne(it) || isProfileTwo(it) } ?: candidates.firstOrNull().orEmpty()
                    awaitingCallChoice ->
                        candidates.firstOrNull { resolveListChoice(it, callChoices.size) != null } ?: candidates.firstOrNull().orEmpty()
                    awaitingPhoneChoice ->
                        candidates.firstOrNull { resolvePhoneChoice(it) != null } ?: candidates.firstOrNull().orEmpty()
                    awaitingMessageContactChoice ->
                        candidates.firstOrNull { resolveListChoice(it, messageContactChoices.size) != null ||
                            messageContactChoices.any { contact -> normalizeVoiceChoice(contact.name) == normalizeVoiceChoice(it) } }
                            ?: candidates.firstOrNull().orEmpty()
                    awaitingCallConfirmation ->
                        candidates.firstOrNull { isYes(it) || isNo(it) } ?: candidates.firstOrNull().orEmpty()
                    else -> candidates.firstOrNull().orEmpty()
                }
                commandText.text = "Você disse: $spoken"
                handleCommand(spoken)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (activeSessionId != speechSessionId || !awaitingMessageDictation) return
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (partial.isNotBlank()) updateStatus("Ditando: ${pendingMessageDraft} ${partial}".trim())
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                when {
                    awaitingWhatsAppProfileChoice -> "Diga perfil um ou perfil dois"
                    awaitingCallChoice -> "Diga apenas o número da opção ou a cor correspondente"
                    awaitingPhoneChoice -> "Diga padrão ou o número da opção"
                    awaitingMessageContactChoice -> "Diga o número da opção ou o nome completo do contato"
                    awaitingCallConfirmation -> "Diga sim ou não"
                    else -> "Fale seu comando para a AURORA IA"
                }
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
            // A escolha de perfil precisa de uma janela maior: respostas curtas como
            // "Vivo", "TIM" e "primeiro" não podem ser cortadas por silêncio precoce.
            val dictating = awaitingMessageDictation
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, when { dictating -> 5000L; awaitingWhatsAppProfileChoice -> 2500L; else -> 1500L })
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, when { dictating -> 10000L; awaitingWhatsAppProfileChoice -> 8000L; else -> 5000L })
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, when { dictating -> 15000L; awaitingWhatsAppProfileChoice -> 12000L; else -> 8000L })
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            updateStatus("Não foi possível iniciar o reconhecimento de fala.")
        }
    }

    private fun normalizeVoiceChoice(raw: String): String =
        java.text.Normalizer.normalize(raw.lowercase(Locale("pt", "BR")), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun resolveListChoice(raw: String, size: Int): Int? {
        val value = normalizeVoiceChoice(raw)
        if (value.isBlank()) return null
        if (value.contains("cancelar") || value.contains("nenhuma")) return -1

        // Aceita a cor isolada ou dentro de frases como "opção azul".
        val colors = listOf(
            setOf("azul", "azuis"),
            setOf("amarelo", "amarela"),
            setOf("verde", "verdes"),
            setOf("vermelho", "vermelha"),
            setOf("roxo", "roxa"),
            setOf("laranja"),
            setOf("rosa"),
            setOf("marrom"),
            setOf("cinza"),
            setOf("preto", "preta")
        )
        colors.forEachIndexed { index, variants ->
            if (index < size && variants.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(value) }) return index
        }

        // Aceita dígitos, números por extenso e ordinais, mesmo acompanhados de
        // "opção", "número", "alternativa" ou "contato".
        val numberVariants = listOf(
            setOf("1", "um", "uma", "primeiro", "primeira", "primeira opcao", "opcao um", "numero um"),
            setOf("2", "dois", "duas", "segundo", "segunda", "segunda opcao", "opcao dois", "numero dois"),
            setOf("3", "tres", "terceiro", "terceira", "terceira opcao", "opcao tres", "numero tres"),
            setOf("4", "quatro", "quarto", "quarta", "quarta opcao", "opcao quatro", "numero quatro"),
            setOf("5", "cinco", "quinto", "quinta"),
            setOf("6", "seis", "sexto", "sexta"),
            setOf("7", "sete", "setimo", "setima"),
            setOf("8", "oito", "oitavo", "oitava"),
            setOf("9", "nove", "nono", "nona"),
            setOf("10", "dez", "decimo", "decima")
        )
        numberVariants.forEachIndexed { index, variants ->
            if (index < size && variants.any { variant ->
                if (variant.contains(" ")) value.contains(variant)
                else Regex("\\b${Regex.escape(variant)}\\b").containsMatchIn(value)
            }) return index
        }

        // Alguns reconhecedores retornam "dois" como "2ª"/"2o" ou frases curtas.
        val compact = value.replace(" ", "")
        val digit = Regex("^(?:opcao|numero|alternativa)?(\\d{1,2})(?:a|o|º|ª)?$").matchEntire(compact)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (digit != null && digit in 1..size) return digit - 1
        return null
    }

    private fun resolvePhoneChoice(raw: String): Int? {
        val value = normalizeVoiceChoice(raw)
        if (value.contains("cancelar")) return -1
        if (value.contains("padrao") || value.contains("principal") || value.contains("preferencial")) {
            return pendingPhoneChoices.indexOfFirst { it.isPrimary }.takeIf { it >= 0 } ?: 0
        }
        val index = resolveListChoice(value, pendingPhoneChoices.size)
        if (index != null) return index
        return pendingPhoneChoices.indexOfFirst { phone ->
            val label = normalizeVoiceChoice(phone.label)
            label.isNotBlank() && (value == label || value.contains(label))
        }.takeIf { it >= 0 }
    }

    private fun isYes(raw: String) = normalizeVoiceChoice(raw) in setOf("sim", "confirmar", "pode ligar", "ligar")
    private fun isNo(raw: String) = normalizeVoiceChoice(raw) in setOf("nao", "cancela", "cancelar", "nao ligar")

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
            value == "vivo" || value.contains("linha vivo") || value.contains("whatsapp vivo") || value == "pessoal" || value == "principal" || value == "primario" || value == "primeiro" || value == "primeira" || value == "linha principal" || value == "linha um" || value == "linha 1" || value.contains("perfil pessoal") || value.contains("perfil principal") || value.contains("perfil primeiro") || value.contains("whatsapp principal") || value.contains("whatsapp pessoal")
    }

    private fun isProfileTwo(raw: String): Boolean {
        val value = normalizeProfileChoice(raw)
        return value.matches(Regex("^(perfil )?(2|dois|duas|segundo|segunda|segundo perfil|segunda opção|segunda opcao|perfil segundo|segundo whatsapp|segunda conta|número dois|numero dois|opção dois|opcao dois|opção 2|opcao 2)$")) ||
            value.matches(Regex(".*\\b(2|dois|duas|segundo|segunda)\\b.*")) ||
            value.contains("segundo whatsapp") || value.contains("segunda opção") || value.contains("segunda opcao") ||
            value == "tim" || value.contains("linha tim") || value.contains("whatsapp tim") || value == "secundario" || value == "secundaria" || value == "segundo" || value == "segunda" || value == "linha secundaria" || value == "linha dois" || value == "linha 2" || value == "profissional" ||
            value == "trabalho" || value.contains("perfil profissional") || value.contains("whatsapp profissional") ||
            value.contains("whatsapp comercial") || value.contains("whatsapp trabalho") || value.contains("perfil secundario") || value.contains("perfil segundo") || value.contains("whatsapp secundario")
    }

    private fun handleCommand(raw: String) {
    if (keyguardManager.isKeyguardLocked) {
        updateStatus("Ação bloqueada porque o aparelho está bloqueado.")
        return
    }

    val command = raw.trim()
    val lower = command.lowercase(Locale("pt", "BR"))

    if (awaitingMessageRecipientRetry) {
        if (normalizeVoiceChoice(raw) in setOf("cancelar", "cancela", "desistir")) {
            awaitingMessageRecipientRetry = false
            pendingMessageRecipientName = null
            pendingMessageWhatsAppPackage = null
            updateStatus("Busca cancelada. Nenhuma mensagem foi preparada.")
            speak("Busca cancelada.")
        } else {
            val packageName = pendingMessageWhatsAppPackage ?: "com.whatsapp"
            awaitingMessageRecipientRetry = false
            beginWhatsAppMessage(raw.trim(), packageName)
        }
        return
    }

    if (awaitingMessageContactChoice) {
        val value = normalizeVoiceChoice(raw)
        val listChoice = resolveListChoice(raw, messageContactChoices.size)
        val selectedByName = if (listChoice == null) messageContactChoices.indexOfFirst {
            val candidate = normalizeVoiceChoice(it.name)
            candidate == value || (value.length >= 5 && candidate == value)
        } else -1
        val selectedIndex = if (listChoice != null) listChoice else selectedByName
        when {
            selectedIndex == -1 -> {
                awaitingMessageContactChoice = false
                messageContactChoices = emptyList()
                pendingMessageRecipientName = null
                pendingMessageWhatsAppPackage = null
                updateStatus("Escolha do destinatário cancelada. Nenhuma mensagem foi preparada.")
                speak("Escolha cancelada.")
            }
            selectedIndex != null && selectedIndex in messageContactChoices.indices -> {
                val contact = messageContactChoices[selectedIndex]
                awaitingMessageContactChoice = false
                messageContactChoices = emptyList()
                prepareMessageForContact(contact)
            }
            else -> {
                updateStatus("Não identifiquei o destinatário. Diga o número da opção ou o nome completo como aparece na lista.")
                speakAndListenForProfile("Não identifiquei. Diga o número da opção ou o nome completo do contato.")
            }
        }
        return
    }

    if (awaitingMessageDictation) {
        val spoken = raw.trim()
        val normalized = normalizeVoiceChoice(spoken)
        if (normalized in setOf("cancelar", "cancela", "desistir", "cancelar mensagem")) {
            awaitingMessageDictation = false
            pendingMessageDraft.clear()
            pendingMessageRecipientName = null
            pendingMessageRecipientNumber = null
            pendingMessageWhatsAppPackage = null
            updateStatus("Preparação da mensagem cancelada.")
            speak("Mensagem cancelada.")
            return
        }
        val finishPhrases = listOf("finalizar mensagem", "concluir mensagem", "terminar mensagem", "encerrar ditado", "finalizar ditado", "pronto", "terminei")
        val finishPhrase = finishPhrases.firstOrNull { normalized.endsWith(it) }
        val segment = if (finishPhrase != null) spoken.dropLast(finishPhrase.length).trim() else spoken
        if (segment.isNotBlank()) {
            if (pendingMessageDraft.isNotEmpty()) pendingMessageDraft.append(" ")
            pendingMessageDraft.append(segment)
        }
        if (finishPhrase != null) {
            val completeMessage = pendingMessageDraft.toString().trim()
            awaitingMessageDictation = false
            if (completeMessage.isBlank()) {
                updateStatus("Ainda não há texto para revisar. Continue ditando ou diga cancelar.")
                awaitingMessageDictation = true
                startListening()
            } else {
                showWhatsAppMessageReview(
                    pendingMessageRecipientName.orEmpty(),
                    pendingMessageRecipientNumber.orEmpty(),
                    pendingMessageWhatsAppPackage ?: "com.whatsapp",
                    completeMessage
                )
            }
        } else {
            updateStatus("Texto acumulado: ${pendingMessageDraft}. Continue ditando. Para revisar, diga ‘finalizar mensagem’. Para apagar, diga ‘cancelar’. ")
            // Cada pausa encerra apenas um trecho de reconhecimento; o texto acumulado
            // permanece e uma nova janela de ditado é iniciada automaticamente.
            window.decorView.postDelayed({ if (awaitingMessageDictation) startListening() }, 300L)
        }
        return
    }

    if (awaitingWhatsAppProfileChoice) {
        val choice = normalizeProfileChoice(lower)
        when {
            isProfileOne(choice) -> {
                awaitingWhatsAppProfileChoice = false
                whatsappProfileChoiceRetries = 0
                val pendingRecipient = pendingMessageRecipientName
                if (!pendingRecipient.isNullOrBlank()) {
                    val prefs = getSharedPreferences("aurora_settings", MODE_PRIVATE)
                    val packageName = prefs.getString("profile_1_package", null)
                        ?.takeIf { it == "com.whatsapp" || it == "com.whatsapp.w4b" }
                        ?: prefs.getString("whatsapp_package", "com.whatsapp") ?: "com.whatsapp"
                    pendingMessageWhatsAppPackage = packageName
                    updateStatus("Perfil 1 selecionado para $pendingRecipient.")
                    beginWhatsAppMessage(pendingRecipient, packageName)
                } else {
                    updateStatus("Ok, perfil 1.")
                    speak("Ok, perfil um.")
                    openWhatsApp()
                }
            }
            isProfileTwo(choice) -> {
                awaitingWhatsAppProfileChoice = false
                whatsappProfileChoiceRetries = 0
                val pendingRecipient = pendingMessageRecipientName
                if (!pendingRecipient.isNullOrBlank()) {
                    val prefs = getSharedPreferences("aurora_settings", MODE_PRIVATE)
                    if (!prefs.getBoolean("profile_2_enabled", false)) {
                        updateStatus("O perfil 2 está desativado. Ative-o nas configurações.")
                        speak("O perfil dois está desativado.")
                        return
                    }
                    val packageName = prefs.getString("profile_2_package", "com.whatsapp") ?: "com.whatsapp"
                    pendingMessageWhatsAppPackage = packageName
                    updateStatus("Perfil 2 selecionado para $pendingRecipient.")
                    beginWhatsAppMessage(pendingRecipient, packageName)
                } else {
                    updateStatus("Ok, perfil 2.")
                    speak("Ok, perfil dois.")
                    openWhatsAppProfile2()
                }
            }
            else -> {
                // Não encerrar a seleção após uma transcrição ambígua: a opção 1
                // costuma ser transcrita de forma variável pelo reconhecedor.
                whatsappProfileChoiceRetries++
                updateStatus("Não identifiquei a opção. Para o perfil 1, diga Vivo, principal ou primeiro. Para o perfil 2, diga Tim, secundário ou segundo.")
                if (whatsappProfileChoiceRetries >= 3) {
                    whatsappProfileChoiceRetries = 0
                    speakAndListenForProfile("Diga Vivo, principal ou primeiro para o perfil 1; Tim, secundário ou segundo para o perfil 2.")
                } else {
                    speakAndListenForProfile("Não entendi. Diga Vivo, principal ou primeiro; ou Tim, secundário ou segundo.")
                }
            }
        }
        return
    }

    if (awaitingCallChoice) {
        val choice = resolveListChoice(raw, callChoices.size) ?: -99
        when {
            choice == -1 -> {
                awaitingCallChoice = false
                callChoices = emptyList()
                updateStatus("Seleção cancelada.")
                speak("Seleção cancelada.")
            }
            choice in callChoices.indices -> {
                awaitingCallChoice = false
                offerPhoneChoices(callChoices[choice])
            }
            else -> speakAndListenForProfile("Não identifiquei o contato. Diga o número da opção ou o nome completo.")
        }
        return
    }

    if (awaitingPhoneChoice) {
        val selectedIndex = resolvePhoneChoice(raw) ?: -99
        when {
            selectedIndex == -1 -> {
                awaitingPhoneChoice = false
                pendingPhoneChoices = emptyList()
                updateStatus("Escolha do telefone cancelada.")
                speak("Escolha do telefone cancelada.")
            }
            selectedIndex in pendingPhoneChoices.indices -> {
                val selected = pendingPhoneChoices[selectedIndex]
                awaitingPhoneChoice = false
                pendingPhoneChoices = emptyList()
                pendingCallNumber = selected.number
                awaitingCallConfirmation = true
                val contact = pendingCallName ?: "contato"
                updateStatus("Telefone selecionado para $contact: ${selected.label}. Deseja ligar? Responda sim ou não.")
                speakAndListenForProfile("Você escolheu o telefone ${selected.label} de $contact. Deseja ligar? Diga sim ou não.")
            }
            else -> speakAndListenForProfile("Não identifiquei o telefone. Diga padrão, primeiro, segundo ou o tipo do número.")
        }
        return
    }

    if (awaitingCallConfirmation) {
        awaitingCallConfirmation = false
        if (isYes(raw)) {
            val number = pendingCallNumber
            val name = pendingCallName ?: "contato"
            if (number.isNullOrBlank()) { updateStatus("Não há número pendente."); return }
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
                updateStatus("Discador aberto para $name. Confira o número e confirme a chamada manualmente.")
                speak("Abri o discador para $name. Confirme a chamada no aparelho.")
            } catch (_: Exception) {
                updateStatus("Não foi possível abrir o discador. Nenhuma chamada foi realizada.")
            }
            pendingCallNumber = null
            pendingCallName = null
        } else {
            pendingCallNumber = null
            pendingCallName = null
            updateStatus("Ligação cancelada.")
            speak("Ligação cancelada.")
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
    val messageVerbs = listOf("enviar mensagem", "envie mensagem", "manda mensagem", "mandar mensagem", "mande mensagem", "enviar um whatsapp", "mandar um whatsapp", "escrever para", "falar com")
    val asksMessage = lower.startsWith("preparar mensagem:") ||
        lower.startsWith("corrigir mensagem:") ||
        (lower.contains("mensagem") && lower.contains("para ") &&
            (messageVerbs.any { lower.contains(it) } || lower.contains("whatsapp") || lower.startsWith("mensagem para "))) ||
        (lower.contains("whatsapp") && lower.contains("para ") &&
            listOf("enviar", "envie", "manda", "mandar", "mande", "escreva", "preparar").any { lower.contains(it) })
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

        asksMessage && !lower.startsWith("preparar mensagem:") && !lower.startsWith("corrigir mensagem:") -> {
            var recipient = command.substringAfter("para", "").trim()
            recipient = recipient
                .replace(Regex("(?i)\\s+(no|na|pelo|pela)\\s+perfil\\s+(1|um|primeiro|2|dois|segundo)\\s*$"), "")
                .replace(Regex("(?i)\\s+(perfil\\s*)?(1|um|primeiro|2|dois|segundo)\\s*$"), "")
                .replace(Regex("(?i)\\s+(por favor|agora|no whatsapp|pelo whatsapp)$"), "")
                .trim(' ', '.', ',', '!', '?')
            val requestedProfile2 = isProfileTwo(lower)
            val requestedProfile1 = isProfileOne(lower)
            val prefs = getSharedPreferences("aurora_settings", MODE_PRIVATE)
            val profileIndex = when {
                requestedProfile2 -> 1
                requestedProfile1 -> 0
                prefs.getBoolean("profile_1_enabled", true) && prefs.getBoolean("profile_2_enabled", false) -> -1
                prefs.getBoolean("profile_2_enabled", false) && !prefs.getBoolean("profile_1_enabled", true) -> 1
                else -> 0
            }
            if (recipient.isBlank()) {
                updateStatus("Diga o nome do contato para a mensagem.")
                speak("Para quem você deseja enviar a mensagem?")
                return
            }
            if (profileIndex == -1) {
                pendingMessageRecipientName = recipient
                awaitingWhatsAppProfileChoice = true
                whatsappProfileChoiceRetries = 0
                updateStatus("Destinatário: $recipient. Escolha o perfil 1 ou perfil 2.")
                speakAndListenForProfile("Para enviar a mensagem a $recipient, diga perfil um ou perfil dois.")
                return
            }
            if (profileIndex == 1 && !prefs.getBoolean("profile_2_enabled", false)) {
                updateStatus("O perfil 2 está desativado. Ative-o nas configurações.")
                speak("O perfil dois está desativado.")
                return
            }
            if (profileIndex == 0 && !prefs.getBoolean("profile_1_enabled", true)) {
                updateStatus("O perfil 1 está desativado. Ative-o nas configurações.")
                speak("O perfil um está desativado.")
                return
            }
            val packageName = prefs.getString("profile_${profileIndex + 1}_package", null)
                ?.takeIf { it == "com.whatsapp" || it == "com.whatsapp.w4b" }
                ?: if (profileIndex == 0) prefs.getString("whatsapp_package", "com.whatsapp") ?: "com.whatsapp" else "com.whatsapp"
            beginWhatsAppMessage(recipient, packageName)
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

        lower.contains("abrir whatsapp") || lower == "whatsapp" || lower.contains("qual whatsapp") || lower.contains("qual perfil do whatsapp") || lower.contains("qual whatsapp abrir") -> {
            val prefs = getSharedPreferences("aurora_settings", MODE_PRIVATE)
            val profile1Enabled = prefs.getBoolean("profile_1_enabled", true)
            val profile2Enabled = prefs.getBoolean("profile_2_enabled", false)
            if (profile1Enabled && profile2Enabled) {
                awaitingWhatsAppProfileChoice = true
                whatsappProfileChoiceRetries = 0
                updateStatus("Aguardando escolha: perfil 1 (Vivo, principal, primeiro) ou perfil 2 (Tim, secundário, segundo).")
                speakAndListenForProfile("Qual WhatsApp deseja abrir? Diga perfil um, Vivo, principal ou primeiro; ou perfil dois, Tim, secundário ou segundo.")
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

    private fun hasPendingVoiceChoice(): Boolean =
        awaitingWhatsAppProfileChoice || awaitingCallConfirmation || awaitingCallChoice || awaitingPhoneChoice || awaitingMessageContactChoice || awaitingMessageRecipientRetry || awaitingMessageDictation

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
                    if (hasPendingVoiceChoice()) {
                        window.decorView.postDelayed({ if (hasPendingVoiceChoice()) startListening() }, 350L)
                    }
                }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    // O microfone é reativado automaticamente assim que a fala termina.
                    if (hasPendingVoiceChoice()) {
                        window.decorView.postDelayed({ if (hasPendingVoiceChoice()) startListening() }, 250L)
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
            // Pesquisa todos os contatos pelo trecho do nome, sem limitar a quantidade.
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
            )
            val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%${contactName.trim()}%")
            val found = linkedMapOf<String, ContactChoice>()

            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                selection,
                args,
                ContactsContract.Contacts.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneIndex = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(idIndex).orEmpty()
                    val name = cursor.getString(nameIndex).orEmpty().trim()
                    if (contactId.isBlank() || name.isBlank() || cursor.getInt(hasPhoneIndex) <= 0) continue

                    val phones = mutableListOf<ContactPhone>()
                    contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.LABEL,
                            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
                        ),
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC, " +
                            ContactsContract.CommonDataKinds.Phone._ID + " ASC"
                    )?.use { phoneCursor ->
                        while (phoneCursor.moveToNext()) {
                            val number = phoneCursor.getString(0).orEmpty().trim()
                            if (number.isBlank()) continue
                            val type = phoneCursor.getInt(1)
                            val customLabel = phoneCursor.getString(2)
                            val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                resources, type, customLabel
                            ).toString().ifBlank { "Telefone" }
                            val isPrimary = phoneCursor.getInt(3) != 0
                            if (phones.none { it.number == number }) {
                                phones.add(ContactPhone(number, label, isPrimary))
                            }
                        }
                    }

                    if (phones.isNotEmpty()) {
                        val key = name.lowercase(Locale("pt", "BR")).replace(Regex("\\s+"), " ").trim()
                        if (!found.containsKey(key)) found[key] = ContactChoice(name, phones)
                    }
                }
            }

            val matches = found.values.toList()
            when (matches.size) {
                0 -> {
                    updateStatus("Não encontrei contatos com telefone cujo nome contenha '$contactName'.")
                    speak("Não encontrei contatos com esse nome.")
                }
                1 -> offerPhoneChoices(matches.first())
                else -> {
                    callChoices = matches
                    awaitingCallChoice = true
                    val colors = listOf("azul", "amarelo", "verde", "vermelho", "roxo", "laranja", "rosa", "marrom", "cinza", "preto")
                    val visibleChoices = callChoices.mapIndexed { index, item ->
                        val color = colors.getOrNull(index)
                        if (color != null) "${index + 1}) ${item.name} ($color)"
                        else "${index + 1}) ${item.name}"
                    }.joinToString("\n")
                    updateStatus(
                        "ENCONTREI ${callChoices.size} CONTATOS COM '$contactName'. " +
                            "Confira a lista e escolha pelo número ou nome completo:\n" + visibleChoices
                    )
                    speakAndListenForProfile(
                        "Encontrei ${callChoices.size} contatos com $contactName. " +
                            "A lista completa está na tela. Escolha dizendo o número ou o nome completo."
                    )
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

    private fun offerPhoneChoices(contact: ContactChoice) {
        pendingCallName = contact.name
        val phones = contact.phones
        if (phones.size == 1) {
            pendingCallNumber = phones.first().number
            awaitingCallConfirmation = true
            updateStatus("Encontrei ${contact.name}. Deseja ligar? Responda sim ou não.")
            speakAndListenForProfile("Encontrei ${contact.name}. Deseja ligar? Diga sim ou não.")
            return
        }

        pendingPhoneChoices = phones
        awaitingPhoneChoice = true
        val defaultIndex = phones.indexOfFirst { it.isPrimary }.takeIf { it >= 0 } ?: 0
        val lines = phones.mapIndexed { index, phone ->
            val defaultText = if (index == defaultIndex) " — padrão" else ""
            "${index + 1}) ${phone.label}$defaultText"
        }.joinToString("\n")
        updateStatus(
            "${contact.name} possui ${phones.size} números. Escolha qual deseja usar; os números não serão exibidos:\n$lines"
        )
        speakAndListenForProfile(
            "${contact.name} possui ${phones.size} telefones. " +
                "Diga padrão para o número principal, ou escolha pelo número da opção ou tipo do telefone."
        )
    }


    private fun beginWhatsAppMessage(recipient: String, packageName: String) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingMessageRecipientName = recipient
            pendingMessageWhatsAppPackage = packageName
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1004)
            updateStatus("Preciso de acesso aos contatos para localizar $recipient.")
            speak("Preciso de permissão para consultar seus contatos.")
            return
        }
        try {
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
            )
            val found = linkedMapOf<String, ContactChoice>()
            // Consulta a agenda sem depender de LIKE textual, que pode falhar
            // com acentos, ordem de sobrenomes e nomes compostos.
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, projection,
                null, null,
                ContactsContract.Contacts.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                val requested = normalizeVoiceChoice(recipient)
                val requestedTokens = requested.split(" ").filter { it.length >= 2 }.toSet()
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0).orEmpty()
                    val name = cursor.getString(1).orEmpty().trim()
                    if (id.isBlank() || name.isBlank() || cursor.getInt(2) <= 0) continue
                    val normalizedName = normalizeVoiceChoice(name)
                    val nameTokens = normalizedName.split(" ").filter { it.length >= 2 }.toSet()
                    val exact = normalizedName == requested
                    val contains = normalizedName.contains(requested) || requested.contains(normalizedName)
                    val tokenMatch = requestedTokens.isNotEmpty() && requestedTokens.all { it in nameTokens }
                    if (!exact && !contains && !tokenMatch) continue
                    val phones = mutableListOf<ContactPhone>()
                    contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.LABEL,
                            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
                        ),
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(id),
                        ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC, " +
                            ContactsContract.CommonDataKinds.Phone._ID + " ASC"
                    )?.use { phoneCursor ->
                        while (phoneCursor.moveToNext()) {
                            val number = phoneCursor.getString(0).orEmpty().trim()
                            if (number.isBlank() || phones.any { it.number == number }) continue
                            val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                resources, phoneCursor.getInt(1), phoneCursor.getString(2)
                            ).toString().ifBlank { "Telefone" }
                            phones.add(ContactPhone(number, label, phoneCursor.getInt(3) != 0))
                        }
                    }
                    if (phones.isNotEmpty() && !found.containsKey(normalizedName)) {
                        found[normalizedName] = ContactChoice(name, phones)
                    }
                }
            }
            val matches = found.values.toList()
            when (matches.size) {
                0 -> {
                    pendingMessageRecipientName = recipient
                    pendingMessageWhatsAppPackage = packageName
                    awaitingMessageRecipientRetry = true
                    updateStatus("Não localizei '$recipient'. Diga novamente o nome, tente apenas o primeiro nome ou sobrenome, ou diga cancelar.")
                    speakAndListenForProfile("Não localizei esse contato. Diga novamente o nome, tente primeiro nome ou sobrenome, ou diga cancelar.")
                }
                1 -> prepareMessageForContact(matches.first())
                else -> {
                    pendingMessageRecipientName = recipient
                    pendingMessageWhatsAppPackage = packageName
                    messageContactChoices = matches
                    awaitingMessageContactChoice = true
                    val labels = matches.mapIndexed { index, item -> "${index + 1}. ${item.name}" }
                    updateStatus(
                        "Encontrei ${matches.size} contatos relacionados a '$recipient'. " +
                            "Escolha o destinatário correto tocando no nome ou dizendo o número/nome completo:\\n" +
                            labels.joinToString("\n")
                    )
                    AlertDialog.Builder(this)
                        .setTitle("Qual contato você deseja?")
                        .setItems(labels.toTypedArray()) { _, which ->
                            if (which in messageContactChoices.indices) {
                                val selected = messageContactChoices[which]
                                awaitingMessageContactChoice = false
                                messageContactChoices = emptyList()
                                prepareMessageForContact(selected)
                            }
                        }
                        .setNegativeButton("Cancelar") { _, _ ->
                            awaitingMessageContactChoice = false
                            messageContactChoices = emptyList()
                            pendingMessageRecipientName = null
                            pendingMessageWhatsAppPackage = null
                            updateStatus("Seleção cancelada. Nenhuma mensagem foi preparada.")
                        }
                        .show()
                    speakAndListenForProfile(
                        "Encontrei ${matches.size} contatos relacionados a $recipient. " +
                            "Escolha na tela ou diga o número da opção ou o nome completo."
                    )
                }
            }
        } catch (_: SecurityException) {
            updateStatus("O Android não autorizou a leitura dos contatos. Nenhuma mensagem foi preparada.")
            speak("Não tenho autorização para consultar os contatos.")
        } catch (_: Exception) {
            updateStatus("Não consegui consultar a agenda. Nenhuma mensagem foi preparada.")
            speak("Não consegui consultar seus contatos.")
        }
    }

    private fun prepareMessageForContact(contact: ContactChoice) {
        val selectedPhone = contact.phones.firstOrNull { it.isPrimary } ?: contact.phones.firstOrNull()
        if (selectedPhone == null) {
            updateStatus("O contato não possui telefone disponível. Nenhuma conversa foi aberta.")
            speak("Esse contato não possui telefone disponível.")
            return
        }
        pendingMessageRecipientName = contact.name
        pendingMessageRecipientNumber = selectedPhone.number
        pendingMessageDraft.clear()
        awaitingMessageDictation = true
        updateStatus("Destinatário selecionado: ${contact.name}. Agora dite a mensagem. Você pode fazer pausas; diga ‘finalizar mensagem’ quando terminar.")
        speakAndListenForProfile(
            "Destinatário selecionado: ${contact.name}. " +
                "Vou usar o telefone ${selectedPhone.label}. Dite sua mensagem. Pode fazer pausas e continuar; quando terminar, diga finalizar mensagem."
        )
    }

    // Ajuste conservador inteiramente local: não envia texto para a internet e não
    // troca palavras, nomes, números ou sentido. É formatação, não correção gramatical completa.
    private fun refineDictatedTextLocally(input: String): String {
        var value = input.replace(Regex("\\s+"), " ").trim()
        if (value.isBlank()) return value
        value = value.replace(Regex("\\s+([,.;!?])"), "$1")
            .replace(Regex("([,.;!?])(?=\\S)"), "$1 ")
        val first = value.firstOrNull()
        if (first != null && first.isLetter()) {
            value = first.uppercaseChar() + value.substring(1)
        }
        if (value.lastOrNull() !in listOf('.', '!', '?')) value += "."
        return value
    }

    private fun showWhatsAppMessageReview(name: String, rawNumber: String, packageName: String, message: String) {
        val refined = refineDictatedTextLocally(message)
        val preview = android.widget.EditText(this).apply {
            setText(refined)
            minLines = 3
            textSize = 17f
        }
        AlertDialog.Builder(this)
            .setTitle("Mensagem para $name")
            .setMessage("Sugestão de organização local (sem internet): espaços, início de frase e pontuação final. Confira nomes, acentos e sentido; a AURORA não envia automaticamente.")
            .setView(preview)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Abrir conversa") { _, _ ->
                val digits = rawNumber.filter { it.isDigit() }
                val international = if (digits.startsWith("55")) digits else "55$digits"
                val url = Uri.parse("https://wa.me/$international?text=" + Uri.encode(preview.text.toString()))
                try {
                    val intent = Intent(Intent.ACTION_VIEW, url).setPackage(packageName)
                    startActivity(intent)
                    updateStatus("Conversa de $name aberta com o texto preenchido. Revise e toque em Enviar no WhatsApp.")
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                        updateStatus("Conversa preparada. Confira o aplicativo aberto e toque em Enviar; nada foi enviado automaticamente.")
                    } catch (_: Exception) {
                        updateStatus("Não foi possível abrir a conversa do WhatsApp.")
                    }
                }
            }
            .show()
    }

    private fun showMessageReview(message: String) {
        val refined = refineDictatedTextLocally(message)
        val preview = android.widget.EditText(this).apply {
            setText(refined)
            minLines = 3
            textSize = 17f
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Revise sua mensagem")
            .setMessage("Sugestão local sem envio de dados. A revisão automática avançada de gramática ainda não está integrada; confira o sentido antes de continuar.")
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
        } else if (requestCode == 1004) {
            val recipient = pendingMessageRecipientName
            val packageName = pendingMessageWhatsAppPackage ?: "com.whatsapp"
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && !recipient.isNullOrBlank()) {
                beginWhatsAppMessage(recipient, packageName)
            } else {
                pendingMessageRecipientName = null
                pendingMessageWhatsAppPackage = null
                updateStatus("Permissão de contatos negada. Nenhuma mensagem foi preparada.")
                speak("Sem permissão para consultar os contatos, não posso preparar a mensagem.")
            }
        }
    }
}
