@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package dev.remux.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.remux.app.ui.terminal.ModifierMode
import dev.remux.app.ui.terminal.TerminalKey

internal enum class TerminalInputMode {
    HIDDEN,
    CUSTOM,
    SYSTEM,
}

private enum class KeyboardLayer {
    LETTERS,
    SYMBOLS,
    FUNCTIONS,
}

@Composable
internal fun TerminalKeyboard(
    tab: TerminalTabState,
    tmuxPrefix: String,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onControlC: () -> Unit,
    onModifier: (ctrl: Boolean, locked: Boolean) -> Unit,
    onPaste: () -> Unit,
    onSystemIme: () -> Unit,
    onHide: () -> Unit,
) {
    var shifted by remember(tab.id) { mutableStateOf(false) }
    var layer by remember(tab.id) { mutableStateOf(KeyboardLayer.LETTERS) }
    val sendCharacter: (String) -> Unit = { value ->
        onText(if (shifted && layer == KeyboardLayer.LETTERS) value.uppercase() else value)
        if (shifted) shifted = false
    }
    val rows = if (layer == KeyboardLayer.SYMBOLS) {
        listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("-", "/", ":", ";", "(", ")", "\$", "&", "@", "\""),
            listOf("[", "]", "{", "}", "#", "%", "*", "+", "=", "_"),
        )
    } else {
        listOf(
            "qwertyuiop".map(Char::toString),
            "asdfghjkl".map(Char::toString),
            "zxcvbnm".map(Char::toString),
        )
    }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                UtilityKey("Esc", "Send Escape") { onKey(TerminalKey.ESC) }
                UtilityKey("Tab", "Send Tab") { onKey(TerminalKey.TAB) }
                ModifierKey("Ctrl", tab.ctrl, ctrl = true, onModifier = onModifier)
                ModifierKey("Alt", tab.alt, ctrl = false, onModifier = onModifier)
                UtilityKey(tmuxPrefix, "Send configured tmux prefix") {
                    onKey(TerminalKey.TMUX_PREFIX)
                }
                UtilityKey("^C", "Send Control C", danger = true, onClick = onControlC)
                UtilityKey("Paste", "Paste clipboard", onClick = onPaste)
                UtilityKey("←", "Send Left arrow") { onKey(TerminalKey.LEFT) }
                UtilityKey("↑", "Send Up arrow") { onKey(TerminalKey.UP) }
                UtilityKey("↓", "Send Down arrow") { onKey(TerminalKey.DOWN) }
                UtilityKey("→", "Send Right arrow") { onKey(TerminalKey.RIGHT) }
                UtilityKey("⌄", "Hide terminal keyboard", onClick = onHide)
            }

            if (layer == KeyboardLayer.FUNCTIONS) {
                FunctionRow(
                    listOf(
                        TerminalKey.F1,
                        TerminalKey.F2,
                        TerminalKey.F3,
                        TerminalKey.F4,
                        TerminalKey.F5,
                        TerminalKey.F6,
                    ),
                    onKey,
                )
                FunctionRow(
                    listOf(
                        TerminalKey.F7,
                        TerminalKey.F8,
                        TerminalKey.F9,
                        TerminalKey.F10,
                        TerminalKey.F11,
                        TerminalKey.F12,
                    ),
                    onKey,
                )
                FunctionRow(
                    listOf(
                        TerminalKey.HOME,
                        TerminalKey.END,
                        TerminalKey.PAGE_UP,
                        TerminalKey.PAGE_DOWN,
                        TerminalKey.INSERT,
                        TerminalKey.DELETE,
                    ),
                    onKey,
                )
            } else {
                CharacterRow(rows[0], sendCharacter)
                CharacterRow(
                    rows[1],
                    sendCharacter,
                    sideInset = if (layer == KeyboardLayer.SYMBOLS) 0.dp else 14.dp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeyboardKey(
                        label = if (shifted) "⇧" else "↑",
                        description = "Shift",
                        selected = shifted,
                        modifier = Modifier.weight(1.25f),
                    ) { shifted = !shifted }
                    rows[2].forEach { character ->
                        KeyboardKey(
                            label = if (shifted && layer == KeyboardLayer.LETTERS) {
                                character.uppercase()
                            } else {
                                character
                            },
                            description = "Type $character",
                            modifier = Modifier.weight(1f),
                        ) { sendCharacter(character) }
                    }
                    KeyboardKey(
                        label = "⌫",
                        description = "Backspace",
                        modifier = Modifier.weight(1.25f),
                    ) { onKey(TerminalKey.BACKSPACE) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyboardKey(
                    label = if (layer == KeyboardLayer.LETTERS) "123" else "ABC",
                    description = if (layer == KeyboardLayer.LETTERS) {
                        "Show symbols"
                    } else {
                        "Show letters"
                    },
                    modifier = Modifier.weight(1.05f),
                ) {
                    layer = if (layer == KeyboardLayer.LETTERS) {
                        KeyboardLayer.SYMBOLS
                    } else {
                        KeyboardLayer.LETTERS
                    }
                    shifted = false
                }
                KeyboardKey(
                    label = "Fn",
                    description = "Show function and navigation keys",
                    selected = layer == KeyboardLayer.FUNCTIONS,
                    modifier = Modifier.weight(0.8f),
                ) {
                    layer = if (layer == KeyboardLayer.FUNCTIONS) {
                        KeyboardLayer.LETTERS
                    } else {
                        KeyboardLayer.FUNCTIONS
                    }
                    shifted = false
                }
                KeyboardKey("中/EN", "Use system input method", Modifier.weight(1.1f), onClick = onSystemIme)
                KeyboardKey("Space", "Type space", Modifier.weight(3f)) { sendCharacter(" ") }
                KeyboardKey(".", "Type period", Modifier.weight(0.65f)) { sendCharacter(".") }
                KeyboardKey("/", "Type slash", Modifier.weight(0.65f)) { sendCharacter("/") }
                KeyboardKey("↵", "Send Enter", Modifier.weight(1.1f), accent = true) {
                    onKey(TerminalKey.ENTER)
                }
            }
        }
    }
}

@Composable
private fun FunctionRow(keys: List<TerminalKey>, onKey: (TerminalKey) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        keys.forEach { key ->
            KeyboardKey(
                label = key.label,
                description = key.contentDescription,
                modifier = Modifier.weight(1f),
            ) { onKey(key) }
        }
    }
}

@Composable
private fun CharacterRow(
    characters: List<String>,
    onCharacter: (String) -> Unit,
    sideInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = sideInset),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        characters.forEach { character ->
            KeyboardKey(
                label = character,
                description = "Type $character",
                modifier = Modifier.weight(1f),
            ) { onCharacter(character) }
        }
    }
}

@Composable
private fun RowScope.KeyboardKey(
    label: String,
    description: String,
    modifier: Modifier,
    selected: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = when {
            accent -> MaterialTheme.colorScheme.primaryContainer
            selected -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier
            .height(35.dp)
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            .semantics { contentDescription = description },
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun UtilityKey(
    label: String,
    description: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .height(29.dp)
            .combinedClickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ModifierKey(
    label: String,
    mode: ModifierMode,
    ctrl: Boolean,
    onModifier: (Boolean, Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = when (mode) {
            ModifierMode.OFF -> MaterialTheme.colorScheme.surfaceVariant
            ModifierMode.ARMED -> MaterialTheme.colorScheme.secondaryContainer
            ModifierMode.LOCKED -> MaterialTheme.colorScheme.primaryContainer
        },
        modifier = Modifier
            .height(29.dp)
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onModifier(ctrl, false)
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onModifier(ctrl, true)
                },
            )
            .padding(horizontal = 9.dp)
            .semantics {
                contentDescription = "$label modifier"
            },
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Text(
                if (mode == ModifierMode.LOCKED) "$label·" else label,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
