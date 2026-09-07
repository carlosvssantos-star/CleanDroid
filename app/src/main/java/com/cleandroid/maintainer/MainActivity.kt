package com.cleandroid.maintainer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cleandroid.maintainer.apps.AppManager
import com.cleandroid.maintainer.apps.AppUpdateHelper
import com.cleandroid.maintainer.battery.BatteryMonitor
import com.cleandroid.maintainer.cleaner.DeepCleanOrchestrator
import com.cleandroid.maintainer.cleaner.DuplicateFinder
import com.cleandroid.maintainer.cleaner.JunkScanner
import com.cleandroid.maintainer.cleaner.WhatsAppCleaner
import com.cleandroid.maintainer.core.JunkItem
import com.cleandroid.maintainer.core.PermissionHelper
import com.cleandroid.maintainer.core.ReportExporter
import com.cleandroid.maintainer.core.ShizukuHelper
import com.cleandroid.maintainer.core.VersionCompat
import com.cleandroid.maintainer.core.formatBytes
import com.cleandroid.maintainer.diagnostics.DiagnosticRunner
import com.cleandroid.maintainer.optimizer.RamCpuMonitor
import com.cleandroid.maintainer.work.ScheduledCleanWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela principal simples (sem ViewBinding complexo para máxima compatibilidade).
 * Usa layout activity_main.xml com botões por módulo.
 * Toda operação pesada roda em Dispatchers.IO.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var scanner: JunkScanner
    private lateinit var ramCpu: RamCpuMonitor
    private lateinit var appManager: AppManager
    private lateinit var battery: BatteryMonitor
    private lateinit var diagnostics: DiagnosticRunner

    private var lastScan: List<JunkItem> = emptyList()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) toast("Permissões atualizadas")
        else toast("Sem permissão, a varredura será limitada")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scanner = JunkScanner(this)
        ramCpu = RamCpuMonitor(this)
        appManager = AppManager(this)
        battery = BatteryMonitor(this)
        diagnostics = DiagnosticRunner(this)

        title = "CleanDroid • ${VersionCompat.label()}"

        requestInitialPermissions()
        AppUpdateHelper.checkSelfUpdate(this)

        findViewById<android.view.View>(R.id.btnScan).setOnClickListener { runScan() }
        findViewById<android.view.View>(R.id.btnClean).setOnClickListener { confirmAndClean() }
        findViewById<android.view.View>(R.id.btnDuplicates).setOnClickListener { runDuplicates() }
        findViewById<android.view.View>(R.id.btnWhats).setOnClickListener { runWhats() }
        findViewById<android.view.View>(R.id.btnBoost).setOnClickListener { runBoost() }
        findViewById<android.view.View>(R.id.btnBattery).setOnClickListener { showBattery() }
        findViewById<android.view.View>(R.id.btnDiag).setOnClickListener { runDiag() }
        findViewById<android.view.View>(R.id.btnApps).setOnClickListener { showApps() }
        findViewById<android.view.View>(R.id.btnUpdates).setOnClickListener {
            if (!AppUpdateHelper.openPlayUpdates(this)) toast("Não foi possível abrir a Play Store")
        }
        findViewById<android.view.View>(R.id.btnAllFiles).setOnClickListener {
            try { startActivity(PermissionHelper.intentAllFilesAccess()) }
            catch (_: Exception) { toast("Tela indisponível neste aparelho") }
        }
        findViewById<android.view.View>(R.id.btnDeep).setOnClickListener { runDeepClean() }
        findViewById<android.view.View>(R.id.btnSchedule).setOnClickListener { toggleSchedule() }
        findViewById<android.view.View>(R.id.btnReport).setOnClickListener { exportReport() }

        updateStatus("Pronto. Toque em Varrer para começar.\n${VersionCompat.label()}")
    }

    private fun requestInitialPermissions() {
        val needed = PermissionHelper.storagePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (needed.isNotEmpty()) {
            // API 23+ pede runtime; abaixo disso é install-time
            if (Build.VERSION.SDK_INT >= 23) permLauncher.launch(needed)
        }
        if (VersionCompat.needsAllFilesAccess() && !PermissionHelper.hasAllFilesAccess()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Acesso total opcional")
                .setMessage("No Android 11+, a limpeza profunda de APKs soltos, pastas vazias e lixo oculto precisa de 'Acesso a todos os arquivos'. Sem isso o app limpa só o próprio cache + áreas públicas. Deseja abrir a tela agora?")
                .setPositiveButton("Abrir") { _, _ ->
                    try { startActivity(PermissionHelper.intentAllFilesAccess()) } catch (_: Exception) {}
                }
                .setNegativeButton("Depois", null)
                .show()
        }
    }

    private fun runScan() {
        updateStatus("Varrendo... (pode levar 30-60s)")
        lifecycleScope.launch(Dispatchers.IO) {
            val res = scanner.scan(progress = object : JunkScanner.Progress {
                override fun onDir(path: String, count: Int) {
                    if (count % 2000 == 0) {
                        lifecycleScope.launch(Dispatchers.Main) { updateStatus("Varrendo...\n$path\n$count arquivos") }
                    }
                }
            })
            lastScan = res.items
            withContext(Dispatchers.Main) {
                val byCat = res.byCategory().entries.sortedByDescending { it.value.sumOf { i -> i.sizeBytes } }
                val sb = StringBuilder()
                sb.append("Achado: ${formatBytes(res.totalBytes)} em ${res.items.size} itens (${res.durationMs}ms)\n\n")
                for ((cat, list) in byCat) {
                    sb.append("• ${cat.title}: ${list.size} itens (${formatBytes(list.sumOf { it.sizeBytes })})\n")
                }
                sb.append("\nToque LIMPAR para remover (com confirmação). Duplicados têm botão próprio.")
                updateStatus(sb.toString())
                if (res.items.isEmpty()) toast("Nada para limpar — sistema já enxuto")
            }
        }
    }

    private fun confirmAndClean() {
        if (lastScan.isEmpty()) { toast("Varra primeiro"); return }
        val total = lastScan.sumOf { it.sizeBytes }
        // Segurança: pastas vazias (0 bytes) e itens críticos pedem atenção
        MaterialAlertDialogBuilder(this)
            .setTitle("Apagar ${lastScan.size} itens (${formatBytes(total)})?")
            .setMessage("Itens incluem APKs soltos, temporários, pastas vazias, lixo oculto e rastros. WhatsApp sensível e backups NÃO entram aqui (use botão WhatsApp). Esta ação não pode ser desfeita.")
            .setPositiveButton("Limpar") { _, _ -> doClean(lastScan) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doClean(items: List<JunkItem>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var ok = 0; var fail = 0; var freed = 0L
            for (it in items) {
                if (scanner.delete(it)) { ok++; freed += it.sizeBytes } else fail++
            }
            withContext(Dispatchers.Main) {
                updateStatus("Limpeza concluída: $ok removidos, $fail falharam.\nLiberado ~${formatBytes(freed)}")
                lastScan = emptyList()
            }
        }
    }

    private fun runDuplicates() {
        updateStatus("Procurando duplicados (hash)...")
        lifecycleScope.launch(Dispatchers.IO) {
            val finder = DuplicateFinder()
            val groups = finder.find { n ->
                if (n % 2000 == 0) lifecycleScope.launch(Dispatchers.Main) { updateStatus("Duplicados... $n arquivos") }
            }
            val items = finder.toJunkItems(groups)
            lastScan = items
            val wasted = groups.sumOf { it.wastedBytes() }
            withContext(Dispatchers.Main) {
                if (groups.isEmpty()) { updateStatus("Nenhum duplicado encontrado."); return@withContext }
                val sb = StringBuilder("Grupos: ${groups.size}, desperdício ~${formatBytes(wasted)}\n\n")
                groups.take(15).forEach { g ->
                    sb.append("• ${formatBytes(g.size)} x${g.files.size}: ${g.files.first().name}\n")
                    g.files.drop(1).take(2).forEach { f -> sb.append("    - ${f.absolutePath}\n") }
                }
                sb.append("\nToque LIMPAR para manter 1 cópia de cada e apagar o resto.")
                updateStatus(sb.toString())
            }
        }
    }

    private fun runWhats() {
        val names = WhatsAppCleaner.categories.map { "${it.label}\n(${it.hint})" }.toTypedArray()
        val checked = booleanArrayOf(true, true, true, true, false, false, false)
        MaterialAlertDialogBuilder(this)
            .setTitle("Lixo do WhatsApp — escolher")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Varrer") { _, _ ->
                val ids = WhatsAppCleaner.categories.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                lifecycleScope.launch(Dispatchers.IO) {
                    val items = WhatsAppCleaner.scan(ids)
                    lastScan = items
                    withContext(Dispatchers.Main) {
                        updateStatus("WhatsApp: ${items.size} itens (${formatBytes(items.sumOf { it.sizeBytes })})\nToque LIMPAR para remover.")
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun runBoost() {
        lifecycleScope.launch(Dispatchers.IO) {
            val before = ramCpu.ram()
            val cpu = try { ramCpu.cpu() } catch (_: Exception) { null }
            val top = try { ramCpu.topProcesses() } catch (_: Exception) { emptyList() }
            val res = try { ramCpu.optimize() } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                val sb = StringBuilder()
                sb.append("RAM: ${before.usedPct}% em uso (${before.usedMb}/${before.totalMb} MB)\n")
                if (cpu?.usagePct != null) sb.append("CPU: ${String.format("%.0f", cpu.usagePct)}% (${cpu.cores} núcleos)\n")
                if (top.isNotEmpty()) {
                    sb.append("\nTop processos:\n")
                    top.take(5).forEach { sb.append("• ${it.name} — ${it.pssKb / 1024} MB (${it.importance})\n") }
                }
                if (res != null) sb.append("\nBoost: ${res.killed} em 2º plano encerrados, +${res.freedMb} MB livres.")
                sb.append("\n\nNota: Android gerencia RAM sozinho; boost ajuda pontualmente, não faz milagre.")
                updateStatus(sb.toString())
            }
        }
    }

    private fun showBattery() {
        lifecycleScope.launch(Dispatchers.IO) {
            val info = battery.read()
            val tips = battery.tips(info)
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Bateria ${info.pct}% • ${info.chargeType}")
                    .setMessage("Temp: ${info.tempC}°C\nTensão: ${info.voltageMv} mV\nSaúde: ${info.health}\nEconomia: ${if (info.saverOn) "ATIVA" else "desligada"}\n\n${tips.joinToString("\n\n")}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun runDiag() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = diagnostics.runAll()
            withContext(Dispatchers.Main) {
                updateStatus(list.joinToString("\n\n") { "${if (it.ok) "✅" else "⚠️"} ${it.name}\n${it.detail}" })
            }
        }
    }

    private fun showApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apps = appManager.listInstalled(false, 100)
            withContext(Dispatchers.Main) {
                if (apps.isEmpty()) { toast("Lista vazia (permissão?)"); return@withContext }
                val labels = apps.take(30).map { "${it.label} — ${formatBytes(it.sizeBytes)}${it.lastUsedAgo?.let { u -> " • $u" } ?: ""}" }.toTypedArray()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Maiores apps (top 30)")
                    .setItems(labels) { _, which ->
                        val app = apps[which]
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(app.label)
                            .setMessage("${app.packageName}\nv${app.version}\n${formatBytes(app.sizeBytes)}\nÚltimo uso: ${app.lastUsedAgo ?: "?"}")
                            .setPositiveButton("Abrir detalhes") { _, _ -> startActivity(appManager.openAppDetails(app.packageName)) }
                            .setNeutralButton("Play Store") { _, _ -> startActivity(appManager.openPlayStore(app.packageName)) }
                            .setNegativeButton("Desinstalar") { _, _ -> startActivity(appManager.uninstall(app.packageName)) }
                            .show()
                    }
                    .show()
                updateStatus("Top app: ${apps.firstOrNull()?.label} (${apps.firstOrNull()?.let { formatBytes(it.sizeBytes) }})")
            }
        }
    }

    private fun updateStatus(s: String) {
        findViewById<android.widget.TextView>(R.id.txtStatus).text = s
    }

    private fun runDeepClean() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apps = try { appManager.listInstalled(false, 50).map { it.packageName } } catch (_: Exception) { emptyList() }
            val shizukuOk = try { ShizukuHelper.isInstalled(this@MainActivity) && ShizukuHelper.isPermissionGranted() } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Limpeza profunda")
                    .setMessage(
                        "Shizuku: ${if (ShizukuHelper.isInstalled(this@MainActivity)) "instalado" else "não instalado"} / " +
                        "${if (shizukuOk) "autorizado" else "não autorizado"}\n\n" +
                        "• Com Shizuku: trim-caches automático de todos os apps.\n" +
                        "• Com Acessibilidade: abre telas e clica 'Limpar cache'.\n" +
                        "• Sem nada: abre telas para você limpar manual (15 apps maiores)."
                    )
                    .setPositiveButton("Iniciar") { _, _ ->
                        if (!shizukuOk && !ShizukuHelper.isInstalled(this@MainActivity)) {
                            // oferece pedir permissão Shizuku se instalado, senão segue guiada
                        }
                        if (!shizukuOk && ShizukuHelper.isInstalled(this@MainActivity)) {
                            try { ShizukuHelper.requestPermission() } catch (_: Exception) {}
                            toast("Autorize no Shizuku e toque Iniciar de novo")
                            return@setPositiveButton
                        }
                        DeepCleanOrchestrator.start(this@MainActivity, appManager, apps)
                    }
                    .setNeutralButton("Como instalar Shizuku") { _, _ ->
                        toast("Instale Shizuku + ative via ADB/Wi-Fi (shizuku.rikka.app)")
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    private fun toggleSchedule() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Varredura semanal")
            .setMessage("Agenda notificação semanal com total de lixo (nunca apaga sozinho). Requer NOTIFICAÇÕES no Android 13+.")
            .setPositiveButton("Ativar semanal") { _, _ ->
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                ScheduledCleanWorker.scheduleWeekly(this)
                toast("Agendada: 1x por semana")
            }
            .setNeutralButton("Desativar") { _, _ ->
                ScheduledCleanWorker.cancel(this)
                toast("Agendamento removido")
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun exportReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = try { findViewById<android.widget.TextView>(R.id.txtStatus).text.toString() } catch (_: Exception) { "" }
            val report = ReportExporter.buildReport(status)
            val msg = ReportExporter.export(this@MainActivity, report)
            withContext(Dispatchers.Main) { toast(msg); updateStatus("$status\n\n$msg") }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 5001) toast("Verificação de update concluída")
    }
}
