package ru.lega.radio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Экономия заряда — главный враг фонового радио.
 *
 * На Honor/Huawei/Xiaomi система «замораживает» приложение в фоне даже с
 * foreground-сервисом, и музыка обрывается. Лечится только исключением
 * из оптимизации батареи — его пользователь выдаёт один раз вручную.
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return true
    return runCatching {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)
}

/**
 * Открывает системный диалог «разрешить работу в фоне».
 * Если производитель такое действие не поддерживает — падаем в общий
 * список настроек батареи, чтобы пользователь нашёл приложение сам.
 */
fun requestIgnoreBatteryOptimizations(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )

    val opened = runCatching { context.startActivity(direct) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
