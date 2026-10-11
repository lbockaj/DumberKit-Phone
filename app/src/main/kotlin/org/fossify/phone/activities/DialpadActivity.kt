package org.fossify.phone.activities

import android.content.Intent
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.applyColorFilter
import android.text.style.ForegroundColorSpan
import android.text.SpannableStringBuilder
import android.os.Looper
import android.os.Handler
import android.graphics.Color
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony.Sms.Intents.SECRET_CODE_ACTION
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkAppSideloading
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.KeypadHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.REQUEST_CODE_SET_DEFAULT_DIALER
import org.fossify.commons.helpers.isOreoPlus
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.extensions.isOwnDefaultDialer
import org.fossify.phone.adapters.ContactsAdapter
import org.fossify.phone.databinding.ActivityDialpadBinding
import org.fossify.phone.extensions.areMultipleSIMsAvailable
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.helpers.DIALPAD_TONE_LENGTH_MS
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.ToneGeneratorHelper
import org.fossify.phone.helpers.dpadPopupMenu
import org.fossify.phone.helpers.focusItem
import org.fossify.phone.models.SpeedDial
import java.util.Locale

/**
 * Dialing with the phone's keypad: the digits type the number (no on-screen dialpad, no keyboard), the contacts
 * whose number or T9 name matches it are listed below. Call (or OK) calls, Back deletes, hold 0 types +.
 */
private const val CURSOR = "|"
private const val CURSOR_BLINK_MS = 530L

class DialpadActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDialpadBinding::inflate)

    private var number = ""
    private var allContacts = ArrayList<Contact>()
    private var speedDialValues = ArrayList<SpeedDial>()
    private var privateCursor: Cursor? = null
    private var toneGeneratorHelper: ToneGeneratorHelper? = null
    private var backLongPressed = false
    private var digitLongPressed = false
    private var callStarted = false
    private val cursorHandler = Handler(Looper.getMainLooper())
    private var cursorShown = true

    private var hasRussianLocale = false
    private val russianCharsMap by lazy {
        hashMapOf(
            'а' to 2, 'б' to 2, 'в' to 2, 'г' to 2,
            'д' to 3, 'е' to 3, 'ё' to 3, 'ж' to 3, 'з' to 3,
            'и' to 4, 'й' to 4, 'к' to 4, 'л' to 4,
            'м' to 5, 'н' to 5, 'о' to 5, 'п' to 5,
            'р' to 6, 'с' to 6, 'т' to 6, 'у' to 6,
            'ф' to 7, 'х' to 7, 'ц' to 7, 'ч' to 7,
            'ш' to 8, 'щ' to 8, 'ъ' to 8, 'ы' to 8,
            'ь' to 9, 'э' to 9, 'ю' to 9, 'я' to 9
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        hasRussianLocale = Locale.getDefault().language == "ru"
        setupEdgeToEdge(padTopSystem = listOf(binding.dialpadHolder), padBottomImeAndSystem = listOf(binding.dialpadList))

        if (checkAppSideloading()) {
            return
        }

        speedDialValues = config.getSpeedDialValues()
        privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        toneGeneratorHelper = ToneGeneratorHelper(this, DIALPAD_TONE_LENGTH_MS)

        // the typed number holds the focus, ↓ moves into the matching contacts
        binding.dialpadNumber.requestFocusFromTouch()
        // the cursor shows only while the keys type into the number
        binding.dialpadNumber.setOnFocusChangeListener { _, _ -> showNumber() }
        checkDialIntent()
        showNumber()

        ContactsHelper(this).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
            gotContacts(contacts)
        }
    }

    override fun onResume() {
        super.onResume()
        val textColor = getProperTextColor()
        binding.dialpadHolder.setBackgroundColor(getProperBackgroundColor())
        updateTextColors(binding.dialpadHolder)
        binding.dialpadDivider.setBackgroundColor(textColor)
        listOf(binding.dialpadNumber, binding.dialpadName).forEach { it.setTextColor(textColor) }
        binding.dialpadNumberIcon.applyColorFilter(getProperPrimaryColor())
        restartCursorBlink()
        window.statusBarColor = getProperBackgroundColor()
    }

    override fun onStop() {
        super.onStop()
        // the call screen took over: after the call the app returns to the call history, not to an empty dialer
        if (callStarted && !isChangingConfigurations) {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (checkDialIntent()) {
            numberChanged()
        }
    }

    private fun checkDialIntent(): Boolean {
        return if (
            (intent.action == Intent.ACTION_DIAL || intent.action == Intent.ACTION_VIEW)
            && intent.data != null && intent.dataString?.contains("tel:") == true
        ) {
            number = Uri.decode(intent.dataString).substringAfter("tel:")
            true
        } else {
            false
        }
    }

    // ---- keys ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val char = dialChar(keyCode)
        if (char != null) {
            if (event.repeatCount == 0) {
                digitLongPressed = false
                event.startTracking()
                type(char)
                startTone(char)
            }
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL -> {
                if (event.repeatCount == 0) {
                    backLongPressed = false
                    event.startTracking()
                }
                return true
            }

            KeyEvent.KEYCODE_CALL -> {
                if (event.repeatCount == 0) {
                    callFocusedOrTyped()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // OK on a contact is its click (calls it), on the number it calls the number
                if (binding.dialpadNumber.isFocused) {
                    if (event.repeatCount == 0) {
                        callFocusedOrTyped()
                    }
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.dialpadNumber.isFocused) {
                    binding.dialpadList.focusItem(0)
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                val list = binding.dialpadList
                val focused = currentFocus
                val row = focused?.let { list.findContainingItemView(it) }
                if (row != null && list.getChildAdapterPosition(row) == 0) {
                    binding.dialpadNumber.requestFocus()
                    return true
                }
            }

            KeyEvent.KEYCODE_MENU -> return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL -> {
                backLongPressed = true
                setNumber("")
                return true
            }

            KeyEvent.KEYCODE_0 -> {
                // hold 0: + for international numbers, instead of the 0 typed on the press
                digitLongPressed = true
                stopTone()
                if (number.endsWith("0")) {
                    setNumber(number.dropLast(1) + "+")
                }
                return true
            }

            KeyEvent.KEYCODE_STAR -> {
                digitLongPressed = true
                stopTone()
                if (number.endsWith("*")) {
                    setNumber(number.dropLast(1) + ",")
                }
                return true
            }

            KeyEvent.KEYCODE_POUND -> {
                digitLongPressed = true
                stopTone()
                if (number.endsWith("#")) {
                    setNumber(number.dropLast(1) + ";")
                }
                return true
            }

            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                digitLongPressed = true
                stopTone()
                speedDial(keyCode - KeyEvent.KEYCODE_0)
                return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (dialChar(keyCode) != null) {
            stopTone()
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL -> {
                if (event.isTracking && !event.isCanceled && !backLongPressed) {
                    if (number.isEmpty()) {
                        finish()
                    } else {
                        setNumber(number.dropLast(1))
                    }
                }
                return true
            }

            // opened on key up: a popup opened on key down gets the key up and stops reacting to keys
            KeyEvent.KEYCODE_MENU -> {
                if (!event.isCanceled) {
                    showMenu()
                }
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun dialChar(keyCode: Int): Char? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyCode - KeyEvent.KEYCODE_0)
        KeyEvent.KEYCODE_STAR -> '*'
        KeyEvent.KEYCODE_POUND -> '#'
        KeyEvent.KEYCODE_PLUS -> '+'
        else -> null
    }

    private fun type(char: Char) {
        setNumber(number + char)
    }

    private fun setNumber(newNumber: String) {
        number = newNumber
        numberChanged()
    }

    private fun startTone(char: Char) {
        if (config.dialpadBeeps) {
            toneGeneratorHelper?.startTone(char)
        }
    }

    private fun stopTone() {
        if (config.dialpadBeeps) {
            toneGeneratorHelper?.stopTone()
        }
    }

    // ---- menu ----

    private fun showMenu() {
        dpadPopupMenu(binding.dialpadNumber).apply {
            menu.apply {
                if (number.isNotEmpty()) {
                    if (areMultipleSIMsAvailable()) {
                        add(0, R.id.dpad_call_with_sim, 0, R.string.select_sim)
                    }
                    add(0, R.id.add_number_to_contact, 1, R.string.add_number_to_contact)
                    add(0, R.id.dpad_send_sms, 2, org.fossify.commons.R.string.send_sms)
                    add(0, R.id.dpad_add_pause, 3, R.string.dpad_add_pause)
                    add(0, R.id.dpad_add_wait, 4, R.string.dpad_add_wait)
                }
                add(0, R.id.dpad_speed_dial, 5, R.string.speed_dial)
            }

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.dpad_call_with_sim -> {
                        callStarted = true
                        startCallWithConfirmationCheck(recipient = number, name = number, forceSimSelector = true)
                    }

                    R.id.add_number_to_contact -> startAddContactIntent(number)
                    R.id.dpad_send_sms -> launchSendSMSIntent(number)
                    R.id.dpad_add_pause -> type(',')
                    R.id.dpad_add_wait -> type(';')
                    R.id.dpad_speed_dial -> startActivity(Intent(this@DialpadActivity, ManageSpeedDialActivity::class.java))
                }
                true
            }
            show()
        }
    }

    // ---- matching contacts ----

    private fun gotContacts(newContacts: ArrayList<Contact>) {
        allContacts = newContacts

        val privateContacts = MyContactsContentProvider.getContacts(this, privateCursor)
        if (privateContacts.isNotEmpty()) {
            allContacts.addAll(privateContacts)
            allContacts.sort()
        }

        runOnUiThread { numberChanged() }
    }

    override fun onPause() {
        super.onPause()
        cursorHandler.removeCallbacksAndMessages(null)
    }

    /**
     * The number with a blinking cursor after it, so it's clear the keys type into it; while a contact below is picked
     * the cursor is hidden. The hidden cursor stays as an invisible character, so the number doesn't move.
     */
    private fun showNumber() {
        val contactPicked = currentFocus?.let { binding.dialpadList.findContainingItemView(it) } != null
        val cursorColor = if (!contactPicked && cursorShown) getProperPrimaryColor() else Color.TRANSPARENT
        binding.dialpadNumber.text = SpannableStringBuilder(number).append(CURSOR, ForegroundColorSpan(cursorColor), 0)
    }

    /** Typing keeps the cursor on, it starts blinking again after a pause. */
    private fun restartCursorBlink() {
        cursorHandler.removeCallbacksAndMessages(null)
        cursorShown = true
        showNumber()
        cursorHandler.postDelayed(blinkCursor, CURSOR_BLINK_MS)
    }

    private val blinkCursor = object : Runnable {
        override fun run() {
            cursorShown = !cursorShown
            showNumber()
            cursorHandler.postDelayed(this, CURSOR_BLINK_MS)
        }
    }

    private fun numberChanged() {
        restartCursorBlink()
        // typing goes on in the number even when a contact below was picked
        if (!binding.dialpadNumber.isFocused) {
            binding.dialpadNumber.requestFocus()
        }

        val text = number
        if (text.length > 8 && text.startsWith("*#*#") && text.endsWith("#*#*")) {
            val secretCode = text.substring(4, text.length - 4)
            if (isOreoPlus()) {
                if (isOwnDefaultDialer()) {
                    getSystemService(TelephonyManager::class.java)?.sendDialerSpecialCode(secretCode)
                } else {
                    launchSetDefaultDialerIntent()
                }
            } else {
                val intent = Intent(SECRET_CODE_ACTION, "android_secret_code://$secretCode".toUri())
                sendBroadcast(intent)
            }
            return
        }

        val digits = text.filter { it.isDigit() }
        val filtered = if (digits.isEmpty()) {
            arrayListOf()
        } else {
            allContacts.filter { contact ->
                var convertedName = KeypadHelper.convertKeypadLettersToDigits(contact.name.normalizeString()).filterNot { it.isWhitespace() }
                if (hasRussianLocale) {
                    convertedName = convertedName.lowercase(Locale.getDefault()).map { russianCharsMap.getOrElse(it) { it } }.joinToString("")
                }

                contact.doesContainPhoneNumber(text) || convertedName.contains(text, true)
            }.sortedWith(compareBy { !it.doesContainPhoneNumber(text) }).toMutableList() as ArrayList<Contact>
        }

        // the typed number belongs to a contact: its name under the number
        val exact = allContacts.firstOrNull { contact -> text.length >= 3 && contact.phoneNumbers.any { it.normalizedNumber.endsWith(text.filter { c -> c.isDigit() || c == '+' }) && it.normalizedNumber.length - digits.length <= 4 } }
        binding.dialpadName.text = exact?.getNameToDisplay().orEmpty()
        binding.dialpadName.beVisibleIf(exact != null)

        val adapter = binding.dialpadList.adapter as? ContactsAdapter
        if (adapter == null) {
            ContactsAdapter(
                activity = this,
                contacts = filtered,
                recyclerView = binding.dialpadList,
                highlightText = text,
                allowLongClick = false,
                itemClick = {
                    callStarted = true
                    startCallWithConfirmationCheck(it as Contact)
                    clearNumberWithDelay()
                }
            ).apply {
                binding.dialpadList.adapter = this
            }
        } else {
            adapter.updateItems(filtered, text)
        }

        binding.dialpadList.beVisibleIf(filtered.isNotEmpty())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER && isOwnDefaultDialer()) {
            numberChanged()
        }
    }

    // ---- calling ----

    private fun callFocusedOrTyped() {
        val list = binding.dialpadList
        val row = currentFocus?.let { list.findContainingItemView(it) }
        if (row != null) {
            row.performClick()
        } else {
            initCall()
        }
    }

    private fun initCall(number: String = this.number, name: String? = null) {
        if (number.isNotEmpty()) {
            callStarted = true
            startCallWithConfirmationCheck(number, name ?: number)
            clearNumberWithDelay()
        } else {
            // empty: Call puts the last dialed number in, like on most phones
            RecentsHelper(this).getRecentCalls(queryLimit = 1) {
                val mostRecentNumber = it.firstOrNull()?.phoneNumber
                if (!mostRecentNumber.isNullOrEmpty()) {
                    runOnUiThread { setNumber(mostRecentNumber) }
                }
            }
        }
    }

    private fun clearNumberWithDelay() {
        lifecycleScope.launch {
            delay(1000)
            setNumber("")
        }
    }

    /** Hold 1–9 with only that digit typed: call its speed dial number. */
    private fun speedDial(id: Int): Boolean {
        if (number.length == 1) {
            val speedDial = speedDialValues.firstOrNull { it.id == id }
            if (speedDial?.isValid() == true) {
                setNumber("")
                initCall(speedDial.number, speedDial.getName(this))
                return true
            }
        }
        return false
    }
}
