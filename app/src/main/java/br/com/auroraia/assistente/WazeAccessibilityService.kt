package br.com.auroraia.assistente

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.text.Normalizer
import java.util.Locale

/**
 * Assistente opcional de navegação. Só atua dentro do pacote oficial do Waze
 * e apenas em botões com rótulos conhecidos; nunca usa coordenadas de toque.
 * O usuário precisa ativar manualmente este serviço nas configurações Android.
 */
class WazeAccessibilityService : AccessibilityService() {
    private var lastActionLabel = ""
    private var lastActionAt = 0L

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 250
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        Toast.makeText(this, "AURORA IA: assistência do Waze ativada", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != WAZE_PACKAGE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return

        val root = rootInActiveWindow ?: return
        try {
            val target = findTarget(root) ?: return
            val label = normalize(target.text?.toString().orEmpty().ifBlank {
                target.contentDescription?.toString().orEmpty()
            })
            if (label == lastActionLabel && now - lastActionAt < SAME_ACTION_GUARD_MS) return

            val clickable = findClickableAncestor(target) ?: return
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastActionLabel = label
                lastActionAt = now
            }
        } finally {
            root.recycle()
        }
    }

    private fun findTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = normalize(
            node.text?.toString().orEmpty().ifBlank {
                node.contentDescription?.toString().orEmpty()
            }
        )
        if (label in TARGET_LABELS && node.isVisibleToUser && node.isEnabled) return node

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findTarget(child)
            if (match != null) return match
            child.recycle()
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        repeat(8) {
            val item = current ?: return null
            if (item.isClickable && item.isEnabled && item.isVisibleToUser) return item
            val parent = item.parent
            item.recycle()
            current = parent
        }
        current?.recycle()
        return null
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    override fun onInterrupt() {
        // Nenhuma ação adicional é necessária quando o Android interrompe o serviço.
    }

    companion object {
        private const val WAZE_PACKAGE = "com.waze"
        private const val ACTION_COOLDOWN_MS = 1800L
        private const val SAME_ACTION_GUARD_MS = 9000L
        private val TARGET_LABELS = setOf(
            "ver rotas",
            "dirigir ate la",
            "seguir",
            "iniciar",
            "comecar a dirigir"
        )
    }
}
