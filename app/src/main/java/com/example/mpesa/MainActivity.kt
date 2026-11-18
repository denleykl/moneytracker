package com.example.mpesa

// UNTESTED sample: I have no Android SDK here. Everything it calls (StatementCsv, MpesaSms, StatementProcessor,
// ReviewEngine, TransactionClassifier, KnownMerchants) IS tested on the JVM. If Android Studio shows red imports, press Alt+Enter.
//
// Needs in AndroidManifest.xml, directly inside <manifest> and above <application>:
//     <uses-permission android:name="android.permission.READ_SMS" />
// Optional: app/src/main/assets/known_merchants.csv (made by export_known.py) for much better automatic categories.

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.mpesa.ui.theme.MpesaTheme
import com.mpesa.app.*   // MUST equal the first line ('package ...') of Classifier.kt. Delete this line if that package is com.example.mpesa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Keeps the user's answers on the phone, keyed by the hashed counterparty id (no names stored). */
class PrefsCorrections(private val prefs: SharedPreferences) : CorrectionStore {
    override fun get(counterparty: String): String? = prefs.getString(counterparty, null)
    override fun set(counterparty: String, category: String) { prefs.edit { putString(counterparty, category) } }
    override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
}

// ---------- look and feel ----------
private val Teal = Color(0xFF0B6E4F)
private val TealLight = Color(0xFF1B998B)
private val Purple = Color(0xFF6750A4)
private val Slate = Color(0xFF607D8B)
private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

private fun kes(x: Double) = "KES " + "%,.0f".format(x)
private fun colorFor(category: String) = when {
    category.startsWith("Business") -> Teal
    category.startsWith("Personal") -> Purple
    else -> Slate
}

@Composable
private fun Header(s: Summary) {
    Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Teal, TealLight)), RoundedCornerShape(24.dp)).padding(20.dp)) {
        Column {
            Text("Net business", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            Text(kes(s.netBusiness), color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Business income", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                    Text(kes(s.flow(Flow.BUSINESS_INCOME)), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Business expenses", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                    Text(kes(s.flow(Flow.BUSINESS_EXPENSE)), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, color = accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CategoryBars(s: Summary) {
    val rows = s.byCategory.filter { it.moneyOut > 0 }.sortedByDescending { it.moneyOut }.take(6)
    val top = rows.maxOfOrNull { it.moneyOut } ?: 1.0
    rows.forEach { c ->
        Column(Modifier.padding(vertical = 6.dp)) {
            Row {
                Text(c.category, Modifier.weight(1f), maxLines = 1)
                Text(kes(c.moneyOut), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth((c.moneyOut / top).toFloat().coerceIn(0.02f, 1f)).height(8.dp).background(colorFor(c.category), RoundedCornerShape(4.dp)))
            }
        }
    }
}

private fun sourceLabel(r: Categorised) = when (r.source) {
    Source.USER -> "you"
    Source.KNOWN -> "known"
    Source.RULE -> "rule"
    Source.MODEL -> "${"%.0f".format(r.confidence * 100)}%"
}

class MainActivity : ComponentActivity() {
    private val classifier by lazy { TransactionClassifier(ModelIO.load(assets.open("model.bin"))) }
    private val store by lazy { PrefsCorrections(getSharedPreferences("corrections", MODE_PRIVATE)) }
    private val known: KnownMerchants? by lazy { try { KnownMerchants.load(assets.open("known_merchants.csv")) } catch (_: Exception) { null } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MpesaTheme { App() } }
    }

    /** Opens this app's page in system Settings, where Permissions > SMS can be switched on. */
    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun readSms(): List<SmsInput> {
        val out = ArrayList<SmsInput>()
        contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.ADDRESS} LIKE ?", arrayOf("MPESA"), "${Telephony.Sms.DATE} ASC")?.use { c ->
            val b = c.getColumnIndexOrThrow(Telephony.Sms.BODY); val d = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) out.add(SmsInput(c.getString(b) ?: "", LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(d)), ZoneId.systemDefault())))
        }
        return out
    }

    @Composable
    fun App() {
        var cats by remember { mutableStateOf<List<Categorised>>(emptyList()) }
        var txs by remember { mutableStateOf<List<Transaction>>(emptyList()) }
        var status by remember { mutableStateOf("Import a statement CSV or read your M-Pesa messages.") }
        var tab by remember { mutableIntStateOf(0) }
        var openFor by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        fun recompute() { cats = ReviewEngine.categorise(txs, classifier, store, known = known) }
        fun answer(counterparty: String, category: String) { store.set(counterparty, category); recompute() }

        fun load(rows: List<RawRow>, note: String) = scope.launch {
            try {
                val loaded = withContext(Dispatchers.Default) { StatementProcessor.process(rows) }
                txs = loaded
                cats = withContext(Dispatchers.Default) { ReviewEngine.categorise(loaded, classifier, store, known = known) }
                status = "${cats.size} transactions$note"
            } catch (e: Throwable) { status = "Failed: $e" }   // Throwable: show errors instead of crashing
        }

        fun importSms() = scope.launch {
            status = "Reading messages..."
            try {
                val res = withContext(Dispatchers.Default) { MpesaSms.parseAll(readSms()) }
                res.unrecognised.forEach { Log.w("MpesaSms", "Unrecognised: $it") }
                load(res.rows, " from SMS" + if (res.unrecognised.isNotEmpty()) " (${res.unrecognised.size} messages not understood, see Logcat 'MpesaSms')" else "")
            } catch (e: Throwable) { status = "Failed: $e" }
        }

        val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                status = "Reading..."
                try {
                    val rows = withContext(Dispatchers.Default) { contentResolver.openInputStream(uri)!!.use { StatementCsv.read(it) } }
                    load(rows, " from CSV")
                } catch (e: Throwable) { status = "Failed: $e" }
            }
        }
        val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) importSms() else {
                status = "SMS permission is off. In Settings open Permissions > SMS > Allow, then come back and tap Read M-Pesa SMS again."
                openAppSettings()
            }
        }

        val summary = remember(cats) { ReviewEngine.summarise(cats) }
        val queue = remember(cats) { ReviewEngine.reviewQueue(cats) }
        val options = remember { (classifier.classes + listOf("Business - Customer/Supplier (P2P)", "Personal - Family & Friends", "Business - Sales income")).distinct() }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    val labels = listOf("Dashboard", "Review (${queue.size})", "Transactions")
                    val icons = listOf("📊", "❓", "🧾")
                    labels.forEachIndexed { i, label ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icons[i]) }, label = { Text(label, maxLines = 1) })
                    }
                }
            }
        ) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "*/*")) }) { Text("Import CSV") }
                        FilledTonalButton(onClick = {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) importSms()
                            else smsPermission.launch(Manifest.permission.READ_SMS)
                        }) { Text("Read M-Pesa SMS") }
                    }
                    Text(status, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (cats.isNotEmpty()) when (tab) {
                    0 -> {
                        item { Header(summary) }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatCard("Personal spending", kes(summary.flow(Flow.PERSONAL_SPEND)), Purple, Modifier.weight(1f))
                                StatCard("Loans, fees, cash & transfers out", kes(summary.flow(Flow.OTHER_OUT)), Slate, Modifier.weight(1f))
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatCard("Money in", kes(summary.moneyIn), Green, Modifier.weight(1f))
                                StatCard("Money out", kes(summary.moneyOut), Red, Modifier.weight(1f))
                            }
                        }
                        if (queue.isNotEmpty()) item {
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${queue.size} questions need your answer", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    TextButton(onClick = { tab = 1 }) { Text("Answer") }
                                }
                            }
                        }
                        item { Text("Where your money went", style = MaterialTheme.typography.titleMedium) }
                        item { CategoryBars(summary) }
                    }
                    1 -> {
                        if (queue.isEmpty()) item { Text("Nothing to review. Everything has a category.") }
                        items(queue) { q ->
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(q.displayName.take(40), fontWeight = FontWeight.SemiBold)
                                    Text("${q.count} transactions, ${kes(q.totalAmount)}", style = MaterialTheme.typography.bodySmall)
                                    Text("Best guess: ${q.suggested} (${"%.0f".format(q.confidence * 100)}%)", style = MaterialTheme.typography.bodySmall, color = colorFor(q.suggested))
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { answer(q.counterparty, q.suggested) }) { Text("Accept") }
                                        Box {
                                            OutlinedButton(onClick = { openFor = q.counterparty }) { Text("Change...") }
                                            DropdownMenu(expanded = openFor == q.counterparty, onDismissRequest = { openFor = null }) {
                                                options.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { openFor = null; answer(q.counterparty, c) }) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> items(cats) { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.tx.details.take(44), maxLines = 1)
                                Text("${r.category}  (${sourceLabel(r)})", style = MaterialTheme.typography.bodySmall, color = colorFor(r.category))
                            }
                            Text((if (r.tx.isInflow) "+" else "-") + "%,.0f".format(r.tx.amount),
                                color = if (r.tx.isInflow) Green else Red, fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
