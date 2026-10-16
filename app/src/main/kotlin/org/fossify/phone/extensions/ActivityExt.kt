package org.fossify.phone.extensions

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import org.fossify.commons.extensions.isPackageInstalled
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.launchViewContactIntent
import org.fossify.commons.helpers.CONTACT_ID
import org.fossify.commons.helpers.FIRST_CONTACT_ID
import org.fossify.commons.helpers.IS_PRIVATE
import org.fossify.commons.helpers.ON_CLICK_CALL_CONTACT
import org.fossify.commons.helpers.ON_CLICK_VIEW_CONTACT
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.receivers.ScreenOffAdminReceiver
import org.fossify.phone.R

fun SimpleActivity.handleGenericContactClick(contact: Contact) {
    when (config.onContactClick) {
        ON_CLICK_CALL_CONTACT -> startCallWithConfirmationCheck(contact)
        ON_CLICK_VIEW_CONTACT -> startContactDetailsIntent(contact)
    }
}

fun SimpleActivity.launchCreateNewContactIntent() {
    Intent().apply {
        action = Intent.ACTION_INSERT
        data = ContactsContract.Contacts.CONTENT_URI
        startActivityOnTop(this)
    }
}

// handle private contacts differently, only Simple Contacts Pro can open them
fun Activity.startContactDetailsIntent(contact: Contact) {
    val simpleContacts = "com.dumberkit.contacts"
    val simpleContactsDebug = "com.dumberkit.contacts.debug"
    val isPrivateContact = contact.rawId > FIRST_CONTACT_ID
            && contact.contactId > FIRST_CONTACT_ID
            && contact.rawId == contact.contactId
            && (isPackageInstalled(simpleContacts) || isPackageInstalled(simpleContactsDebug))
    if (isPrivateContact) {
        Intent().apply {
            action = Intent.ACTION_VIEW
            putExtra(CONTACT_ID, contact.rawId)
            putExtra(IS_PRIVATE, true)
            `package` =
                if (isPackageInstalled(simpleContacts)) simpleContacts else simpleContactsDebug
            setDataAndType(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                "vnd.android.cursor.dir/person"
            )
            launchActivityIntent(this)
        }
    } else {
        ensureBackgroundThread {
            val lookupKey =
                SimpleContactsHelper(this).getContactLookupKey((contact).rawId.toString())
            val publicUri =
                Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
            runOnUiThread {
                launchViewContactIntent(publicUri, dumberKitContacts())
            }
        }
    }
}

/** DumberKit Contacts, when installed, opens and edits contacts without asking which app to use. */
fun Context.dumberKitContacts(): String? = DUMBERKIT_CONTACTS.firstOrNull { isPackageInstalled(it) }

private val DUMBERKIT_CONTACTS = listOf("com.dumberkit.contacts", "com.dumberkit.contacts.debug")

fun Activity.launchViewContactIntent(uri: Uri, packageName: String?) {
    Intent().apply {
        action = ContactsContract.QuickContact.ACTION_QUICK_CONTACT
        data = uri
        packageName?.let { `package` = it }
        startActivityOnTop(this)
    }
}

/**
 * Unlike Commons' launchActivityIntent() without a new task: the contacts app opens on top of the phone app, so
 * Back returns here and not into the contacts app's own list.
 */
fun Activity.startActivityOnTop(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast(org.fossify.commons.R.string.no_app_found)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}


/**
 * Some systems (DumberOS among them) leave out pages of the system settings, starting a missing one crashed the app
 * right after its first start: the app's own settings page instead, where every permission can be found too.
 */
fun Activity.openSettingsSafely(open: () -> Unit) {
    try {
        open()
    } catch (e: ActivityNotFoundException) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            toast(org.fossify.commons.R.string.no_app_found)
        }
    }
}

/** Android's own screen for the device admin of ScreenOffAdminReceiver (only "lock the screen"). */
fun Activity.requestScreenOffAdmin() {
    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ScreenOffAdminReceiver.component(this@requestScreenOffAdmin))
        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.dpad_screen_off_admin_description))
        startActivityOnTop(this)
    }
}
