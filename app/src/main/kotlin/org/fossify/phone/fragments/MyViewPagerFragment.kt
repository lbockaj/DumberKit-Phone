package org.fossify.phone.fragments

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.RelativeLayout
import androidx.recyclerview.widget.LinearLayoutManager
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.helpers.SORT_BY_FIRST_NAME
import org.fossify.commons.helpers.SORT_BY_SURNAME
import org.fossify.commons.views.MyRecyclerView
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.ContactsAdapter
import org.fossify.phone.adapters.RecentCallsAdapter
import org.fossify.phone.databinding.FragmentLettersLayoutBinding
import org.fossify.phone.databinding.FragmentRecentsBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.helpers.Config
import org.fossify.phone.helpers.applyDpadFocusHighlight
import org.fossify.phone.helpers.focusItem

abstract class MyViewPagerFragment<BINDING : MyViewPagerFragment.InnerBinding>(context: Context, attributeSet: AttributeSet) :
    RelativeLayout(context, attributeSet) {
    protected var activity: SimpleActivity? = null
    protected lateinit var innerBinding: BINDING
    private lateinit var config: Config

    fun setupFragment(activity: SimpleActivity) {
        config = activity.config
        if (this.activity == null) {
            this.activity = activity
            innerBinding.placeholderAction.apply {
                isFocusable = true
                applyDpadFocusHighlight()
            }
            // no floating button to leave room for
            getListView()?.apply {
                setPadding(paddingLeft, paddingTop, paddingRight, 0)
            }

            setupFragment()
            setupColors(activity.getProperTextColor(), activity.getProperPrimaryColor(), activity.getProperPrimaryColor())
        }
    }

    fun startNameWithSurnameChanged(startNameWithSurname: Boolean) {
        if (this !is RecentsFragment) {
            (innerBinding.fragmentList?.adapter as? ContactsAdapter)?.apply {
                config.sorting = if (startNameWithSurname) SORT_BY_SURNAME else SORT_BY_FIRST_NAME
                (this@MyViewPagerFragment.activity!! as MainActivity).refreshFragments()
            }
        }
    }

    fun finishActMode() {
        (innerBinding.fragmentList?.adapter as? MyRecyclerViewAdapter)?.finishActMode()
        (innerBinding.recentsList?.adapter as? MyRecyclerViewAdapter)?.finishActMode()
    }

    fun fontSizeChanged() {
        if (this is RecentsFragment) {
            (innerBinding.recentsList.adapter as? RecentCallsAdapter)?.apply {
                fontSize = activity.getTextSize()
                notifyDataSetChanged()
            }
        } else {
            (innerBinding.fragmentList?.adapter as? ContactsAdapter)?.apply {
                fontSize = activity.getTextSize()
                notifyDataSetChanged()
            }
        }
    }

    fun getListView(): MyRecyclerView? = innerBinding.fragmentList ?: innerBinding.recentsList

    /** The first row that can take the focus: call history starts with a date header. */
    fun firstItemPosition(): Int {
        val adapter = getListView()?.adapter
        return if (adapter is RecentCallsAdapter && adapter.itemCount > 1 && adapter.isDateHeader(0)) 1 else 0
    }

    /** Focuses a list row ([position], or the first visible one), or the "request access…" link of an empty tab. */
    fun focusContent(position: Int = -1): Boolean {
        val list = getListView()
        val adapter = list?.adapter
        var target = position
        if (adapter is RecentCallsAdapter && target < 0) {
            target = (list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
        }
        if (adapter is RecentCallsAdapter && target >= 0 && adapter.isDateHeader(target)) {
            target++
        }

        if (list != null && list.focusItem(target)) {
            return true
        }

        val action = innerBinding.placeholderAction
        return action.isVisible() && action.requestFocus()
    }

    abstract fun setupFragment()

    abstract fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int)

    abstract fun onSearchClosed()

    abstract fun onSearchQueryChanged(text: String)

    interface InnerBinding {
        val fragmentList: MyRecyclerView?
        val recentsList: MyRecyclerView?
        val placeholderAction: View
    }

    class LettersInnerBinding(val binding: FragmentLettersLayoutBinding) : InnerBinding {
        override val fragmentList: MyRecyclerView = binding.fragmentList
        override val recentsList = null
        override val placeholderAction: View = binding.fragmentPlaceholder2
    }

    class RecentsInnerBinding(val binding: FragmentRecentsBinding) : InnerBinding {
        override val fragmentList = null
        override val recentsList = binding.recentsList
        override val placeholderAction: View = binding.recentsPlaceholder2
    }
}
