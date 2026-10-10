package org.fossify.phone.activities

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.postDelayed
import org.fossify.phone.dialogs.DpadRadioGroupDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.RadioItem
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityCallBinding
import org.fossify.phone.extensions.*
import org.fossify.phone.helpers.*
import org.fossify.phone.models.AudioRoute
import org.fossify.phone.models.CallContact
import org.fossify.phone.receivers.ScreenOffAdminReceiver

/**
 * The call screen for keypad phones. Ringing: Call / OK / ← answer, End / → decline, Back silences the ringing and
 * a second Back hides the screen (the call keeps ringing silently). During a call: rows for the speaker, mute, hold
 * and adding a call (↑ / ↓ and OK), the digits send tones, the red key hangs up.
 */
class CallActivity : SimpleActivity() {
    companion object {
        private const val SCREEN_BRIGHT = 0
        private const val SCREEN_DIMMED = 1
        private const val DIM_AFTER_MS = 10_000L
        private const val DIMMED_BRIGHTNESS = 0.05f
        private const val SHADE_CHECK_DELAY_MS = 150L
        private const val SCREEN_OFF_AFTER_MS = 3_000L
        private const val WAKE_KEY_GRACE_MS = 700L

        fun getStartIntent(context: Context): Intent {
            val openAppIntent = Intent(context, CallActivity::class.java)
            openAppIntent.flags = Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            return openAppIntent
        }
    }

    private val binding by viewBinding(ActivityCallBinding::inflate)

    private var isSpeakerOn = false
    private var isMicrophoneOff = false
    private var isCallEnded = false
    private var isRingingSilenced = false
    private var callContact: CallContact? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var screenOnWakeLock: PowerManager.WakeLock? = null
    private var callDuration = 0
    private var dtmfTyped = ""
    private val callDurationHandler = Handler(Looper.getMainLooper())

    private val rows get() = with(binding) { listOf(callToggleSpeaker, callToggleMicrophone, callToggleHold, callAdd, callSwap, callMerge, callManage, callEnd) }
    private val rowIcons get() = with(binding) {
        mapOf(
            callToggleSpeaker to callToggleSpeakerIcon, callToggleMicrophone to callToggleMicrophoneIcon, callToggleHold to callToggleHoldIcon,
            callAdd to callAddIcon, callSwap to callSwapIcon, callMerge to callMergeIcon, callManage to callManageIcon, callEnd to callEndIcon
        )
    }

    // round buttons that are switched on (speaker, mute, hold) are filled with the primary color
    private val activeRows = mutableSetOf<View>()
    private var holdLabel = R.string.hold_call
    private var speakerState = ""

    // a long call dims the screen (Android turns it off after the phone's screen timeout), Back or green lights it up
    private val dimHandler = Handler(Looper.getMainLooper())
    private val shadeHandler = Handler(Looper.getMainLooper())
    private val screenOffHandler = Handler(Looper.getMainLooper())
    private var isScreenResumed = false
    private var screenOffScheduled = false
    private var resumedAt = 0L
    private var statusBarWatched = false
    private var screenDimLevel = SCREEN_BRIGHT
    private val isRinging get() = CallManager.getState() == Call.STATE_RINGING && !isCallEnded

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        if (CallManager.getPhoneState() == NoCall) {
            finish()
            return
        }

        setupEdgeToEdge(
            padTopSystem = listOf(binding.callHolder),
            padBottomSystem = listOf(binding.callHolder),
        )

        initButtons()
        updateColors()
        hideStatusBar()
        audioManager.mode = AudioManager.MODE_IN_CALL
        addLockScreenFlags()
        CallManager.addListener(callCallback)
        updateCallContactInfo(CallManager.getPrimaryCall())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        updateState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideStatusBar()
        } else if (isScreenResumed) {
            // the window lost the focus while still on screen: the ear may have pulled down the notification shade
            shadeHandler.postDelayed({ closeShadeIfOpen() }, SHADE_CHECK_DELAY_MS)
        }
    }

    /**
     * Without a proximity sensor the ear touches the screen during a call and can pull down the shade (and tap the
     * quick settings). The shade is closed again right away, unless the focus is in one of this app's own dialogs.
     * StatusBarManager.collapsePanels() isn't public API, but needs only the normal EXPAND_STATUS_BAR permission.
     */
    @SuppressLint("WrongConstant")
    private fun closeShadeIfOpen() {
        if (!isScreenResumed || hasWindowFocus() || appWindowHasFocus() || isCallEnded) {
            return
        }

        try {
            val statusBar = getSystemService("statusbar")
            statusBar?.javaClass?.getMethod("collapsePanels")?.invoke(statusBar)
        } catch (ignored: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        resumedAt = SystemClock.uptimeMillis()
        isScreenResumed = true
        updateState()
    }

    override fun onPause() {
        super.onPause()
        isScreenResumed = false
        shadeHandler.removeCallbacksAndMessages(null)
        // back to normal for any other screen
        dimHandler.removeCallbacksAndMessages(null)
        setScreenDimLevel(SCREEN_BRIGHT)
    }

    override fun onDestroy() {
        super.onDestroy()
        dimHandler.removeCallbacksAndMessages(null)
        screenOffHandler.removeCallbacksAndMessages(null)
        CallManager.removeListener(callCallback)
        disableProximitySensor()

        if (screenOnWakeLock?.isHeld == true) {
            screenOnWakeLock!!.release()
        }
    }

    override fun onBackPressedCompat(): Boolean {
        val callState = CallManager.getState()
        if (callState == Call.STATE_CONNECTING || callState == Call.STATE_DIALING) {
            toast(R.string.call_is_being_connected)
        }

        // leaving the call screen keeps the call going, the notification brings it back
        return false
    }

    private fun initButtons() = binding.apply {
        rowIcons.values.forEach { it.applyDpadRoundFocusHighlight() }
        rows.forEach { row ->
            row.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    updateActionLabel()
                }
            }
        }

        callAccept.setOnClickListener { acceptCall() }
        callDecline.setOnClickListener { endCall() }
        callToggleMicrophone.setOnClickListener { toggleMicrophone() }
        callToggleSpeaker.setOnClickListener { changeCallAudioRoute() }
        callToggleHold.setOnClickListener { toggleHold() }

        callAdd.setOnClickListener {
            Intent(applicationContext, DialpadActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                startActivity(this)
            }
        }

        callSwap.setOnClickListener { CallManager.swap() }
        callMerge.setOnClickListener { CallManager.merge() }
        callManage.setOnClickListener {
            startActivity(Intent(this@CallActivity, ConferenceActivity::class.java))
        }
        callEnd.setOnClickListener { endCall() }
    }

    private fun updateColors() = binding.apply {
        val textColor = getProperTextColor()
        callHolder.setBackgroundColor(getProperBackgroundColor())
        window.statusBarColor = getProperBackgroundColor()
        callHolder.allTextViews().forEach { it.setTextColor(textColor) }
        listOf(callSimImage, callLeftArrow, callRightArrow).forEach { it.applyColorFilter(textColor) }
        updateRowColors()
        callDivider.setBackgroundColor(textColor)
        callSimId.setTextColor(textColor.getContrastColor())

        val green = ContextCompat.getColor(this@CallActivity, R.color.dpad_save_green)
        val red = ContextCompat.getColor(this@CallActivity, R.color.dpad_cancel_red)
        callAcceptIcon.applyColorFilter(green)
        callDeclineIcon.applyColorFilter(red)
    }

    /** Round buttons: switched on = primary color, hang up = red, the rest a faint circle. */
    private fun updateRowColors() {
        val textColor = getProperTextColor()
        val primary = getProperPrimaryColor()
        val red = ContextCompat.getColor(this, R.color.dpad_cancel_red)
        rowIcons.forEach { (row, icon) ->
            val fill = when {
                row == binding.callEnd -> red
                row in activeRows -> primary
                else -> textColor.adjustAlpha(0.12f)
            }
            icon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
            }
            icon.applyColorFilter(if (row == binding.callEnd || row in activeRows) fill.getContrastColor() else textColor)
        }
    }

    private fun setRowActive(row: View, active: Boolean) {
        if (active) activeRows.add(row) else activeRows.remove(row)
        updateRowColors()
        updateActionLabel()
    }

    /** The name of the selected button, with its state for the switches. */
    private fun updateActionLabel() = binding.apply {
        val focused = currentFocus?.takeIf { it in rows } ?: return@apply
        val onOff = { row: View -> getString(if (row in activeRows) R.string.dpad_on else R.string.dpad_off) }
        callActionLabel.text = when (focused) {
            callToggleSpeaker -> "${callToggleSpeaker.contentDescription}: $speakerState"
            callToggleMicrophone -> "${getString(R.string.dpad_mute)}: ${onOff(callToggleMicrophone)}"
            callToggleHold -> getString(holdLabel)
            else -> focused.contentDescription
        }
    }

    /**
     * No status bar during a call: the ear on a touchscreen could pull down the notifications and tap something.
     * A swipe only shows it for a moment.
     */
    private fun hideStatusBar() {
        if (!statusBarWatched) {
            statusBarWatched = true
            // a swipe from the top shows the bar for a moment: hide it again right away, before a second swipe can
            // pull down the shade from it
            ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
                if (insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
                    view.postDelayed({ hideStatusBar() }, 300)
                }
                ViewCompat.onApplyWindowInsets(view, insets)
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** The display really off (device admin, see ScreenOffAdminReceiver), only during a call. */
    private fun turnScreenOff() {
        if (isScreenResumed && !isCallEnded && !isRinging && binding.ongoingCallHolder.isVisible()) {
            ScreenOffAdminReceiver.turnScreenOff(this)
        }
    }

    // ---- dimming during a long call ----

    /** Restarts the countdown to dim the screen; only during a call, not while it rings. */
    private fun restartDimTimer() {
        dimHandler.removeCallbacksAndMessages(null)
        if (isRinging || isCallEnded || !binding.ongoingCallHolder.isVisible()) {
            setScreenDimLevel(SCREEN_BRIGHT)
            return
        }

        dimHandler.postDelayed({ setScreenDimLevel(SCREEN_DIMMED) }, DIM_AFTER_MS)
        // turning the display off is left to Android's own screen timeout from the phone's settings
    }

    /** Dimmed = the lowest backlight; the display itself turns off after the phone's screen timeout. */
    private fun setScreenDimLevel(level: Int) {
        if (screenDimLevel == level) {
            return
        }

        screenDimLevel = level
        window.attributes = window.attributes.apply {
            screenBrightness = when (level) {
                SCREEN_DIMMED -> DIMMED_BRIGHTNESS
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    private fun View.allTextViews(): List<TextView> = when (this) {
        is TextView -> listOf(this)
        is android.view.ViewGroup -> (0 until childCount).flatMap { getChildAt(it).allTextViews() }
        else -> emptyList()
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isRinging) {
            return handleRingingKey(event)
        }

        // the green key turns the display off during a call; the key that woke the display up (it may come in right
        // after) doesn't turn it off again
        if (event.keyCode == KeyEvent.KEYCODE_CALL && screenDimLevel == SCREEN_BRIGHT && binding.ongoingCallHolder.isVisible() && !isCallEnded) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && SystemClock.uptimeMillis() - resumedAt > WAKE_KEY_GRACE_MS) {
                turnScreenOff()
            }
            return true
        }

        // dimmed: Back and the green key only light the screen up, the other keys also do their job
        if (screenDimLevel != SCREEN_BRIGHT && (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_CALL)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                setScreenDimLevel(SCREEN_BRIGHT)
                restartDimTimer()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            setScreenDimLevel(SCREEN_BRIGHT)
            restartDimTimer()
        }

        val char = when (event.keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (event.keyCode - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_STAR -> '*'
            KeyEvent.KEYCODE_POUND -> '#'
            else -> null
        }

        if (char != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !isCallEnded) {
                dialpadPressed(char)
            }
            return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_ENDCALL -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    endCall()
                }
                return true
            }

            // nothing to call during a call
            KeyEvent.KEYCODE_CALL -> return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun handleRingingKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down) {
                    acceptCall()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENDCALL -> {
                if (down) {
                    endCall()
                }
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    if (!isRingingSilenced) {
                        silenceRinging()
                    } else {
                        // hidden, the call keeps ringing silently; the notification or the phone app brings it back
                        finish()
                    }
                }
                return true
            }

            // the volume keys keep working (Android silences the ringing with them too)
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
        }

        // no accidental actions while it rings
        return true
    }

    /** Only the ringing (and vibration) stops, the call still waits to be answered. */
    @SuppressLint("MissingPermission")
    private fun silenceRinging() {
        isRingingSilenced = true
        try {
            (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).silenceRinger()
        } catch (ignored: Exception) {
        }
        binding.callSilencedLabel.beVisible()
    }

    private fun dialpadPressed(char: Char) {
        CallManager.keypad(char)
        dtmfTyped += char
        binding.callDtmfLabel.text = dtmfTyped
        binding.callDtmfLabel.beVisible()
    }

    // ---- audio ----

    private fun changeCallAudioRoute() {
        val supportAudioRoutes = CallManager.getSupportedAudioRoutes()
        if (supportAudioRoutes.contains(AudioRoute.BLUETOOTH)) {
            showAudioRouteChooser(supportAudioRoutes)
        } else {
            val isSpeakerOn = !isSpeakerOn
            val newRoute = if (isSpeakerOn) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
            CallManager.setAudioRoute(newRoute)
        }
    }

    /** With a Bluetooth headset there are more than two routes: a list instead of on / off. */
    private fun showAudioRouteChooser(routes: Array<AudioRoute>) {
        val current = CallManager.getCallAudioRoute()
        val items = routes.sortedByDescending { it.route }.map { RadioItem(it.route, getString(it.stringRes)) }
        DpadRadioGroupDialog(this, ArrayList(items), current?.route ?: -1, R.string.choose_audio_route) {
            CallManager.setAudioRoute(it as Int)
        }
    }

    private fun updateCallAudioState(route: AudioRoute?) {
        if (route != null) {
            isMicrophoneOff = audioManager.isMicrophoneMute
            updateMicrophoneRow()

            isSpeakerOn = route == AudioRoute.SPEAKER
            val bluetoothConnected = CallManager.getSupportedAudioRoutes().contains(AudioRoute.BLUETOOTH)
            binding.apply {
                callToggleSpeaker.contentDescription = getString(if (bluetoothConnected) R.string.choose_audio_route else R.string.dpad_speaker)
                speakerState = when {
                    bluetoothConnected -> getString(route.stringRes)
                    isSpeakerOn -> getString(R.string.dpad_on)
                    else -> getString(R.string.dpad_off)
                }
                callToggleSpeakerIcon.setImageResource(if (route == AudioRoute.WIRED_HEADSET) R.drawable.ic_volume_down_vector else route.iconRes)
                setRowActive(callToggleSpeaker, isSpeakerOn || route == AudioRoute.BLUETOOTH)
            }

            if (isSpeakerOn) {
                disableProximitySensor()
            } else {
                enableProximitySensor()
            }
        }
    }

    private fun toggleMicrophone() {
        isMicrophoneOff = !isMicrophoneOff
        audioManager.isMicrophoneMute = isMicrophoneOff
        CallManager.inCallService?.setMuted(isMicrophoneOff)
        updateMicrophoneRow()
    }

    private fun updateMicrophoneRow() {
        setRowActive(binding.callToggleMicrophone, isMicrophoneOff)
    }

    private fun toggleHold() {
        val isOnHold = CallManager.toggleHold()
        updateHoldRow(isOnHold)
    }

    private fun updateHoldRow(isOnHold: Boolean) = binding.apply {
        holdLabel = if (isOnHold) R.string.resume_call else R.string.hold_call
        callToggleHold.contentDescription = getString(holdLabel)
        callToggleHoldIcon.setImageResource(if (isOnHold) R.drawable.ic_phone_vector else R.drawable.ic_pause_vector)
        setRowActive(callToggleHold, isOnHold)
        holdStatusLabel.beVisibleIf(isOnHold)
    }

    // ---- caller ----

    private fun updateOtherPersonsInfo() {
        val contact = callContact ?: return
        binding.apply {
            val (name, _, number, numberLabel) = contact
            callerNameLabel.text = name.ifEmpty { getString(R.string.unknown_caller) }
            if (number.isNotEmpty() && number != name) {
                callerNumber.text = if (numberLabel.isNotEmpty()) "$number - $numberLabel" else number
                callerNumber.beVisible()
            } else {
                callerNumber.beGone()
            }
        }
    }

    /**
     * The contact's photo in a circle; without one the colored letter circle of the contact lists, for an unknown
     * number or a conference a person icon.
     */
    private fun updateCallerAvatar(contact: CallContact, isConference: Boolean) {
        if (isFinishing || isDestroyed) {
            return
        }

        val avatar = binding.callerAvatar
        val isKnown = contact.name.isNotEmpty() && contact.name != contact.number
        avatar.background = null
        avatar.setPadding(0, 0, 0, 0)
        avatar.clearColorFilter()
        when {
            !isConference && contact.photoUri.isNotEmpty() -> {
                Glide.with(this)
                    .load(contact.photoUri)
                    .apply(RequestOptions.circleCropTransform())
                    .into(avatar)
            }

            !isConference && isKnown -> avatar.setImageBitmap(SimpleContactsHelper(this).getContactLetterIcon(contact.name))
            else -> {
                val color = getProperPrimaryColor()
                avatar.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                avatar.setImageResource(if (isConference) R.drawable.ic_people_vector else R.drawable.ic_person_vector)
                avatar.applyColorFilter(color.getContrastColor())
                val padding = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.big_margin)
                avatar.setPadding(padding, padding, padding, padding)
            }
        }
    }

    private fun getContactNameOrNumber(contact: CallContact): String {
        return contact.name.ifEmpty {
            contact.number.ifEmpty {
                getString(R.string.unknown_caller)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkCalledSIMCard() {
        try {
            val simLabels = getAvailableSIMCardLabels()
            if (simLabels.size > 1) {
                simLabels.forEach { sim ->
                    if (sim.handle == CallManager.getPrimaryCall()?.details?.accountHandle) {
                        binding.apply {
                            callSimId.text = sim.id.toString()
                            callSimId.beVisible()
                            callSimImage.beVisible()
                            val simColor = sim.color.adjustForContrast(getProperBackgroundColor())
                            callSimId.setTextColor(simColor.getContrastColor())
                            callSimImage.applyColorFilter(simColor)
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    // ---- state ----

    private fun updateCallState(call: Call) {
        val state = call.getStateCompat()
        when (state) {
            Call.STATE_RINGING -> callRinging()
            Call.STATE_ACTIVE -> callStarted()
            Call.STATE_DISCONNECTED -> endCall()
            Call.STATE_CONNECTING, Call.STATE_DIALING -> initOutgoingCallUI()
            Call.STATE_SELECT_PHONE_ACCOUNT -> showPhoneAccountPicker()
        }

        val statusTextId = when (state) {
            Call.STATE_RINGING -> R.string.is_calling
            Call.STATE_CONNECTING, Call.STATE_DIALING -> R.string.dialing
            else -> 0
        }

        binding.apply {
            if (statusTextId != 0) {
                callStatusLabel.text = getString(statusTextId)
            }

            callManage.beVisibleIf(!isCallEnded && call.hasCapability(Call.Details.CAPABILITY_MANAGE_CONFERENCE))
            setRowEnabled(callSwap, enabled = !isCallEnded && state == Call.STATE_ACTIVE)
            setRowEnabled(callMerge, enabled = !isCallEnded && state == Call.STATE_ACTIVE)
        }
    }

    private fun updateState() {
        val phoneState = CallManager.getPhoneState()
        if (phoneState is SingleCall) {
            updateCallState(phoneState.call)
            updateCallOnHoldState(null)
            val state = phoneState.call.getStateCompat()
            val isSingleCallActionsEnabled = !isCallEnded && (state == Call.STATE_ACTIVE || state == Call.STATE_DISCONNECTED
                || state == Call.STATE_DISCONNECTING || state == Call.STATE_HOLDING)
            setRowEnabled(binding.callToggleHold, isSingleCallActionsEnabled)
            setRowEnabled(binding.callAdd, isSingleCallActionsEnabled)
            updateHoldRow(state == Call.STATE_HOLDING)
        } else if (phoneState is TwoCalls) {
            updateCallState(phoneState.active)
            updateCallOnHoldState(phoneState.onHold)
        }

        updateCallAudioState(CallManager.getCallAudioRoute())
        focusFirstRowIfNeeded()
        if (screenDimLevel == SCREEN_BRIGHT) {
            restartDimTimer()
        }
    }

    private fun updateCallOnHoldState(call: Call?) {
        val hasCallOnHold = call != null
        if (hasCallOnHold) {
            getCallContact(applicationContext, call) { contact ->
                runOnUiThread {
                    binding.onHoldStatusHolder.text = "${getString(R.string.call_on_hold)}: ${getContactNameOrNumber(contact)}"
                }
            }
        }
        binding.apply {
            onHoldStatusHolder.beVisibleIf(hasCallOnHold)
            // one call: hold and add; two calls: swap and merge
            callToggleHold.beVisibleIf(!hasCallOnHold)
            callAdd.beVisibleIf(!hasCallOnHold)
            callSwap.beVisibleIf(hasCallOnHold)
            callMerge.beVisibleIf(hasCallOnHold)
        }
    }

    private fun updateCallContactInfo(call: Call?) {
        getCallContact(applicationContext, call) { contact ->
            if (call != CallManager.getPrimaryCall()) {
                return@getCallContact
            }
            callContact = contact
            val isConference = call.isConference()
            runOnUiThread {
                updateOtherPersonsInfo()
                updateCallerAvatar(contact, isConference)
                checkCalledSIMCard()
            }
        }
    }

    private fun acceptCall() {
        CallManager.accept()
    }

    private fun initOutgoingCallUI() {
        enableProximitySensor()
        showOngoing()
    }

    private fun callRinging() = binding.apply {
        incomingCallHolder.beVisible()
        callIncomingLabel.beVisible()
        ongoingCallHolder.beGone()
        callSilencedLabel.beVisibleIf(isRingingSilenced)
    }

    private fun callStarted() {
        enableProximitySensor()
        showOngoing()
        // once per call: 3 s after it starts the display goes off (no proximity sensor), the green key turns it on
        if (!screenOffScheduled) {
            screenOffScheduled = true
            screenOffHandler.postDelayed({ turnScreenOff() }, SCREEN_OFF_AFTER_MS)
        }
        callDurationHandler.removeCallbacks(updateCallDurationTask)
        callDurationHandler.post(updateCallDurationTask)
    }

    private fun showOngoing() = binding.apply {
        incomingCallHolder.beGone()
        callIncomingLabel.beGone()
        ongoingCallHolder.beVisible()
    }

    /** A row keeps the focus while the call goes on, the first usable one gets it when the rows show up. */
    private fun focusFirstRowIfNeeded() {
        if (!binding.ongoingCallHolder.isVisible()) {
            return
        }

        binding.callRows.post {
            val focused = currentFocus
            if (focused == null || focused !in rows || !focused.isShown || !focused.isFocusable) {
                rows.firstOrNull { it.isShown && it.isFocusable }?.requestFocusFromTouch()
            }
            updateActionLabel()
        }
    }

    private fun showPhoneAccountPicker() {
        if (callContact != null) {
            getHandleToUse(intent, callContact!!.number) { handle ->
                CallManager.getPrimaryCall()?.phoneAccountSelected(handle, false)
            }
        }
    }

    private fun endCall() {
        CallManager.reject()
        disableProximitySensor()

        if (isCallEnded) {
            safeFinishAndRemoveTask()
            return
        }

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (ignored: Exception) {
        }

        isCallEnded = true
        dimHandler.removeCallbacksAndMessages(null)
        runOnUiThread {
            setScreenDimLevel(SCREEN_BRIGHT)
            disableAllRows()
            binding.incomingCallHolder.beGone()
            binding.callIncomingLabel.beGone()
            if (callDuration > 0) {
                @SuppressLint("SetTextI18n")
                binding.callStatusLabel.text = "${callDuration.getFormattedDuration()} (${getString(R.string.call_ended)})"
                Handler(mainLooper).postDelayed(3000) {
                    safeFinishAndRemoveTask()
                }
            } else {
                binding.callStatusLabel.text = getString(R.string.call_ended)
                finish()
            }
        }
    }

    private fun safeFinishAndRemoveTask() {
        try {
            if (intent != null) {
                finishAndRemoveTask()
            } else {
                finish()
            }
        } catch (_: Exception) {
            finish()
        }
    }

    private val callCallback = object : CallManagerListener {
        override fun onStateChanged() {
            updateState()
        }

        override fun onAudioStateChanged(audioState: AudioRoute) {
            updateCallAudioState(audioState)
        }

        override fun onPrimaryCallChanged(call: Call) {
            callDurationHandler.removeCallbacks(updateCallDurationTask)
            updateCallContactInfo(call)
            updateState()
        }
    }

    private val updateCallDurationTask = object : Runnable {
        override fun run() {
            callDuration = CallManager.getPrimaryCall().getCallDuration()
            if (!isCallEnded) {
                binding.callStatusLabel.text = callDuration.getFormattedDuration()
                callDurationHandler.postDelayed(this, 1000)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun addLockScreenFlags() {
        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        if (isOreoPlus()) {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            screenOnWakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "org.fossify.phone:full_wake_lock")
            screenOnWakeLock!!.acquire(5 * 1000L)
        } catch (e: Exception) {
        }
    }

    private fun enableProximitySensor() {
        if (!config.disableProximitySensor && (proximityWakeLock == null || proximityWakeLock?.isHeld == false)) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            proximityWakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "org.fossify.phone:wake_lock")
            proximityWakeLock!!.acquire(60 * MINUTE_SECONDS * 1000L)
        }
    }

    private fun disableProximitySensor() {
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock!!.release()
        }
    }

    private fun disableAllRows() {
        rows.forEach { setRowEnabled(it, enabled = false) }
    }

    /** A disabled row is dimmed and skipped by the arrows. */
    private fun setRowEnabled(row: View, enabled: Boolean) {
        row.isEnabled = enabled
        row.isFocusable = enabled
        row.alpha = if (enabled) 1.0f else LOWER_ALPHA
        if (!enabled && row.isFocused) {
            rows.firstOrNull { it != row && it.isShown && it.isFocusable }?.requestFocus()
        }
    }
}
