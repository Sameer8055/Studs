package com.sameer8055.studs

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile

private val Background = Color(0xFF0B0D12)
private val Panel = Color(0xFF151922)
private val PanelBorder = Color(0xFF252B38)
private val PrimaryText = Color(0xFFF2F4FA)
private val MutedText = Color(0xFF8D96A8)
private val Accent = Color(0xFFB8C7FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                StudsHome(
                    onPdfSelected = { uri ->
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun StudsHome(onPdfSelected: (Uri) -> Unit) {
    var selectedPdf by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }

    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedPdf = uri
            onPdfSelected(uri)
            selectedName = runCatching {
                DocumentFile.fromSingleUri(null, uri)?.name
            }.getOrNull() ?: "Selected PDF"
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(Accent, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("S", color = Background, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("STUDS", color = PrimaryText, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("YOUR READING SPACE", color = MutedText, fontSize = 9.sp, letterSpacing = 1.8.sp)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .border(1.dp, PanelBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("◐", color = Accent, fontSize = 18.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("A quieter way\nto read.", color = PrimaryText, fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Your PDFs, without the noise.",
                color = MutedText,
                fontSize = 15.sp
            )

            Button(
                onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)
            ) {
                Text("＋   Open a PDF", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(22.dp))
                    .border(1.dp, PanelBorder, RoundedCornerShape(22.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("CONTINUE READING", color = MutedText, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                if (selectedPdf == null) {
                    Text("Nothing here yet", color = PrimaryText, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("Open your first PDF and it will appear here.", color = MutedText, fontSize = 13.sp, lineHeight = 19.sp)
                } else {
                    Text(selectedName ?: "Selected PDF", color = PrimaryText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text("Ready to open · Reader screen is the next build step", color = MutedText, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.weight(1f))
            Text("MADE FOR FOCUS", modifier = Modifier.align(Alignment.CenterHorizontally), color = MutedText.copy(alpha = 0.65f), fontSize = 9.sp, letterSpacing = 2.sp)
        }
    }
}
