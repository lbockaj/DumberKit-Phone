package org.fossify.phone.dialogs

import android.app.Activity
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.databinding.DialogRadioGroupBinding
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.models.RadioItem
import org.fossify.phone.helpers.applyDpadFocusHighlight

/**
 * Same as Commons' RadioGroupDialog, but usable with keys: every option is one full-width D-pad stop with a clear
 * highlight, and the selected option (or the first one) has the focus when the dialog opens.
 */
class DpadRadioGroupDialog(
    val activity: Activity,
    val items: ArrayList<RadioItem>,
    val checkedItemId: Int = -1,
    val titleId: Int = 0,
    showOKButton: Boolean = false,
    val cancelCallback: (() -> Unit)? = null,
    val callback: (newValue: Any) -> Unit
) {
    private var dialog: AlertDialog? = null
    private var wasInit = false
    private var selectedItemId = -1

    init {
        val view = DialogRadioGroupBinding.inflate(activity.layoutInflater, null, false)
        val buttons = ArrayList<RadioButton>()
        view.dialogRadioGroup.apply {
            for (i in 0 until items.size) {
                val radioButton = (activity.layoutInflater.inflate(org.fossify.commons.R.layout.radio_button, null) as RadioButton).apply {
                    text = items[i].title
                    isChecked = items[i].id == checkedItemId
                    id = i
                    isFocusable = true
                    applyDpadFocusHighlight()
                    setOnClickListener { itemSelected(i) }
                }

                if (items[i].id == checkedItemId) {
                    selectedItemId = i
                }

                buttons.add(radioButton)
                addView(radioButton, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        val builder = activity.getAlertDialogBuilder()
            .setOnCancelListener { cancelCallback?.invoke() }

        if (selectedItemId != -1 && showOKButton) {
            builder.setPositiveButton(org.fossify.commons.R.string.ok) { _, _ -> itemSelected(selectedItemId) }
        }

        builder.apply {
            activity.setupDialogStuff(view.root, this, titleId) { alertDialog ->
                dialog = alertDialog
            }
        }

        view.dialogRadioHolder.onGlobalLayout {
            val focusTarget = buttons.getOrNull(selectedItemId) ?: buttons.firstOrNull()
            focusTarget?.requestFocusFromTouch()
            if (selectedItemId != -1) {
                view.dialogRadioHolder.scrollY = view.dialogRadioGroup.findViewById<RadioButton>(selectedItemId).bottom - view.dialogRadioHolder.height
            }
        }

        wasInit = true
    }

    private fun itemSelected(checkedId: Int) {
        if (wasInit) {
            callback(items[checkedId].value)
            dialog?.dismiss()
        }
    }
}
