package com.sameer8055.studs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

private val OledBlack = Color(0xFF000000)
private val CardBlack = Color(0xFF0D0D0D)
private val PreviewBlack = Color(0xFF171717)
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
    var folders by remember { mutableStateOf(prefs.getStringSet("folders", emptySet())?.toList()?.sorted() ?: emptyList()) }
    var activeFolder by remember { mutableStateOf<String?>(null) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }

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
            val name = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: "PDF document"
            val entry = PdfEntry(uri.toString(), name, activeFolder.orEmpty())
            saveLibrary(pdfs.filterNot { it.uri == entry.uri } + entry, folders)
            onOpenPdf(uri, name)
        }
    }

    val visibleFolders = if (activeFolder == null) folders else emptyList()
    val visiblePdfs = pdfs.filter { it.folder == activeFolder.orEmpty() }

    Surface(Modifier.fillMaxSize(), color = OledBlack) {
        Column(
            Modifier.fillMaxSize().background(OledBlack)
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.studs_logo),
                    contentDescription = "Studs logo",
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("STUDS", color = MainText, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("PDF READER", color = SubText, fontSize = 9.sp, letterSpacing = 2.sp)
                }
                if (activeFolder != null) {
                    TextButton(onClick = { activeFolder = null }) {
                        Text("All files", color = Accent, fontSize = 12.sp)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        activeFolder ?: "Your library",
                        color = MainText,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.6).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (activeFolder == null) "${pdfs.size} documents  ·  ${folders.size} folders"
                        else "${visiblePdfs.size} documents",
                        color = SubText,
                        fontSize = 11.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(13.dp))
                            .background(Color(0xFF171717))
                            .border(1.dp, Hairline, RoundedCornerShape(13.dp))
                            .clickable { picker.launch(arrayOf("application/pdf")) }
                            .padding(horizontal = 13.dp, vertical = 11.dp)
                    ) {
                        Text("＋ Import", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                            .background(CardBlack).border(1.dp, Hairline, RoundedCornerShape(13.dp))
                            .clickable { folderName = ""; showFolderDialog = true },
                        contentAlignment = Alignment.Center
                    ) { Text("＋", color = MainText, fontSize = 23.sp) }
                }
            }

            Spacer(Modifier.height(20.dp))
            if (visibleFolders.isEmpty() && visiblePdfs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(R.drawable.studs_logo),
                            contentDescription = null,
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
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 28.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(visibleFolders, key = { "folder:$it" }) { folder ->
                        FolderCard(folder, pdfs.count { it.folder == folder }) { activeFolder = folder }
                    }
                    items(visiblePdfs, key = { "pdf:${it.uri}" }) { entry ->
                        DocumentCard(entry.name) { onOpenPdf(Uri.parse(entry.uri), entry.name) }
                    }
                }
            }
        }
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            containerColor = CardBlack,
            title = { Text("Create folder", color = MainText) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    singleLine = true,
                    placeholder = { Text("Folder name") }
                )
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
        Modifier.fillMaxWidth().height(154.dp).clip(RoundedCornerShape(19.dp))
            .background(CardBlack).border(1.dp, Hairline, RoundedCornerShape(19.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            Modifier.fillMaxWidth().height(82.dp).clip(RoundedCornerShape(13.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF20202A), Color(0xFF111116)))),
            contentAlignment = Alignment.Center
        ) { Text("▰", color = Accent, fontSize = 34.sp) }
        Column {
            Text(name, color = MainText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text("$count PDFs", color = SubText, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DocumentCard(name: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(19.dp))
            .background(CardBlack).border(1.dp, Hairline, RoundedCornerShape(19.dp))
            .clickable(onClick = onClick).padding(11.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(13.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF202020), PreviewBlack))),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.fillMaxHeight().fillMaxWidth(0.72f)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8))
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Top
            ) {
                Box(Modifier.fillMaxWidth(0.42f).height(4.dp).background(Color(0xFF6C789D), RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(10.dp))
                repeat(5) { index ->
                    Box(
                        Modifier.fillMaxWidth(if (index == 3) 0.68f else 0.94f)
                            .height(2.dp).background(Color(0xFFB7B7B7), RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(9.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFF111111).copy(alpha = 0.9f))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) { Text("PDF", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        }
        Spacer(Modifier.height(10.dp))
        Text(name, color = MainText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        Spacer(Modifier.height(3.dp))
        Text("PDF DOCUMENT", color = SubText, fontSize = 8.sp, letterSpacing = 1.1.sp)
    }
}
