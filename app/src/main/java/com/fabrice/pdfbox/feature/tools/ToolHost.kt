package com.fabrice.pdfbox.feature.tools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.core.pdf.PdfSource
import com.fabrice.pdfbox.core.util.Storage
import com.fabrice.pdfbox.core.util.sanitizeFileName
import com.fabrice.pdfbox.feature.common.AppViewModel
import com.fabrice.pdfbox.feature.reader.ReaderViewModel
import java.io.File

/** Aiguillage : chaque outil est une boîte de dialogue autonome. */
@Composable
fun ToolHost(
    app: AppViewModel,
    reader: ReaderViewModel,
    doc: Doc?,
    allDocs: List<Doc>,
    active: Tool?,
    onClose: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    if (active == null) return
    when (active) {
        Tool.MERGE -> MergeDialog(app, allDocs, onClose)
        Tool.EXTRACT -> PageToolDialog(app, doc, PageToolMode.EXTRACT, onClose)
        Tool.DELETE_PAGES -> PageToolDialog(app, doc, PageToolMode.DELETE, onClose)
        Tool.ROTATE -> PageToolDialog(app, doc, PageToolMode.ROTATE, onClose)
        Tool.NUMBER -> NumberPagesDialog(app, doc, onClose)
        Tool.REORDER -> ReorderDialog(app, doc, onClose)
        Tool.WATERMARK -> WatermarkDialog(app, doc, onClose)
        Tool.REDACT -> RedactDialog(app, reader, doc, onClose)
        Tool.EXPORT_ANNOTATED -> AnnotatedExportDialog(app, reader, doc, onClose)
        Tool.SIGN_INFO -> SignInfoDialog(onClose)
        Tool.COMPRESS -> CompressDialog(app, doc, onClose)
        Tool.PASSWORD -> PasswordDialog(app, doc, onClose)
        Tool.UNLOCK -> UnlockDialog(app, doc, onClose)
        Tool.TO_IMAGE -> ToImageDialog(app, doc, onClose)
        Tool.FROM_IMAGE -> FromImagesDialog(app, onClose)
        Tool.PRINT -> PrintDialog(app, doc, onClose)
        Tool.OCR -> OcrDialog(app, doc, searchable = false, onClose = onClose)
        Tool.SEARCHABLE -> OcrDialog(app, doc, searchable = true, onClose = onClose)
        Tool.TABLE_CSV -> TableDialog(app, doc, onClose)
        Tool.FORMS -> FormsDialog(app, doc, onClose)
        Tool.INVOICE_FIELDS -> InvoiceFieldsDialog(app, doc, onClose)
        Tool.TEXT_HISTORY -> HistoryDialog(app, onClose)
        Tool.BATCH -> BatchDialog(app, allDocs, onClose)
    }
}

/** Fichier de sortie dans l'espace d'export, au nom dérivé du document. */
internal fun outputFile(context: Context, doc: Doc?, suffix: String, extension: String = "pdf"): File =
    Storage.uniqueFile(
        Storage.exportsDir(context),
        sanitizeFileName("${doc?.title ?: "document"} ($suffix).$extension")
    )

internal fun sourceOf(context: Context, doc: Doc): File = PdfSource.localFile(context, doc)

@Composable
internal fun requireContext(): Context = LocalContext.current
