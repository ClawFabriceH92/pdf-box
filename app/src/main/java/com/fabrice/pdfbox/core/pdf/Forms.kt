package com.fabrice.pdfbox.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File

/** P2F — formulaires AcroForm : détection des champs, saisie, export. */
object Forms {

    enum class FieldKind { TEXT, MULTILINE, CHECKBOX, RADIO, CHOICE, SIGNATURE, BUTTON }

    data class Field(
        val name: String,
        val label: String,
        val kind: FieldKind,
        val value: String,
        val options: List<String> = emptyList(),
        val onValue: String? = null,
        val readOnly: Boolean = false,
        val required: Boolean = false,
        val page: Int = -1
    )

    fun read(file: File, password: String? = null): List<Field> =
        PdfDoc.use(file, password) { doc -> read(doc) }

    fun read(doc: PDDocument): List<Field> {
        val acroForm: PDAcroForm = doc.documentCatalog?.acroForm ?: return emptyList()
        val pageOfWidget = widgetPages(doc)
        val out = mutableListOf<Field>()
        val iterator = acroForm.fieldIterator
        while (iterator.hasNext()) {
            val field = iterator.next() ?: continue
            if (field is PDPushButton) continue
            out += describe(field, pageOfWidget)
        }
        return out
    }

    private fun describe(field: PDField, pageOfWidget: Map<String, Int>): Field {
        val name = field.fullyQualifiedName ?: field.partialName ?: "champ"
        val page = field.widgets.orEmpty().firstNotNullOfOrNull { widget ->
            pageOfWidget[keyOf(widget)]
        } ?: -1
        val label = field.alternateFieldName?.takeIf { it.isNotBlank() }
            ?: name.substringAfterLast('.')
        val value = runCatching { field.valueAsString.orEmpty() }.getOrDefault("")
        return when (field) {
            is PDTextField -> Field(
                name, label,
                if (field.isMultiline) FieldKind.MULTILINE else FieldKind.TEXT,
                value, readOnly = field.isReadOnly, required = field.isRequired, page = page
            )
            is PDCheckBox -> Field(
                name, label, FieldKind.CHECKBOX, value,
                onValue = runCatching { field.onValue }.getOrNull(),
                readOnly = field.isReadOnly, required = field.isRequired, page = page
            )
            is PDRadioButton -> Field(
                name, label, FieldKind.RADIO, value,
                options = runCatching { field.onValues.toList() }.getOrDefault(emptyList()),
                readOnly = field.isReadOnly, required = field.isRequired, page = page
            )
            is PDChoice -> Field(
                name, label, FieldKind.CHOICE, value,
                options = runCatching { field.options.orEmpty().toList() }.getOrDefault(emptyList()),
                readOnly = field.isReadOnly, required = field.isRequired, page = page
            )
            is PDSignatureField -> Field(name, label, FieldKind.SIGNATURE, value, readOnly = true, page = page)
            else -> Field(name, label, FieldKind.BUTTON, value, readOnly = true, page = page)
        }
    }

    private fun widgetPages(doc: PDDocument): Map<String, Int> {
        val map = HashMap<String, Int>()
        for (index in 0 until doc.numberOfPages) {
            val annotations = runCatching { doc.getPage(index).annotations }.getOrNull() ?: continue
            for (annotation in annotations) {
                if (annotation is PDAnnotationWidget) map[keyOf(annotation)] = index
            }
        }
        return map
    }

    private fun keyOf(widget: PDAnnotationWidget): String =
        System.identityHashCode(widget.getCOSObject()).toString()

    /**
     * Écrit les valeurs saisies. `flatten` fixe définitivement le contenu : les
     * champs deviennent du dessin, plus personne ne peut les rééditer — c'est
     * ce qu'on veut avant d'envoyer un formulaire rempli.
     */
    fun fill(
        source: File,
        target: File,
        values: Map<String, String>,
        flatten: Boolean,
        password: String? = null
    ): File {
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            val acroForm = doc.documentCatalog?.acroForm
                ?: throw IllegalStateException("Ce document ne contient pas de formulaire.")
            // Sans apparences régénérées, les valeurs saisies restent invisibles
            // dans la plupart des lecteurs.
            acroForm.setNeedAppearances(false)
            values.forEach { (name, raw) ->
                val field = runCatching { acroForm.getField(name) }.getOrNull() ?: return@forEach
                if (field.isReadOnly) return@forEach
                runCatching {
                    when (field) {
                        is PDCheckBox -> if (raw.toBooleanStrictOrNull() == true) field.check() else field.unCheck()
                        is PDRadioButton -> field.setValue(if (raw.isBlank()) "Off" else raw)
                        is PDChoice -> field.setValue(raw)
                        is PDTextField -> field.setValue(PdfText.winAnsiSafe(raw))
                        else -> Unit
                    }
                }
            }
            runCatching { acroForm.refreshAppearances() }
            if (flatten) runCatching { acroForm.flatten() }
            doc.save(target)
        }
        return target
    }
}
