package com.example.polyscan

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val periodic = PeriodicWorkRequestBuilder<WhaleWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "whale_poll",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF000000),
                    surface = Color(0xFF000000),
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WhaleScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhaleScreen() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(SettingsStore.load(context)) }
    var whales by remember { mutableStateOf(WhaleStore.loadWhales(context)) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val categories = remember(settings) {
        buildList {
            if (settings.megalodonActive) add("Megalodon")
            if (settings.whaleActive) add("Whale")
            if (settings.dolphinActive) add("Dolphin")
            add("Einstellungen")
        }
    }
    var selected by remember { mutableStateOf(categories.first()) }
    LaunchedEffect(categories) {
        if (selected !in categories) selected = categories.first()
    }

    fun refreshNow() {
        isLoading = true
        val request = OneTimeWorkRequestBuilder<WhaleWorker>().build()
        val wm = WorkManager.getInstance(context)
        wm.enqueue(request)
        scope.launch {
            wm.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
            whales = WhaleStore.loadWhales(context)
            isLoading = false
        }
    }

    val displayWhales = remember(whales, settings) {
        whales.map { it.copy(category = categorize(it.usd, settings)) }
    }

    val buyCount = displayWhales.count { it.side.equals("BUY", ignoreCase = true) }
    val sellCount = displayWhales.count { it.side.equals("SELL", ignoreCase = true) }
    val sideFilter = if (selectedTab == 0) "BUY" else "SELL"
    val bySide = displayWhales.filter { it.side.equals(sideFilter, ignoreCase = true) }

    val megaCount = bySide.count { it.category == "Megalodon" }
    val whaleCount = bySide.count { it.category == "Whale" }
    val dolphinCount = bySide.count { it.category == "Dolphin" }

    val visible = if (selected == "Einstellungen") emptyList()
    else bySide.filter { it.category == selected }

    val chipListState = rememberLazyListState()
    LaunchedEffect(selected) {
        val i = categories.indexOf(selected)
        if (i >= 0) chipListState.animateScrollToItem(i)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Polymarket Megalodontracker") }) },
        floatingActionButton = {
            if (selected != "Einstellungen") {
                ExtendedFloatingActionButton(onClick = { if (!isLoading) refreshNow() }) {
                    Text(if (isLoading) "Suche…" else "Jetzt prüfen")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectedTab == 0) {
                    Button(onClick = { selectedTab = 0 }, modifier = Modifier.weight(1f)) {
                        Text("BUY ($buyCount)")
                    }
                } else {
                    OutlinedButton(onClick = { selectedTab = 0 }, modifier = Modifier.weight(1f)) {
                        Text("BUY ($buyCount)")
                    }
                }
                if (selectedTab == 1) {
                    Button(onClick = { selectedTab = 1 }, modifier = Modifier.weight(1f)) {
                        Text("SELL ($sellCount)")
                    }
                } else {
                    OutlinedButton(onClick = { selectedTab = 1 }, modifier = Modifier.weight(1f)) {
                        Text("SELL ($sellCount)")
                    }
                }
            }

            LazyRow(
                state = chipListState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(categories) { cat ->
                    val count = when (cat) {
                        "Megalodon" -> megaCount
                        "Whale" -> whaleCount
                        "Dolphin" -> dolphinCount
                        else -> null
                    }
                    FilterChip(
                        selected = selected == cat,
                        onClick = { selected = cat },
                        label = { Text(if (count != null) "$cat ($count)" else cat) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF153D29),
                            selectedLabelColor = Color(0xFFECECEC),
                        ),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                val i = categories.indexOf(selected)
                                if (total <= -60f && i < categories.lastIndex) {
                                    selected = categories[i + 1]
                                } else if (total >= 60f && i > 0) {
                                    selected = categories[i - 1]
                                }
                            },
                        ) { _, dragAmount -> total += dragAmount }
                    }
            ) {
                if (selected == "Einstellungen") {
                    SettingsScreen(
                        settings = settings,
                        onSave = { newSettings ->
                            SettingsStore.save(context, newSettings)
                            settings = newSettings
                        },
                    )
                } else if (visible.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (whales.isEmpty())
                                "Noch keine großen Trades gefunden.\nTippe auf „Jetzt prüfen."
                            else
                                "Keine Treffer in dieser Auswahl."
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible) { whale -> WhaleCard(whale) }
                    }
                }
            }
        }
    }
}

@Composable
fun WhaleCard(w: Whale) {
    val time = remember(w.timestampMs) {
        SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault()).format(Date(w.timestampMs))
    }

    val container = when {
        w.side.equals("BUY", ignoreCase = true) -> Color(0xFF153D29)
        w.side.equals("SELL", ignoreCase = true) -> Color(0xFF40161E)
        else -> Color(0xFF1A1A1A)
    }

    val percent = String.format(Locale.getDefault(), "%.1f%%", w.price * 100)
    val sideEng = w.side.uppercase()
    val rightInfo = if (sideEng.isBlank()) percent else "$sideEng · $percent"
    val displayedOutcome = when {
        w.outcome.isNotBlank() -> w.outcome
        w.outcomeIndex == 0 -> "Yes"
        w.outcomeIndex == 1 -> "No"
        else -> ""
    }
    val titleLine = if (displayedOutcome.isNotBlank()) "$displayedOutcome · ${w.title}" else w.title
    val categoryLabel = if (w.usd >= 1_000_000) "GIGALODON" else w.category

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = Color(0xFFECECEC),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(categoryLabel, fontWeight = FontWeight.Bold)
                Text("%,.0f".format(w.usd) + " $", fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(time)
                Text(rightInfo, fontWeight = FontWeight.Medium)
            }
            Text(titleLine)
        }
    }
}

@Composable
fun SettingsScreen(settings: WhaleSettings, onSave: (WhaleSettings) -> Unit) {
    var megalodonText by remember(settings) { mutableStateOf(settings.megalodonMin.toInt().toString()) }
    var whaleText by remember(settings) { mutableStateOf(settings.whaleMin.toInt().toString()) }
    var dolphinText by remember(settings) { mutableStateOf(settings.dolphinMin.toInt().toString()) }
    var megalodonActive by remember(settings) { mutableStateOf(settings.megalodonActive) }
    var whaleActive by remember(settings) { mutableStateOf(settings.whaleActive) }
    var dolphinActive by remember(settings) { mutableStateOf(settings.dolphinActive) }

    val megalodonVal = megalodonText.toDoubleOrNull()
    val whaleVal = whaleText.toDoubleOrNull()
    val dolphinVal = dolphinText.toDoubleOrNull()

    val megalodonError = when {
        megalodonVal == null || megalodonVal <= 0 -> "Ungültige Zahl"
        whaleVal != null && megalodonVal <= whaleVal -> "Muss größer als Whale-Schwelle sein"
        else -> null
    }
    val whaleError = when {
        whaleVal == null || whaleVal <= 0 -> "Ungültige Zahl"
        dolphinVal != null && whaleVal <= dolphinVal -> "Muss größer als Dolphin-Schwelle sein"
        megalodonVal != null && whaleVal >= megalodonVal -> "Muss kleiner als Megalodon-Schwelle sein"
        else -> null
    }
    val dolphinError = when {
        dolphinVal == null || dolphinVal <= 0 -> "Ungültige Zahl"
        whaleVal != null && dolphinVal >= whaleVal -> "Muss kleiner als Whale-Schwelle sein"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CategorySetting(
            label = "Megalodon",
            valueText = megalodonText,
            onValueChange = { megalodonText = it },
            active = megalodonActive,
            onActiveChange = { megalodonActive = it },
            error = megalodonError,
        )
        CategorySetting(
            label = "Whale",
            valueText = whaleText,
            onValueChange = { whaleText = it },
            active = whaleActive,
            onActiveChange = { whaleActive = it },
            error = whaleError,
        )
        CategorySetting(
            label = "Dolphin",
            valueText = dolphinText,
            onValueChange = { dolphinText = it },
            active = dolphinActive,
            onActiveChange = { dolphinActive = it },
            error = dolphinError,
        )
        Button(
            onClick = {
                onSave(
                    WhaleSettings(
                        megalodonMin = megalodonVal!!,
                        whaleMin = whaleVal!!,
                        dolphinMin = dolphinVal!!,
                        megalodonActive = megalodonActive,
                        whaleActive = whaleActive,
                        dolphinActive = dolphinActive,
                    )
                )
            },
            enabled = megalodonError == null && whaleError == null && dolphinError == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Speichern")
        }
        Text(
            text = "Made in Hannover with Love © by M.Kaydot",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun CategorySetting(
    label: String,
    valueText: String,
    onValueChange: (String) -> Unit,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(checked = !active, onCheckedChange = { onActiveChange(!it) })
            Text("Deaktivieren")
        }
        OutlinedTextField(
            value = valueText,
            onValueChange = onValueChange,
            label = { Text("Mindestbetrag ($)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = error != null,
            supportingText = if (error != null) ({ Text(error) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
