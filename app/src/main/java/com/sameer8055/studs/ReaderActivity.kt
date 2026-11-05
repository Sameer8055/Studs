package com.sameer8055.studs

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

private val ReaderBlack = Color(0xFF000000)
private val ReaderPanel = Color(0xFF111111)
private val ReaderBorder = Color(0xFF292929)
private val ReaderInk = Color(0xFFF4F4F4)
private val ReaderMuted = Color(0xFF858585)

class ReaderActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        val uri = intent.data
        if (uri == null) { finish(); return }
        setContent { MaterialTheme { ReaderScreen(uri) } }
    }
}

private class PdfFile(context: android.content.Context, uri: Uri) {
    private val descriptor: ParcelFileDescriptor =
        requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
    private val renderer = PdfRenderer(descriptor)
    private val cache = object : android.util.LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    val pageCount get() = renderer.pageCount

    @Synchronized
    fun render(index: Int, requestedWidth: Int): Bitmap {
        val width = max(1, requestedWidth)
        val key = "$index-$width"
        cache.get(key)?.let { return it }
        val page = renderer.openPage(index)
        try {
            val height = max(1, (width.toFloat() * page.height / page.width).toInt())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(key, bitmap)
            return bitmap
        } finally {
            page.close()
        }
    }

    @Synchronized
    fun close() {
        cache.evictAll()
        renderer.close()
        descriptor.close()
    }
}

@Composable
private fun ReaderScreen(uri: Uri) {
    val context = LocalContext.current
    val opened = remember(uri) { runCatching { PdfFile(context, uri) } }
    DisposableEffect(opened) { onDispose { opened.getOrNull()?.close() } }

    if (opened.isFailure) {
        Surface(Modifier.fillMaxSize(), color = ReaderBlack) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Unable to open this PDF", color = ReaderInk)
            }
        }
        return
    }
    val pdf = opened.getOrThrow()
    if (pdf.pageCount < 1) {
        Surface(Modifier.fillMaxSize(), color = ReaderBlack) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This PDF has no pages.", color = ReaderInk)
            }
        }
        return
    }

    val prefs = remember(uri) { context.getSharedPreferences("studs_reading_positions", 0) }
    var page by remember(uri) { mutableIntStateOf(prefs.getInt(uri.toString(), 0).coerceIn(0, pdf.pageCount - 1)) }
    var zoom by remember(uri, page) { mutableFloatStateOf(1f) }
    var panX by remember(uri, page) { mutableFloatStateOf(0f) }
    var panY by remember(uri, page) { mutableFloatStateOf(0f) }
    var swipeDistance by remember(uri, page) { mutableFloatStateOf(0f) }
    LaunchedEffect(uri, page) { prefs.edit().putInt(uri.toString(), page).apply() }

    Surface(Modifier.fillMaxSize(), color = ReaderBlack) {
        BoxWithConstraints(Modifier.fillMaxSize().background(ReaderBlack), contentAlignment = Alignment.Center) {
            val renderWidth = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(1) * 2
            val bitmap by produceState<Bitmap?>(null, uri, page, renderWidth) {
                value = withContext(Dispatchers.IO) { runCatching { pdf.render(page, renderWidth) }.getOrNull() }
            }

            // Warm adjacent pages while the current page is on screen, so a swipe is usually cache-backed.
            LaunchedEffect(uri, page, renderWidth) {
                withContext(Dispatchers.IO) {
                    if (page > 0) runCatching { pdf.render(page - 1, renderWidth) }
                    if (page < pdf.pageCount - 1) runCatching { pdf.render(page + 1, renderWidth) }
                }
            }

            Box(
                Modifier.fillMaxSize().pointerInput(uri, page) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        if (zoom > 1f || zoomChange > 1.01f) {
                            zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                            panX += pan.x
                            panY += pan.y
                            swipeDistance = 0f
                            if (zoom <= 1f) { panX = 0f; panY = 0f }
                        } else {
                            swipeDistance += pan.x
                            if (swipeDistance < -72f && page < pdf.pageCount - 1) {
                                page += 1
                                swipeDistance = 0f
                            } else if (swipeDistance > 72f && page > 0) {
                                page -= 1
                                swipeDistance = 0f
                            }
                        }
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = { fadeIn(tween(90)) togetherWith fadeOut(tween(60)) },
                    label = "page-transition"
                ) { targetPage ->
                    if (targetPage == page && bitmap != null) {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = panX
                                translationY = panY
                            },
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(" ", color = ReaderMuted)
                        }
                    }
                }
            }

            // Minimal horizontal controls; no title, page counter, preview, or visible back button.
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 22.dp)
                    .clip(RoundedCornerShape(28.dp)).background(ReaderPanel.copy(alpha = 0.94f))
                    .border(1.dp, ReaderBorder, RoundedCornerShape(28.dp)).padding(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                ReaderControl("‹", page > 0) { page = (page - 1).coerceAtLeast(0) }
                Box(Modifier.width(1.dp).height(22.dp).background(ReaderBorder))
                ReaderControl("›", page < pdf.pageCount - 1) { page = (page + 1).coerceAtMost(pdf.pageCount - 1) }
            }
        }
    }
}

@Composable
private fun ReaderControl(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 48.dp, height = 44.dp).clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = if (enabled) ReaderInk else ReaderMuted.copy(alpha = 0.35f), fontSize = 25.sp)
    }
}
