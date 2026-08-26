package com.fabrice.pdfbox.core.xml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Nœud d'un document XML, tel que l'affiche la visionneuse : repliable,
 * cherchable, copiable.
 */
data class XmlNode(
    val name: String,
    val prefix: String?,
    val attributes: List<Pair<String, String>>,
    val text: String?,
    val children: List<XmlNode>,
    val depth: Int,
    val line: Int
) {
    val displayName: String get() = if (prefix.isNullOrEmpty()) name else "$prefix:$name"
    val isLeaf: Boolean get() = children.isEmpty()
    val hasValue: Boolean get() = !text.isNullOrBlank()

    fun descendants(): Sequence<XmlNode> = sequence {
        yield(this@XmlNode)
        children.forEach { yieldAll(it.descendants()) }
    }

    fun find(vararg names: String): XmlNode? {
        val wanted = names.map { it.lowercase() }.toSet()
        return descendants().firstOrNull { it.name.lowercase() in wanted }
    }

    fun findAll(vararg names: String): List<XmlNode> {
        val wanted = names.map { it.lowercase() }.toSet()
        return descendants().filter { it.name.lowercase() in wanted }.toList()
    }
}

sealed interface XmlParseResult {
    data class Ok(val root: XmlNode, val encoding: String, val nodeCount: Int) : XmlParseResult
    /** X5 — XML illisible : on dit *pourquoi*, sans jamais planter. */
    data class Unreadable(val reason: String, val detail: String?, val hexPreview: String?) : XmlParseResult
}

/** Lecture et diagnostic d'un fichier XML. */
object XmlTree {

    private const val MAX_BYTES = 12 * 1024 * 1024

    fun parse(file: File): XmlParseResult {
        if (!file.exists()) return XmlParseResult.Unreadable("Fichier introuvable.", null, null)
        if (file.length() == 0L) return XmlParseResult.Unreadable("Fichier vide (0 octet).", null, null)
        if (file.length() > MAX_BYTES) {
            return XmlParseResult.Unreadable(
                "Fichier trop volumineux pour la visionneuse (${file.length() / 1_000_000} Mo).",
                "La limite est de ${MAX_BYTES / 1_000_000} Mo ; le partage du fichier reste possible.",
                null
            )
        }
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return XmlParseResult.Unreadable("Lecture impossible.", it.message, null)
        }
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): XmlParseResult {
        val binaryHint = detectBinary(bytes)
        if (binaryHint != null) {
            return XmlParseResult.Unreadable(binaryHint, "Les premiers octets ne sont pas du texte XML.", hex(bytes))
        }
        val charset = detectCharset(bytes)
        val text = runCatching { String(bytes, charset) }.getOrElse {
            return XmlParseResult.Unreadable("Encodage non pris en charge.", it.message, hex(bytes))
        }
        val cleaned = text.trim().removePrefix("\uFEFF")
        if (!cleaned.startsWith("<")) {
            return XmlParseResult.Unreadable(
                "Ce fichier n'est pas du XML.",
                "Il commence par « " + cleaned.take(24).replace('\n', ' ') + " ».",
                hex(bytes)
            )
        }
        return try {
            val root = buildTree(cleaned)
            XmlParseResult.Ok(root, charset.name(), root.descendants().count())
        } catch (e: XmlPullParserException) {
            XmlParseResult.Unreadable(
                "XML mal formé.",
                (e.message ?: "").substringBefore("\n").ifBlank { null },
                null
            )
        } catch (e: Exception) {
            XmlParseResult.Unreadable("Lecture interrompue.", e.message, null)
        }
    }

    private fun buildTree(xml: String): XmlNode {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        val stack = ArrayDeque<Builder>()
        var root: XmlNode? = null
        val textBuffer = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    textBuffer.setLength(0)
                    val attributes = (0 until parser.attributeCount).map { i ->
                        val prefix = parser.getAttributePrefix(i)
                        val name = if (prefix.isNullOrEmpty()) parser.getAttributeName(i)
                        else "$prefix:${parser.getAttributeName(i)}"
                        name to (parser.getAttributeValue(i) ?: "")
                    }
                    stack.addLast(
                        Builder(
                            name = parser.name,
                            prefix = parser.prefix,
                            attributes = attributes,
                            depth = stack.size,
                            line = parser.lineNumber
                        )
                    )
                }

                XmlPullParser.TEXT -> if (!parser.isWhitespace) textBuffer.append(parser.text)

                XmlPullParser.END_TAG -> {
                    val builder = stack.removeLastOrNull() ?: break
                    val node = XmlNode(
                        name = builder.name,
                        prefix = builder.prefix,
                        attributes = builder.attributes,
                        text = textBuffer.toString().trim().takeIf { it.isNotEmpty() },
                        children = builder.children,
                        depth = builder.depth,
                        line = builder.line
                    )
                    textBuffer.setLength(0)
                    val parent = stack.lastOrNull()
                    if (parent == null) root = node else parent.children.add(node)
                }
            }
            event = parser.next()
        }
        return root ?: throw XmlPullParserException("Document sans élément racine.")
    }

    private class Builder(
        val name: String,
        val prefix: String?,
        val attributes: List<Pair<String, String>>,
        val depth: Int,
        val line: Int
    ) {
        val children = mutableListOf<XmlNode>()
    }

    /**
     * X5 — repère les fichiers qui ne sont pas du XML lisible : archive, PDF,
     * flux chiffré. Un contenu chiffré n'a pas de signature ; son entropie et
     * l'absence de caractères imprimables le trahissent.
     */
    private fun detectBinary(bytes: ByteArray): String? {
        if (bytes.size >= 4) {
            val head = bytes.copyOfRange(0, 4)
            if (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
                return "Ce fichier est une archive ZIP, pas un XML."
            }
            if (head[0] == 0x25.toByte() && head[1] == 0x50.toByte() &&
                head[2] == 0x44.toByte() && head[3] == 0x46.toByte()
            ) {
                return "Ce fichier est un PDF, pas un XML."
            }
            if (head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) {
                return "Ce fichier est compressé (gzip) : décompressez-le d'abord."
            }
        }
        val sample = bytes.copyOfRange(0, minOf(bytes.size, 2048))
        // Un XML en UTF-16 commence par une BOM : ce n'est pas du binaire.
        if (sample.size >= 2 &&
            ((sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) ||
                (sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()))
        ) return null
        val printable = sample.count { b ->
            val v = b.toInt() and 0xFF
            v == 9 || v == 10 || v == 13 || (v in 32..126) || v >= 160
        }
        val ratio = if (sample.isEmpty()) 1f else printable.toFloat() / sample.size
        return if (ratio < 0.75f) {
            "Contenu illisible : le fichier est binaire ou chiffré."
        } else null
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return StandardCharsets.UTF_16LE
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return StandardCharsets.UTF_16BE
        }
        val head = String(bytes, 0, minOf(bytes.size, 200), StandardCharsets.ISO_8859_1)
        val declared = Regex("encoding\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.getOrNull(1)
        if (declared != null) {
            runCatching { return Charset.forName(declared) }
        }
        return StandardCharsets.UTF_8
    }

    private fun hex(bytes: ByteArray): String =
        bytes.take(16).joinToString(" ") { "%02X".format(it) }

    /** Réécriture indentée, pour le partage et la copie. */
    fun prettyPrint(node: XmlNode, indent: String = "  "): String {
        val sb = StringBuilder()
        fun write(current: XmlNode, level: Int) {
            val pad = indent.repeat(level)
            sb.append(pad).append('<').append(current.displayName)
            current.attributes.forEach { (key, value) ->
                sb.append(' ').append(key).append("=\"").append(escape(value)).append('"')
            }
            if (current.children.isEmpty() && current.text.isNullOrBlank()) {
                sb.append("/>\n")
                return
            }
            sb.append('>')
            if (current.children.isEmpty()) {
                sb.append(escape(current.text.orEmpty()))
                sb.append("</").append(current.displayName).append(">\n")
                return
            }
            sb.append('\n')
            if (!current.text.isNullOrBlank()) sb.append(pad).append(indent).append(escape(current.text)).append('\n')
            current.children.forEach { write(it, level + 1) }
            sb.append(pad).append("</").append(current.displayName).append(">\n")
        }
        write(node, 0)
        return sb.toString()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
