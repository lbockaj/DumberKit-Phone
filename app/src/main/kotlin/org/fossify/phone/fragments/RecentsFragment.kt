package org.fossify.phone.fragments

import android.content.Context
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import android.util.AttributeSet
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.RecentCallsAdapter
import org.fossify.phone.databinding.FragmentRecentsBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.runAfterAnimations
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.interfaces.RefreshItemsListener
import org.fossify.phone.models.CallLogItem
import org.fossify.phone.models.RecentCall

private const val RECENTS_CACHE_FILE = "dumberkit_recent_calls.json"
private const val RECENTS_CACHE_SIZE = 40

class RecentsFragment(
    context: Context, attributeSet: AttributeSet,
) : MyViewPagerFragment<MyViewPagerFragment.RecentsInnerBinding>(context, attributeSet), RefreshItemsListener {

    private lateinit var binding: FragmentRecentsBinding
    private var allRecentCalls = listOf<CallLogItem>()
    private var recentsAdapter: RecentCallsAdapter? = null

    private var searchQuery: String? = null
    private var recentsHelper = RecentsHelper(context)

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecentsBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)
    }

    override fun setupFragment() {
        showCachedCalls()
        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            R.string.no_previous_calls
        } else {
            R.string.could_not_access_the_call_history
        }

        binding.recentsPlaceholder.text = context.getString(placeholderResId)
        binding.recentsPlaceholder2.apply {
            underlineText()
            setOnClickListener {
                requestCallLogPermission()
            }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        binding.recentsPlaceholder.setTextColor(textColor)
        binding.recentsPlaceholder2.setTextColor(properPrimaryColor)

        recentsAdapter?.apply {
            updateTextColor(textColor)
            initDrawables()
        }
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        if (invalidate) {
            allRecentCalls = emptyList()
        }

        var shown = false
        val onShown = {
            activity?.runOnUiThread {
                if (!shown) {
                    shown = true
                    callback?.invoke()
                }
            }
        }

        refreshCallLog(loadAll = false, onShown = { onShown() }) {
            onShown()
            binding.recentsList.runAfterAnimations {
                refreshCallLog(loadAll = true)
            }
        }
    }

    override fun onSearchClosed() {
        searchQuery = null
        showOrHidePlaceholder(allRecentCalls.isEmpty())
        recentsAdapter?.updateItems(allRecentCalls)
    }

    override fun onSearchQueryChanged(text: String) {
        searchQuery = text
        updateSearchResult()
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateSearchResult() {
        ensureBackgroundThread {
            val fixedText = searchQuery!!.trim().replace("\\s+".toRegex(), " ")
            val recentCalls = allRecentCalls
                .filterIsInstance<RecentCall>()
                .filter {
                    it.name.contains(fixedText, true) || it.doesContainPhoneNumber(fixedText)
                }
                .sortedWith(
                    compareByDescending<RecentCall> { it.dayCode }
                        .thenByDescending { it.name.startsWith(fixedText, true) }
                        .thenByDescending { it.startTS }
                )

            prepareCallLog(recentCalls) {
                activity?.runOnUiThread {
                    showOrHidePlaceholder(recentCalls.isEmpty())
                    recentsAdapter?.updateItems(it, fixedText)
                }
            }
        }
    }

    private fun requestCallLogPermission() {
        activity?.handlePermission(PERMISSION_READ_CALL_LOG) {
            if (it) {
                binding.recentsPlaceholder.text = context.getString(R.string.no_previous_calls)
                binding.recentsPlaceholder2.beGone()
                refreshCallLog()
            }
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        if (show && !binding.progressIndicator.isVisible()) {
            binding.recentsPlaceholder.beVisible()
        } else {
            binding.recentsPlaceholder.beGone()
        }
    }

    private fun gotRecents(recents: List<CallLogItem>) {
        binding.progressIndicator.hide()
        if (recents.isEmpty()) {
            binding.apply {
                showOrHidePlaceholder(true)
                recentsPlaceholder2.beGoneIf(context.hasPermission(PERMISSION_READ_CALL_LOG))
                recentsList.beGone()
            }
        } else {
            binding.apply {
                showOrHidePlaceholder(false)
                recentsPlaceholder2.beGone()
                recentsList.beVisible()
            }

            if (binding.recentsList.adapter == null) {
                recentsAdapter = RecentCallsAdapter(
                    activity = activity as SimpleActivity,
                    recyclerView = binding.recentsList,
                    refreshItemsListener = this,
                    showOverflowMenu = false,
                    itemDelete = { deleted ->
                        allRecentCalls = allRecentCalls.filter { it !in deleted }
                    },
                    itemClick = {
                        val recentCall = it as RecentCall
                        activity?.startCallWithConfirmationCheck(recentCall.phoneNumber, recentCall.name)
                    },
                    profileIconClick = {
                        val recentCall = it as RecentCall
                        val contact = findContactByCall(recentCall)
                        if (contact != null) {
                            activity?.startContactDetailsIntent(contact)
                        } else {
                            activity?.startAddContactIntent(recentCall.phoneNumber)
                        }
                    }
                )

                binding.recentsList.adapter = recentsAdapter
                recentsAdapter?.updateItems(recents)
            } else {
                recentsAdapter?.updateItems(recents)
            }
        }
    }

    private fun refreshCallLog(loadAll: Boolean = false, onShown: (() -> Unit)? = null, callback: (() -> Unit)? = null) {
        getRecentCalls(loadAll, onCallsRead = { calls ->
            // calls shown from the cache don't count, the fresh ones show up right away too
            // the first time the calls show up right away with the names the call log has, the names from the
            // contacts (a slow load with many contacts) fill in after
            if (allRecentCalls.isEmpty() && searchQuery.isNullOrEmpty() && calls.isNotEmpty()) {
                val early = groupCallsByDate(calls)
                activity?.runOnUiThread {
                    gotRecents(early)
                    onShown?.invoke()
                }
            }
        }) {
            allRecentCalls = it
            if (!loadAll && searchQuery.isNullOrEmpty()) {
                saveCachedCalls(it)
            }
            if (searchQuery.isNullOrEmpty()) {
                activity?.runOnUiThread { gotRecents(it) }
            } else {
                updateSearchResult()
            }

            callback?.invoke()
        }
    }

    /** Rows bound before the contacts were loaded showed no contact (e.g. the country of every number). */
    fun contactsLoaded() {
        recentsAdapter?.apply { notifyItemRangeChanged(0, itemCount) }
    }

    private val cacheFile get() = File(context.cacheDir, RECENTS_CACHE_FILE)
    private val cacheJson = Json { ignoreUnknownKeys = true }

    /**
     * The calls shown at the last start, right away at the next one (reading the call log and the contacts takes a
     * moment); the fresh list replaces them shortly after.
     */
    private fun showCachedCalls() {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG) || allRecentCalls.isNotEmpty()) {
            return
        }

        val calls = try {
            cacheJson.decodeFromString(ListSerializer(RecentCall.serializer()), cacheFile.readText())
        } catch (e: Exception) {
            return
        }

        if (calls.isNotEmpty()) {
            gotRecents(groupCallsByDate(calls))
        }
    }

    private fun saveCachedCalls(items: List<CallLogItem>) {
        ensureBackgroundThread {
            try {
                val calls = items.filterIsInstance<RecentCall>().take(RECENTS_CACHE_SIZE)
                cacheFile.writeText(cacheJson.encodeToString(ListSerializer(RecentCall.serializer()), calls))
            } catch (ignored: Exception) {
            }
        }
    }

    private fun getRecentCalls(loadAll: Boolean, onCallsRead: (List<RecentCall>) -> Unit = {}, callback: (List<CallLogItem>) -> Unit) {
        val queryCount = if (loadAll) Int.MAX_VALUE else RecentsHelper.QUERY_LIMIT
        val existingRecentCalls = allRecentCalls.filterIsInstance<RecentCall>()

        with(recentsHelper) {
            if (context.config.groupSubsequentCalls) {
                getGroupedRecentCalls(existingRecentCalls, queryCount) {
                    onCallsRead(it)
                    prepareCallLog(it, callback)
                }
            } else {
                getRecentCalls(existingRecentCalls, queryCount) {
                    onCallsRead(it)
                    prepareCallLog(it, callback)
                }
            }
        }
    }

    private fun prepareCallLog(calls: List<RecentCall>, callback: (List<CallLogItem>) -> Unit) {
        if (calls.isEmpty()) {
            callback(emptyList())
            return
        }

        // the main screen keeps all contacts loaded already, no need to load them again for every refresh
        val cached = (activity as? MainActivity)?.cachedContacts?.takeIf { it.isNotEmpty() }?.let { ArrayList(it) }
        val withContacts: (ArrayList<Contact>) -> Unit = { contacts ->
            ensureBackgroundThread {
                val privateContacts = getPrivateContacts()
                val updatedCalls = updateNamesIfEmpty(
                    calls = maybeFilterPrivateCalls(calls, privateContacts),
                    contacts = contacts,
                    privateContacts = privateContacts
                )

                callback(
                    groupCallsByDate(updatedCalls)
                )
            }
        }

        if (cached != null) {
            withContacts(cached)
        } else {
            ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { withContacts(it) }
        }
    }

    private fun getPrivateContacts(): ArrayList<Contact> {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return MyContactsContentProvider.getContacts(context, privateCursor)
    }

    private fun maybeFilterPrivateCalls(calls: List<RecentCall>, privateContacts: List<Contact>): List<RecentCall> {
        val ignoredSources = context.baseConfig.ignoredContactSources
        return if (SMT_PRIVATE in ignoredSources) {
            val privateNumbers = privateContacts.flatMap { it.phoneNumbers }.map { it.value }
            calls.filterNot { it.phoneNumber in privateNumbers }
        } else {
            calls
        }
    }

    private fun updateNamesIfEmpty(calls: List<RecentCall>, contacts: List<Contact>, privateContacts: List<Contact>): List<RecentCall> {
        if (calls.isEmpty()) return mutableListOf()

        val contactsWithNumbers = contacts.filter { it.phoneNumbers.isNotEmpty() }
        return calls.map { call ->
            if (call.phoneNumber == call.name) {
                val privateContact = privateContacts.firstOrNull { it.doesContainPhoneNumber(call.phoneNumber) }
                val contact = contactsWithNumbers.firstOrNull { it.phoneNumbers.first().normalizedNumber == call.phoneNumber }

                when {
                    privateContact != null -> withUpdatedName(call = call, name = privateContact.getNameToDisplay())
                    contact != null -> withUpdatedName(call = call, name = contact.getNameToDisplay())
                    else -> call
                }
            } else {
                call
            }
        }
    }

    private fun withUpdatedName(call: RecentCall, name: String): RecentCall {
        return call.copy(
            name = name,
            groupedCalls = call.groupedCalls
                ?.map { it.copy(name = name) }
                ?.toMutableList()
                ?.ifEmpty { null }
        )
    }

    private fun groupCallsByDate(recentCalls: List<RecentCall>): MutableList<CallLogItem> {
        val callLog = mutableListOf<CallLogItem>()
        var lastDayCode = ""
        for (call in recentCalls) {
            val currentDayCode = call.dayCode
            if (currentDayCode != lastDayCode) {
                callLog += CallLogItem.Date(timestamp = call.startTS, dayCode = currentDayCode)
                lastDayCode = currentDayCode
            }

            callLog += call
        }

        return callLog
    }

    private fun findContactByCall(recentCall: RecentCall): Contact? {
        return (activity as MainActivity).cachedContacts.find { it.name == recentCall.name && it.doesHavePhoneNumber(recentCall.phoneNumber) }
    }
}
