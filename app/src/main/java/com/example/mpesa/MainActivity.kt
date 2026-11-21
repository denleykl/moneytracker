package com.example.mpesa

// UNTESTED sample: I have no Android SDK here. Everything it calls (StatementCsv, MpesaSms, RawMerge, ReviewEngine, Similar,
// Insights, Ask, TransactionClassifier, KnownMerchants) IS tested on the JVM. If Android Studio shows red imports, press Alt+Enter.
//
// Needs in AndroidManifest.xml, directly inside <manifest> and above <application>:
//     <uses-permission android:name="android.permission.READ_SMS" />
// Optional: app/src/main/assets/known_merchants.csv (made by export_known.py) for better automatic categories.

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.mpesa.ui.theme.MpesaTheme
import com.mpesa.app.*   // MUST equal the first line ('package ...') of Classifier.kt. Delete this line if that package is com.example.mpesa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Keeps the user's answers on the phone, keyed by the hashed counterparty id (no names stored). */
class PrefsCorrections(private val prefs: SharedPreferences) : CorrectionStore {
    override fun get(counterparty: String): String? = prefs.getString(counterparty, null)
    override fun set(counterparty: String, category: String) { prefs.edit { putString(counterparty, category) } }
    override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
}

/** Monthly budgets per category, kept on the phone. */
class PrefsBudgets(private val prefs: SharedPreferences) : BudgetStore {
    override fun all(): Map<String, Double> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.toDoubleOrNull()?.let { k to it } }.toMap()
    override fun set(category: String, amount: Double?) { prefs.edit { if (amount == null) remove(category) else putString(category, amount.toString()) } }
}

private class SimilarPrompt(val name: String, val category: String, val items: List<ReviewItem>)

// ---------- look and feel ----------
private val Teal = Color(0xFF0B6E4F)
private val TealLight = Color(0xFF1B998B)
private val Purple = Color(0xFF6750A4)
private val Slate = Color(0xFF607D8B)
private val Green = Color(0xFF2E7D32)
private val Orange = Color(0xFFEF6C00)
private val Red = Color(0xFFC62828)

private fun kes(x: Double) = "KES " + "%,.0f".format(x)
private fun colorFor(category: String) = when {
    category.startsWith("Business") -> Teal
    category.startsWith("Personal") -> Purple
    else -> Slate
}

@Composable
private fun Header(s: Summary, rangeLabel: String) {
    Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Teal, TealLight)), RoundedCornerShape(24.dp)).padding(20.dp)) {
        Column {
            Text("Net business  ·  $rangeLabel", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
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
private fun Bar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp).background(color, RoundedCornerShape(4.dp)))
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
            Bar((c.moneyOut / top).toFloat(), colorFor(c.category))
        }
    }
}

@Composable
private fun NudgeCard(n: Nudge) {
    val container = when (n.severity) {
        Severity.ALERT -> MaterialTheme.colorScheme.errorContainer
        Severity.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        Severity.GOOD -> MaterialTheme.colorScheme.primaryContainer
        Severity.INFO -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(14.dp)) {
            Text(n.title, fontWeight = FontWeight.SemiBold)
            Text(n.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CrashScreen(report: String, onShare: () -> Unit, onContinue: () -> Unit) {
    Scaffold(Modifier.fillMaxSize()) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("The app hit an error last time", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Tap Share and send this report to whoever is helping you. Read it first: error messages can contain a little text from your statement.",
                Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShare) { Text("Share report") }
                OutlinedButton(onClick = onContinue) { Text("Continue to the app") }
            }
            Text(report.take(4000), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun PlanCard(title: String, a: Allowance) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            val budget = a.budget
            if (budget == null) {
                Text("Not enough history yet.", fontWeight = FontWeight.SemiBold)
            } else {
                Text(kes(budget), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Spent ${kes(a.spent)}  ·  Left ${kes(a.left ?: 0.0)}", style = MaterialTheme.typography.bodySmall)
                if (budget > 0) { Spacer(Modifier.height(6.dp)); Bar((a.spent / budget).toFloat(), if (a.spent > budget) Red else Teal) }
                Text("Based on: ${a.basis}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            a.notes.forEach { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun sourceLabel(r: Categorised) = when (r.source) {
    Source.USER -> "you"
    Source.KNOWN -> "known"
    Source.RULE -> "rule"
    Source.PATTERN -> "pattern"
    Source.MODEL -> "${"%.0f".format(r.confidence * 100)}%"
}

class MainActivity : ComponentActivity() {
    private val classifier by lazy { TransactionClassifier(ModelIO.load(assets.open("model.bin"))) }
    /** Final engine: strong keyword rule first, and shop names the model never saw go to Review (NameGuard.kt). */
    private val guard by lazy { NameGuard(ModelIO.load(assets.open("model.bin"))) }
    private val store by lazy { PrefsCorrections(getSharedPreferences("corrections", MODE_PRIVATE)) }
    private val budgetStore by lazy { PrefsBudgets(getSharedPreferences("budgets", MODE_PRIVATE)) }
    private val known: KnownMerchants? by lazy { try { KnownMerchants.load(assets.open("known_merchants.csv")) } catch (_: Exception) { null } }
    private val rowsFile by lazy { File(filesDir, "statement_rows.tsv") }
    private val bizPrefs by lazy { getSharedPreferences("business", MODE_PRIVATE) }
    private val crashFile by lazy { File(filesDir, "last_crash.txt") }
    private var problemLogged = false

    private fun deviceLine() = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    /** If the app dies, the error is saved to a file; next launch shows it on screen so it can be shared without Logcat. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { crashFile.writeText("CRASH at ${LocalDateTime.now()} on thread ${thread.name}\n${deviceLine()}\n\n" + Log.getStackTraceString(e)) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }

    /** An error that was caught and survived: logged, and saved once per run so it also shows up on the report screen. */
    private fun recordProblem(e: Throwable) {
        Log.e("Mpesa", "caught problem", e)
        if (problemLogged) return
        problemLogged = true
        try { crashFile.writeText("CAUGHT PROBLEM at ${LocalDateTime.now()} (the app kept running)\n${deviceLine()}\n\n" + Log.getStackTraceString(e)) } catch (_: Throwable) {}
    }

    /** Runs a calculation that is only for display; if it fails the screen shows `default` instead of crashing the app. */
    private fun <T> safe(default: T, block: () -> T): T = try { block() } catch (e: Throwable) { recordProblem(e); default }

    private fun shareText(text: String) = startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share crash report"))

    /** Categorises everything, including the business pattern learned from the user's own money-in history. */
    private fun computeWith(t: List<Transaction>, lookbackDays: Int, manualPrices: String, auto: Boolean): Pair<List<Categorised>, BusinessProfile> {
        val prof = Business.profile(t, lookbackDays, Business.parseBands(manualPrices), auto)
        return guard.apply(ReviewEngine.categorise(t, classifier, store, known = known, business = prof)) to prof
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRecorder()
        enableEdgeToEdge()
        setContent { MpesaTheme { Root() } }
    }

    private fun loadSaved(): List<RawRow> = try { if (rowsFile.exists()) RawCodec.decode(rowsFile.readText()) else emptyList() } catch (_: Exception) { emptyList() }
    private fun saveRows(rows: List<RawRow>) { rowsFile.writeText(RawCodec.encode(rows)) }

    /** Opens this app's page in system Settings, where Permissions > SMS can be switched on. */
    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    /** M-Pesa messages from the inbox, optionally only those received since `sinceMillis`. */
    private fun readSms(sinceMillis: Long?): List<SmsInput> {
        val out = ArrayList<SmsInput>()
        val selection = if (sinceMillis == null) "${Telephony.Sms.ADDRESS} LIKE ?" else "${Telephony.Sms.ADDRESS} LIKE ? AND ${Telephony.Sms.DATE} >= ?"
        val args = if (sinceMillis == null) arrayOf("MPESA") else arrayOf("MPESA", sinceMillis.toString())
        contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE), selection, args, "${Telephony.Sms.DATE} ASC")?.use { c ->
            val b = c.getColumnIndexOrThrow(Telephony.Sms.BODY); val d = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) out.add(SmsInput(c.getString(b) ?: "", LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(d)), ZoneId.systemDefault())))
        }
        return out
    }

    @Composable
    fun Root() {
        var report by remember { mutableStateOf(try { if (crashFile.exists()) crashFile.readText() else null } catch (_: Throwable) { null }) }
        val r = report
        if (r != null) CrashScreen(r, onShare = { shareText(r) }, onContinue = { crashFile.delete(); problemLogged = false; report = null }) else App()
    }

    @Composable
    fun App() {
        // ----- state -----
        var raw by remember { mutableStateOf(loadSaved()) }
        var txs by remember { mutableStateOf<List<Transaction>>(emptyList()) }
        var cats by remember { mutableStateOf<List<Categorised>>(emptyList()) }
        var status by remember { mutableStateOf("Import a statement CSV or read your M-Pesa messages.") }
        var tab by remember { mutableIntStateOf(0) }
        var preset by remember { mutableStateOf(Preset.THIS_MONTH) }
        var smsMonths by remember { mutableIntStateOf(3) }
        var openFor by remember { mutableStateOf<String?>(null) }
        var expandedTx by remember { mutableStateOf<String?>(null) }
        var similar by remember { mutableStateOf<SimilarPrompt?>(null) }
        var budgets by remember { mutableStateOf(budgetStore.all()) }
        var askText by remember { mutableStateOf("") }
        var answer by remember { mutableStateOf<Answer?>(null) }
        var addMenu by remember { mutableStateOf(false) }
        var lookback by remember { mutableIntStateOf(bizPrefs.getInt("lookback", 90)) }
        var manualText by remember { mutableStateOf(bizPrefs.getString("bands", "") ?: "") }
        var bandsInput by remember { mutableStateOf(bizPrefs.getString("bands", "") ?: "") }
        var useAuto by remember { mutableStateOf(bizPrefs.getBoolean("auto", true)) }
        var profile by remember { mutableStateOf<BusinessProfile?>(null) }
        val scope = rememberCoroutineScope()

        // ----- derived values -----
        val ref = remember(cats) { Periods.reference(cats) }
        val range = remember(ref, preset) { Periods.resolve(preset, ref) }
        val view = remember(cats, range) { safe(emptyList()) { Periods.filter(cats, range) } }
        val summary = remember(view) { safe(ReviewEngine.summarise(emptyList())) { ReviewEngine.summarise(view) } }
        val queue = remember(cats) { safe(emptyList()) { ReviewEngine.reviewQueue(cats) } }
        val nudges = remember(cats, budgets) { safe(emptyList()) { Insights.nudges(cats, budgets, ref) } }
        val statuses = remember(cats, budgets) { safe(emptyList()) { Insights.budgetStatus(cats, budgets, ref) } }
        val weekPlan = remember(cats, budgets) { safe<Allowance?>(null) { Advisor.allowance(cats, budgets, ref, Horizon.WEEK) } }
        val todayPlan = remember(cats, budgets) { safe<Allowance?>(null) { Advisor.allowance(cats, budgets, ref, Horizon.TODAY) } }
        val options = remember { (classifier.classes + listOf("Business - Customer/Supplier (P2P)", "Personal - Family & Friends", "Business - Sales income")).distinct() }

        // ----- actions -----
        fun recompute() {
            scope.launch {
                try {
                    val lb = lookback; val mt = manualText; val ua = useAuto; val t = txs
                    val (c, p) = withContext(Dispatchers.Default) { computeWith(t, lb, mt, ua) }
                    cats = c; profile = p
                } catch (e: Throwable) { status = "Failed: $e"; recordProblem(e) }
            }
        }
        fun change(counterparty: String, category: String) { store.set(counterparty, category); recompute() }
        fun answerReview(q: ReviewItem, category: String) {
            val sim = Similar.pending(q, queue)
            change(q.counterparty, category)
            similar = if (sim.isNotEmpty()) SimilarPrompt(q.displayName, category, sim) else null
        }
        fun setBudget(category: String, amount: Double?) { budgetStore.set(category, amount); budgets = budgetStore.all() }

        fun ingest(newRows: List<RawRow>, note: String) = scope.launch {
            try {
                val merged = withContext(Dispatchers.Default) { RawMerge.merge(raw, newRows) }
                withContext(Dispatchers.IO) { saveRows(merged) }
                val t = withContext(Dispatchers.Default) { StatementProcessor.process(merged) }
                val lb = lookback; val mt = manualText; val ua = useAuto
                val (c, p) = withContext(Dispatchers.Default) { computeWith(t, lb, mt, ua) }
                raw = merged; txs = t; cats = c; profile = p
                status = "${t.size} transactions saved on this phone$note"
            } catch (e: Throwable) { status = "Failed: $e" }   // Throwable: show errors instead of crashing
        }

        fun importSms() = scope.launch {
            status = "Reading messages..."
            try {
                val since = if (smsMonths == 0) null else LocalDate.now().minusMonths(smsMonths.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val res = withContext(Dispatchers.IO) { MpesaSms.parseAll(readSms(since)) }
                res.unrecognised.forEach { Log.w("MpesaSms", "Unrecognised: $it") }
                ingest(res.rows, " (SMS)" + if (res.unrecognised.isNotEmpty()) ", ${res.unrecognised.size} messages not understood (see Logcat tag MpesaSms)" else "")
            } catch (e: Throwable) { status = "Failed: $e" }
        }

        // load what was saved last time
        LaunchedEffect(Unit) {
            if (raw.isNotEmpty()) try {
                val t = withContext(Dispatchers.Default) { StatementProcessor.process(raw) }
                txs = t
                val lb = lookback; val mt = manualText; val ua = useAuto
                val (c, p) = withContext(Dispatchers.Default) { computeWith(t, lb, mt, ua) }
                cats = c; profile = p
                status = "${t.size} saved transactions loaded"
            } catch (e: Throwable) { status = "Failed: $e" }
        }

        val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                status = "Reading..."
                try {
                    val rows = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { StatementCsv.read(it) } }
                    ingest(rows, " (CSV)")
                } catch (e: Throwable) { status = "Failed: $e" }
            }
        }
        val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) importSms() else {
                status = "SMS permission is off. In Settings open Permissions > SMS > Allow, then come back and tap Read M-Pesa SMS again."
                openAppSettings()
            }
        }

        @Composable
        fun RangeChips() {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { p -> FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(p.label) }) }
            }
        }

        @Composable
        fun CategoryMenu(counterparty: String, onPick: (String) -> Unit) {
            DropdownMenu(expanded = openFor == counterparty, onDismissRequest = { openFor = null }) {
                options.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { openFor = null; onPick(c) }) }
            }
        }

        // ----- screen -----
        Scaffold(
            bottomBar = {
                NavigationBar {
                    val labels = listOf("Home", "Review ${queue.size}", "Activity", "Insights", "Ask")
                    val icons = listOf("🏠", "❓", "🧾", "💡", "💬")
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
                // import controls: on Home, or whenever there is no data yet
                if (tab == 0 || cats.isEmpty()) item {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "*/*")) }) { Text("Import CSV") }
                            FilledTonalButton(onClick = {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) importSms()
                                else smsPermission.launch(Manifest.permission.READ_SMS)
                            }) { Text("Read M-Pesa SMS") }
                        }
                        Text("Messages to read:", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "Last month", 3 to "3 months", 6 to "6 months", 0 to "All").forEach { (m, label) ->
                                FilterChip(selected = smsMonths == m, onClick = { smsMonths = m }, label = { Text(label) })
                            }
                        }
                        Text(status, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (cats.isEmpty()) {
                    item { Text("Nothing here yet. Import a statement or read your messages above, on the Home tab.") }
                } else when (tab) {
                    0 -> {
                        item { RangeChips() }
                        item { Header(summary, preset.label) }
                        profile?.takeIf { !it.isEmpty }?.let { p ->
                            item { Text("Sale prices I learned from your history: " + p.bands.joinToString(" and ") { it.label } + ". Change them on the Insights tab.", style = MaterialTheme.typography.bodySmall) }
                        }
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
                        item {
                            TextButton(onClick = { raw = emptyList(); txs = emptyList(); cats = emptyList(); profile = null; rowsFile.delete(); status = "Saved data cleared." }) { Text("Clear saved data") }
                        }
                    }
                    1 -> {
                        similar?.let { sp ->
                            item {
                                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text("You set ${sp.name} to ${sp.category}.", fontWeight = FontWeight.SemiBold)
                                        Text("Apply it to ${sp.items.size} similar: ${sp.items.joinToString { it.displayName.take(24) }}?", style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { sp.items.forEach { store.set(it.counterparty, sp.category) }; similar = null; recompute() }) { Text("Apply to all") }
                                            TextButton(onClick = { similar = null }) { Text("No thanks") }
                                        }
                                    }
                                }
                            }
                        }
                        if (queue.isEmpty()) item { Text("Nothing to review. Everything has a category.") }
                        items(queue) { q ->
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(q.displayName.take(40), fontWeight = FontWeight.SemiBold)
                                    Text("${q.count} transactions, ${kes(q.totalAmount)}", style = MaterialTheme.typography.bodySmall)
                                    Text("Best guess: ${q.suggested} (${"%.0f".format(q.confidence * 100)}%)", style = MaterialTheme.typography.bodySmall, color = colorFor(q.suggested))
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { answerReview(q, q.suggested) }) { Text("Accept") }
                                        Box {
                                            OutlinedButton(onClick = { openFor = q.counterparty }) { Text("Change...") }
                                            CategoryMenu(q.counterparty) { c -> answerReview(q, c) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        item { RangeChips() }
                        item { Text("Tap a transaction to see why it has its category.", style = MaterialTheme.typography.bodySmall) }
                        items(view) { r ->
                            val key = r.tx.receipt + r.tx.details
                            Column(Modifier.fillMaxWidth().clickable { expandedTx = if (expandedTx == key) null else key }) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.tx.details.take(44), maxLines = 1)
                                        Text("${r.category}  (${sourceLabel(r)})", style = MaterialTheme.typography.bodySmall, color = colorFor(r.category))
                                    }
                                    Text((if (r.tx.isInflow) "+" else "-") + "%,.0f".format(r.tx.amount),
                                        color = if (r.tx.isInflow) Green else Red, fontWeight = FontWeight.SemiBold)
                                }
                                if (expandedTx == key) {
                                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("Why this category?", style = MaterialTheme.typography.labelLarge)
                                            safe(listOf("No explanation available for this one.")) { ReviewEngine.explain(r, classifier) }.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                            Spacer(Modifier.height(8.dp))
                                            Box {
                                                OutlinedButton(onClick = { openFor = r.counterparty }) { Text("Wrong? Change it") }
                                                CategoryMenu(r.counterparty) { c -> change(r.counterparty, c) }
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    3 -> {
                        item { Text("Your plan", style = MaterialTheme.typography.titleMedium) }
                        weekPlan?.let { w -> item { PlanCard("This week", w) } }
                        todayPlan?.let { t -> item { PlanCard("Today", t) } }
                        item { Text("Nudges", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                        if (nudges.isEmpty()) item { Text("No nudges right now. Set a budget below, or import more history.") }
                        items(nudges) { n -> NudgeCard(n) }
                        item { Text("Monthly budgets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { Insights.suggestBudgets(cats, ref).forEach { (c, a) -> if (c !in budgets) setBudget(c, a) } }) { Text("Suggest budgets") }
                                Box {
                                    OutlinedButton(onClick = { addMenu = true }) { Text("Add budget...") }
                                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                        options.filter { it !in budgets && it != "Savings, Loans & Fuliza" && it != "Person-to-Person" }.forEach { c ->
                                            DropdownMenuItem(text = { Text(c) }, onClick = { addMenu = false; setBudget(c, 2000.0) })
                                        }
                                    }
                                }
                            }
                        }
                        if (statuses.isEmpty()) item { Text("No budgets yet. Tap Suggest budgets for a starting point based on your own spending.", style = MaterialTheme.typography.bodySmall) }
                        items(statuses) { b ->
                            Column {
                                Row {
                                    Text(b.category, Modifier.weight(1f), maxLines = 1, fontWeight = FontWeight.SemiBold)
                                    Text("${kes(b.spent)} / ${kes(b.limit)}")
                                }
                                Spacer(Modifier.height(4.dp))
                                Bar(b.ratio.toFloat(), if (b.ratio >= 1.0) Red else if (b.ratio >= 0.8) Orange else Green)
                                Row {
                                    TextButton(onClick = { setBudget(b.category, maxOf(500.0, b.limit - 500.0)) }) { Text("-500") }
                                    TextButton(onClick = { setBudget(b.category, b.limit + 500.0) }) { Text("+500") }
                                    TextButton(onClick = { setBudget(b.category, null) }) { Text("Remove") }
                                }
                            }
                        }
                        item { Text("My business prices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                        item { Text("I look at the money people send you and find the amounts customers pay again and again. Payments in those ranges from people who only pay you (not family you also send money to) count as sales.", style = MaterialTheme.typography.bodySmall) }
                        item {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(30, 90, 180).forEach { d ->
                                    FilterChip(selected = lookback == d, onClick = { lookback = d; bizPrefs.edit { putInt("lookback", d) }; recompute() }, label = { Text("learn from last $d days") })
                                }
                            }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = useAuto, onCheckedChange = { useAuto = it; bizPrefs.edit { putBoolean("auto", it) }; recompute() })
                                Text("Learn my prices automatically", Modifier.padding(start = 8.dp))
                            }
                        }
                        val bandList = profile?.bands ?: emptyList()
                        if (bandList.isEmpty()) item { Text("No price pattern found yet. I need at least 8 payments from 3 different people at similar amounts, or type your own prices below.", style = MaterialTheme.typography.bodySmall) }
                        items(bandList) { b -> Text("• ${b.label}  (${if (b.manual) "typed in by you" else "${b.count} payments from ${b.senders} people"})") }
                        item {
                            OutlinedTextField(value = bandsInput, onValueChange = { bandsInput = it }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("My prices, e.g. 100-300, 1500-3000") }, singleLine = true)
                        }
                        item { Button(onClick = { manualText = bandsInput; bizPrefs.edit { putString("bands", bandsInput) }; recompute() }) { Text("Save my prices") } }
                    }
                    else -> {
                        item { Text("Ask about your money", style = MaterialTheme.typography.titleMedium) }
                        item { Text("Ask in your own words: budget for next week, compare this week with last week, how many customers, food over 500...", style = MaterialTheme.typography.bodySmall) }
                        item {
                            OutlinedTextField(value = askText, onValueChange = { askText = it }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("e.g. food last week") }, singleLine = true)
                        }
                        item { Button(onClick = { answer = safe(Answer("Something went wrong answering that. Try rewording it.")) { Ask.answer(askText, cats, ref, budgets, profile) } }) { Text("Ask") } }
                        item {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Ask.suggestions.forEach { s -> AssistChip(onClick = { askText = s; answer = safe(Answer("Something went wrong answering that. Try rewording it.")) { Ask.answer(s, cats, ref, budgets, profile) } }, label = { Text(s) }) }
                            }
                        }
                        answer?.let { a ->
                            item {
                                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(a.text, fontWeight = FontWeight.SemiBold)
                                        a.details.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            }
                        }
                        item { Text("Answers come from your own transactions on this phone. Nothing is sent anywhere.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
