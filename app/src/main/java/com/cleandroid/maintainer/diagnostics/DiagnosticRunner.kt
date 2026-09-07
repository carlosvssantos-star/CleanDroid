package com.cleandroid.maintainer.diagnostics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display
import android.view.WindowManager

/** Diagnóstico rápido: sensores, tela, áudio, armazenamento. */
class DiagnosticRunner(private val ctx: Context) {

    data class Check(val name: String, val ok: Boolean, val detail: String)

    fun runAll(): List<Check> = listOf(
        checkStorage(),
        checkSensors(),
        checkDisplay(),
        checkAudio(),
        checkRam()
    )

    private fun checkStorage(): Check {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val (total, free) = if (Build.VERSION.SDK_INT >= 18) {
                stat.blockCountLong * stat.blockSizeLong to stat.availableBlocksLong * stat.blockSizeLong
            } else {
                @Suppress("DEPRECATION")
                stat.blockCount.toLong() * stat.blockSize.toLong() to
                        @Suppress("DEPRECATION")
                        stat.availableBlocks.toLong() * stat.blockSize.toLong()
            }
            val freePct = if (total > 0) (free * 100 / total).toInt() else 0
            Check("Armazenamento", freePct > 10, "Livre ${free / 1048576} MB de ${total / 1048576} MB ($freePct%)")
        } catch (e: Exception) { Check("Armazenamento", false, e.message ?: "erro") }
    }

    private fun checkSensors(): Check {
        return try {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val want = listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY)
            val found = want.mapNotNull { sm.getDefaultSensor(it)?.name }
            Check("Sensores", found.isNotEmpty(), if (found.isEmpty()) "Nenhum sensor básico encontrado" else "OK: ${found.joinToString()}")
        } catch (e: Exception) { Check("Sensores", false, e.message ?: "erro") }
    }

    private fun checkDisplay(): Check {
        return try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val d: Display = if (Build.VERSION.SDK_INT >= 30) ctx.display!! else wm.defaultDisplay
            val mode = if (Build.VERSION.SDK_INT >= 23) {
                try { d.mode.physicalWidth to d.mode.physicalHeight } catch (_: Exception) { 0 to 0 }
            } else 0 to 0
            val refresh = if (Build.VERSION.SDK_INT >= 23) {
                try { d.mode.refreshRate } catch (_: Exception) { d.refreshRate }
            } else d.refreshRate
            Check("Tela", true, "Resolução ${mode.first}x${mode.second}, ${String.format("%.0f", refresh)}Hz")
        } catch (e: Exception) { Check("Tela", false, e.message ?: "erro") }
    }

    private fun checkAudio(): Check {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            val wired = if (Build.VERSION.SDK_INT >= 23) {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
            } else am.isWiredHeadsetOn || am.isBluetoothA2dpOn
            Check("Áudio", true, "Saída: ${if (wired) "fone/BT conectado" else "alto-falante"}; volume música ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
        } catch (e: Exception) { Check("Áudio", false, e.message ?: "erro") }
    }

    private fun checkRam(): Check {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            Check("Memória", !mi.lowMemory, "Disponível ${mi.availMem / 1048576} MB" + if (mi.lowMemory) " (SISTEMA EM POUCA MEMÓRIA)" else "")
        } catch (e: Exception) { Check("Memória", false, e.message ?: "erro") }
    }
}
