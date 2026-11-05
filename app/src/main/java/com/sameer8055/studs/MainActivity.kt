package com.sameer8055.studs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

private val OledBlack = Color(0xFF000000)
private val Tile = Color(0xFF111111)
private val TileBorder = Color(0xFF242424)
private val MainText = Color(0xFFF4F4F4)
private val SubText = Color(0xFF858585)
private val Accent = Color(0xFFB8C7FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        mutableStateOf(runCatching {
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
            saveLibrary((pdfs.filterNot { it.uri == entry.uri } + entry), folders)
            onOpenPdf(uri, name)
        }
    }

    Surface(Modifier.fillMaxSize(), color = OledBlack) {
        Column(Modifier.fillMaxSize().background(OledBlack).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                if (activeFolder != null) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Tile)
                            .clickable { activeFolder = null },
                        contentAlignment = Alignment.Center
                    ) { Text("‹", color = MainText, fontSize = 25.sp) }
                    Text(activeFolder ?: "", Modifier.weight(1f).padding(start = 10.dp), color = MainText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Column(Modifier.weight(1f)) {
                        Text("STUDS", color = MainText, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Text("LIBRARY", color = SubText, fontSize = 9.sp, letterSpacing = 2.sp)
                    }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(13.dp)).background(Tile).border(1.dp, TileBorder, RoundedCornerShape(13.dp))
                        .clickable { picker.launch(arrayOf("application/pdf")) }.padding(horizontal = 13.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("＋ Import PDF", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Tile).border(1.dp, TileBorder, RoundedCornerShape(13.dp))
                        .clickable { folderName = ""; showFolderDialog = true },
                    contentAlignment = Alignment.Center
                ) { Text("＋", color = MainText, fontSize = 23.sp) }
            }

            Spacer(Modifier.height(12.dp))
            val visibleFolders = if (activeFolder == null) folders else emptyList()
            val visiblePdfs = pdfs.filter { it.folder == activeFolder.orEmpty() }

            if (visibleFolders.isEmpty() && visiblePdfs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▤", color = SubText, fontSize = 42.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(if (activeFolder == null) "Your library is empty" else "This folder is empty", color = MainText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(5.dp))
                        Text("Import a PDF to see it here", color = SubText, fontSize = 12.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 142.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visibleFolders, key = { "folder:$it" }) { folder ->
                        Column(
                            Modifier.fillMaxWidth().height(142.dp).clip(RoundedCornerShape(18.dp))
                                .background(Tile).border(1.dp, TileBorder, RoundedCornerShape(18.dp))
                                .clickable { activeFolder = folder }.padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("▰", color = Accent, fontSize = 30.sp)
                            Column {
                                Text(folder, color = MainText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${pdfs.count { it.folder == folder }} PDFs", color = SubText, fontSize = 10.sp)
                            }
                        }
                    }
                    items(visiblePdfs, key = { "pdf:${it.uri}" }) { entry ->
                        Column(
                            Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(18.dp))
                                .background(Tile).border(1.dp, TileBorder, RoundedCornerShape(18.dp))
                                .clickable { onOpenPdf(Uri.parse(entry.uri), entry.name) }.padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(11.dp)).background(Color(0xFF1A1A1A)),
                                contentAlignment = Alignment.Center
                            ) { Text("PDF", color = Accent, fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
                            Spacer(Modifier.height(10.dp))
                            Text(entry.name, color = MainText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                            Text("PDF DOCUMENT", color = SubText, fontSize = 8.sp, letterSpacing = 1.2.sp)
                        }
                    }
                }
            }
        }
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            containerColor = Tile,
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
