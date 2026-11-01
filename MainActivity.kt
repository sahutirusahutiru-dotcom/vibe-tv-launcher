@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.vibe.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppEntry(
    val label: String,
    val pkg: String,
    val intent: Intent,
    val image: ImageBitmap,
    val isBanner: Boolean,
    val accent: Color,
)

class MainActivity : ComponentActivity() {
    private val refresh = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LauncherApp(refresh.intValue) }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }
}

fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    fun query(cat: String) =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)

    return (query(Intent.CATEGORY_LEANBACK_LAUNCHER) + query(Intent.CATEGORY_LAUNCHER))
        .filter { it.activityInfo.packageName != ctx.packageName }
        .distinctBy { it.activityInfo.packageName }
        .mapNotNull { ri ->
            val ai = ri.activityInfo
            val banner = ai.loadBanner(pm)
            val drawable = banner ?: ai.loadIcon(pm) ?: return@mapNotNull null
            val bmp = if (banner != null) drawable.toBitmap(320, 180) else drawable.toBitmap(128, 128)
            val px = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
            AppEntry(
                label = ri.loadLabel(pm).toString(),
                pkg = ai.packageName,
                intent = Intent(Intent.ACTION_MAIN)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                image = bmp.asImageBitmap(),
                isBanner = banner != null,
                accent = Color(px).copy(alpha = 1f),
            )
        }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun LauncherApp(refresh: Int) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var focused by remember { mutableStateOf<AppEntry?>(null) }

    LaunchedEffect(refresh) { apps = withContext(Dispatchers.Default) { loadApps(ctx) } }

    // "Dynamic": most-used shelf is built from launch counts
    val mostUsed = remember(apps, refresh) {
        apps.filter { prefs.getInt(it.pkg, 0) > 0 }
            .sortedByDescending { prefs.getInt(it.pkg, 0) }
            .take(10)
    }
    val open: (AppEntry) -> Unit = { app ->
        prefs.edit().putInt(app.pkg, prefs.getInt(app.pkg, 0) + 1).apply()
        runCatching { ctx.startActivity(app.intent) }
    }

    val accent by animateColorAsState(focused?.accent ?: Color(0xFF1E3A8A), tween(600), label = "accent")
    BackHandler { }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(accent.copy(alpha = 0.65f), Color.Black), endY = 1100f)
                )
            )
            Column(Modifier.fillMaxSize().padding(top = 36.dp)) {
                TopBar()
                Hero(focused, Modifier.weight(1f))
                if (mostUsed.isNotEmpty()) {
                    Shelf("Most used", mostUsed, false, { focused = it }, open)
                }
                Shelf("All apps", apps, mostUsed.isEmpty(), { focused = it }, open)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun TopBar() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(10_000) } }
    val time = remember(now) { SimpleDateFormat("h:mm a", Locale.getDefault()).format(now) }
    val day = remember(now) { SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Home", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Column(horizontalAlignment = Alignment.End) {
            Text(time, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Text(day, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        }
    }
}

@Composable
fun Hero(app: AppEntry?, modifier: Modifier) {
    Column(modifier.padding(horizontal = 56.dp), verticalArrangement = Arrangement.Bottom) {
        Text(
            app?.label ?: "Welcome",
            color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text("Press OK to open", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
fun Shelf(
    title: String,
    items: List<AppEntry>,
    requestFirst: Boolean,
    onFocus: (AppEntry) -> Unit,
    onOpen: (AppEntry) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(items.isNotEmpty()) {
        if (requestFirst && items.isNotEmpty()) runCatching { first.requestFocus() }
    }
    Text(
        title, color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 56.dp, top = 8.dp),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(items, key = { _, a -> title + a.pkg }) { i, app ->
            Tile(
                app,
                Modifier
                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { if (it.hasFocus) onFocus(app) },
                onOpen,
            )
        }
    }
}

@Composable
fun Tile(app: AppEntry, modifier: Modifier, onOpen: (AppEntry) -> Unit) {
    Column(Modifier.width(224.dp)) {
        Surface(
            onClick = { onOpen(app) },
            modifier = modifier,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Color.White))
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF1C1C1E),
                focusedContainerColor = Color(0xFF2C2C2E),
            ),
        ) {
            Image(
                bitmap = app.image,
                contentDescription = app.label,
                contentScale = if (app.isBanner) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.size(224.dp, 126.dp).padding(if (app.isBanner) 0.dp else 22.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            app.label, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
