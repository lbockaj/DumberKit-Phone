package org.fossify.phone

import android.app.Activity
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.phone.helpers.RecentsCache
import com.bumptech.glide.Glide
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import org.fossify.commons.helpers.ensureBackgroundThread
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import org.fossify.commons.FossifyApp
import org.fossify.commons.activities.CustomizationActivity
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getAppIconColors
import org.fossify.commons.extensions.toggleAppIconColor
import org.fossify.commons.helpers.appIconColorStrings
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.setFillWithStroke
import org.fossify.commons.helpers.IS_SYSTEM_THEME_ENABLED
import org.fossify.commons.helpers.ON_CLICK_VIEW_CONTACT
import org.fossify.commons.helpers.PREFS_KEY
import org.fossify.commons.helpers.SIDELOADING_FALSE
import org.fossify.commons.views.MyAppBarLayout
import org.fossify.phone.helpers.applyDpadFocusHighlight
import org.fossify.phone.helpers.dpadCompactTopBar
import org.fossify.phone.helpers.ignoreTouches

// Commons' preference key of "On contact click"
private const val ON_CONTACT_CLICK_KEY = "on_contact_click"

class App : FossifyApp() {

    override fun onCreate() {
        super.onCreate()
        // Commons' check for old installs with an orange icon switches the icon to green, never wanted here
        baseConfig.wasOrangeIconChecked = true
        useSystemThemeByDefault()
        openContactOnClickByDefault()
        syncLauncherIconColor()
        warmUpLibraries()
        if (hasPermission(PERMISSION_READ_CALL_LOG)) {
            RecentsCache.preload(this)
        }
        // Commons calls any build without one of its unused images (removed by the release shrinker) a "fake version"
        // and sends people to its website; this app is built from its own source on purpose
        baseConfig.appSideloadingStatus = SIDELOADING_FALSE
        // Commons warns about launchers losing a recolored icon (restore the "default green", reinstall from Google
        // Play) the first time the theme or icon color is picked; neither fits here, and it hid the theme list behind it
        baseConfig.wasAppIconCustomizationWarningShown = true
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                // keys only: touches are ignored in every screen of the app
                activity.ignoreTouches()
                // Commons' screens we can't change directly: clear focus highlight and a focused first row
                if (activity is CustomizationActivity) {
                    activity.window.decorView.post {
                        setupCustomizationForKeys(activity)
                        prepareForKeys(activity)
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Compact always-colored top bar, and Cancel / Save rows at the end instead of the toolbar's check icon. */
    private fun setupCustomizationForKeys(activity: CustomizationActivity) {
        keepIconColorOnThemeChange(activity)
        val appBar = activity.findViewById<MyAppBarLayout>(org.fossify.commons.R.id.app_bar)
        val scrollView = activity.findViewById<NestedScrollView>(org.fossify.commons.R.id.customization_nested_scrollview)
        if (appBar != null) {
            activity.dpadCompactTopBar(appBar, scrollView)
        }

        val holder = activity.findViewById<ViewGroup>(org.fossify.commons.R.id.customization_holder) ?: return
        if (holder.findViewById<View>(R.id.dpad_save_cancel_rows) == null) {
            val rows = LayoutInflater.from(activity).inflate(R.layout.dpad_save_cancel_rows, holder, false)
            holder.addView(rows)

            val toolbar = activity.findViewById<MaterialToolbar>(org.fossify.commons.R.id.customization_toolbar)
            rows.findViewById<View>(R.id.dpad_save_row_holder).setOnClickListener {
                toolbar?.menu?.performIdentifierAction(org.fossify.commons.R.id.save, 0)
            }
            // cancel = leave without saving, like choosing "discard" when going back
            rows.findViewById<View>(R.id.dpad_cancel_row_holder).setOnClickListener { activity.finish() }

            // Commons puts the back arrow and the check icon back after every color change, hide them again each time;
            // the hidden save item still works for the Save row (menus only check if an item is enabled)
            toolbar?.viewTreeObserver?.addOnGlobalLayoutListener {
                if (toolbar.navigationIcon != null) {
                    toolbar.navigationIcon = null
                }
                toolbar.menu.findItem(org.fossify.commons.R.id.save)?.takeIf { it.isVisible }?.isVisible = false
            }

            // a theme picked here (not saved yet) recolors only Commons' own labels, follow them
            val themeLabel = activity.findViewById<TextView>(org.fossify.commons.R.id.customization_theme_label)
            val labels = listOf(R.id.dpad_save_row_label, R.id.dpad_cancel_row_label).mapNotNull { rows.findViewById<TextView>(it) }
            holder.viewTreeObserver.addOnPreDrawListener {
                val color = themeLabel?.currentTextColor
                if (color != null) {
                    labels.filter { it.currentTextColor != color }.forEach { it.setTextColor(color) }
                }
                true
            }
        }

        holder.findViewById<ImageView>(R.id.dpad_save_row_icon)?.applyColorFilter(ContextCompat.getColor(activity, R.color.dpad_save_green))
        holder.findViewById<ImageView>(R.id.dpad_cancel_row_icon)?.applyColorFilter(ContextCompat.getColor(activity, R.color.dpad_cancel_red))
    }

    /**
     * Commons' ready-made themes (Light, Dark…) also set the launcher icon to their own color, usually green, and saving
     * then changes the icon on the phone. The icon color changes here only when picked in its own row; after a theme
     * pick it's put back. Commons keeps it in private fields, see proguard-rules.pro.
     */
    private fun keepIconColorOnThemeChange(activity: CustomizationActivity) {
        val iconHolder = activity.findViewById<View>(org.fossify.commons.R.id.customization_app_icon_color_holder) ?: return
        val iconPreview = activity.findViewById<ImageView>(org.fossify.commons.R.id.customization_app_icon_color) ?: return
        if (iconHolder.getTag(R.id.dpad_focus_prepared) == true) {
            return
        }

        val fields = try {
            val type = CustomizationActivity::class.java
            Triple(
                type.getDeclaredField("curAppIconColor").apply { isAccessible = true },
                type.getDeclaredField("originalAppIconColor").apply { isAccessible = true },
                type.getDeclaredMethod("pickAppIconColor").apply { isAccessible = true }
            )
        } catch (ignored: Exception) {
            // another Commons version: its own behavior stays
            return
        }
        val (current, original, pick) = fields
        var pickedByUser = false
        iconHolder.setTag(R.id.dpad_focus_prepared, true)
        iconHolder.setOnClickListener {
            pickedByUser = true
            pick.invoke(activity)
        }

        var wantedColor: Int? = null
        iconHolder.rootView.viewTreeObserver.addOnPreDrawListener {
            val wanted = wantedColor ?: original.getInt(activity)
            val color = current.getInt(activity)
            if (color != wanted) {
                if (pickedByUser) {
                    // a color from the icon row itself stays, also through later theme picks
                    wantedColor = color
                    pickedByUser = false
                } else {
                    current.setInt(activity, wanted)
                    iconPreview.setFillWithStroke(wanted, activity.getProperBackgroundColor())
                }
            }
            true
        }
    }

    private fun prepareForKeys(activity: Activity) {
        val rows = ArrayList<View>()
        fun collect(view: View) {
            // hidden rows too: some only show up after picking a theme (text, background, primary color)
            if (view !is EditText && view.isClickable && view.id != View.NO_ID &&
                activity.resources.getResourceEntryName(view.id).endsWith("_holder")
            ) {
                rows.add(view)
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    collect(view.getChildAt(i))
                }
            }
        }
        collect(activity.window.decorView)

        rows.forEach {
            it.isFocusable = true
            it.applyDpadFocusHighlight()
        }

        val focused = activity.currentFocus
        if (focused == null || focused is ScrollView || focused is NestedScrollView) {
            rows.firstOrNull { it.isShown }?.requestFocusFromTouch()
        }
    }

    /** The "System" color theme unless one was chosen; Commons only defaults to it on Android 12+. */
    private fun useSystemThemeByDefault() {
        val prefs = getSharedPreferences(PREFS_KEY, MODE_PRIVATE)
        if (!prefs.contains(IS_SYSTEM_THEME_ENABLED)) {
            baseConfig.isSystemThemeEnabled = true
        }
    }

    /** OK on a contact opens it, like in DumberKit Contacts (the green Call key calls); changeable in the settings. */
    private fun openContactOnClickByDefault() {
        val prefs = getSharedPreferences(PREFS_KEY, MODE_PRIVATE)
        if (!prefs.contains(ON_CONTACT_CLICK_KEY)) {
            baseConfig.onContactClick = ON_CLICK_VIEW_CONTACT
        }
    }

    /**
     * The phone number library (a big metadata load, used for the country of foreign numbers in the call history) and
     * the image loader start up in the background right away, not while the first rows of the history are drawn.
     */
    private fun warmUpLibraries() {
        ensureBackgroundThread {
            try {
                PhoneNumberUtil.getInstance().parse("+421900000000", "SK")
                PhoneNumberOfflineGeocoder.getInstance()
                Glide.get(this)
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * The launcher entry (activity-alias) of the icon color picked in the settings. Before the aliases were renamed
     * to this app's id Commons' switch failed, but it still noted the color as done and never tried again; this puts
     * the right entry on whenever they don't match.
     */
    private fun syncLauncherIconColor() {
        val colors = getAppIconColors()
        val wanted = colors.indexOf(baseConfig.appIconColor)
        if (wanted < 0) {
            return
        }

        val names = appIconColorStrings
        val appId = BuildConfig.APPLICATION_ID.removeSuffix(".debug")
        val enabledIndex = names.indexOfFirst { name ->
            val component = ComponentName(appId, "$appId.activities.SplashActivity$name")
            val state = packageManager.getComponentEnabledSetting(component)
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && isEnabledInManifest(component))
        }

        if (enabledIndex != wanted) {
            colors.forEachIndexed { index, color ->
                toggleAppIconColor(appId, index, color, index == wanted)
            }
            baseConfig.lastIconColor = baseConfig.appIconColor
        }
    }

    private fun isEnabledInManifest(component: ComponentName): Boolean = try {
        packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
