package com.sameer8055.studs

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.delay
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
    private val cache = object : android.util.LruCache<String, Bitmap>(80 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    val pageCount: Int get() = renderer.pageCount

    @Synchronized
    fun render(index: Int, viewportWidth: Int, viewportHeight: Int): Bitmap {
        val safeIndex = index.coerceIn(0, renderer.pageCount - 1)
        val page = renderer.openPage(safeIndex)
        try {
            val fit = min(viewportWidth.toFloat() / page.width, viewportHeight.toFloat() / page.height).coerceAtLeast(0.1f)
            val pixelScale = fit * 1.8f
            var width = max(1, (page.width * pixelScale).toInt())
            var height = max(1, (page.height * pixelScale).toInt())
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This PDF has no pages.", color = ReaderInk) }
        }
        return
    }

    val prefs = remember(uri) { context.getSharedPreferences("studs_reading_positions", 0) }
    var page by remember(uri) { mutableIntStateOf(prefs.getInt(uri.toString(), 0).coerceIn(0, pdf.pageCount - 1)) }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var panX by remember(uri) { mutableFloatStateOf(0f) }
    var panY by remember(uri) { mutableFloatStateOf(0f) }
    var swipeDistance by remember(uri) { mutableFloatStateOf(0f) }
    var displayedPage by remember(uri) { mutableIntStateOf(-1) }
    var displayedBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var showPagePreviews by remember { mutableStateOf(false) }

    LaunchedEffect(uri, page) { prefs.edit().putInt(uri.toString(), page).apply() }
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

            LaunchedEffect(uri, page, viewportWidth, viewportHeight) {
                // Coalesce quick taps: render the latest requested page, not every skipped page.
                delay(38)
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
                Modifier.fillMaxSize().pointerInput(uri, page, viewportWidth, viewportHeight) {
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
                            if (swipeDistance <= -58f && page < pdf.pageCount - 1) {
                                page = (page + 1).coerceAtMost(pdf.pageCount - 1)
                                swipeDistance = 0f
                            } else if (swipeDistance >= 58f && page > 0) {
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

            AnimatedVisibility(
                visible = showPagePreviews,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.78f).padding(end = 12.dp),
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(tween(180)),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(tween(140))
            ) {
                Column(
                    Modifier.width(158.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                        .background(Color(0xF20D0D0D)).border(1.dp, ReaderBorder, RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    Text("PAGE OVERVIEW", color = ReaderMuted, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(11.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        items((0 until pdf.pageCount).toList(), key = { it }) { index ->
                            PagePreviewItem(
                                pdf = pdf,
                                index = index,
                                selected = index == page,
                                onClick = {
                                    page = index
                                    showPagePreviews = false
                                }
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(ReaderPanel.copy(alpha = 0.96f))
                        .border(1.dp, ReaderBorder, CircleShape).clickable { showPagePreviews = !showPagePreviews },
                    contentAlignment = Alignment.Center
                ) {
                    Text("▦", color = if (showPagePreviews) Color(0xFFC4CEFF) else ReaderInk, fontSize = 21.sp)
                }
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
private fun PagePreviewItem(pdf: PdfFile, index: Int, selected: Boolean, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = pdf, key2 = index) {
        value = withContext(Dispatchers.IO) {
            runCatching { pdf.render(index, 260, 340) }.getOrNull()
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF292A38) else Color.Transparent)
            .border(if (selected) 1.dp else 0.dp, if (selected) Color(0xFFC4CEFF) else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF222222)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxHeight().fillMaxWidth(0.82f), contentScale = ContentScale.Fit)
            } else {
                Text("…", color = ReaderMuted, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text("Page ${index + 1}", color = if (selected) Color(0xFFC4CEFF) else ReaderMuted, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
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
