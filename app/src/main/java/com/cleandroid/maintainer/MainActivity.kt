package com.cleandroid.maintainer

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cleandroid.maintainer.battery.BatteryMonitor
import com.cleandroid.maintainer.cleaner.DeepCleanOrchestrator
import com.cleandroid.maintainer.cleaner.DuplicateFinder
import com.cleandroid.maintainer.cleaner.JunkScanner
import com.cleandroid.maintainer.cleaner.WhatsAppCleaner
import com.cleandroid.maintainer.core.JunkCategory
import com.cleandroid.maintainer.core.JunkItem
import com.cleandroid.maintainer.core.PermissionHelper
import com.cleandroid.maintainer.core.PrivilegeManager
import com.cleandroid.maintainer.core.ReportExporter
import com.cleandroid.maintainer.core.VersionCompat
import com.cleandroid.maintainer.core.formatBytes
import com.cleandroid.maintainer.diagnostics.DiagnosticRunner
import com.cleandroid.maintainer.work.ScheduledCleanWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CleanDroid v2 — simples e seguro.
 * Fluxo: Varrer → ver resumo por categoria → marcar o que quer → Limpar (com explicação).
 * Categorias arriscadas (⚠️) vêm desmarcadas e pedem confirmação extra.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var scanner: JunkScanner
    private lateinit var battery: BatteryMonitor
    private lateinit var diagnostics: DiagnosticRunner

    private var fullScan: List<JunkItem> = emptyList()

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
        battery = BatteryMonitor(this)
        diagnostics = DiagnosticRunner(this)

        title = "CleanDroid"

        requestInitialPermissions()
        refreshLevel()

        onClick(R.id.btnScan) { runScan() }
        onClick(R.id.btnClean) { confirmAndClean() }
        onClick(R.id.btnDuplicates) { runDuplicates() }
        onClick(R.id.btnWhats) { runWhats() }
        onClick(R.id.btnDeep) { runDeepClean() }
        onClick(R.id.btnBattery) { showBattery() }
        onClick(R.id.btnDiag) { runDiag() }
        onClick(R.id.btnReport) { exportReport() }
        onClick(R.id.btnSchedule) { toggleSchedule() }
        onClick(R.id.btnAllFiles) {
            try { startActivity(PermissionHelper.intentAllFilesAccess()) }
            catch (_: Exception) { toast("Tela indisponível neste aparelho") }
        }

        updateStatus("Toque em Varrer agora para começar.\n${VersionCompat.label()}")
    }

    private fun onClick(id: Int, fn: () -> Unit) =
        findViewById<View>(id).setOnClickListener { fn() }

    private fun refreshLevel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val desc = PrivilegeManager.describe()
            withContext(Dispatchers.Main) {
                findViewById<TextView>(R.id.txtLevel).text = desc
            }
        }
    }

    // ---------- Varredura ----------

    private fun runScan() {
        showProgress(true, "Varrendo... (leva uns segundos)")
        lifecycleScope.launch(Dispatchers.IO) {
            val res = scanner.scan(progress = object : JunkScanner.Progress {
                override fun onDir(path: String, count: Int) {
                    if (count % 3000 == 0) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            updateStatus("Varrendo... $count arquivos\n$path")
                        }
                    }
                }
            })
            fullScan = res.items
            withContext(Dispatchers.Main) {
                showProgress(false)
                if (res.items.isEmpty()) {
                    updateStatus("✨ Nada para limpar — sistema já enxuto.")
                    return@withContext
                }
                updateStatus(summaryText(res.items))
                toast("Achado ~${formatBytes(res.totalBytes)}. Confira e toque em Limpar.")
            }
        }
    }

    private fun summaryText(items: List<JunkItem>): String {
        val sb = StringBuilder("Achado ~${formatBytes(items.sumOf { it.sizeBytes })}:\n\n")
        for ((cat, list) in items.groupBy { it.category }
            .entries.sortedByDescending { it.value.sumOf { i -> i.sizeBytes } }) {
            val mark = if (cat.safe) "✅" else "⚠️"
            sb.append("$mark ${cat.title}: ${list.size} (${formatBytes(list.sumOf { it.sizeBytes })})\n")
        }
        sb.append("\nDesmarque no card 'O que limpar' o que não quiser apagar.")
        return sb.toString()
    }

    /** Filtra o scan completo pelas caixas marcadas na tela. */
    private fun selectedItems(): List<JunkItem> {
        val temp = checked(R.id.cbTemp)
        val org = checked(R.id.cbOrganizar)
        val wa = checked(R.id.cbWhats)
        val dl = checked(R.id.cbDownloads)
        return fullScan.filter {
            when (it.category) {
                JunkCategory.APP_CACHE, JunkCategory.RESIDUAL_APK, JunkCategory.TEMP_FILES -> temp
                JunkCategory.EMPTY_FOLDERS, JunkCategory.HIDDEN_TRASH, JunkCategory.UNINSTALL_TRACES -> org
                JunkCategory.WHATSAPP_JUNK -> wa
                JunkCategory.DOWNLOADS_LARGE -> dl
                JunkCategory.DUPLICATES -> true // duplicados entram pelo próprio fluxo
            }
        }
    }

    private fun checked(id: Int): Boolean =
        findViewById<CheckBox>(id).isChecked

    // ---------- Limpeza ----------

    private fun confirmAndClean() {
        val items = selectedItems()
        if (items.isEmpty()) { toast("Varra primeiro ou marque algo em 'O que limpar'"); return }
        val risky = items.groupBy { it.category }.keys.filter { !it.safe }
        val sb = StringBuilder("Apagar ${items.size} itens (${formatBytes(items.sumOf { it.sizeBytes })})?\n\n")
        for ((cat, list) in items.groupBy { it.category }) {
            sb.append("• ${cat.title} (${list.size})\n  ${cat.desc}\n\n")
        }
        if (risky.isNotEmpty()) sb.append("⚠️ Inclui categorias sensíveis. Confira a lista com calma.\n")
        sb.append("Não pode ser desfeito.")
        MaterialAlertDialogBuilder(this)
            .setTitle("Confirmar limpeza")
            .setMessage(sb.toString())
            .setPositiveButton("Limpar") { _, _ -> doClean(items) }
            .setNegativeButton("Revisar", null)
            .show()
    }

    private fun doClean(items: List<JunkItem>) {
        showProgress(true, "Limpando...")
        lifecycleScope.launch(Dispatchers.IO) {
            val rooted = PrivilegeManager.hasRoot()
            var ok = 0; var fail = 0; var freed = 0L
            for (it in items) {
                val deleted = if (scanner.delete(it)) true
                else if (rooted) PrivilegeManager.deleteAsRoot(it.path) else false
                if (deleted) { ok++; freed += it.sizeBytes } else fail++
            }
            withContext(Dispatchers.Main) {
                showProgress(false)
                fullScan = fullScan - items.toSet()
                updateStatus("✅ Limpeza concluída: $ok removidos" +
                        (if (fail > 0) ", $fail sem acesso" else "") +
                        ".\nLiberado ~${formatBytes(freed)}." +
                        (if (fail > 0) "\nDica: a Limpeza profunda (root) alcança o restante." else ""))
            }
        }
    }

    // ---------- Fluxos específicos ----------

    private fun runDuplicates() {
        showProgress(true, "Comparando arquivos (hash)...")
        lifecycleScope.launch(Dispatchers.IO) {
            val finder = DuplicateFinder()
            val groups = finder.find { n ->
                if (n % 3000 == 0) lifecycleScope.launch(Dispatchers.Main) {
                    updateStatus("Comparando... $n arquivos")
                }
            }
            val items = finder.toJunkItems(groups)
            fullScan = (fullScan.filter { it.category != JunkCategory.DUPLICATES } + items)
            withContext(Dispatchers.Main) {
                showProgress(false)
                if (groups.isEmpty()) { updateStatus("Nenhum duplicado encontrado."); return@withContext }
                val sb = StringBuilder("📑 ${groups.size} grupos, ~${formatBytes(groups.sumOf { it.wastedBytes() })} repetidos.\n" +
                        "Mantemos sempre 1 cópia.\n\n")
                groups.take(12).forEach { g ->
                    sb.append("• ${formatBytes(g.size)} ×${g.files.size}: ${g.files.first().name}\n")
                }
                sb.append("\nToque em LIMPAR para apagar as cópias extras.")
                updateStatus(sb.toString())
            }
        }
    }

    private fun runWhats() {
        val names = WhatsAppCleaner.categories.map {
            "${if (it.safe) "✅" else "⚠️"} ${it.label}\n${it.hint}"
        }.toTypedArray()
        val checkedArr = WhatsAppCleaner.categories.map { it.safe }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("WhatsApp — o que procurar")
            .setMultiChoiceItems(names, checkedArr) { _, which, v -> checkedArr[which] = v }
            .setPositiveButton("Varrer") { _, _ ->
                val ids = WhatsAppCleaner.categories
                    .filterIndexed { i, _ -> checkedArr[i] }.map { it.id }.toSet()
                showProgress(true, "Varrendo WhatsApp...")
                lifecycleScope.launch(Dispatchers.IO) {
                    val items = WhatsAppCleaner.scan(ids)
                    fullScan = (fullScan.filter { it.category != JunkCategory.WHATSAPP_JUNK } + items)
                    withContext(Dispatchers.Main) {
                        showProgress(false)
                        findViewById<CheckBox>(R.id.cbWhats).isChecked = items.isNotEmpty()
                        updateStatus("💬 WhatsApp: ${items.size} itens (${formatBytes(items.sumOf { it.sizeBytes })}).\n" +
                                "Marque a caixa do WhatsApp e toque em LIMPAR.")
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun runDeepClean() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pkgs = PrivilegeManager.installedPackages(this@MainActivity)
            val desc = PrivilegeManager.describe()
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("🚀 Limpeza profunda")
                    .setMessage("$desc\n\nLimpa o cache de TODOS os apps de uma vez.\n" +
                            "• Com root: automático e total.\n" +
                            "• Sem root: abre a tela de cada app para você confirmar (até 15 por vez).")
                    .setPositiveButton("Iniciar") { _, _ ->
                        DeepCleanOrchestrator.start(this@MainActivity, pkgs)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    // ---------- Utilidades ----------

    private fun showBattery() {
        lifecycleScope.launch(Dispatchers.IO) {
            val info = battery.read()
            val tips = battery.tips(info)
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🔋 Bateria ${info.pct}% • ${info.chargeType}")
                    .setMessage("Temperatura: ${info.tempC}°C\nSaúde: ${info.health}\nEconomia: ${if (info.saverOn) "ativa" else "desligada"}\n\n${tips.joinToString("\n\n")}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun runDiag() {
        showProgress(true, "Verificando aparelho...")
        lifecycleScope.launch(Dispatchers.IO) {
            val list = diagnostics.runAll()
            withContext(Dispatchers.Main) {
                showProgress(false)
                updateStatus(list.joinToString("\n\n") { "${if (it.ok) "✅" else "⚠️"} ${it.name}\n${it.detail}" })
            }
        }
    }

    private fun exportReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = runCatching { findViewById<TextView>(R.id.txtStatus).text.toString() }.getOrDefault("")
            val msg = ReportExporter.export(this@MainActivity, ReportExporter.buildReport(status))
            withContext(Dispatchers.Main) { toast(msg) }
        }
    }

    private fun toggleSchedule() {
        MaterialAlertDialogBuilder(this)
            .setTitle("⏰ Varredura semanal")
            .setMessage("Avisa 1x por semana quanto lixo encontrou. Nunca apaga sozinho.")
            .setPositiveButton("Ativar") { _, _ ->
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    permLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
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

    private fun requestInitialPermissions() {
        val needed = PermissionHelper.storagePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (needed.isNotEmpty() && Build.VERSION.SDK_INT >= 23) permLauncher.launch(needed)
        if (VersionCompat.needsAllFilesAccess() && !PermissionHelper.hasAllFilesAccess()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Acesso total (opcional)")
                .setMessage("No Android 11+, a varredura completa precisa de 'Acesso a todos os arquivos'. Sem isso, limpamos o acessível. Abrir a tela agora?")
                .setPositiveButton("Abrir") { _, _ ->
                    try { startActivity(PermissionHelper.intentAllFilesAccess()) } catch (_: Exception) {}
                }
                .setNegativeButton("Depois", null)
                .show()
        }
    }

    private fun showProgress(show: Boolean, msg: String = "") {
        findViewById<ProgressBar>(R.id.progressBar).visibility = if (show) View.VISIBLE else View.GONE
        if (show && msg.isNotBlank()) updateStatus(msg)
    }

    private fun updateStatus(s: String) {
        findViewById<TextView>(R.id.txtStatus).text = s
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
