package org.fossify.phone.activities

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import org.fossify.phone.BuildConfig
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityDpadAboutBinding
import org.fossify.phone.helpers.dpadCompactTopBar
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.views.MyTextView

/** App name, version, the keypad shortcuts of each screen and the license; the arrows scroll the page. */
class DpadAboutActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDpadAboutBinding::inflate)

    /** Screen title, then its (key, action) rows. */
    private val shortcuts = listOf(
        R.string.dpad_about_main to listOf(
            R.string.dpad_key_left_right to R.string.dpad_key_main_left_right,
            R.string.dpad_key_digits to R.string.dpad_key_main_digits,
            R.string.dpad_key_ok to R.string.dpad_key_main_ok,
            R.string.dpad_key_ok_hold to R.string.dpad_key_main_ok_hold,
            R.string.dpad_key_call to R.string.dpad_key_main_call,
            R.string.dpad_key_pound to R.string.dpad_key_main_pound,
            R.string.dpad_key_star to R.string.dpad_key_main_star,
            R.string.dpad_key_up_down to R.string.dpad_key_main_up,
            R.string.dpad_key_menu to R.string.dpad_key_main_menu,
            R.string.dpad_key_back to R.string.dpad_key_main_back
        ),
        R.string.dpad_about_dialing to listOf(
            R.string.dpad_key_digits to R.string.dpad_key_dial_digits,
            R.string.dpad_key_call to R.string.dpad_key_dial_call,
            R.string.dpad_key_up_down to R.string.dpad_key_dial_down,
            R.string.dpad_key_back to R.string.dpad_key_dial_back,
            R.string.dpad_key_back_hold to R.string.dpad_key_dial_back_hold,
            R.string.dpad_key_zero_hold to R.string.dpad_key_dial_zero_hold,
            R.string.dpad_key_digit_hold to R.string.dpad_key_dial_digit_hold,
            R.string.dpad_key_menu to R.string.dpad_key_dial_menu
        ),
        R.string.dpad_about_incoming to listOf(
            R.string.dpad_key_call to R.string.dpad_key_incoming_answer,
            R.string.dpad_key_right to R.string.dpad_key_incoming_decline,
            R.string.dpad_key_end to R.string.dpad_key_incoming_red,
            R.string.dpad_key_back to R.string.dpad_key_incoming_back
        ),
        R.string.dpad_about_ongoing to listOf(
            R.string.dpad_key_left_right to R.string.dpad_key_ongoing_rows,
            R.string.dpad_key_ok to R.string.dpad_key_ongoing_ok,
            R.string.dpad_key_digits to R.string.dpad_key_ongoing_digits,
            R.string.dpad_key_call to R.string.dpad_key_ongoing_green,
            R.string.dpad_key_end to R.string.dpad_key_ongoing_end
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        // a margin, not padding: Android's scroll views ignore their padding when they scroll to the focused row, the
        // last rows ended up under the navigation bar
        setupEdgeToEdge(moveBottomSystem = listOf(binding.aboutNestedScrollview))

        binding.aboutVersion.text = getString(R.string.dpad_about_version, BuildConfig.VERSION_NAME)
        binding.aboutIcon.setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
        addShortcuts()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.aboutAppbar, NavigationIcon.None)
        dpadCompactTopBar(binding.aboutAppbar, binding.aboutNestedScrollview)
        updateTextColors(binding.aboutHolder)
        listOf(binding.aboutShortcutsLabel, binding.aboutLicenseLabel).forEach { it.setTextColor(getProperPrimaryColor()) }
        binding.aboutNestedScrollview.requestFocus()
    }

    private fun addShortcuts() {
        val keyWidth = resources.getDimensionPixelSize(R.dimen.dpad_about_key_width)
        val smallMargin = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
        val sectionMargin = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.medium_margin)
        shortcuts.forEach { (screen, keys) ->
            binding.aboutShortcuts.addView(MyTextView(this).apply {
                setText(screen)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, sectionMargin, 0, 0)
            })

            keys.forEach { (key, action) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, smallMargin, 0, 0)
                }

                row.addView(MyTextView(this).apply {
                    setText(key)
                    textSize = 15f
                    alpha = 0.8f
                    gravity = Gravity.START
                }, LinearLayout.LayoutParams(keyWidth, LinearLayout.LayoutParams.WRAP_CONTENT))

                row.addView(MyTextView(this).apply {
                    setText(action)
                    textSize = 15f
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                binding.aboutShortcuts.addView(row)
            }
        }
    }
}
