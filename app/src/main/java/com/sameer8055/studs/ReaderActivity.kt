@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.sameer8055.studs

import android.graphics.Bitmap
import java.io.File
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfiumCore
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val ReaderBlack = Color(0xFF000000)
private val ReaderPanel = Color(0xFF111111)
private val ReaderBorder = Color(0xFF292929)
private val ReaderInk = Color(0xFFF4F4F4)
private val ReaderMuted = Color(0xFF858585)

class ReaderActivity : ComponentActivity() {
    var volumePageHandler: ((Int) -> Unit)? = null

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> { volumePageHandler?.invoke(1); return true }
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> { volumePageHandler?.invoke(-1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

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
        val name = intent.getStringExtra("document_name") ?: "PDF document"
        setContent { MaterialTheme { ReaderScreen(uri, name, this) } }
    }
}

private class PdfFile(context: android.content.Context, uri: Uri) {
    private val descriptor: ParcelFileDescriptor = if (uri.scheme == "file") ParcelFileDescriptor.open(File(requireNotNull(uri.path)), ParcelFileDescriptor.MODE_READ_ONLY) else requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
    private val pdfium = PdfiumCore(context)
    private val document = pdfium.newDocument(descriptor)
    private val cache = object : android.util.LruCache<String, Bitmap>(80 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    val pageCount: Int get() = document.getPageCount()
    @Volatile private var priorityPage: Int = -1

    fun prioritize(index: Int) {
        priorityPage = index
    }

    @Synchronized
    fun render(index: Int, viewportWidth: Int, viewportHeight: Int): Bitmap? {
        // Drop stale full-size requests waiting behind an in-progress render.
        if (viewportWidth > 400 && index != priorityPage) return null
        val safeIndex = index.coerceIn(0, document.getPageCount() - 1)
        val page = document.openPage(safeIndex) ?: return null
        try {
            val pageWidth = page.getPageWidth(72).coerceAtLeast(1)
            val pageHeight = page.getPageHeight(72).coerceAtLeast(1)
            val fit = min(viewportWidth.toFloat() / pageWidth, viewportHeight.toFloat() / pageHeight).coerceAtLeast(0.1f)
            val pixelScale = fit * 1.8f
            var width = max(1, (pageWidth * pixelScale).toInt())
            var height = max(1, (pageHeight * pixelScale).toInt())
            val pixels = width.toLong() * height.toLong()
            val maxPixels = 6_000_000L
            if (pixels > maxPixels) {
                val reduction = sqrt(maxPixels.toDouble() / pixels.toDouble()).toFloat()
                width = max(1, (width * reduction).toInt())
                height = max(1, (height * reduction).toInt())
            }
            val key = "$safeIndex:$width:$height"
            cache.get(key)?.let { return it }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.renderPageBitmap(bitmap, 0, 0, width, height)
            cache.put(key, bitmap)
            return bitmap
        } finally {
            page.close()
        }
    }

    @Synchronized
    fun close() {
        cache.evictAll()
        document.close()
        descriptor.close()
    }
}

@Composable
private fun ReaderScreen(uri: Uri, documentName: String, activity: ReaderActivity) {
    val context = LocalContext.current
    var activeUri by remember(uri) { mutableStateOf(uri) }
    val opened = remember(activeUri) { runCatching { PdfFile(context, activeUri) } }
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This PDF has no pages.", color = ReaderInk) }
        }
        return
    }

    val prefs = remember { context.getSharedPreferences("studs_reading_positions", 0) }
    var page by remember(activeUri) { mutableIntStateOf(prefs.getInt(activeUri.toString(), 0).coerceIn(0, pdf.pageCount - 1)) }
    DisposableEffect(Unit) {
        activity.volumePageHandler = { direction ->
            page = (page + direction).coerceIn(0, pdf.pageCount - 1)
        }
        onDispose { activity.volumePageHandler = null }
    }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var panX by remember(uri) { mutableFloatStateOf(0f) }
    var panY by remember(uri) { mutableFloatStateOf(0f) }
    var swipeDistance by remember(uri) { mutableFloatStateOf(0f) }
    var displayedPage by remember(activeUri) { mutableIntStateOf(-1) }
    var displayedBitmap by remember(activeUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(activeUri, page) {
        pdf.prioritize(page)
        prefs.edit().putInt(activeUri.toString(), page).apply()
    }
    LaunchedEffect(page) {
        zoom = 1f
        panX = 0f
        panY = 0f
        swipeDistance = 0f
    }

    Surface(Modifier.fillMaxSize(), color = ReaderBlack) {
        BoxWithConstraints(Modifier.fillMaxSize().background(ReaderBlack), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val viewportWidth = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
            val viewportHeight = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)

            LaunchedEffect(activeUri, page, viewportWidth, viewportHeight) {
                // Coalesce quick taps: render the latest requested page, not every skipped page.
                val requestedPage = page
                val rendered = withContext(Dispatchers.IO) {
                    runCatching { pdf.render(requestedPage, viewportWidth, viewportHeight) }.getOrNull()
                }
                if (rendered != null && requestedPage == page) {
                    displayedBitmap = rendered
                    displayedPage = requestedPage
                }
            }

            Box(
                Modifier.fillMaxSize().pointerInput(activeUri, page, viewportWidth, viewportHeight) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        val previousZoom = zoom
                        val nextZoom = (previousZoom * zoomChange).coerceIn(1f, 5f)
                        val appliedScale = nextZoom / previousZoom
                        if (nextZoom > 1.001f || previousZoom > 1.001f) {
                            val focusX = centroid.x - size.width / 2f
                            val focusY = centroid.y - size.height / 2f
                            panX = focusX + (panX - focusX) * appliedScale + pan.x
                            panY = focusY + (panY - focusY) * appliedScale + pan.y
                            zoom = nextZoom
                            val maxX = size.width * (zoom - 1f) / 2f
                            val maxY = size.height * (zoom - 1f) / 2f
                            panX = panX.coerceIn(-maxX, maxX)
                            panY = panY.coerceIn(-maxY, maxY)
                            if (zoom <= 1.001f) {
                                zoom = 1f
                                panX = 0f
                                panY = 0f
                            }
                            swipeDistance = 0f
                        } else {
                            swipeDistance += pan.x
                            if (swipeDistance <= -36f && page < pdf.pageCount - 1) {
                                page = (page + 1).coerceAtMost(pdf.pageCount - 1)
                                swipeDistance = 0f
                            } else if (swipeDistance >= 36f && page > 0) {
                                page = (page - 1).coerceAtLeast(0)
                                swipeDistance = 0f
                            }
                        }
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                displayedBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = panX
                            translationY = panY
                        },
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(28.dp)).background(ReaderPanel.copy(alpha = 0.96f))
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
