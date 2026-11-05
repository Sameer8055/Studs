package com.sameer8055.studs

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Applies real page-level edits while preserving PDF vector/text content. */
object PdfEditor {
    suspend fun edit(
        context: Context,
        sourceUri: Uri,
        deletePages: Set<Int> = emptySet(),
        moveFrom: Int? = null,
        moveTo: Int? = null,
        appendUri: Uri? = null
    ): Uri = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val input = copyUriToCache(context, sourceUri, "source")
        val output = File(context.filesDir, "edited_pdfs/" + UUID.randomUUID() + ".pdf")
        output.parentFile?.mkdirs()
        PDDocument.load(input).use { document ->
            require(document.numberOfPages > 0) { "This PDF has no pages." }
            val validDeletes = deletePages.filter { it in 0 until document.numberOfPages }.distinct().sortedDescending()
            require(validDeletes.size < document.numberOfPages) { "A PDF must contain at least one page." }
            validDeletes.forEach { document.removePage(it) }
            if (moveFrom != null && moveTo != null && moveFrom in 0 until document.numberOfPages) {
                val movedPage = document.getPage(moveFrom)
                document.pages.remove(movedPage)
                val target = moveTo.coerceIn(0, document.numberOfPages)
                if (target >= document.numberOfPages) document.addPage(movedPage)
                else document.pages.insertBefore(movedPage, document.getPage(target))
            }
            if (appendUri != null) {
                val appendFile = copyUriToCache(context, appendUri, "append")
                PDDocument.load(appendFile).use { extra ->
                    for (index in 0 until extra.numberOfPages) document.importPage(extra.getPage(index))
                }
                appendFile.delete()
            }
            document.save(output)
        }
        input.delete()
        val prefs = context.getSharedPreferences("studs_library", Context.MODE_PRIVATE)
        val library = runCatching { JSONObject(prefs.getString("pdfs", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val oldEntry = library.optJSONObject(sourceUri.toString())
        if (oldEntry != null) {
            library.remove(sourceUri.toString())
            library.put(Uri.fromFile(output).toString(), oldEntry)
            prefs.edit().putString("pdfs", library.toString()).apply()
        }
        Uri.fromFile(output)
    }

    private fun copyUriToCache(context: Context, uri: Uri, prefix: String): File {
        val file = File(context.cacheDir, prefix + "-" + UUID.randomUUID() + ".pdf")
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path)).copyTo(file, overwrite = true)
        } else {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to read the selected PDF." }.use { source ->
                    file.outputStream().use { target -> source.copyTo(target) }
                }
            }
        }
        return file
    }
}
