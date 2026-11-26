package br.com.auroraia.assistente

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.Environment
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Qwen3 1.7B INT4 is downloaded on demand and runs entirely on-device.
 * A single executor owns the LiteRT-LM Engine and all inference operations.
 */
class LocalQwenAssistant(context: Context) {
    companion object {
        const val MODEL_FILE = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"
        const val MODEL_BYTES_MIN = 900L * 1024L * 1024L
        const val MODEL_EXPECTED_BYTES = 977184032L
        const val MODEL_SHA256 = "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b"
        // Revision pinned to the original Xet object whose SHA-256 and size are published by Hugging Face.
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/d9b8a9126e5ac18591306eacd4311ba43b92421e/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm?download=true"
    }

    private val appContext = context.applicationContext
    private val modelFile = File(File(appContext.filesDir, "aurora_models"), MODEL_FILE)
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: Engine? = null

    fun isModelInstalled(): Boolean = modelFile.isFile && modelFile.length() >= MODEL_BYTES_MIN

    fun modelPath(): String = modelFile.absolutePath

    fun downloadModel(
        onProgress: (Int, String) -> Unit,
        onComplete: (Result<File>) -> Unit
    ) {
        worker.execute {
            var publicDownloadUri: Uri? = null
            try {
                if (isModelInstalled() && sha256(modelFile) == MODEL_SHA256) {
                    mainHandler.post { onProgress(100, "Modelo já instalado e verificado.") }
                    mainHandler.post { onComplete(Result.success(modelFile)) }
                    return@execute
                }

                val parent = modelFile.parentFile ?: error("Diretório interno do modelo indisponível.")
                if (!parent.exists() && !parent.mkdirs()) error("Não foi possível criar a pasta interna da IA.")
                val storagePath = appContext.getExternalFilesDir(null)?.absolutePath ?: parent.absolutePath
                val available = StatFs(storagePath).availableBytes
                val required = MODEL_EXPECTED_BYTES + 350L * 1024L * 1024L
                if (available < required) {
                    error("Espaço insuficiente. Libere pelo menos 1,3 GB no armazenamento do aparelho.")
                }

                val partial = File(parent, "$MODEL_FILE.part")
                var verified = false
                var lastFailure = "Falha desconhecida no download."

                // Salva em Downloads/AURORA IA, visível no app Meus Arquivos.
                // Após SHA-256, copia para a área privada usada pelo LiteRT-LM.
                for (attempt in 1..3) {
                    publicDownloadUri?.let { appContext.contentResolver.delete(it, null, null) }
                    publicDownloadUri = null
                    if (partial.exists()) partial.delete()

                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, MODEL_FILE)
                            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AURORA IA")
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        publicDownloadUri = appContext.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                        ) ?: error("O Android não permitiu criar o arquivo na pasta Downloads.")

                        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 30000
                            readTimeout = 120000
                            instanceFollowRedirects = true
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", "AURORA-IA-Android")
                            setRequestProperty("Accept", "application/octet-stream")
                            setRequestProperty("Accept-Encoding", "identity")
                            setRequestProperty("Cache-Control", "no-cache")
                        }

                        try {
                            connection.connect()
                            val response = connection.responseCode
                            if (response !in 200..299) error("Servidor do modelo respondeu HTTP $response.")
                            val contentType = connection.contentType.orEmpty().lowercase()
                            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                                error("O servidor retornou uma página ou resposta de erro, não o arquivo do modelo.")
                            }
                            val total = connection.contentLengthLong
                            if (total > 0L && total != MODEL_EXPECTED_BYTES) {
                                error("Tamanho informado pelo servidor diferente do modelo oficial ($total bytes).")
                            }

                            val output = appContext.contentResolver.openOutputStream(publicDownloadUri!!, "w")
                                ?: error("Não foi possível gravar o modelo na pasta Downloads.")
                            connection.inputStream.use { input ->
                                output.use { stream ->
                                    val buffer = ByteArray(256 * 1024)
                                    var downloaded = 0L
                                    var lastPercent = -1
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        stream.write(buffer, 0, count)
                                        downloaded += count
                                        val percent = ((downloaded * 100) / MODEL_EXPECTED_BYTES)
                                            .toInt().coerceIn(0, 99)
                                        if (percent != lastPercent) {
                                            lastPercent = percent
                                            val detail = "${downloaded / (1024 * 1024)} MB de ${MODEL_EXPECTED_BYTES / (1024 * 1024)} MB (tentativa $attempt/3) — Downloads/AURORA IA"
                                            mainHandler.post { onProgress(percent, detail) }
                                        }
                                    }
                                    stream.flush()
                                }
                            }
                        } finally {
                            connection.disconnect()
                        }

                        val downloadedFile = appContext.contentResolver.openInputStream(publicDownloadUri!!)
                            ?: error("Não foi possível reabrir o arquivo salvo em Downloads para verificar.")
                        val digest = MessageDigest.getInstance("SHA-256")
                        var actualBytes = 0L
                        downloadedFile.buffered().use { input ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                actualBytes += count
                                digest.update(buffer, 0, count)
                            }
                        }
                        if (actualBytes != MODEL_EXPECTED_BYTES) {
                            error("Download incompleto em Downloads: recebidos $actualBytes bytes; esperado $MODEL_EXPECTED_BYTES.")
                        }
                        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actualHash.equals(MODEL_SHA256, ignoreCase = true)) {
                            error("SHA-256 divergente. O arquivo em Downloads não passou na verificação de integridade.")
                        }

                        val source = appContext.contentResolver.openInputStream(publicDownloadUri!!)
                            ?: error("Não foi possível ler o modelo validado em Downloads.")
                        source.use { input ->
                            FileOutputStream(partial).use { output ->
                                input.copyTo(output, 1024 * 1024)
                                output.fd.sync()
                            }
                        }
                        if (partial.length() != MODEL_EXPECTED_BYTES || sha256(partial) != MODEL_SHA256) {
                            error("A cópia interna do modelo não passou na verificação de integridade.")
                        }

                        verified = true
                        break
                    } catch (failure: Throwable) {
                        lastFailure = failure.message ?: failure.javaClass.simpleName
                        publicDownloadUri?.let {
                            try { appContext.contentResolver.delete(it, null, null) } catch (_: Exception) {}
                        }
                        publicDownloadUri = null
                        if (partial.exists()) partial.delete()
                        if (attempt < 3) {
                            mainHandler.post {
                                onProgress(0, "Falha na tentativa $attempt/3. Preparando novo download…")
                            }
                            Thread.sleep(1500L * attempt)
                        }
                    }
                }

                if (!verified) error("Não foi possível baixar e validar o modelo após 3 tentativas. $lastFailure")
                if (modelFile.exists() && !modelFile.delete()) error("Não foi possível substituir o modelo antigo.")
                if (!partial.renameTo(modelFile)) error("Não foi possível finalizar a instalação interna do modelo.")

                val publish = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                if (publicDownloadUri == null ||
                    appContext.contentResolver.update(publicDownloadUri!!, publish, null, null) <= 0
                ) {
                    error("O modelo foi validado, mas o Android não conseguiu concluir sua publicação na pasta Downloads.")
                }

                mainHandler.post { onProgress(100, "Modelo salvo em Downloads/AURORA IA e instalado na AURORA.") }
                mainHandler.post { onComplete(Result.success(modelFile)) }
            } catch (error: Throwable) {
                publicDownloadUri?.let {
                    try { appContext.contentResolver.delete(it, null, null) } catch (_: Exception) {}
                }
                mainHandler.post { onComplete(Result.failure(error)) }
            }
        }
    }

    fun review(
        originalText: String,
        onComplete: (Result<String>) -> Unit
    ) {
        worker.execute {
            try {
                if (!isModelInstalled()) {
                    error("A IA local não está instalada ou o arquivo não passou na verificação. Abra Configurações e instale o modelo.")
                }
                val activeEngine = engine ?: Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        cacheDir = appContext.cacheDir.absolutePath
                    )
                ).also {
                    it.initialize()
                    engine = it
                }

                val prompt = """
                    Você é um revisor profissional de mensagens em português brasileiro.
                    Corrija o texto abaixo para que fique natural, claro e correto.
                    Corrija ortografia, acentuação, gramática, concordância, regência e pontuação.
                    Organize frases e parágrafos quando necessário.
                    Preserve rigorosamente a intenção, todos os fatos, nomes próprios, números,
                    datas, locais, pedidos, perguntas, nível de informalidade e conteúdo original.
                    Não invente informação, não resuma, não omita trechos e não mude o sentido.
                    Se uma expressão for ambígua, preserve a formulação original em vez de adivinhar.
                    Retorne somente a mensagem revisada, sem explicações, títulos, aspas ou comentários.

                    MENSAGEM ORIGINAL:
                    <mensagem>
                    $originalText
                    </mensagem>
                """.trimIndent()

                // O template nativo do Qwen3 neste modelo LiteRT-LM não aceita
                // systemInstruction separado: a renderização falha ao concatenar
                // string com sequência (nativeSendMessage / template:14).
                // Todas as instruções já estão incorporadas ao prompt do usuário.
                val config = ConversationConfig(
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    maxOutputToken = 1200
                )
                val revised = activeEngine.createConversation(config).use { conversation ->
                    conversation.sendMessage(prompt).toString().trim()
                }
                val cleaned = revised
                    .replace(Regex("(?s)<think>.*?</think>"), "")
                    .replace(Regex("""^\x60\x60\x60(?:text)?\s*|\s*\x60\x60\x60$"""), "")
                    .trim()
                if (cleaned.isBlank()) error("O modelo não retornou uma revisão utilizável.")
                mainHandler.post { onComplete(Result.success(cleaned)) }
            } catch (error: Throwable) {
                mainHandler.post { onComplete(Result.failure(error)) }
            }
        }
    }

    fun close() {
        worker.execute {
            try {
                engine?.close()
            } catch (_: Exception) {
            } finally {
                engine = null
                worker.shutdown()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
