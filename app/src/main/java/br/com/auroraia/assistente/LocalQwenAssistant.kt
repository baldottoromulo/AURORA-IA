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
        const val MODEL_SHA256 = "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"
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
                if (partial.exists()) partial.delete()

                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "AURORA-IA-Android")
                }

                try {
                    connection.connect()
                    val response = connection.responseCode
                    if (response !in 200..299) error("Servidor do modelo respondeu HTTP $response.")
                    val total = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            var lastPercent = -1
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 99) else -1
                                if (percent != lastPercent && percent >= 0) {
                                    lastPercent = percent
                                    val detail = "${downloaded / (1024 * 1024)} MB de ${total / (1024 * 1024)} MB"
                                    mainHandler.post { onProgress(percent, detail) }
                                }
                            }
                            output.fd.sync()
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                if (partial.length() < MODEL_BYTES_MIN) {
                    partial.delete()
                    error("O download terminou incompleto. Tente novamente com uma conexão estável.")
                }
                val digest = sha256(partial)
                if (!digest.equals(MODEL_SHA256, ignoreCase = true)) {
                    partial.delete()
                    error("A verificação de integridade do modelo falhou. O arquivo foi descartado.")
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
                    conversation.sendMessage(prompt).text.trim()
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
