package br.com.auroraia.assistente

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
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
            try {
                if (isModelInstalled() && sha256(modelFile) == MODEL_SHA256) {
                    mainHandler.post { onProgress(100, "Modelo já instalado e verificado.") }
                    mainHandler.post { onComplete(Result.success(modelFile)) }
                    return@execute
                }

                val parent = modelFile.parentFile ?: error("Diretório do modelo indisponível.")
                if (!parent.exists() && !parent.mkdirs()) error("Não foi possível criar a pasta da IA.")
                val available = StatFs(parent.absolutePath).availableBytes
                val required = MODEL_BYTES_MIN + 350L * 1024L * 1024L
                if (available < required) {
                    error("Espaço insuficiente. Libere pelo menos 1,3 GB para baixar e validar o modelo.")
                }

                val partial = File(parent, "$MODEL_FILE.part")
                var verified = false
                var lastFailure = "Falha desconhecida no download."

                // Each attempt starts a fresh request so Hugging Face can issue a new Xet URL.
                for (attempt in 1..3) {
                    if (partial.exists() && !partial.delete()) {
                        error("Não foi possível limpar o arquivo temporário para uma nova tentativa.")
                    }
                    try {
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
                            if (response !in 200..299) {
                                error("Servidor do modelo respondeu HTTP $response.")
                            }
                            val contentType = connection.contentType.orEmpty().lowercase()
                            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                                error("O servidor retornou uma página ou resposta de erro, não o arquivo do modelo.")
                            }
                            val total = connection.contentLengthLong
                            if (total > 0L && total != MODEL_EXPECTED_BYTES) {
                                error("Tamanho informado pelo servidor diferente do modelo oficial ($total bytes).")
                            }

                            connection.inputStream.use { input ->
                                FileOutputStream(partial).use { output ->
                                    val buffer = ByteArray(256 * 1024)
                                    var downloaded = 0L
                                    var lastPercent = -1
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                        downloaded += count
                                        val percent = ((downloaded * 100) / MODEL_EXPECTED_BYTES)
                                            .toInt().coerceIn(0, 99)
                                        if (percent != lastPercent) {
                                            lastPercent = percent
                                            val detail = "${downloaded / (1024 * 1024)} MB de ${MODEL_EXPECTED_BYTES / (1024 * 1024)} MB (tentativa $attempt/3)"
                                            mainHandler.post { onProgress(percent, detail) }
                                        }
                                    }
                                    output.fd.sync()
                                }
                            }
                        } finally {
                            connection.disconnect()
                        }

                        val actualBytes = partial.length()
                        if (actualBytes != MODEL_EXPECTED_BYTES) {
                            error("Download incompleto: recebidos $actualBytes bytes; esperado $MODEL_EXPECTED_BYTES.")
                        }

                        val digest = sha256(partial)
                        if (!digest.equals(MODEL_SHA256, ignoreCase = true)) {
                            error("SHA-256 divergente. Recebido: ${digest.take(16)}…; esperado: ${MODEL_SHA256.take(16)}….")
                        }

                        verified = true
                        break
                    } catch (failure: Throwable) {
                        lastFailure = failure.message ?: failure.javaClass.simpleName
                        if (partial.exists()) partial.delete()
                        if (attempt < 3) {
                            mainHandler.post {
                                onProgress(0, "Falha na tentativa $attempt/3. Preparando novo download…")
                            }
                            Thread.sleep(1500L * attempt)
                        }
                    }
                }

                if (!verified) {
                    error("Não foi possível validar o modelo após 3 tentativas. $lastFailure Verifique a conexão e tente novamente.")
                }
                if (modelFile.exists() && !modelFile.delete()) error("Não foi possível substituir o modelo antigo.")
                if (!partial.renameTo(modelFile)) error("Não foi possível finalizar a instalação do modelo.")
                mainHandler.post { onProgress(100, "Modelo baixado e verificado com sucesso.") }
                mainHandler.post { onComplete(Result.success(modelFile)) }
            } catch (error: Throwable) {
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

                val config = ConversationConfig(
                    systemInstruction = Contents.of(
                        "Revise mensagens em português brasileiro com máxima fidelidade ao texto original. Retorne apenas o texto revisado."
                    ),
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
