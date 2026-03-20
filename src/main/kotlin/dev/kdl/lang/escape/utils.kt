package dev.kdl.lang.escape

import java.util.stream.IntStream

fun unescapeString(rawValue: String): String {
    val result = StringBuilder(rawValue.length)
    var index = 0

    while (index < rawValue.length) {
        val ch = rawValue[index]
        if (ch != '\\' || index + 1 >= rawValue.length) {
            result.append(ch)
            index++
            continue
        }

        val next = rawValue[index + 1]
        when (next) {
            'n' -> {
                result.append('\n')
                index += 2
            }
            'r' -> {
                result.append('\r')
                index += 2
            }
            't' -> {
                result.append('\t')
                index += 2
            }
            '\\' -> {
                result.append('\\')
                index += 2
            }
            '/' -> {
                result.append('/')
                index += 2
            }
            '"' -> {
                result.append('"')
                index += 2
            }
            'b' -> {
                result.append('\u0008')
                index += 2
            }
            'f' -> {
                result.append('\u000C')
                index += 2
            }
            's' -> {
                result.append(' ')
                index += 2
            }
            'u' -> {
                val end = rawValue.indexOf('}', index + 2)
                if (end > index + 3 && rawValue.getOrNull(index + 2) == '{') {
                    val codePoint = rawValue.substring(index + 3, end).toInt(16)
                    result.appendCodePoint(codePoint)
                    index = end + 1
                } else {
                    result.append('\\')
                    index++
                }
            }
            else -> {
                if (isEscapedWhitespace(next)) {
                    index += 2
                    while (index < rawValue.length) {
                        val current = rawValue[index]
                        if (isEscapedWhitespace(current)) {
                            index++
                            continue
                        }
                        if (current == '\r' || current == '\n') {
                            index = normalizedNewlineEnd(rawValue, index)
                            continue
                        }
                        break
                    }
                } else {
                    result.append(next)
                    index += 2
                }
            }
        }
    }

    return result.toString()
}

fun escapeString(rawValue: String): String {
    val codepoints = rawValue.codePoints()
        .flatMap {
            when (it) {
                0x000A -> "\\n".codePoints() // LF
                0x000D -> "\\r".codePoints() // CR
                0x0009 -> "\\t".codePoints() // TAB
                0x005C -> "\\\\".codePoints() // BACKSLASH
                0x002F -> "\\/".codePoints() // FORWARDSLASH
                0x0022 -> "\\\"".codePoints() // QUOTE
                0x0008 -> "\\b".codePoints() // BACKSPACE
                0x000C -> "\\f".codePoints() // FORM_FEED
                else -> IntStream.of(it)
            }
        }
        .toArray()

    return String(codepoints, 0, codepoints.size)
}

private fun isEscapedWhitespace(ch: Char): Boolean = when (ch) {
    '\t', ' ', '\u00A0', '\u1680',
    '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007',
    '\u2008', '\u2009', '\u200A', '\u202F', '\u205F', '\u3000' -> true
    else -> false
}

private fun normalizedNewlineEnd(text: String, index: Int): Int =
    if (text[index] == '\r' && text.getOrNull(index + 1) == '\n') index + 2 else index + 1
