package com.sameer8055.studs

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

private val Bg = Color(0xFF0B0D12)
private val Panel = Color(0xFF171C27)
private val Stroke = Color(0xFF2A3140)
private val Ink = Color(0xFFF2F4FA)
private val Muted = Color(0xFF929BAD)
private val Accent = Color(0xFFB8C7FF)

class ReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) { finish(); return }
        val title = intent.getStringExtra("document_name") ?: "PDF document"
        setContent { MaterialTheme { ReaderScreen(uri, title) { finish() } } }
    }
}

private class PdfFile(context: android.content.Context, uri: Uri) {
    private val descriptor: ParcelFileDescriptor =
        requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
    private val renderer = PdfRenderer(descriptor)
    val pageCount get() = renderer.pageCount
    fun render(index: Int, width: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val w = max(1, width)
            val h = max(1, (w.toFloat() * page.height / page.width).toInt())
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(android.graphics.Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally { page.close() }
    }
    fun close() { renderer.close(); descriptor.close() }
}

@Composable
private fun ReaderScreen(uri: Uri, title: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val opened = remember(uri) { runCatching { PdfFile(context, uri) } }
    DisposableEffect(opened) { onDispose { opened.getOrNull()?.close() } }

    if (opened.isFailure) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Unable to open this PDF", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(opened.exceptionOrNull()?.localizedMessage ?: "The file may be damaged or unavailable.", color = Muted)
                TextButton(onClick = onExit) { Text("Back", color = Accent) }
            }
        }
        return
    }
    val pdf = opened.getOrThrow()
    if (pdf.pageCount < 1) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("This PDF has no pages.", color = Ink)
                TextButton(onClick = onExit) { Text("Back", color = Accent) }
            }
        }
        return
    }
    val prefs = remember(uri) { context.getSharedPreferences("studs_reading_positions", 0) }
    var page by remember(uri) { mutableIntStateOf(prefs.getInt(uri.toString(), 0).coerceIn(0, pdf.pageCount - 1)) }
    var showPreview by remember { mutableStateOf(false) }
    LaunchedEffect(uri, page) { prefs.edit().putInt(uri.toString(), page).apply() }
    BackHandler(onBack = onExit)

    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(Panel).clickable(onClick = onExit), contentAlignment = Alignment.Center) {
                    Text("‹", color = Ink, fontSize = 27.sp)
                }
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("STUDS  ·  READER", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                }
                Text("${page + 1} / ${pdf.pageCount}", color = Muted, fontSize = 12.sp)
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF10131A)).padding(10.dp), contentAlignment = Alignment.Center) {
                val targetWidth = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(1) * 2
                val bitmap by produceState<Bitmap?>(null, uri, page, targetWidth) {
                    value = withContext(Dispatchers.IO) { runCatching { pdf.render(page, targetWidth) }.getOrNull() }
                }
                var zoom by remember(uri, page) { mutableFloatStateOf(1f) }
                var panX by remember(uri, page) { mutableFloatStateOf(0f) }
                var panY by remember(uri, page) { mutableFloatStateOf(0f) }

                Box(Modifier.fillMaxSize().pointerInput(uri, page) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                        panX += pan.x
                        panY += pan.y
                        if (zoom <= 1f) { panX = 0f; panY = 0f }
                    }
                }, contentAlignment = Alignment.Center) {
                    if (bitmap != null) {
                        Image(bitmap!!.asImageBitmap(), "PDF page ${page + 1}", Modifier.fillMaxWidth().graphicsLayer {
                            scaleX = zoom; scaleY = zoom; translationX = panX; translationY = panY
                        })
                    } else Text("Loading page…", color = Muted, fontSize = 13.sp)
                }

                // The requested controls float at the bottom-right, not in a full-width toolbar.
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(28.dp)).background(Panel.copy(alpha = 0.97f))
                        .border(1.dp, Stroke, RoundedCornerShape(28.dp)).padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    FloatControl("‹", page > 0) { page = (page - 1).coerceAtLeast(0) }
                    FloatControl("▦", true) { showPreview = true }
                    FloatControl("›", page < pdf.pageCount - 1) { page = (page + 1).coerceAtMost(pdf.pageCount - 1) }
                }
            }
        }
    }

    if (showPreview) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            containerColor = Panel,
            title = { Text("Page preview", color = Ink, fontWeight = FontWeight.SemiBold) },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items((0 until pdf.pageCount).toList()) { index ->
                        Box(
                            Modifier.aspectRatio(0.76f).clip(RoundedCornerShape(11.dp))
                                .background(if (index == page) Accent.copy(alpha = 0.16f) else Color(0xFF222937))
                                .border(1.dp, if (index == page) Accent else Stroke, RoundedCornerShape(11.dp))
                                .clickable { page = index; showPreview = false },
                            contentAlignment = Alignment.Center
                        ) { Text("${index + 1}", color = if (index == page) Accent else Ink, fontWeight = FontWeight.Medium) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPreview = false }) { Text("Close", color = Accent) } }
        )
    }
}

@Composable
private fun FloatControl(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(symbol, color = if (enabled) Ink else Muted.copy(alpha = 0.35f), fontSize = 22.sp)
    }
}
