package com.flowmic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    private val recordLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                FlowMicApp(
                    onRequestOverlay = { requestOverlay() },
                    onRequestRecord = {
                        recordLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                    onOpenAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
            }
        }
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowMicApp(
    onRequestOverlay: () -> Unit,
    onRequestRecord: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    val ctx = LocalContext.current
    var prefs by remember { mutableStateOf(AppPrefs()) }
    var overlayGranted by remember { mutableStateOf(false) }
    var accGranted by remember { mutableStateOf(false) }
    var hasRecord by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { ctx.prefsFlow().collectLatest { prefs = it } }
    LaunchedEffect(Unit) {
        while (true) {
            overlayGranted = Settings.canDrawOverlays(ctx)
            val acc = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            accGranted = acc.contains("com.flowmic")
            hasRecord = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            kotlinx.coroutines.delay(1000)
        }
    }

    fun save(block: suspend androidx.datastore.preferences.core.MutablePreferences.() -> Unit) {
        (ctx as ComponentActivity).lifecycleScope.launch { ctx.dataStore.edit(block) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FlowMic 🎤", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E88E5), titleContentColor = Color.White)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Dicte dans n'importe quelle app : maintiens 🎤, parle, relâche.",
                fontWeight = FontWeight.SemiBold)
            Text("Mets le curseur dans un champ texte (WhatsApp, SMS, Chrome…), dicte, le texte se tape tout seul.",
                fontSize = 13.sp, color = Color.DarkGray)

            Card(colors = CardDefaults.cardColors(
                containerColor = if (accGranted) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1️⃣ Service d'accessibilité (OBLIGATOIRE)", fontWeight = FontWeight.Bold)
                    Text(if (accGranted) "✅ Activé" else "❌ Désactivé",
                        fontWeight = FontWeight.Bold,
                        color = if (accGranted) Color(0xFF2E7D32) else Color.Red)
                    Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                        Text(if (accGranted) "Vérifier" else "Activer FlowMic")
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(
                containerColor = if (overlayGranted) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2️⃣ Survol d'écran (bouton micro flottant)", fontWeight = FontWeight.Bold)
                    Text(if (overlayGranted) "✅ Autorisé" else "❌ Non autorisé",
                        fontWeight = FontWeight.Bold)
                    Button(onClick = onRequestOverlay, modifier = Modifier.fillMaxWidth()) {
                        Text("Autoriser l'affichage par dessus")
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(
                containerColor = if (hasRecord) Color(0xFFE8F5E9) else Color(0xFFFFF3E0))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3️⃣ Micro", fontWeight = FontWeight.Bold)
                    Text(if (hasRecord) "✅ Autorisé" else "⏳ Non autorisé",
                        fontWeight = FontWeight.Bold)
                    Button(onClick = onRequestRecord, modifier = Modifier.fillMaxWidth()) {
                        Text("Autoriser le micro")
                    }
                }
            }

            val canEnable = overlayGranted && accGranted && hasRecord
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🚀 Lancer la dictée", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Micro flottant actif")
                        Switch(checked = prefs.overlayEnabled, enabled = canEnable,
                            onCheckedChange = { on ->
                                save { this[PrefsKeys.OVERLAY_ENABLED] = on }
                                if (on) {
                                    FlowService.instance?.tryShowOverlay()
                                        ?: run {
                                            val i = Intent(ctx, MicService::class.java)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                                ctx.startForegroundService(i)
                                            else ctx.startService(i)
                                        }
                                } else {
                                    FlowService.instance?.hideOverlay()
                                    try { ctx.stopService(Intent(ctx, MicService::class.java)) }
                                    catch (_: Exception) {}
                                }
                            })
                    }
                    if (!canEnable) Text("Active 1, 2 et 3 d'abord",
                        color = Color.Red, fontSize = 12.sp)
                }
            }

            HorizontalDivider()
            Text("⚙️ Moteur de transcription", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = prefs.mode == "google",
                    onClick = { save { this[PrefsKeys.MODE] = "google" } },
                    label = { Text("Google gratuit") })
                FilterChip(selected = prefs.mode == "whisper_api",
                    onClick = { save { this[PrefsKeys.MODE] = "whisper_api" } },
                    label = { Text("Whisper API") })
            }
            Text("Google = gratuit, rapide, offline si pack vocal installé. Whisper = plus précis, multilingue, payant à l'usage.",
                fontSize = 11.sp, color = Color.Gray)

            if (prefs.mode == "whisper_api") {
                var key by remember(prefs.apiKey) { mutableStateOf(prefs.apiKey) }
                OutlinedTextField(value = key, onValueChange = { key = it },
                    label = { Text("Clé OpenAI sk-...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = { save { this[PrefsKeys.API_KEY] = key } },
                    modifier = Modifier.fillMaxWidth()) { Text("Sauvegarder clé") }
            }

            Text("Langue", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("fr" to "Français", "en" to "English", "auto" to "Auto (Whisper)")
                    .forEach { (k, l) ->
                        FilterChip(selected = prefs.language == k,
                            onClick = { save { this[PrefsKeys.LANGUAGE] = k } },
                            label = { Text(l) })
                    }
            }

            Text("Taille du bouton : ${prefs.micSize} dp")
            Slider(value = prefs.micSize.toFloat(),
                onValueChange = { v -> save { this[PrefsKeys.MIC_SIZE] = v.toInt() } },
                valueRange = 48f..96f)

            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("Espace avant insertion")
                Switch(checked = prefs.autoSpace,
                    onCheckedChange = { v -> save { this[PrefsKeys.AUTO_SPACE] = v } })
            }

            HorizontalDivider()
            Text("🕘 Historique", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (prefs.history.isEmpty()) {
                Text("Rien dicté pour l'instant.", fontSize = 12.sp, color = Color.Gray)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(prefs.history.toList()) { h ->
                        Card {
                            Row(Modifier.padding(10.dp)
                                .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(h, modifier = Modifier.weight(1f), fontSize = 13.sp)
                                TextButton(onClick = {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("flow", h))
                                }) { Text("Copier") }
                                TextButton(onClick = {
                                    FlowService.instance?.insertText(h)
                                }) { Text("Taper") }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    (ctx as ComponentActivity).lifecycleScope.launch {
                        ctx.dataStore.edit { it[PrefsKeys.HISTORY] = emptySet() }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Effacer l'historique") }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Mode d'emploi :", fontWeight = FontWeight.Bold)
                    Text("• Maintiens 🎤 → parle → relâche → texte tapé.", fontSize = 12.sp)
                    Text("• Tap court = dicte 8 secondes.", fontSize = 12.sp)
                    Text("• Glisse 🎤 pour le déplacer où tu veux.", fontSize = 12.sp)
                    Text("• Sans champ focus, le texte est copié (colle-le à la main).",
                        fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
