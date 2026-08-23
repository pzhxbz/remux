package dev.remux.app.ui.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TerminalKeyEncoderTest {
    @Test
    fun `control c is one byte`() {
        assertArrayEquals(
            byteArrayOf(0x03),
            TerminalKeyEncoder.applyTextModifiers("c".encodeToByteArray(), ctrl = true, alt = false),
        )
    }

    @Test
    fun `modified arrow uses xterm modifier encoding`() {
        assertArrayEquals(
            "\u001b[1;7A".encodeToByteArray(),
            TerminalKeyEncoder.encode(TerminalKey.UP, ctrl = true, alt = true),
        )
    }

    @Test
    fun `tmux prefix is control b regardless of modifiers`() {
        assertArrayEquals(
            byteArrayOf(0x02),
            TerminalKeyEncoder.encode(TerminalKey.TMUX_PREFIX, ctrl = true, alt = true),
        )
    }

    @Test
    fun `custom tmux prefixes support control meta and hex`() {
        assertArrayEquals(byteArrayOf(0x01), TerminalKeyEncoder.encodeTmuxPrefix("C-a"))
        assertArrayEquals(
            byteArrayOf(0x1b, 'x'.code.toByte()),
            TerminalKeyEncoder.encodeTmuxPrefix("M-x"),
        )
        assertArrayEquals(byteArrayOf(0x02), TerminalKeyEncoder.encodeTmuxPrefix("0x02"))
    }
}
