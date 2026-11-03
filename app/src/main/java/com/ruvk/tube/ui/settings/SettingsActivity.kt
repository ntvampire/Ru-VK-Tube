package com.ruvk.tube.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.ruvk.tube.BuildConfig
import com.ruvk.tube.R
import com.ruvk.tube.data.local.AppPreferences
import com.ruvk.tube.data.update.UpdateInfo
import com.ruvk.tube.data.update.UpdateManager
import kotlinx.coroutines.launch

class SettingsActivity : FragmentActivity() {

    private lateinit var btnBack: TextView
    private lateinit var cardSettingPlayer: View
    private lateinit var txtPlayerStatus: TextView
    private lateinit var cardSettingApiKey: View
    private lateinit var txtApiKeyStatus: TextView
    private lateinit var cardSettingUpdate: View
    private lateinit var txtVersionInfo: TextView
    private lateinit var progressUpdate: ProgressBar

    private var isUpdating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        refreshState()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_back)
        cardSettingPlayer = findViewById(R.id.card_setting_player)
        txtPlayerStatus = findViewById(R.id.txt_player_status)
        cardSettingApiKey = findViewById(R.id.card_setting_api_key)
        txtApiKeyStatus = findViewById(R.id.txt_api_key_status)
        cardSettingUpdate = findViewById(R.id.card_setting_update)
        txtVersionInfo = findViewById(R.id.txt_version_info)
        progressUpdate = findViewById(R.id.progress_update_download)

        btnBack.setOnClickListener {
            finish()
        }

        cardSettingPlayer.setOnClickListener {
            showPlayerChooserDialog()
        }

        cardSettingApiKey.setOnClickListener {
            showApiKeyDialog()
        }

        cardSettingUpdate.setOnClickListener {
            if (!isUpdating) {
                checkForUpdatesManual()
            }
        }

        cardSettingPlayer.post {
            cardSettingPlayer.requestFocus()
        }
    }

    private fun refreshState() {
        // 1. Статус плеера
        txtPlayerStatus.text = if (AppPreferences.isExternalPlayer) {
            "Внешний плеер (Vimu, MX Player, VLC)"
        } else {
            "Встроенный плеер (ExoPlayer)"
        }

        // 2. Статус API ключа TMDB
        val key = AppPreferences.tmdbApiKey
        txtApiKeyStatus.text = if (key.isBlank()) {
            "Используется встроенный ключ по умолчанию"
        } else {
            "Пользовательский ключ: ${key.take(4)}****${key.takeLast(4)}"
        }

        // 3. Статус версии
        txtVersionInfo.text = "Текущая версия: v${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE}) • Нажмите для проверки"
    }

    private fun showPlayerChooserDialog() {
        val options = arrayOf(
            "Встроенный плеер ExoPlayer (рекомендуется)",
            "Внешний плеер (Vimu, MX Player, VLC)"
        )
        val selectedIdx = if (AppPreferences.isExternalPlayer) 1 else 0

        AlertDialog.Builder(this)
            .setTitle("Выберите плеер по умолчанию")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                AppPreferences.playerType = if (which == 1) {
                    AppPreferences.PLAYER_EXTERNAL
                } else {
                    AppPreferences.PLAYER_INTERNAL
                }
                refreshState()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "Введите 32-значный ключ TMDB API"
            setText(AppPreferences.tmdbApiKey)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Ключ TMDB API")
            .setMessage("Персональный API-ключ позволяет обойти возможные ограничения лимитов запросов:")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newKey = input.text.toString().trim()
                AppPreferences.tmdbApiKey = newKey
                refreshState()
                Toast.makeText(this, "Ключ TMDB сохранен", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Сбросить") { _, _ ->
                AppPreferences.tmdbApiKey = ""
                refreshState()
                Toast.makeText(this, "Сброшено на встроенный ключ", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun checkForUpdatesManual() {
        txtVersionInfo.text = "Проверка наличия обновлений..."
        lifecycleScope.launch {
            val update = UpdateManager.checkUpdate(force = true)
            if (update != null) {
                txtVersionInfo.text = "Доступно обновление v${update.versionName}!"
                showUpdateConfirmDialog(update)
            } else {
                txtVersionInfo.text = "У вас установлена последняя версия v${BuildConfig.VERSION_NAME}"
                Toast.makeText(this@SettingsActivity, "Обновлений не найдено, у вас актуальная версия", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateConfirmDialog(update: UpdateInfo) {
        val changelog = update.changelog ?: "Улучшения стабильности и новые возможности."
        AlertDialog.Builder(this)
            .setTitle("Доступна новая версия v${update.versionName}")
            .setMessage("Что нового:\n$changelog\n\nСкачать и установить обновление?")
            .setPositiveButton("Обновить") { _, _ ->
                startDownloadAndInstall(update)
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun startDownloadAndInstall(update: UpdateInfo) {
        isUpdating = true
        progressUpdate.visibility = View.VISIBLE
        progressUpdate.progress = 0
        txtVersionInfo.text = "Загрузка обновления: 0%..."

        lifecycleScope.launch {
            val apkFile = UpdateManager.downloadApk(this@SettingsActivity, update.apkUrl) { percent ->
                progressUpdate.progress = percent
                txtVersionInfo.text = "Загрузка обновления: $percent%..."
            }

            isUpdating = false
            progressUpdate.visibility = View.GONE

            if (apkFile != null && apkFile.exists()) {
                txtVersionInfo.text = "Файл загружен. Запуск установки..."
                UpdateManager.installApk(this@SettingsActivity, apkFile)
            } else {
                txtVersionInfo.text = "Ошибка при загрузке обновления"
                Toast.makeText(this@SettingsActivity, "Не удалось скачать обновление", Toast.LENGTH_LONG).show()
            }
        }
    }
}
