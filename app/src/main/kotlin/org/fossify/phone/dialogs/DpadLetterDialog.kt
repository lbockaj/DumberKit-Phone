package org.fossify.phone.dialogs

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.GridLayout
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.views.MyTextView
import org.fossify.phone.R
import org.fossify.phone.helpers.applyDpadFocusHighlight

private const val COLUMNS = 7
const val DPAD_OTHER_LETTER = "#"

// keypad digits and their letters, like on the phone keys
private val KEY_LETTERS = mapOf(
    KeyEvent.KEYCODE_2 to "ABC", KeyEvent.KEYCODE_3 to "DEF", KeyEvent.KEYCODE_4 to "GHI", KeyEvent.KEYCODE_5 to "JKL",
    KeyEvent.KEYCODE_6 to "MNO", KeyEvent.KEYCODE_7 to "PQRS", KeyEvent.KEYCODE_8 to "TUV", KeyEvent.KEYCODE_9 to "WXYZ",
    KeyEvent.KEYCODE_0 to DPAD_OTHER_LETTER, KeyEvent.KEYCODE_1 to DPAD_OTHER_LETTER
)

/**
 * A–Z grid for jumping in the contact list. Letters no contact starts with are greyed out and skipped by the arrows;
 * the digit keys jump to their first letter (2 = A, B, C…), pressing the same key again moves to the next one.
 */
class DpadLetterDialog(
    val activity: Activity,
    availableLetters: Set<String>,
    currentLetter: String?,
    val callback: (letter: String) -> Unit
) {
    private var dialog: AlertDialog? = null
    private val cells = LinkedHashMap<String, MyTextView>()
    private var lastDigit = -1

    init {
        val letters = ('A'..'Z').map { it.toString() } + listOfNotNull(DPAD_OTHER_LETTER.takeIf { it in availableLetters })
        val textColor = activity.getProperTextColor()
        val cellHeight = activity.resources.getDimensionPixelSize(R.dimen.dpad_letter_cell_height)
        val grid = GridLayout(activity).apply {
            columnCount = COLUMNS
            val padding = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
            setPadding(padding, padding, padding, padding)
        }

        letters.forEach { letter ->
            val enabled = letter in availableLetters
            val cell = MyTextView(activity).apply {
                text = letter
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(textColor)
                isFocusable = enabled
                isClickable = enabled
                alpha = if (enabled) 1f else 0.3f
                if (enabled) {
                    applyDpadFocusHighlight()
                    setOnClickListener { select(letter) }
                }
            }

            val params = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                height = cellHeight
            }
            grid.addView(cell, params)
            cells[letter] = cell
        }

        // no buttons: Back closes the dialog
        val builder = activity.getAlertDialogBuilder()
            .setOnKeyListener { _, keyCode, event -> handleDigit(keyCode, event) }

        activity.setupDialogStuff(grid, builder, R.string.dpad_jump_to_letter) { alertDialog ->
            dialog = alertDialog
            val start = cells[currentLetter]?.takeIf { it.isFocusable } ?: cells.values.firstOrNull { it.isFocusable }
            grid.post { start?.requestFocus() }
        }
    }

    private fun handleDigit(keyCode: Int, event: KeyEvent): Boolean {
        val group = KEY_LETTERS[keyCode] ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val targets = cells.filterKeys { it in group.chunked(1) || it == group }.values.filter { it.isFocusable }
            if (targets.isNotEmpty()) {
                // the first press starts at the key's first letter, pressing it again moves on
                val focused = if (keyCode == lastDigit) targets.indexOfFirst { it.isFocused } else -1
                targets[(focused + 1) % targets.size].requestFocus()
            }
            lastDigit = keyCode
        }
        return true
    }

    private fun select(letter: String) {
        dialog?.dismiss()
        callback(letter)
    }
}
