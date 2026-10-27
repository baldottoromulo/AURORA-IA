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
