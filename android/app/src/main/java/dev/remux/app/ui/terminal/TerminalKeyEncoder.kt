package dev.remux.app.ui.terminal

enum class ModifierMode {
    OFF,
    ARMED,
    LOCKED,
}

enum class TerminalKey(val label: String, val contentDescription: String) {
    ESC("Esc", "Send Escape"),
    TAB("Tab", "Send Tab"),
    ENTER("Enter", "Send Enter"),
    UP("↑", "Send Up arrow"),
    DOWN("↓", "Send Down arrow"),
    LEFT("←", "Send Left arrow"),
    RIGHT("→", "Send Right arrow"),
    HOME("Home", "Send Home"),
    END("End", "Send End"),
    PAGE_UP("PgUp", "Send Page Up"),
    PAGE_DOWN("PgDn", "Send Page Down"),
    INSERT("Ins", "Send Insert"),
    DELETE("Del", "Send Delete"),
    F1("F1", "Send F1"),
    F2("F2", "Send F2"),
    F3("F3", "Send F3"),
    F4("F4", "Send F4"),
    F5("F5", "Send F5"),
    F6("F6", "Send F6"),
    F7("F7", "Send F7"),
    F8("F8", "Send F8"),
    F9("F9", "Send F9"),
    F10("F10", "Send F10"),
    F11("F11", "Send F11"),
    F12("F12", "Send F12"),
    PIPE("|", "Send pipe"),
    SLASH("/", "Send slash"),
    BACKSLASH("\\", "Send backslash"),
    DASH("-", "Send dash"),
    UNDERSCORE("_", "Send underscore"),
    TMUX_PREFIX("tmux", "Send configured tmux prefix"),
}

object TerminalKeyEncoder {
    fun encode(
        key: TerminalKey,
        ctrl: Boolean = false,
        alt: Boolean = false,
        tmuxPrefix: String = "C-b",
    ): ByteArray {
        if (key == TerminalKey.TMUX_PREFIX) return encodeTmuxPrefix(tmuxPrefix)
        val modifier = 1 + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        val plain = plainSequence(key)
        if (!ctrl && !alt) return plain

        val modifiedEscape = when (key) {
            TerminalKey.UP -> csi("1;${modifier}A")
            TerminalKey.DOWN -> csi("1;${modifier}B")
            TerminalKey.RIGHT -> csi("1;${modifier}C")
            TerminalKey.LEFT -> csi("1;${modifier}D")
            TerminalKey.HOME -> csi("1;${modifier}H")
            TerminalKey.END -> csi("1;${modifier}F")
            TerminalKey.INSERT -> csi("2;${modifier}~")
            TerminalKey.DELETE -> csi("3;${modifier}~")
            TerminalKey.PAGE_UP -> csi("5;${modifier}~")
            TerminalKey.PAGE_DOWN -> csi("6;${modifier}~")
            TerminalKey.F1 -> csi("1;${modifier}P")
            TerminalKey.F2 -> csi("1;${modifier}Q")
            TerminalKey.F3 -> csi("1;${modifier}R")
            TerminalKey.F4 -> csi("1;${modifier}S")
            TerminalKey.F5 -> csi("15;${modifier}~")
            TerminalKey.F6 -> csi("17;${modifier}~")
            TerminalKey.F7 -> csi("18;${modifier}~")
            TerminalKey.F8 -> csi("19;${modifier}~")
            TerminalKey.F9 -> csi("20;${modifier}~")
            TerminalKey.F10 -> csi("21;${modifier}~")
            TerminalKey.F11 -> csi("23;${modifier}~")
            TerminalKey.F12 -> csi("24;${modifier}~")
            else -> null
        }
        if (modifiedEscape != null) return modifiedEscape

        var result = plain
        if (ctrl && plain.size == 1) {
            val value = plain[0].toInt() and 0xff
            result = when (value) {
                in 'a'.code..'z'.code -> byteArrayOf((value - 'a'.code + 1).toByte())
                in 'A'.code..'Z'.code -> byteArrayOf((value - 'A'.code + 1).toByte())
                '@'.code, '['.code, '\\'.code, ']'.code, '^'.code, '_'.code -> {
                    byteArrayOf((value and 0x1f).toByte())
                }
                else -> result
            }
        }
        return if (alt) byteArrayOf(0x1b) + result else result
    }

    fun applyTextModifiers(input: ByteArray, ctrl: Boolean, alt: Boolean): ByteArray {
        var result = input
        if (ctrl && input.size == 1) {
            val value = input[0].toInt() and 0xff
            result = when (value) {
                in 'a'.code..'z'.code -> byteArrayOf((value - 'a'.code + 1).toByte())
                in 'A'.code..'Z'.code -> byteArrayOf((value - 'A'.code + 1).toByte())
                '@'.code, '['.code, '\\'.code, ']'.code, '^'.code, '_'.code -> {
                    byteArrayOf((value and 0x1f).toByte())
                }
                else -> input
            }
        }
        return if (alt) byteArrayOf(0x1b) + result else result
    }

    fun encodeTmuxPrefix(value: String): ByteArray {
        val normalized = value.trim()
        Regex("(?i)^(?:c|ctrl)-([a-z@\\[\\]\\\\^_?])$")
            .matchEntire(normalized)
            ?.let { match ->
                val character = match.groupValues[1].uppercase()[0]
                return if (character == '?') {
                    byteArrayOf(0x7f)
                } else {
                    byteArrayOf((character.code and 0x1f).toByte())
                }
            }
        Regex("(?i)^(?:m|alt)-([ -~])$")
            .matchEntire(normalized)
            ?.let { match ->
                return byteArrayOf(0x1b, match.groupValues[1][0].code.toByte())
            }
        Regex("(?i)^0x([0-9a-f]{2})$")
            .matchEntire(normalized)
            ?.let { match ->
                return byteArrayOf(match.groupValues[1].toInt(16).toByte())
            }
        throw IllegalArgumentException("tmux prefix must look like C-b, M-a, or 0x02")
    }

    private fun plainSequence(key: TerminalKey): ByteArray = when (key) {
        TerminalKey.ESC -> byteArrayOf(0x1b)
        TerminalKey.TAB -> byteArrayOf(0x09)
        TerminalKey.ENTER -> byteArrayOf(0x0d)
        TerminalKey.UP -> csi("A")
        TerminalKey.DOWN -> csi("B")
        TerminalKey.RIGHT -> csi("C")
        TerminalKey.LEFT -> csi("D")
        TerminalKey.HOME -> csi("H")
        TerminalKey.END -> csi("F")
        TerminalKey.PAGE_UP -> csi("5~")
        TerminalKey.PAGE_DOWN -> csi("6~")
        TerminalKey.INSERT -> csi("2~")
        TerminalKey.DELETE -> csi("3~")
        TerminalKey.F1 -> byteArrayOf(0x1b, 'O'.code.toByte(), 'P'.code.toByte())
        TerminalKey.F2 -> byteArrayOf(0x1b, 'O'.code.toByte(), 'Q'.code.toByte())
        TerminalKey.F3 -> byteArrayOf(0x1b, 'O'.code.toByte(), 'R'.code.toByte())
        TerminalKey.F4 -> byteArrayOf(0x1b, 'O'.code.toByte(), 'S'.code.toByte())
        TerminalKey.F5 -> csi("15~")
        TerminalKey.F6 -> csi("17~")
        TerminalKey.F7 -> csi("18~")
        TerminalKey.F8 -> csi("19~")
        TerminalKey.F9 -> csi("20~")
        TerminalKey.F10 -> csi("21~")
        TerminalKey.F11 -> csi("23~")
        TerminalKey.F12 -> csi("24~")
        TerminalKey.PIPE -> byteArrayOf('|'.code.toByte())
        TerminalKey.SLASH -> byteArrayOf('/'.code.toByte())
        TerminalKey.BACKSLASH -> byteArrayOf('\\'.code.toByte())
        TerminalKey.DASH -> byteArrayOf('-'.code.toByte())
        TerminalKey.UNDERSCORE -> byteArrayOf('_'.code.toByte())
        TerminalKey.TMUX_PREFIX -> byteArrayOf(0x02)
    }

    private fun csi(value: String): ByteArray = "\u001b[$value".encodeToByteArray()
}
