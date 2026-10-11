package org.fossify.phone.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.provider.Settings
import android.telecom.Call
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnNextLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import me.grantland.widget.AutofitHelper
import org.fossify.commons.dialogs.ChangeViewTypeDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.phone.dialogs.DpadRadioGroupDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.BuildConfig
import org.fossify.phone.R
import org.fossify.phone.adapters.ContactsAdapter
import org.fossify.phone.adapters.RecentCallsAdapter
import org.fossify.phone.adapters.ViewPagerAdapter
import org.fossify.phone.databinding.ActivityMainBinding
import org.fossify.phone.dialogs.ChangeSortingDialog
import org.fossify.phone.dialogs.DPAD_OTHER_LETTER
import org.fossify.phone.dialogs.DpadLetterDialog
import org.fossify.phone.dialogs.FilterContactSourcesDialog
import org.fossify.phone.extensions.clearMissedCalls
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.isOwnDefaultDialer
import org.fossify.phone.extensions.handleFullScreenNotificationsPermission
import org.fossify.phone.extensions.launchCreateNewContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.fragments.ContactsFragment
import org.fossify.phone.fragments.FavoritesFragment
import org.fossify.phone.fragments.MyViewPagerFragment
import org.fossify.phone.fragments.RecentsFragment
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.applyDpadFocusHighlight
import org.fossify.phone.helpers.dpadPopupMenu
import org.fossify.phone.helpers.focusItem
import org.fossify.phone.helpers.focusedAdapterPosition
import org.fossify.phone.helpers.visibleTabs
import org.fossify.phone.models.Events
import org.fossify.phone.models.RecentCall
import org.fossify.phone.receivers.ScreenOffAdminReceiver
import org.fossify.phone.extensions.requestScreenOffAdmin
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Locale

/**
 * Tabs Call history / Contacts / Favorites for keypad phones, laid out and controlled like DumberKit Contacts:
 * a top bar with "Ab" (jump to letter), search and menu, ←/→ switch the tabs, a digit opens dialing with it.
 */
class MainActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityMainBinding::inflate)

    private var storedShowTabs = 0
    private var storedFontSize = 0
    private var storedStartNameWithSurname = false
    private var focusListOnStart = true
    private var isFirstResume = true
    private var allowLeavingList = false
    var cachedContacts = ArrayList<Contact>()

    private val isSearchOpen get() = binding.mainSearchBar.visibility == View.VISIBLE
    private val toolbarButtons get() = listOf(binding.mainLetters, binding.mainSearch, binding.mainDialpad, binding.mainMore)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupToolbar()
        keepFocusInList()
        setupEdgeToEdge(
            padTopSystem = listOf(binding.mainTopBar),
            padBottomImeAndSystem = listOf(binding.mainTabsHolder),
        )

        EventBus.getDefault().register(this)

        if (isOwnDefaultDialer()) {
            checkPermissions()
        } else {
            launchSetDefaultDialerIntent()
        }

        if (isQPlus() && (config.blockUnknownNumbers || config.blockHiddenNumbers)) {
            setDefaultCallerIdApp()
        }

        setupTabs()
        Contact.sorting = config.sorting

        // until the first call is there the pager holds the focus (no frame); the top bar is out of reach meanwhile,
        // Android would otherwise give the focus to "Ab" while the list is still empty
        toolbarButtons.forEach { it.isFocusable = false }
        binding.viewPager.isFocusable = true
        binding.viewPager.defaultFocusHighlightEnabled = false
        binding.viewPager.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        binding.viewPager.requestFocus()
        focusListAtStart()
    }

    override fun onResume() {
        super.onResume()
        // a ringing call hidden with Back comes back when the phone app is opened
        if (CallManager.getState() == Call.STATE_RINGING) {
            startActivity(CallActivity.getStartIntent(this))
        }

        if (storedShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            finish()
            startActivity(intent)
            return
        }

        updateToolbarColors()
        updateTextColors(binding.mainHolder)
        binding.mainTabsHolder.background = ColorDrawable(getProperBackgroundColor())
        setupTabColors()

        getAllFragments().forEach {
            it?.setupColors(getProperTextColor(), getProperPrimaryColor(), getProperPrimaryColor())
        }

        val configStartNameWithSurname = config.startNameWithSurname
        if (storedStartNameWithSurname != configStartNameWithSurname) {
            getContactsFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            getFavoritesFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            storedStartNameWithSurname = config.startNameWithSurname
        }

        if (!isSearchOpen) {
            refreshItems()
        }

        val configFontSize = config.fontSize
        if (storedFontSize != configFontSize) {
            getAllFragments().forEach {
                it?.fontSizeChanged()
            }
        }

        checkShortcuts()
        // a call that just ended may show up in the call log a moment later; not needed on the first start
        if (!isFirstResume) {
            Handler(Looper.getMainLooper()).postDelayed({
                getRecentsFragment()?.refreshItems()
            }, 2000)
        }
        isFirstResume = false
    }

    override fun onPause() {
        super.onPause()
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
        storedFontSize = config.fontSize
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        // the app can work without being the default Dialer too
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER) {
            checkPermissions()
        } else if (requestCode == REQUEST_CODE_SET_DEFAULT_CALLER_ID && resultCode != Activity.RESULT_OK) {
            toast(R.string.must_make_default_caller_id_app, length = Toast.LENGTH_LONG)
            baseConfig.blockUnknownNumbers = false
            baseConfig.blockHiddenNumbers = false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshItems()
    }

    override fun onBackPressedCompat(): Boolean {
        if (isSearchOpen) {
            closeSearch()
            return true
        }

        // anywhere else than on the first row, Back first jumps there, only then it leaves the app
        val fragment = getCurrentFragment()
        val list = fragment?.getListView()
        if (fragment != null && list != null && list.isShown && (list.adapter?.itemCount ?: 0) > 0 &&
            list.focusedAdapterPosition() != fragment.firstItemPosition()
        ) {
            focusRowAt(list, fragment.firstItemPosition())
            return true
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    /** Contacts, then the call history, both shown right away instead of behind a "Request access" link. */
    private fun checkPermissions() {
        handlePermission(PERMISSION_READ_CONTACTS) {
            handlePermission(PERMISSION_READ_CALL_LOG) {
                // the lists only fill now on a fresh install: focus the first call once they're there
                focusListOnStart = true
                refreshFragments()
                askForOverlayAndNotifications()
            }
        }
    }

    /** Notifications first, then (once) the overlay permission: Commons' snackbar can't be reached with the keys. */
    private fun askForOverlayAndNotifications() {
        handleFullScreenNotificationsPermission { granted ->
            if (!granted) {
                toast(org.fossify.commons.R.string.notifications_disabled)
            }

            if (config.wasOverlaySnackbarConfirmed || Settings.canDrawOverlays(this)) {
                askForScreenOff()
            }

            if (!config.wasOverlaySnackbarConfirmed && !Settings.canDrawOverlays(this)) {
                config.wasOverlaySnackbarConfirmed = true
                ConfirmationDialog(
                    this,
                    getString(R.string.allow_displaying_over_other_apps),
                    positive = org.fossify.commons.R.string.ok,
                    negative = org.fossify.commons.R.string.cancel
                ) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
    }

    /** Once: the call screen may turn the display off (no proximity sensor on keypad phones), see ScreenOffAdminReceiver. */
    private fun askForScreenOff() {
        if (config.dpadScreenOffAsked || ScreenOffAdminReceiver.isActive(this)) {
            return
        }

        config.dpadScreenOffAsked = true
        ConfirmationDialog(
            this,
            getString(R.string.dpad_screen_off_question),
            positive = org.fossify.commons.R.string.ok,
            negative = org.fossify.commons.R.string.cancel
        ) {
            requestScreenOffAdmin()
        }
    }

    // ---- top bar ----

    private fun setupToolbar() {
        toolbarButtons.forEach { it.applyDpadFocusHighlight() }
        binding.mainSearchClose.applyDpadFocusHighlight()

        binding.mainSearch.setOnClickListener { openSearch() }
        binding.mainLetters.setOnClickListener { showLetterDialog() }
        binding.mainDialpad.setOnClickListener { launchDialpad() }
        binding.mainMore.setOnClickListener { showMoreMenu() }
        binding.mainSearchClose.setOnClickListener { closeSearch() }

        // Traditional T9 and similar keyboards: "visible password" disables predictive mode, so names are typed
        // letter by letter (ABC), and the locale hint switches to English just for this field
        binding.mainSearchInput.imeHintLocales = LocaleList(Locale.ENGLISH)

        binding.mainSearchInput.doAfterTextChanged { text ->
            if (isSearchOpen && !searchFromHistory) {
                getCurrentFragment()?.onSearchQueryChanged(text?.toString() ?: "")
            }
        }
    }

    private fun updateToolbarColors() {
        val textColor = getProperTextColor()
        val barColor = getProperBackgroundColor()
        binding.mainTopBar.setBackgroundColor(barColor)
        binding.mainTitle.setTextColor(textColor)
        binding.mainSearchInput.setTextColor(textColor)
        binding.mainSearchInput.setHintTextColor(textColor.adjustAlpha(0.5f))
        (toolbarButtons + binding.mainSearchClose).forEach { it.applyColorFilter(textColor) }
        window.statusBarColor = barColor
    }

    private fun showMoreMenu() {
        dpadPopupMenu(binding.mainMore).apply {
            inflate(R.menu.menu)
            refreshMenuItems(menu)
            setOnMenuItemClickListener { onMenuItemClicked(it) }
            show()
        }
    }

    private fun refreshMenuItems(menu: Menu) {
        val currentFragment = getCurrentFragment()
        menu.apply {
            // the dialpad has its own button in the top bar
            findItem(R.id.dpad_dialpad).isVisible = false
            findItem(R.id.clear_call_history).isVisible = currentFragment is RecentsFragment
            findItem(R.id.sort).isVisible = currentFragment !is RecentsFragment
            findItem(R.id.filter).isVisible = currentFragment !is RecentsFragment
            findItem(R.id.create_new_contact).isVisible = currentFragment is ContactsFragment
            findItem(R.id.change_view_type).isVisible = currentFragment is FavoritesFragment
            findItem(R.id.column_count).isVisible = currentFragment is FavoritesFragment && config.viewType == VIEW_TYPE_GRID
            findItem(R.id.more_apps_from_us).isVisible = false
        }
    }

    private fun onMenuItemClicked(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.dpad_dialpad -> launchDialpad()
            R.id.clear_call_history -> clearCallHistory()
            R.id.create_new_contact -> launchCreateNewContactIntent()
            R.id.sort -> showSortingDialog(showCustomSorting = getCurrentFragment() is FavoritesFragment)
            R.id.filter -> showFilterDialog()
            R.id.settings -> launchSettings()
            R.id.change_view_type -> changeViewType()
            R.id.column_count -> changeColumnCount()
            R.id.about -> launchAbout()
            else -> return false
        }
        return true
    }

    private fun openSearch() {
        searchFromHistory = getCurrentFragment() is RecentsFragment && visibleTabs(config.showTabs).contains(TAB_CONTACTS)
        if (!isSearchOpen) {
            getAllFragments().forEach { it?.finishActMode() }
            binding.mainToolbar.beGone()
            binding.mainSearchBar.beVisible()
        }

        binding.mainSearchInput.apply {
            setText("")
            moveFocusOutOfList { requestFocus() }
            showSearchKeyboard(this)
        }
        if (!searchFromHistory) {
            getCurrentFragment()?.onSearchQueryChanged("")
        }
    }

    /** Connects the phone's keyboard (e.g. Traditional T9) right away, so it's active before the first key press. */
    private fun showSearchKeyboard(input: EditText, attemptsLeft: Int = 10) {
        input.postDelayed({
            if (!isSearchOpen || isDestroyed) {
                return@postDelayed
            }

            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            if (!input.hasWindowFocus() || !inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)) {
                if (attemptsLeft > 0) {
                    showSearchKeyboard(input, attemptsLeft - 1)
                }
            }
        }, 50)
    }

    private fun closeSearch() {
        if (!isSearchOpen) {
            return
        }

        hideKeyboardKeepingFocus()
        searchFromHistory = false
        binding.mainSearchInput.setText("")
        binding.mainSearchBar.beGone()
        binding.mainToolbar.beVisible()
        getAllFragments().forEach { it?.onSearchClosed() }
        binding.root.post {
            if (!focusCurrentList()) {
                binding.mainSearch.requestFocus()
            }
        }
    }

    private fun getCurrentQuery() = if (isSearchOpen) binding.mainSearchInput.text.toString() else ""

    /** Unlike Commons' hideKeyboard() this keeps the focus, which would otherwise flash on the top bar. */
    private fun hideKeyboardKeepingFocus() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    /**
     * Search and "Ab" look for contacts, like in Fossify Phone. From the call history the history stays on screen while
     * typing or picking a letter, the contacts tab shows up only once OK confirms it.
     */
    private var searchFromHistory = false

    private fun switchToContacts(then: () -> Unit) {
        val contactsTab = visibleTabs(config.showTabs).indexOf(TAB_CONTACTS)
        if (contactsTab < 0) {
            return
        }

        binding.viewPager.setCurrentItem(contactsTab, false)
        binding.root.post { then() }
    }

    // ---- jump to letter ----

    /** The "Ab" button or *: pick a letter, the list jumps to the first contact (or call) starting with it. */
    private fun showLetterDialog() {
        // from the call history: the contacts' letters, the contacts tab opens on the picked one
        val fromHistory = getCurrentFragment() is RecentsFragment
        val list = (if (fromHistory) getContactsFragment() else getCurrentFragment())?.getListView() ?: return
        val firstPositions = LinkedHashMap<String, Int>()
        when (val adapter = list.adapter) {
            is ContactsAdapter -> adapter.contacts.forEachIndexed { index, contact ->
                firstPositions.putIfAbsent(letterOf(contact.getFirstLetter()), index)
            }

            is RecentCallsAdapter -> adapter.currentList.forEachIndexed { index, item ->
                if (item is RecentCall) {
                    firstPositions.putIfAbsent(letterOf(item.name.normalizeString().take(1).uppercase()), index)
                }
            }

            else -> return
        }

        if (firstPositions.isEmpty()) {
            return
        }

        val focusedPosition = list.focusedAdapterPosition()
        val current = firstPositions.entries.lastOrNull { it.value <= focusedPosition }?.key
        DpadLetterDialog(this, firstPositions.keys, if (fromHistory) null else current) { letter ->
            val position = firstPositions[letter] ?: return@DpadLetterDialog
            if (fromHistory) {
                switchToContacts { focusRowAt(list, position) }
            } else {
                focusRowAt(list, position)
            }
        }
    }

    /** Anything else than A–Z (numbers without a name too) under "#". */
    private fun letterOf(letter: String) = if (letter.length == 1 && letter[0] in 'A'..'Z') letter else DPAD_OTHER_LETTER

    // ---- focus ----

    /**
     * Commons' hideKeyboard() clears the focus before opening a contact, calling etc., which throws the D-pad focus
     * to the toolbar. Put it back on the row unless we moved it away on purpose.
     */
    /** The row keepFocusInList puts the focus back on; forgotten when the tab changes (it was the other tab's row). */
    private var lastListPosition = -1

    /** While a tab switch puts the focus on the first row nothing else moves it (keepFocusInList stays out). */
    private var switchingTabs = false

    private fun keepFocusInList() {
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            val list = getCurrentFragment()?.getListView() ?: return@addOnGlobalFocusChangeListener
            val item = newFocus?.let { list.findContainingItemView(it) }
            when {
                item != null -> lastListPosition = list.getChildAdapterPosition(item)
                switchingTabs || allowLeavingList || newFocus == null || newFocus.isInside(binding.viewPager) -> lastListPosition = -1
                lastListPosition >= 0 -> {
                    // move it back right away, before the next frame, so the toolbar doesn't flash as focused
                    val position = lastListPosition
                    val row = list.findViewHolderForAdapterPosition(position)?.itemView
                    if (row != null) {
                        row.requestFocus()
                    } else {
                        list.post {
                            if (!switchingTabs) {
                                list.focusItem(position)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun moveFocusOutOfList(action: () -> Unit) {
        allowLeavingList = true
        try {
            action()
        } finally {
            allowLeavingList = false
        }
    }

    /** Focuses the row at [position], scrolled to the top of the list unless it's fully on screen already. */
    private fun focusRowAt(list: RecyclerView, position: Int) {
        val holder = list.findViewHolderForAdapterPosition(position)?.itemView
        val fullyShown = holder != null && holder.top >= 0 && holder.bottom <= list.height
        if (holder != null && (fullyShown || position <= 1)) {
            holder.requestFocus()
            if (position <= 1) {
                list.scrollToPosition(0)
            }
            return
        }

        // a jump recycles the focused row, the list itself holds the focus meanwhile
        val wasFocusable = list.isFocusable
        list.isFocusable = true
        list.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        list.requestFocus()
        list.doOnNextLayout {
            list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            list.isFocusable = wasFocusable
        }
        // a call right under its date: the date stays visible
        val offsetPosition = if (position > 0 && (list.adapter as? RecentCallsAdapter)?.isDateHeader(position - 1) == true) position - 1 else position
        (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(offsetPosition, 0) ?: list.scrollToPosition(position)
    }

    /**
     * ↑ / ↓ in a list (one column): the list scrolls right away and only then the row gets the focus. Android's own
     * way scrolls with an animation once the focus is on a half visible row; holding the arrow sent the next key
     * before it ended, and the focus jumped around (to "Ab" for a moment too, under not yet laid out rows). The call
     * history's date headers are skipped, its first call of a day comes with its date.
     */
    private fun stepInList(list: RecyclerView, direction: Int): Boolean {
        val adapter = list.adapter ?: return false
        val history = adapter as? RecentCallsAdapter
        var target = list.focusedAdapterPosition() + direction
        while (history != null && target in 0 until adapter.itemCount && history.isDateHeader(target)) {
            target += direction
        }

        if (target < 0) {
            list.scrollToPosition(0)
            focusTopBar()
            return true
        }

        if (target >= adapter.itemCount) {
            return true
        }

        var row = list.findViewHolderForAdapterPosition(target)?.itemView
        if (row == null) {
            // not laid out yet: one row further, the layout happens right in scrollBy
            val focusedRow = currentFocus?.let { list.findContainingItemView(it) }
            list.scrollBy(0, direction * (focusedRow?.height ?: (list.height / 4)))
            row = list.findViewHolderForAdapterPosition(target)?.itemView
        }

        if (row == null) {
            (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(target, 0)
            list.post { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
            return true
        }

        // fully into view before the focus moves, so nothing animates afterwards
        val top = list.paddingTop
        val bottom = list.height - list.paddingBottom
        val header = if (direction < 0 && history?.isDateHeader(target - 1) == true) list.findViewHolderForAdapterPosition(target - 1)?.itemView else null
        when {
            row.bottom > bottom -> list.scrollBy(0, row.bottom - bottom)
            direction < 0 && history?.isDateHeader(target - 1) == true && (header == null || header.top < top) ->
                list.scrollBy(0, (header?.top ?: (row.top - row.height / 2)) - top)
            row.top < top -> list.scrollBy(0, row.top - top)
        }
        row.requestFocus()
        return true
    }

    private fun RecyclerView.isOneColumn() = ((layoutManager as? GridLayoutManager)?.spanCount ?: 1) == 1

    /** Moves focus into the list of the visible tab, keeping [position] if given. */
    private fun focusCurrentList(position: Int = -1): Boolean = getCurrentFragment()?.focusContent(position) == true

    private fun focusTopBar() = moveFocusOutOfList {
        if (isSearchOpen) {
            binding.mainSearchInput.requestFocus()
        } else {
            binding.mainLetters.requestFocus()
        }
    }

    /**
     * The neighbouring tabs stay laid out next to the visible one, so up/down could move the focus into their lists,
     * where it can't be seen; only the visible tab takes the focus.
     */
    private fun updateTabFocusability() {
        val current = getCurrentFragment()
        getAllFragments().forEach {
            it?.descendantFocusability = if (it == current) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    private fun switchTab(delta: Int): Boolean {
        val target = binding.viewPager.currentItem + delta
        val count = binding.viewPager.adapter?.count ?: 0
        if (target !in 0 until count) {
            return true
        }

        // always the first row when coming to a tab: the list goes to the top while still hidden, so it shows up
        // there right away instead of jumping up from where it was left. Meanwhile the pager itself holds the focus
        // (no frame): the old tab's row is gone, and Android would put it on "Ab" for a moment
        val fragment = getAllFragments().getOrNull(target)
        val list = fragment?.getListView()
        switchingTabs = true
        lastListPosition = -1
        // the old tab's row loses the focus: with the top bar out of reach for a moment it can't land on "Ab"
        toolbarButtons.forEach { it.isFocusable = false }
        // the pager takes the focus itself, not one of its rows
        binding.viewPager.isFocusable = true
        binding.viewPager.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        binding.viewPager.requestFocus()
        list?.scrollToPosition(0)
        binding.viewPager.setCurrentItem(target, false)

        fun done() {
            toolbarButtons.forEach { it.isFocusable = true }
            binding.viewPager.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            binding.viewPager.isFocusable = false
            lastListPosition = -1
            switchingTabs = false
        }

        fun focusFirstRow(attemptsLeft: Int) {
            val first = fragment?.firstItemPosition() ?: 0
            val row = list?.findViewHolderForAdapterPosition(first)?.itemView
            when {
                row != null -> {
                    row.requestFocus()
                    done()
                }

                attemptsLeft > 0 && (list?.adapter?.itemCount ?: 0) > 0 -> binding.root.postDelayed({ focusFirstRow(attemptsLeft - 1) }, 30)
                else -> {
                    done()
                    if (fragment?.focusContent(first) != true) {
                        focusTopBar()
                    }
                }
            }
        }
        binding.root.post { focusFirstRow(20) }
        return true
    }

    private fun View.isInside(parent: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current == parent) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    // ---- keys ----

    private fun getFocusedItem(): Any? {
        val list = getCurrentFragment()?.getListView() ?: return null
        val position = list.focusedAdapterPosition()
        return when (val adapter = list.adapter) {
            is ContactsAdapter -> adapter.contacts.getOrNull(position)
            is RecentCallsAdapter -> adapter.currentList.getOrNull(position) as? RecentCall
            else -> null
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val inEditText = currentFocus is EditText

        // open the popup and the letter grid on key up, otherwise the new window receives the key up and stops
        // reacting to keys
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                showMoreMenu()
            }
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_STAR && !inEditText) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                showLetterDialog()
            }
            return true
        }

        // a digit anywhere starts dialing with it; the dialer gets the next keys
        if (!inEditText && event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                launchDialpad(('0' + (event.keyCode - KeyEvent.KEYCODE_0)).toString())
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && handleKeyDown(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleKeyDown(event: KeyEvent): Boolean {
        val focused = currentFocus
        val inEditText = focused is EditText
        val fragment = getCurrentFragment()
        val list = fragment?.getListView()
        val inList = focused != null && list != null && list.findContainingItemView(focused) != null
        val inPager = focused != null && focused.isInside(binding.viewPager)
        val inTopBar = focused != null && (focused in toolbarButtons || focused == binding.mainSearchClose || focused == binding.mainSearchInput)

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!inPager) {
                    return false
                }

                val grid = list?.layoutManager as? GridLayoutManager
                if (inList && grid != null && grid.spanCount > 1) {
                    val column = list.focusedAdapterPosition() % grid.spanCount
                    val atEdge = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) column == 0 else column == grid.spanCount - 1
                    if (!atEdge) {
                        return false
                    }
                }

                return switchTab(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (inTopBar) {
                    return true
                }

                if (inList && list!!.isOneColumn()) {
                    return stepInList(list, -1)
                }

                if (inList) {
                    val position = list!!.focusedAdapterPosition()
                    val span = (list.layoutManager as? GridLayoutManager)?.spanCount ?: 1
                    if (position < fragment.firstItemPosition() + span) {
                        focusTopBar()
                        return true
                    }
                } else if (inPager) {
                    focusTopBar()
                    return true
                }
                return false
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (inTopBar) {
                    focusCurrentList()
                    return true
                }

                if (inList && list!!.isOneColumn()) {
                    return stepInList(list, 1)
                }
                return false
            }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (focused == binding.mainSearchInput) {
                    hideKeyboardKeepingFocus()
                    if (searchFromHistory) {
                        // typed in the call history: now the matching contacts
                        searchFromHistory = false
                        switchToContacts {
                            getCurrentFragment()?.onSearchQueryChanged(getCurrentQuery())
                            binding.root.post { focusCurrentList(0) }
                        }
                    } else {
                        focusCurrentList(0)
                    }
                    return true
                }
                return false
            }

            KeyEvent.KEYCODE_SEARCH -> {
                openSearch()
                return true
            }

            KeyEvent.KEYCODE_CALL -> {
                when (val item = getFocusedItem()) {
                    is Contact -> startCallWithConfirmationCheck(item)
                    is RecentCall -> if (!item.isUnknownNumber) startCallWithConfirmationCheck(item.phoneNumber, item.name)
                    // nothing picked: the dialer, like the green key on the home screen
                    else -> launchDialpad()
                }
                return true
            }

            KeyEvent.KEYCODE_POUND -> {
                if (inEditText) {
                    return false
                }

                when (val item = getFocusedItem()) {
                    is Contact -> item.getPrimaryNumber()?.let { launchSendSMSIntent(it) }
                    is RecentCall -> if (!item.isUnknownNumber) launchSendSMSIntent(item.phoneNumber)
                    else -> launchDialpad("#")
                }
                return true
            }
        }

        return false
    }

    // ---- menu actions ----

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..CONTACTS_GRID_MAX_COLUMNS_COUNT) {
            items.add(RadioItem(i, resources.getQuantityString(R.plurals.column_counts, i, i)))
        }

        val currentColumnCount = config.contactsGridColumnCount
        DpadRadioGroupDialog(this, ArrayList(items), currentColumnCount) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.contactsGridColumnCount = newColumnCount
                getFavoritesFragment()?.columnCountChanged()
            }
        }
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this) {
            getFavoritesFragment()?.refreshItems()
        }
    }

    private fun clearCallHistory() {
        val confirmationText = "${getString(R.string.clear_history_confirmation)}\n\n${getString(R.string.cannot_be_undone)}"
        ConfirmationDialog(this, confirmationText) {
            RecentsHelper(this).removeAllRecentCalls(this) {
                runOnUiThread {
                    getRecentsFragment()?.refreshItems(invalidate = true)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        val appIconColor = config.appIconColor
        if (isNougatMR1Plus() && config.lastHandledShortcutColor != appIconColor) {
            val launchDialpad = getLaunchDialpadShortcut(appIconColor)

            try {
                shortcutManager.dynamicShortcuts = listOf(launchDialpad)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getLaunchDialpadShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.dialpad)
        val drawable = resources.getDrawable(R.drawable.shortcut_dialpad)
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_dialpad_background).applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, DialpadActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "launch_dialpad")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    // ---- tabs ----

    private fun setupTabColors() {
        val activeView = binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.customView
        updateBottomTabItemColors(activeView, true, getTabDrawableIds(true)[binding.viewPager.currentItem])

        getInactiveTabIndexes(binding.viewPager.currentItem).forEach { index ->
            val inactiveView = binding.mainTabsHolder.getTabAt(index)?.customView
            updateBottomTabItemColors(inactiveView, false, getTabDrawableIds(false)[index])
        }

        binding.mainTabsHolder.setBackgroundColor(getBottomNavigationBackgroundColor())
    }

    private fun getInactiveTabIndexes(activeIndex: Int) = (0 until binding.mainTabsHolder.tabCount).filter { it != activeIndex }

    private fun getTabDrawableIds(selected: Boolean): List<Int> = visibleTabs(config.showTabs).map {
        when (it) {
            TAB_CONTACTS -> if (selected) R.drawable.ic_person_vector else R.drawable.ic_person_outline_vector
            TAB_FAVORITES -> if (selected) R.drawable.ic_star_vector else R.drawable.ic_star_outline_vector
            else -> if (selected) R.drawable.ic_clock_filled_vector else R.drawable.ic_clock_vector
        }
    }

    private fun setupTabs() {
        binding.viewPager.adapter = null
        // a horizontal scroll view makes itself focusable whatever the layout says
        binding.mainTabsHolder.isFocusable = false
        binding.mainTabsHolder.isFocusableInTouchMode = false
        binding.mainTabsHolder.removeAllTabs()
        visibleTabs(config.showTabs).forEach { tab ->
            binding.mainTabsHolder.newTab().setCustomView(R.layout.bottom_tablayout_item).apply tab@{
                val icon = customView?.findViewById<ImageView>(R.id.tab_item_icon)
                val label = customView?.findViewById<TextView>(R.id.tab_item_label)
                icon?.setImageDrawable(getTabIcon(tab))
                label?.text = getTabLabel(tab)
                AutofitHelper.create(label)

                // icons only, to leave more room for the lists on small screens
                label?.beGone()
                icon?.contentDescription = getTabLabel(tab)
                val iconSize = resources.getDimensionPixelSize(R.dimen.dpad_tab_icon_size)
                icon?.layoutParams = icon?.layoutParams?.apply {
                    width = iconSize
                    height = iconSize
                }
                (customView?.findViewById<View>(R.id.tab_item_holder)?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 0
                binding.mainTabsHolder.addTab(this@tab)
                // tabs switch with left/right, they are never a D-pad stop themselves
                view.isFocusable = false
                view.isFocusableInTouchMode = false
            }
        }

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
                updateBottomTabItemColors(it.customView, false, getTabDrawableIds(false)[it.position])
            },
            tabSelectedAction = {
                binding.viewPager.currentItem = it.position
                if (getCurrentFragment() !is RecentsFragment) {
                    searchFromHistory = false
                }
                if (isSearchOpen && !searchFromHistory) {
                    getCurrentFragment()?.onSearchQueryChanged(getCurrentQuery())
                }
                updateBottomTabItemColors(it.customView, true, getTabDrawableIds(true)[it.position])

                if (getCurrentFragment() is RecentsFragment) {
                    clearMissedCalls()
                }
            }
        )

        binding.mainTabsHolder.beGoneIf(binding.mainTabsHolder.tabCount == 1)
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
        storedFontSize = config.fontSize
    }

    private fun getTabIcon(tab: Int): Drawable {
        val drawableId = when (tab) {
            TAB_CONTACTS -> R.drawable.ic_person_vector
            TAB_FAVORITES -> R.drawable.ic_star_vector
            else -> R.drawable.ic_clock_vector
        }

        return resources.getColoredDrawableWithColor(drawableId, getProperTextColor())
    }

    private fun getTabLabel(tab: Int): String {
        val stringId = when (tab) {
            TAB_CONTACTS -> R.string.contacts_tab
            TAB_FAVORITES -> R.string.favorites_tab
            else -> R.string.call_history_tab
        }

        return resources.getString(stringId)
    }

    private fun refreshItems() {
        if (isDestroyed || isFinishing) {
            return
        }

        binding.apply {
            if (viewPager.adapter == null) {
                viewPager.offscreenPageLimit = 2
                viewPager.adapter = ViewPagerAdapter(this@MainActivity)
                viewPager.currentItem = getDefaultTab()
                mainTabsHolder.getTabAt(viewPager.currentItem)?.select()
                viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                    override fun onPageSelected(position: Int) {
                        mainTabsHolder.getTabAt(position)?.select()
                        getAllFragments().forEach { it?.finishActMode() }
                        updateTabFocusability()
                    }
                })
                viewPager.onGlobalLayout {
                    refreshFragments()
                    updateTabFocusability()
                }
            } else {
                refreshFragments()
            }
        }
    }

    /** The app always starts on the call history (the missed call notification opens it too). */
    private fun getDefaultTab(): Int = visibleTabs(config.showTabs).indexOf(TAB_CALL_HISTORY).coerceAtLeast(0)

    private fun launchDialpad(number: String = "") {
        Intent(applicationContext, DialpadActivity::class.java).apply {
            if (number.isNotEmpty()) {
                action = Intent.ACTION_DIAL
                data = Uri.fromParts("tel", number, null)
            }
            startActivity(this)
        }
    }

    /** The call history first (the app starts on it), the contacts only once it's shown, so they don't slow it down. */
    fun refreshFragments() {
        val recents = getRecentsFragment()
        val loadContacts = {
            cacheContacts()
            getContactsFragment()?.refreshItems()
            getFavoritesFragment()?.refreshItems()
        }

        if (recents == null) {
            loadContacts()
            return
        }

        var contactsStarted = false
        recents.refreshItems {
            focusListAtStart()
            if (!contactsStarted) {
                contactsStarted = true
                loadContacts()
            }
        }
    }

    /** At startup the first call (or contact) is focused, ready for the Call key. */
    /** At startup the first call is focused (ready for the Call key) as soon as the list has one. */
    private fun focusListAtStart(attemptsLeft: Int = 60) {
        if (!focusListOnStart) {
            return
        }

        binding.root.postDelayed({
            if (!focusListOnStart || isSearchOpen) {
                return@postDelayed
            }

            val fragment = getCurrentFragment()
            val list = fragment?.getListView()
            // only a row that is really laid out counts, the top bar comes back once it has the focus
            val row = if (list != null && list.isShown) list.findViewHolderForAdapterPosition(fragment.firstItemPosition())?.itemView else null
            if (row != null) {
                row.requestFocus()
                startFocusDone()
            } else if (attemptsLeft > 0) {
                focusListAtStart(attemptsLeft - 1)
            } else {
                startFocusDone()
                if (fragment?.focusContent() != true) {
                    focusTopBar()
                }
            }
        }, 50)
    }

    private fun startFocusDone() {
        focusListOnStart = false
        toolbarButtons.forEach { it.isFocusable = true }
        binding.viewPager.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.viewPager.isFocusable = false
    }

    private fun getAllFragments(): List<MyViewPagerFragment<*>?> = visibleTabs(config.showTabs).map {
        when (it) {
            TAB_CONTACTS -> getContactsFragment()
            TAB_FAVORITES -> getFavoritesFragment()
            else -> getRecentsFragment()
        }
    }

    private fun getCurrentFragment(): MyViewPagerFragment<*>? = getAllFragments().getOrNull(binding.viewPager.currentItem)

    private fun getContactsFragment(): ContactsFragment? = findViewById(R.id.contacts_fragment)

    private fun getFavoritesFragment(): FavoritesFragment? = findViewById(R.id.favorites_fragment)

    private fun getRecentsFragment(): RecentsFragment? = findViewById(R.id.recents_fragment)

    private fun launchSettings() {
        hideKeyboardKeepingFocus()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        startActivity(Intent(this, DpadAboutActivity::class.java))
    }

    private fun showSortingDialog(showCustomSorting: Boolean) {
        ChangeSortingDialog(this, showCustomSorting) {
            getFavoritesFragment()?.refreshItems {
                if (isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(getCurrentQuery())
                }
            }

            getContactsFragment()?.refreshItems {
                if (isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(getCurrentQuery())
                }
            }
        }
    }

    private fun showFilterDialog() {
        FilterContactSourcesDialog(this) {
            listOf(getFavoritesFragment(), getContactsFragment(), getRecentsFragment()).forEach { fragment ->
                fragment?.refreshItems {
                    if (isSearchOpen) {
                        getCurrentFragment()?.onSearchQueryChanged(getCurrentQuery())
                    }
                }
            }
        }
    }

    fun cacheContacts() {
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsHelper(this).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { contacts ->
            if (SMT_PRIVATE !in config.ignoredContactSources) {
                val privateContacts = MyContactsContentProvider.getContacts(this, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                    contacts.sort()
                }
            }

            try {
                cachedContacts.clear()
                cachedContacts.addAll(contacts)
            } catch (ignored: Exception) {
            }

            // the call history may be on screen before the contacts: its rows look up names and locations again
            runOnUiThread { getRecentsFragment()?.contactsLoaded() }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshCallLog(event: Events.RefreshCallLog) {
        getRecentsFragment()?.refreshItems()
    }
}
