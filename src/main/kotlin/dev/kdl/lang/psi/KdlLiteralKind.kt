package dev.kdl.lang.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import dev.kdl.lang.escape.unescapeString
import dev.kdl.lang.psi.ext.KdlElementTypes.*
import java.math.BigDecimal
import java.math.BigInteger

sealed class KdlLiteralKind(val node: ASTNode) {
    class BooleanLiteral(node: ASTNode) : KdlLiteralKind(node) {
        val value: Boolean = node.chars.toString().removePrefix("#") == "true"
    }

    class NumberLiteral(node: ASTNode) : KdlLiteralKind(node) {
        val rawText: String get() = node.text

        val value: BigDecimal? get() {
            if (isKeywordNumber) {
                return null
            }

            val textValue = offsetsForNumber(node).substring(node.text)
            val negative = textValue.startsWith('-')
            val cleanText = textValue.removePrefix("+").removePrefix("-")
            val (start, radix) = when (cleanText.take(2)) {
                "0x" -> 2 to 16
                "0o" -> 2 to 8
                "0b" -> 2 to 2
                else -> 0 to 10
            }

            val cleanTextValue = cleanText.substring(start).filter { it != '_' }
            return try {
                if (radix == 10) {
                    BigDecimal(if (negative) "-$cleanTextValue" else cleanTextValue)
                } else {
                    val integer = BigInteger(cleanTextValue, radix)
                    (if (negative) integer.negate() else integer).toBigDecimal()
                }
            } catch (e: NumberFormatException) {
                null
            }
        }

        val keyword: String? get() = rawText.takeIf { isKeywordNumber }

        private val isKeywordNumber: Boolean
            get() = rawText == "#inf" || rawText == "#-inf" || rawText == "#nan"
    }

    class StringLiteral(node: ASTNode) : KdlLiteralKind(node) {
        val value: String get() {
            return decodeStringLiteral(node)
        }
    }

    class NullLiteral(node: ASTNode) : KdlLiteralKind(node)

    companion object {
        fun fromAstNode(node: ASTNode): KdlLiteralKind? = when (node.elementType) {
            TRUE_LITERAL, FALSE_LITERAL -> BooleanLiteral(node)
            DECIMAL_LITERAL, OCTAL_LITERAL, BINARY_LITERAL, HEX_LITERAL -> NumberLiteral(node)

            STRING_LITERAL, RAW_STRING_LITERAL -> StringLiteral(node)
            NULL_LITERAL -> NullLiteral(node)

            else -> null
        }
    }

}

fun offsetsForNumber(node: ASTNode): TextRange {
    val cleanText = node.text.removePrefix("+").removePrefix("-")
    val offset = node.text.length - cleanText.length

    val start = offset + when (cleanText.take(2)) {
        "0b" -> 2
        "0o" -> 2
        "0x" -> 2
        else -> 0
    }

    return TextRange.allOf(node.text.substring(start))
}

fun offsetsForText(node: ASTNode): TextRange {
    return when {
        isRawString(node.text) -> offsetsForRawText(node)
        isMultilineString(node.text) -> TextRange(0, node.textLength)
        else -> TextRange(1, node.textLength - 1)
    }
}

private fun offsetsForRawText(node: ASTNode): TextRange {
    val text = node.text
    val textLength = node.textLength

    val prefixEnd = if (text.startsWith("r")) 1 else 0

    val hashes = run {
        var pos = prefixEnd
        while (pos < textLength && text[pos] == '#') {
            pos++
        }
        pos - prefixEnd
    }

    val openDelimEnd = doLocate(node, prefixEnd) {
        assert(textLength - it >= 1 + hashes && text[it] == '#' || text[it] == '"') { "expected open delim" }
        it + 1 + hashes
    }

    val valueEnd = doLocate(node, openDelimEnd, fun(start: Int): Int {
        text.substring(start).forEachIndexed { i, ch ->
            if (start + i + hashes < textLength &&
                ch == '"' &&
                text.subSequence(start + i + 1, start + i + 1 + hashes).all { it == '#' }) {
                return i + start
            }
        }
        return textLength
    })

    val closeDelimEnd = doLocate(node, valueEnd) {
        assert(textLength - it >= 1 + hashes && text[it] == '"') { "expected close delim" }
        it + 1 + hashes
    }

    return TextRange(openDelimEnd, valueEnd)
}

private inline fun doLocate(node: ASTNode, start: Int, locator: (Int) -> Int): Int =
    if (start >= node.textLength) start else locator(start)

private fun decodeStringLiteral(node: ASTNode): String {
    val text = node.text
    return when {
        isRawMultilineString(text) -> dedentMultilineString(extractMultilineBody(text, raw = true), raw = true)
        isMultilineString(text) -> unescapeString(dedentMultilineString(extractMultilineBody(text, raw = false), raw = false))
        isRawString(text) -> offsetsForRawText(node).substring(text)
        else -> unescapeString(offsetsForText(node).substring(text))
    }
}

private fun extractMultilineBody(text: String, raw: Boolean): String {
    val quoteIndex = if (raw) text.indexOf("\"\"\"") else 0
    val start = quoteIndex + 3
    val end = text.lastIndexOf("\"\"\"")
    return if (start <= end) text.substring(start, end) else ""
}

private fun dedentMultilineString(body: String, raw: Boolean): String {
    val normalized = normalizeNewlines(body)
    if (normalized.isEmpty() || normalized[0] != '\n') {
        return if (raw) normalized else unescapeString(normalized)
    }

    val lastNewline = normalized.lastIndexOf('\n')
    if (lastNewline < 0) {
        return normalized
    }

    val indent = normalized.substring(lastNewline + 1)
    val content = normalized.substring(1, lastNewline)
    val lines = content.split('\n')

    return lines.joinToString("\n") { line ->
        if (line.startsWith(indent)) {
            line.removePrefix(indent)
        } else {
            line
        }
    }
}

private fun normalizeNewlines(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n').replace('\u0085', '\n').replace('\u000C', '\n').replace('\u2028', '\n').replace('\u2029', '\n')

private fun isRawString(text: String): Boolean = text.startsWith("r") || text.startsWith("#")

private fun isMultilineString(text: String): Boolean =
    text.startsWith("\"\"\"") || isRawMultilineString(text)

private fun isRawMultilineString(text: String): Boolean {
    val quoteIndex = text.indexOf("\"\"\"")
    return quoteIndex > 0 && text.take(quoteIndex).all { it == '#' || it == 'r' }
}
