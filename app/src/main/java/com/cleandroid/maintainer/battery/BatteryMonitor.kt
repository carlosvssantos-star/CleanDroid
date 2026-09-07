package com.cleandroid.maintainer.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/** Monitor de bateria multi-versão (API 21+). */
class BatteryMonitor(private val ctx: Context) {

    data class Info(
        val pct: Int,
        val charging: Boolean,
        val chargeType: String,
        val tempC: Float,
        val voltageMv: Int,
        val capacityMah: Long?,
        val health: String,
        val saverOn: Boolean
    )

    fun read(): Info {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val b = ctx.registerReceiver(null, filter)
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0) (level * 100 / scale) else -1
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargeType = when {
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "Sem fio"
            Build.VERSION.SDK_INT >= 26 && plugged and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> "Dock"
            charging -> "Carregando"
            else -> "Bateria"
        }
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val volt = b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val health = when (b?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Boa"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Superaquecida"
            BatteryManager.BATTERY_HEALTH_COLD -> "Fria"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobretensão"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Esgotada"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Falha"
            else -> "Desconhecida"
        }
        var cap: Long? = null
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            if (Build.VERSION.SDK_INT >= 28) {
                val uah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                if (uah > 0) cap = uah / 1000
            }
            if (cap == null && Build.VERSION.SDK_INT >= 21) {
                // capacity restante em % — não é mAh; mantém null para não enganar
                cap = null
            }
        } catch (_: Exception) {}

        val saver = try {
            if (Build.VERSION.SDK_INT >= 21) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (Build.VERSION.SDK_INT >= 21) pm.isPowerSaveMode else false
            } else false
        } catch (_: Exception) { false }

        return Info(pct, charging, chargeType, temp, volt, cap, health, saver)
    }

    fun tips(info: Info): List<String> {
        val t = mutableListOf<String>()
        if (info.tempC >= 40) t += "Temperatura alta (${info.tempC}°C): feche apps pesados e tire do sol/carregador."
        if (!info.charging && info.pct in 1..19) t += "Bateria baixa: ative economia de energia e reduza brilho."
        if (info.charging && info.pct > 90) t += "Acima de 90%: pode remover do carregador para preservar longevidade."
        if (t.isEmpty()) t += "Bateria saudável. Nada urgente."
        t += "Dica: 'Otimizar RAM' não economiza bateria por si só — vilões são tela, GPS e 2º plano com sync."
        return t
    }
}
