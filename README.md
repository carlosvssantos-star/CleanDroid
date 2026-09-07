# CleanDroid — Ferramenta de Limpeza e Manutenção Android (multi-versões)

App nativo **Kotlin**, `minSdk 21` (Android 5.0) → `targetSdk 34` (Android 14, pronto p/ 35).
Escolhido Kotlin nativo porque limpeza/RAM/bateria/diagnóstico exigem APIs do sistema
que Flutter limita ou acessa com overhead.

## Módulos entregues

| Pedido | Onde está | Observação honesta Android |
|---|---|---|
| Limpeza arquivos (cache, APK residual, temp) | `cleaner/JunkScanner.kt` | Sem root, app **não pode** apagar cache de terceiros (bloqueio desde Android 8). Limpamos próprio cache + APKs soltos + .tmp/.log/.bak + Downloads grandes/obsoletos + guia p/ tela de cada app |
| Pastas vazias | `JunkScanner` categoria EMPTY_FOLDERS | Lista e apaga com confirmação |
| Lixo oculto (.thumbnails, .trash, .cache) | `JunkScanner` HIDDEN_TRASH | Não desce recursivo gigante p/ ser rápido |
| Rastro desinstalações | `JunkScanner.uninstallTraces()` | Heurística: pasta com nome de pacote não instalado +7 dias |
| Duplicados | `cleaner/DuplicateFinder.kt` | 2 fases: tamanho → SHA-256 parcial → total. Mantém 1 cópia |
| Lixo WhatsApp | `cleaner/WhatsAppCleaner.kt` | Cobre legado `/WhatsApp` + novo `/Android/media/com.whatsapp` + Business. Categorias seguras separadas de sensíveis (áudio/backup/docs exigem opt-in) |
| RAM/CPU/Armazenamento | `optimizer/RamCpuMonitor.kt` | RAM via ActivityManager, CPU via /proc/stat, top processos, boost (kill 2º plano). Sem promessa falsa: Android gerencia RAM sozinho |
| Gestão apps | `apps/AppManager.kt` | Lista por tamanho, último uso (UsageStats), abrir detalhes, desinstalar, Play Store |
| "winget upgrade --all" | `apps/AppUpdateHelper.kt` | **Não existe equivalente silencioso** sem ser loja do sistema. Implementado: In-App Updates p/ o próprio app + abre Play Store em updates + abre página de cada app p/ update guiado |
| Bateria | `battery/BatteryMonitor.kt` | % / tipo carga / temp / saúde / economia + dicas |
| Diagnóstico | `diagnostics/DiagnosticRunner.kt` | Armazenamento, sensores, tela, áudio, RAM |
| Permissões multi-versão | `core/PermissionHelper.kt`, `core/VersionCompat.kt` | API 21-28 storage amplo, 29 legacy, 30+ All Files, 33+ mídia granular |

## Estrutura

```
CleanDroid/
  settings.gradle.kts, build.gradle.kts, gradle.properties
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/cleandroid/maintainer/
    App.kt, MainActivity.kt
    core/VersionCompat.kt, Models.kt, PermissionHelper.kt
    cleaner/JunkScanner.kt, DuplicateFinder.kt, WhatsAppCleaner.kt
    optimizer/RamCpuMonitor.kt
    apps/AppManager.kt, AppUpdateHelper.kt
    battery/BatteryMonitor.kt
    diagnostics/DiagnosticRunner.kt
```

## Como compilar

1. Abra **Android Studio Ladybug+** → Open → pasta `CleanDroid`
2. Deixe sync Gradle (JDK 17).
3. Run em emulador ou celular (API 21+).
4. Para testar `MANAGE_EXTERNAL_STORAGE`, rode em Android 11+ e aceite tela "Acesso a todos os arquivos".

Sem Android Studio? Via linha de comando (com Android SDK instalado):
```
cd CleanDroid
./gradlew :app:assembleDebug
```

## Fluxo de uso

1. `1. Varrer lixo` → lista por categoria com tamanho
2. `2. Limpar` → confirmação → apaga (bloqueia `/system`, próprio `Android/data`)
3. `Duplicados` → hash → Limpar mantém 1 cópia
4. `WhatsApp` → escolha categorias → varre → Limpar
5. `Boost`, `Bateria`, `Diagnóstico`, `Apps`, `Atualizar`, `Permissão total`

## Limites e próximos passos (recomendado)

- **Limpeza profunda de cache alheio**: exige AccessibilityService (guia usuário) ou integração **Shizuku** / root. Posso adicionar Shizuku (+~200 linhas) se quiser.
- **Agendamento**: WorkManager p/ varredura semanal + notificação (dependência já incluída).
- **Photo Picker API 33+** p/ duplicados de mídia sem permissão total.
- **Testes**: adicionar `JunkScannerTest` (paths protegidos) e `DuplicateFinderTest` (hash).
- Se quiser, gero: ícone adaptativo, modo escuro, exportação de relatório .txt, e APK assinado.

## Segurança

- Nada apaga sem confirmação. Backups msgstore mantêm o mais recente.
- `isProtected()` impede apagar `/system` e próprio `Android/data`.
- Sem envio de dados — 100% offline.
