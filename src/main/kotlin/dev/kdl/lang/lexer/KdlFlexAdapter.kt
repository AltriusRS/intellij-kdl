package dev.kdl.lang.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.kdl.lang.psi.ext.KdlElementTypes

class KdlFlexAdapter : LexerBase() {
    private data class Token(val type: IElementType, val start: Int, val end: Int)

    private var buffer: CharSequence = ""
    private var endOffset: Int = 0
    private var tokens: List<Token> = emptyList()
    private var index: Int = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        this.tokens = lex(buffer, startOffset, endOffset)
        this.index = 0
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = tokens.getOrNull(index)?.type

    override fun getTokenStart(): Int = tokens.getOrNull(index)?.start ?: endOffset

    override fun getTokenEnd(): Int = tokens.getOrNull(index)?.end ?: endOffset

    override fun advance() {
        if (index < tokens.size) {
            index++
        }
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset

    private fun lex(buffer: CharSequence, startOffset: Int, endOffset: Int): List<Token> {
        val tokens = ArrayList<Token>()
        var pos = startOffset

        while (pos < endOffset) {
            val ch = buffer[pos]
            val token = when {
                isNewlineStart(buffer, pos, endOffset) -> {
                    val end = newlineEnd(buffer, pos, endOffset)
                    Token(KdlElementTypes.NEWLINE, pos, end)
                }
                isUnicodeSpace(ch) -> {
                    var end = pos + 1
                    while (end < endOffset && isUnicodeSpace(buffer[end])) {
                        end++
                    }
                    Token(KdlElementTypes.UNICODE_SPACE, pos, end)
                }
                matches(buffer, pos, endOffset, "//") -> {
                    var end = pos + 2
                    while (end < endOffset && !isNewlineStart(buffer, end, endOffset)) {
                        end++
                    }
                    Token(KdlElementTypes.SINGLE_LINE_COMMENT, pos, end)
                }
                matches(buffer, pos, endOffset, "/*") -> {
                    var depth = 1
                    var end = pos + 2
                    while (end < endOffset && depth > 0) {
                        when {
                            matches(buffer, end, endOffset, "/*") -> {
                                depth++
                                end += 2
                            }
                            matches(buffer, end, endOffset, "*/") -> {
                                depth--
                                end += 2
                            }
                            else -> end++
                        }
                    }
                    Token(KdlElementTypes.MULTI_LINE_COMMENT, pos, end)
                }
                matches(buffer, pos, endOffset, "/-") -> Token(KdlElementTypes.SLASHDASH, pos, pos + 2)
                ch == '/' -> Token(KdlElementTypes.SLASH, pos, pos + 1)
                ch == '\\' -> Token(KdlElementTypes.BACKSLASH, pos, pos + 1)
                ch == ';' -> Token(KdlElementTypes.SEMI, pos, pos + 1)
                ch == ',' -> Token(KdlElementTypes.COMMA, pos, pos + 1)
                ch == '{' -> Token(KdlElementTypes.L_BRACE, pos, pos + 1)
                ch == '}' -> Token(KdlElementTypes.R_BRACE, pos, pos + 1)
                ch == '(' -> Token(KdlElementTypes.L_PAREN, pos, pos + 1)
                ch == ')' -> Token(KdlElementTypes.R_PAREN, pos, pos + 1)
                ch == '[' -> Token(KdlElementTypes.L_BRACK, pos, pos + 1)
                ch == ']' -> Token(KdlElementTypes.R_BRACK, pos, pos + 1)
                ch == '<' -> Token(KdlElementTypes.LESS_THEN, pos, pos + 1)
                ch == '>' -> Token(KdlElementTypes.MORE_THEN, pos, pos + 1)
                ch == '=' -> Token(KdlElementTypes.EQ, pos, pos + 1)
                isV2RawStringStart(buffer, pos, endOffset) -> lexV2RawString(buffer, pos, endOffset)
                isLegacyRawStringStart(buffer, pos, endOffset) -> lexLegacyRawString(buffer, pos, endOffset)
                isQuotedStringStart(buffer, pos, endOffset) -> lexQuotedString(buffer, pos, endOffset)
                matchesKeyword(buffer, pos, endOffset, "#true") -> Token(KdlElementTypes.TRUE_LITERAL, pos, pos + 5)
                matchesKeyword(buffer, pos, endOffset, "#false") -> Token(KdlElementTypes.FALSE_LITERAL, pos, pos + 6)
                matchesKeyword(buffer, pos, endOffset, "#null") -> Token(KdlElementTypes.NULL_LITERAL, pos, pos + 5)
                matchesKeywordNumber(buffer, pos, endOffset, "#-inf") -> Token(KdlElementTypes.DECIMAL_LITERAL, pos, pos + 5)
                matchesKeywordNumber(buffer, pos, endOffset, "#inf") -> Token(KdlElementTypes.DECIMAL_LITERAL, pos, pos + 4)
                matchesKeywordNumber(buffer, pos, endOffset, "#nan") -> Token(KdlElementTypes.DECIMAL_LITERAL, pos, pos + 4)
                matchesKeyword(buffer, pos, endOffset, "true") -> Token(KdlElementTypes.TRUE_LITERAL, pos, pos + 4)
                matchesKeyword(buffer, pos, endOffset, "false") -> Token(KdlElementTypes.FALSE_LITERAL, pos, pos + 5)
                matchesKeyword(buffer, pos, endOffset, "null") -> Token(KdlElementTypes.NULL_LITERAL, pos, pos + 4)
                else -> lexNumber(buffer, pos, endOffset) ?: lexBareIdentifier(buffer, pos, endOffset)
            }

            tokens += token
            pos = token.end.coerceAtLeast(pos + 1)
        }

        return tokens
    }

    private fun lexQuotedString(buffer: CharSequence, start: Int, endOffset: Int): Token {
        if (matches(buffer, start, endOffset, "\"\"\"")) {
            var end = start + 3
            while (end < endOffset) {
                if (matches(buffer, end, endOffset, "\"\"\"")) {
                    end += 3
                    break
                }
                if (buffer[end] == '\\' && end + 1 < endOffset) {
                    end += 2
                } else {
                    end++
                }
            }
            return Token(KdlElementTypes.STRING_LITERAL, start, end)
        }

        var end = start + 1
        while (end < endOffset) {
            when (buffer[end]) {
                '\\' -> end = (end + 2).coerceAtMost(endOffset)
                '"' -> {
                    end++
                    break
                }
                else -> end++
            }
        }
        return Token(KdlElementTypes.STRING_LITERAL, start, end)
    }

    private fun lexLegacyRawString(buffer: CharSequence, start: Int, endOffset: Int): Token {
        var hashes = 0
        var pos = start + 1
        while (pos < endOffset && buffer[pos] == '#') {
            hashes++
            pos++
        }

        val quoteCount = if (matches(buffer, pos, endOffset, "\"\"\"")) 3 else 1
        pos += quoteCount

        while (pos < endOffset) {
            if (quoteCount == 3 && matches(buffer, pos, endOffset, "\"\"\"") && hasHashes(buffer, pos + 3, endOffset, hashes)) {
                pos += 3 + hashes
                break
            }
            if (quoteCount == 1 && pos < endOffset && buffer[pos] == '"' && hasHashes(buffer, pos + 1, endOffset, hashes)) {
                pos += 1 + hashes
                break
            }
            pos++
        }

        return Token(KdlElementTypes.RAW_STRING_LITERAL, start, pos)
    }

    private fun lexV2RawString(buffer: CharSequence, start: Int, endOffset: Int): Token {
        var hashes = 0
        var pos = start
        while (pos < endOffset && buffer[pos] == '#') {
            hashes++
            pos++
        }

        val quoteCount = if (matches(buffer, pos, endOffset, "\"\"\"")) 3 else 1
        pos += quoteCount

        while (pos < endOffset) {
            if (quoteCount == 3 && matches(buffer, pos, endOffset, "\"\"\"") && hasHashes(buffer, pos + 3, endOffset, hashes)) {
                pos += 3 + hashes
                break
            }
            if (quoteCount == 1 && pos < endOffset && buffer[pos] == '"' && hasHashes(buffer, pos + 1, endOffset, hashes)) {
                pos += 1 + hashes
                break
            }
            pos++
        }

        return Token(KdlElementTypes.RAW_STRING_LITERAL, start, pos)
    }

    private fun lexNumber(buffer: CharSequence, start: Int, endOffset: Int): Token? {
        val text = buffer.subSequence(start, endOffset).toString()
        val candidates = listOf(
            HEX_PATTERN.find(text)?.takeIf { it.range.first == 0 }?.value?.length to KdlElementTypes.HEX_LITERAL,
            OCTAL_PATTERN.find(text)?.takeIf { it.range.first == 0 }?.value?.length to KdlElementTypes.OCTAL_LITERAL,
            BINARY_PATTERN.find(text)?.takeIf { it.range.first == 0 }?.value?.length to KdlElementTypes.BINARY_LITERAL,
            DECIMAL_PATTERN.find(text)?.takeIf { it.range.first == 0 }?.value?.length to KdlElementTypes.DECIMAL_LITERAL,
        )

        val match = candidates
            .mapNotNull { (length, type) -> length?.let { Triple(length, type, start + it) } }
            .maxByOrNull { it.first }
            ?: return null

        if (!isIdentifierBoundary(buffer, match.third, endOffset)) {
            return null
        }

        return Token(match.second, start, match.third)
    }

    private fun lexBareIdentifier(buffer: CharSequence, start: Int, endOffset: Int): Token {
        var end = start + 1
        while (end < endOffset && !isBareIdentifierKiller(buffer[end])) {
            end++
        }
        return if (end == start) {
            Token(TokenType.BAD_CHARACTER, start, start + 1)
        } else {
            Token(KdlElementTypes.BARE_IDENTIFIER, start, end)
        }
    }

    private fun matchesKeyword(buffer: CharSequence, start: Int, endOffset: Int, keyword: String): Boolean =
        matches(buffer, start, endOffset, keyword) && isIdentifierBoundary(buffer, start + keyword.length, endOffset)

    private fun matchesKeywordNumber(buffer: CharSequence, start: Int, endOffset: Int, keyword: String): Boolean =
        matches(buffer, start, endOffset, keyword) && isIdentifierBoundary(buffer, start + keyword.length, endOffset)

    private fun isLegacyRawStringStart(buffer: CharSequence, start: Int, endOffset: Int): Boolean {
        if (start + 1 >= endOffset || buffer[start] != 'r') {
            return false
        }

        var pos = start + 1
        while (pos < endOffset && buffer[pos] == '#') {
            pos++
        }

        return pos < endOffset && buffer[pos] == '"'
    }

    private fun isV2RawStringStart(buffer: CharSequence, start: Int, endOffset: Int): Boolean {
        if (buffer[start] != '#') {
            return false
        }

        var pos = start
        while (pos < endOffset && buffer[pos] == '#') {
            pos++
        }

        return pos < endOffset && buffer[pos] == '"'
    }

    private fun isQuotedStringStart(buffer: CharSequence, start: Int, endOffset: Int): Boolean =
        buffer[start] == '"'

    private fun hasHashes(buffer: CharSequence, start: Int, endOffset: Int, hashes: Int): Boolean {
        if (start + hashes > endOffset) {
            return false
        }
        for (i in 0 until hashes) {
            if (buffer[start + i] != '#') {
                return false
            }
        }
        return true
    }

    private fun matches(buffer: CharSequence, start: Int, endOffset: Int, text: String): Boolean {
        if (start + text.length > endOffset) {
            return false
        }
        for (i in text.indices) {
            if (buffer[start + i] != text[i]) {
                return false
            }
        }
        return true
    }

    private fun isIdentifierBoundary(buffer: CharSequence, pos: Int, endOffset: Int): Boolean =
        pos >= endOffset || isBareIdentifierKiller(buffer[pos])

    private fun isBareIdentifierKiller(ch: Char): Boolean = when (ch) {
        '\t', ' ', '\u00A0', '\u1680',
        '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007',
        '\u2008', '\u2009', '\u200A', '\u202F', '\u205F', '\u3000',
        '/', '\\', ';', ',', '{', '}', '(', ')', '[', ']', '<', '>', '=', '"',
        '\r', '\n', '\u0085', '\u000B', '\u000C', '\u2028', '\u2029' -> true
        else -> false
    }

    private fun isUnicodeSpace(ch: Char): Boolean = when (ch) {
        '\t', ' ', '\u00A0', '\u1680',
        '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007',
        '\u2008', '\u2009', '\u200A', '\u202F', '\u205F', '\u3000' -> true
        else -> false
    }

    private fun isNewlineStart(buffer: CharSequence, pos: Int, endOffset: Int): Boolean {
        val ch = buffer[pos]
        return ch == '\n' || ch == '\r' || ch == '\u0085' || ch == '\u000B' || ch == '\u000C' || ch == '\u2028' || ch == '\u2029'
    }

    private fun newlineEnd(buffer: CharSequence, pos: Int, endOffset: Int): Int {
        if (buffer[pos] == '\r' && pos + 1 < endOffset && buffer[pos + 1] == '\n') {
            return pos + 2
        }
        return pos + 1
    }

    companion object {
        private val DECIMAL_PATTERN = Regex("[+-]?[0-9](?:[0-9_]*)(?:\\.[0-9](?:[0-9_]*))?(?:[eE][+-]?[0-9](?:[0-9_]*))?")
        private val HEX_PATTERN = Regex("[+-]?0x[0-9a-fA-F](?:[0-9a-fA-F_])*")
        private val OCTAL_PATTERN = Regex("[+-]?0o[0-7](?:[0-7_])*")
        private val BINARY_PATTERN = Regex("[+-]?0b[01](?:[01_])*")
    }
}
