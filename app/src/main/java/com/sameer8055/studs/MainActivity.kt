package com.sameer8055.studs

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val OledBlack = Color(0xFF000000)
private val CardBlack = Color(0xFF0D0D0D)
private val Hairline = Color(0xFF252525)
private val MainText = Color(0xFFF4F4F4)
private val SubText = Color(0xFF858585)
private val Accent = Color(0xFFC4CEFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent {
            MaterialTheme {
                StudsHome(
                    onOpenPdf = { uri, name ->
                        runCatching {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent(this, ReaderActivity::class.java).apply {
                            data = uri
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            putExtra("document_name", name)
                        })
                    }
                )
            }
        }
    }
}

private data class PdfEntry(val uri: String, val name: String, val folder: String)

private fun createPdfThumbnail(context: android.content.Context, uriString: String): Bitmap? {
    return runCatching {
        val uri = Uri.parse(uriString)
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount < 1) return null
                renderer.openPage(0).use { page ->
                    val width = 360
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()
}

@Composable
private fun StudsHome(onOpenPdf: (Uri, String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("studs_library", 0) }
    var pdfs by remember {
        mutableStateOf<List<PdfEntry>>(runCatching {
            val json = JSONObject(prefs.getString("pdfs", "{}") ?: "{}")
            buildList {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = json.getJSONObject(key)
                    add(PdfEntry(key, item.optString("name", "PDF document"), item.optString("folder", "")))
                }
            }
        }.getOrDefault(emptyList()))
    }
    var folders by remember { mutableStateOf<List<String>>(prefs.getStringSet("folders", emptySet<String>())?.toList()?.sorted() ?: emptyList()) }
    var activeFolder by remember { mutableStateOf<String?>(null) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var typedBrand by remember { mutableStateOf("") }
    var logoShifted by remember { mutableStateOf(false) }
    var selectedPdfs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var renameTarget by remember { mutableStateOf<PdfEntry?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = activeFolder != null) { activeFolder = null }

    LaunchedEffect(Unit) {
        delay(180)
        logoShifted = true
        "STUDS".forEach { character ->
            delay(82)
            typedBrand += character
        }
    }

    fun saveLibrary(updatedPdfs: List<PdfEntry>, updatedFolders: List<String>) {
        val json = JSONObject()
        updatedPdfs.forEach { entry ->
            json.put(entry.uri, JSONObject().put("name", entry.name).put("folder", entry.folder))
        }
        prefs.edit().putString("pdfs", json.toString()).putStringSet("folders", updatedFolders.toSet()).apply()
        pdfs = updatedPdfs
        folders = updatedFolders
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: "PDF document"
            val entry = PdfEntry(uri.toString(), name, activeFolder.orEmpty())
            saveLibrary(pdfs.filterNot { it.uri == entry.uri } + entry, folders)
            onOpenPdf(uri, name)
        }
    }

    val visibleFolders: List<String> = if (activeFolder == null) folders else emptyList()
    val visiblePdfs = pdfs.filter { it.folder == activeFolder.orEmpty() }

    Surface(Modifier.fillMaxSize(), color = OledBlack) {
        Column(
            Modifier.fillMaxSize().background(OledBlack).statusBarsPadding().padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val logoOffset by animateDpAsState(if (logoShifted) 0.dp else 42.dp, tween(650), label = "logo-slide")
                Image(
                    painter = painterResource(R.drawable.studs_logo),
                    contentDescription = "Studs logo",
                    modifier = Modifier.offset(x = logoOffset).size(39.dp).clip(RoundedCornerShape(11.dp))
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(typedBrand, color = MainText, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("PDF READER", color = SubText, fontSize = 9.sp, letterSpacing = 2.sp)
                }
                if (activeFolder != null) {
                    TextButton(onClick = { activeFolder = null }) { Text("All files", color = Accent, fontSize = 12.sp) }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        activeFolder ?: "Your library",
                        color = MainText, fontSize = 27.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.6).sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (activeFolder == null) "${pdfs.size} documents  ·  ${folders.size} folders" else "${visiblePdfs.size} documents",
                        color = SubText, fontSize = 11.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(13.dp)).background(Color(0xFF171717))
                            .border(1.dp, Hairline, RoundedCornerShape(13.dp))
                            .clickable { picker.launch(arrayOf("application/pdf")) }
                            .padding(horizontal = 13.dp, vertical = 11.dp)
                    ) { Text("＋ Import", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(CardBlack)
                            .border(1.dp, Hairline, RoundedCornerShape(13.dp))
                            .clickable { folderName = ""; showFolderDialog = true },
                        contentAlignment = Alignment.Center
                    ) { Text("＋", color = MainText, fontSize = 23.sp) }
                }
            }

            Spacer(Modifier.height(20.dp))
            if (selectedPdfs.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF171717))
                        .border(1.dp, Hairline, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${selectedPdfs.size} selected", color = MainText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (selectedPdfs.size == 1) TextButton(onClick = {
                        renameTarget = pdfs.firstOrNull { it.uri in selectedPdfs }
                        renameValue = renameTarget?.name.orEmpty()
                    }) { Text("Rename", color = Accent) }
                    TextButton(onClick = { showDeleteConfirm = true }) { Text("Delete", color = Color(0xFFFF8C8C)) }
                    TextButton(onClick = { selectedPdfs = emptySet() }) { Text("Cancel", color = SubText) }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (visibleFolders.isEmpty() && visiblePdfs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(R.drawable.studs_logo), contentDescription = null,
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(if (activeFolder == null) "A clear space for your PDFs" else "Nothing here yet", color = MainText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(7.dp))
                        Text("Your documents will appear here", color = SubText, fontSize = 12.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp), modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 28.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(visibleFolders, key = { "folder:$it" }) { folder ->
                        FolderCard(folder, pdfs.count { it.folder == folder }) { activeFolder = folder }
                    }
                    items(visiblePdfs, key = { "pdf:${it.uri}" }) { entry ->
                        DocumentCard(
                            entry,
                            selected = entry.uri in selectedPdfs,
                            onClick = {
                                if (selectedPdfs.isNotEmpty()) selectedPdfs = if (entry.uri in selectedPdfs) selectedPdfs - entry.uri else selectedPdfs + entry.uri
                                else onOpenPdf(Uri.parse(entry.uri), entry.name)
                            },
                            onLongClick = { selectedPdfs = selectedPdfs + entry.uri }
                        )
                    }
                }
            }
        }
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null }, containerColor = CardBlack,
            title = { Text("Rename PDF", color = MainText) },
            text = { OutlinedTextField(value = renameValue, onValueChange = { renameValue = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val target = renameTarget
                val newName = renameValue.trim()
                if (target != null && newName.isNotEmpty()) saveLibrary(pdfs.map { if (it.uri == target.uri) it.copy(name = newName) else it }, folders)
                renameTarget = null
                selectedPdfs = emptySet()
            }) { Text("Save", color = Accent) } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = SubText) } }
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false }, containerColor = CardBlack,
            title = { Text("Delete ${selectedPdfs.size} PDF${if (selectedPdfs.size == 1) "" else "s"}?", color = MainText) },
            text = { Text("The selected files will be removed from your Studs library.", color = SubText) },
            confirmButton = { TextButton(onClick = {
                saveLibrary(pdfs.filterNot { it.uri in selectedPdfs }, folders)
                selectedPdfs = emptySet()
                showDeleteConfirm = false
            }) { Text("Delete", color = Color(0xFFFF8C8C)) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = SubText) } }
        )
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false }, containerColor = CardBlack,
            title = { Text("Create folder", color = MainText) },
            text = {
                OutlinedTextField(value = folderName, onValueChange = { folderName = it }, singleLine = true, placeholder = { Text("Folder name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = folderName.trim()
                    if (name.isNotEmpty() && name !in folders) saveLibrary(pdfs, folders + name)
                    showFolderDialog = false
                }) { Text("Create", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { showFolderDialog = false }) { Text("Cancel", color = SubText) } }
        )
    }
}

@Composable
private fun FolderCard(name: String, count: Int, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(21.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF191923), Color(0xFF0B0B0F))))
            .border(1.dp, Color(0xFF30303B), RoundedCornerShape(21.dp))
            .clickable(onClick = onClick).padding(13.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(Modifier.fillMaxWidth().height(111.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.align(Alignment.Center).padding(top = 13.dp).fillMaxWidth(0.82f).height(76.dp)
                    .graphicsLayer { rotationZ = -5f }.shadow(9.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xFF252637))
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.91f).height(78.dp)
                    .shadow(12.dp, RoundedCornerShape(15.dp)).clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF44465F), Color(0xFF20212E))))
                    .border(1.dp, Color(0xFF5B5E7B), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("▤", color = Color(0xFFD5D8FF), fontSize = 34.sp)
            }
        }
        Column {
            Text(name, color = MainText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text("$count PDFs", color = SubText, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DocumentCard(entry: PdfEntry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = entry.uri) {
        value = withContext(Dispatchers.IO) { createPdfThumbnail(context, entry.uri) }
    }
    Column(
        Modifier.fillMaxWidth().height(202.dp).clip(RoundedCornerShape(19.dp))
            .background(if (selected) Color(0xFF202235) else CardBlack)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Accent else Hairline, RoundedCornerShape(19.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(11.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF171717)), contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(bitmap = thumbnail!!.asImageBitmap(), contentDescription = "First page of ${entry.name}", modifier = Modifier.fillMaxHeight().fillMaxWidth(0.76f), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            } else {
                Box(Modifier.fillMaxHeight().fillMaxWidth(0.72f).clip(RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8)), contentAlignment = Alignment.Center) {
                    Text("PDF", color = Color(0xFF6C789D), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(entry.name, color = MainText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        Spacer(Modifier.height(3.dp))
        Text("PDF DOCUMENT", color = SubText, fontSize = 8.sp, letterSpacing = 1.1.sp)
    }
}
