package br.com.auroraia.assistente

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Central de configurações da Fase 2.
 * Os dois perfis são opcionais e suas preferências ficam salvas no aparelho.
 */
class SettingsActivity : Activity() {
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var profileEnabled: Array<CheckBox>
    private lateinit var profileName: Array<EditText>
    private lateinit var profileNumber: Array<EditText>
    private val profilePackages = arrayOf("com.whatsapp", "com.whatsapp")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            Toast.makeText(this, "Desbloqueie o aparelho para alterar as configurações.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        buildSettings()
        loadSettings()
    }

    private fun buildSettings() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF0B1020.toInt())
        }
        root.addView(TextView(this).apply {
            text = "CONFIGURAÇÕES DA AURORA"
            textSize = 24f
            setTextColor(0xFF77D8FF.toInt())
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Perfis do WhatsApp"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 22, 0, 8)
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Cadastre um ou dois perfis. O nome e o número servem para identificar cada perfil; o Android não permite confirmar automaticamente qual conta está ativa dentro do WhatsApp."
            textSize = 14f
            setTextColor(0xFFB9C8DD.toInt())
        }, matchWrap())

        profileEnabled = Array(2) { index ->
            CheckBox(this).apply {
                text = "Ativar perfil ${index + 1}"
                setTextColor(0xFFFFFFFF.toInt())
                isChecked = index == 0
                setPadding(0, 12, 0, 4)
            }.also { root.addView(it, matchWrap()) }
        }
        profileName = Array(2) { index ->
            EditText(this).apply {
                hint = if (index == 0) "Nome do perfil 1 (ex.: Pessoal)" else "Nome do perfil 2 (ex.: Trabalho)"
                setSingleLine(true)
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF9AA9BD.toInt())
                setBackgroundColor(0xFF1A2638.toInt())
            }.also { root.addView(it, matchWrap()) }
        }
        profileNumber = Array(2) { index ->
            EditText(this).apply {
                hint = if (index == 0) "Número do perfil 1 (opcional)" else "Número do perfil 2 (opcional)"
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_PHONE
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF9AA9BD.toInt())
                setBackgroundColor(0xFF1A2638.toInt())
            }.also { root.addView(it, matchWrap()) }
        }

        repeat(2) { index ->
            val targetButton = Button(this).apply {
                text = "Instalação do WhatsApp — perfil ${index + 1}"
                setOnClickListener { chooseInstallation(index, this) }
            }
            root.addView(targetButton, matchWrap())
        }

        root.addView(TextView(this).apply {
            text = "Nota sobre Samsung Dual Messenger: o Android pode apresentar a cópia duplicada de forma diferente conforme o aparelho. Se a cópia não aparecer no seletor, esta versão ainda não consegue vinculá-la por identificador próprio."
            textSize = 13f
            setTextColor(0xFFFFD18A.toInt())
            setPadding(0, 16, 0, 8)
        }, matchWrap())

        root.addView(Button(this).apply {
            text = "SALVAR CONFIGURAÇÕES"
            setOnClickListener { saveSettings() }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "TESTAR PERFIL 1"
            setOnClickListener { saveSettings(); launchProfile(0) }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "TESTAR PERFIL 2"
            setOnClickListener { saveSettings(); launchProfile(1) }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "VOLTAR"
            setOnClickListener { finish() }
        }, matchWrap())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun chooseInstallation(index: Int, button: Button) {
        val choices = arrayOf("WhatsApp comum", "WhatsApp Business", "Perguntar ao Android ao abrir")
        val packages = arrayOf("com.whatsapp", "com.whatsapp.w4b", "")
        AlertDialog.Builder(this)
            .setTitle("Vincular instalação ao perfil ${index + 1}")
            .setItems(choices) { _, which ->
                profilePackages[index] = packages[which]
                button.text = "Perfil ${index + 1}: ${choices[which]}"
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveSettings() {
        if (keyguardManager.isKeyguardLocked) {
            Toast.makeText(this, "Ação bloqueada enquanto o aparelho estiver bloqueado.", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = getSharedPreferences("aurora_settings", MODE_PRIVATE)
        val editor = prefs.edit()
        repeat(2) { index ->
            editor.putBoolean("profile_${index + 1}_enabled", profileEnabled[index].isChecked)
            editor.putString("profile_${index + 1}_name", profileName[index].text.toString().trim())
            editor.putString("profile_${index + 1}_number", profileNumber[index].text.toString().trim())
            editor.putString("profile_${index + 1}_package", profilePackages[index])
        }
        editor.putString("whatsapp_package", profilePackages[0].takeIf { it.isNotBlank() } ?: "com.whatsapp")
        editor.apply()
        Toast.makeText(this, "Configurações salvas neste aparelho.", Toast.LENGTH_SHORT).show()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("aurora_settings", MODE_PRIVATE)
        repeat(2) { index ->
            profileEnabled[index].isChecked = prefs.getBoolean("profile_${index + 1}_enabled", index == 0)
            profileName[index].setText(prefs.getString("profile_${index + 1}_name", ""))
            profileNumber[index].setText(prefs.getString("profile_${index + 1}_number", ""))
            profilePackages[index] = prefs.getString("profile_${index + 1}_package", if (index == 0) "com.whatsapp" else "com.whatsapp") ?: "com.whatsapp"
        }
    }

    private fun launchProfile(index: Int) {
        if (!profileEnabled[index].isChecked) {
            Toast.makeText(this, "Ative esse perfil antes de testá-lo.", Toast.LENGTH_LONG).show()
            return
        }
        val packageName = profilePackages[index]
        if (packageName.isBlank()) {
            val intent = packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            if (intent == null) {
                Toast.makeText(this, "Não encontrei uma instalação do WhatsApp.", Toast.LENGTH_LONG).show()
                return
            }
            startActivity(Intent.createChooser(intent, "Escolha a instalação disponível"))
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Toast.makeText(this, "A instalação selecionada não foi encontrada. Confira se está instalada.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(launch)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
