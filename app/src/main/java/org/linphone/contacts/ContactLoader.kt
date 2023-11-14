/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.contacts

import android.content.ContentUris
import android.database.Cursor
import android.database.StaleDataException
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Patterns
import androidx.annotation.MainThread
import androidx.loader.app.LoaderManager
import androidx.loader.content.CursorLoader
import androidx.loader.content.Loader
import java.lang.Exception
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.*
import org.linphone.core.tools.Log
import org.linphone.utils.PhoneNumberUtils

class ContactLoader : LoaderManager.LoaderCallbacks<Cursor> {
    companion object {
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
        )

        private const val TAG = "[Contacts Loader]"

        private const val NATIVE_ADDRESS_BOOK_FRIEND_LIST = "Native address-book"
        const val LINPHONE_ADDRESS_BOOK_FRIEND_LIST = "Linphone address-book"

        private const val MAX_INTERVAL_TO_REFRESH = 60000L // 1 minute
    }

    @MainThread
    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor> {
        val mimeType = ContactsContract.Data.MIMETYPE
        val mimeSelection = "$mimeType = ? OR $mimeType = ? OR $mimeType = ? OR $mimeType = ?"

        val selection = ContactsContract.Data.IN_DEFAULT_DIRECTORY + " == 1 AND ($mimeSelection)"
        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
        )

        val loader = CursorLoader(
            coreContext.context,
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            ContactsContract.Data.CONTACT_ID + " ASC"
        )

        loader.setUpdateThrottle(MAX_INTERVAL_TO_REFRESH) // Update at most once per minute

        return loader
    }

    @MainThread
    override fun onLoadFinished(loader: Loader<Cursor>, cursor: Cursor?) {
        if (cursor == null) {
            Log.e("$TAG Cursor is null!")
            return
        }
        Log.i("$TAG Load finished, found ${cursor.count} entries in cursor")

        coreContext.postOnCoreThread { core ->
            val state = coreContext.core.globalState
            if (state == GlobalState.Shutdown || state == GlobalState.Off) {
                Log.w("$TAG Core is being stopped or already destroyed, abort")
                return@postOnCoreThread
            }

            val friends = HashMap<String, Friend>()
            try {
                // Cursor can be null now that we are on a different dispatcher according to Crashlytics
                val friendsPhoneNumbers = arrayListOf<String>()
                val friendsAddresses = arrayListOf<Address>()
                var previousId = ""
                while (cursor != null && !cursor.isClosed && cursor.moveToNext()) {
                    try {
                        val id: String =
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                            )
                        val mime: String? =
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                            )

                        if (previousId.isEmpty() || previousId != id) {
                            friendsPhoneNumbers.clear()
                            friendsAddresses.clear()
                            previousId = id
                        }

                        val friend = friends[id] ?: core.createFriend()
                        friend.refKey = id
                        if (friend.name.isNullOrEmpty()) {
                            val displayName: String? =
                                cursor.getString(
                                    cursor.getColumnIndexOrThrow(
                                        ContactsContract.Data.DISPLAY_NAME_PRIMARY
                                    )
                                )
                            friend.name = displayName

                            friend.photo = Uri.withAppendedPath(
                                ContentUris.withAppendedId(
                                    ContactsContract.Contacts.CONTENT_URI,
                                    id.toLong()
                                ),
                                ContactsContract.Contacts.Photo.CONTENT_DIRECTORY
                            ).toString()

                            val starred =
                                cursor.getInt(
                                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)
                                ) == 1
                            friend.starred = starred

                            val lookupKey =
                                cursor.getString(
                                    cursor.getColumnIndexOrThrow(
                                        ContactsContract.Contacts.LOOKUP_KEY
                                    )
                                )
                            friend.nativeUri =
                                "${ContactsContract.Contacts.CONTENT_LOOKUP_URI}/$lookupKey"

                            friend.isSubscribesEnabled = false
                            // Disable peer to peer short term presence
                            friend.incSubscribePolicy = SubscribePolicy.SPDeny
                        }

                        when (mime) {
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                                val data1: String? =
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                            ContactsContract.CommonDataKinds.Phone.NUMBER
                                        )
                                    )
                                val data2: String? =
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                            ContactsContract.CommonDataKinds.Phone.TYPE
                                        )
                                    )
                                val data3: String? =
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                            ContactsContract.CommonDataKinds.Phone.LABEL
                                        )
                                    )
                                val data4: String? =
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
                                        )
                                    )

                                val label =
                                    PhoneNumberUtils.addressBookLabelTypeToVcardParamString(
                                        data2?.toInt()
                                            ?: ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM,
                                        data3
                                    )

                                val number =
                                    if (data1.isNullOrEmpty() ||
                                        !Patterns.PHONE.matcher(data1).matches()
                                    ) {
                                        data4 ?: data1
                                    } else {
                                        data1
                                    }

                                if (number != null) {
                                    if (
                                        friendsPhoneNumbers.find {
                                            PhoneNumberUtils.arePhoneNumberWeakEqual(
                                                it,
                                                number
                                            )
                                        } == null
                                    ) {
                                        val phoneNumber = Factory.instance()
                                            .createFriendPhoneNumber(number, label)
                                        friend.addPhoneNumberWithLabel(phoneNumber)
                                        friendsPhoneNumbers.add(number)
                                    }
                                }
                            }
                            ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE -> {
                                val sipAddress: String? =
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                            ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS
                                        )
                                    )
                                if (sipAddress != null) {
                                    val address = core.interpretUrl(sipAddress, true)
                                    if (address != null &&
                                        friendsAddresses.find {
                                            it.weakEqual(address)
                                        } == null
                                    ) {
                                        friend.addAddress(address)
                                        friendsAddresses.add(address)
                                    }
                                }
                            }
                            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                                val organization: String? =
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                            ContactsContract.CommonDataKinds.Organization.COMPANY
                                        )
                                    )
                                if (organization != null) {
                                    friend.organization = organization
                                }

                                val job: String? =
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                            ContactsContract.CommonDataKinds.Organization.TITLE
                                        )
                                    )
                                if (job != null) {
                                    friend.jobTitle = job
                                }
                            }
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                                val vCard = friend.vcard
                                if (vCard != null) {
                                    val givenName: String? =
                                        cursor.getString(
                                            cursor.getColumnIndexOrThrow(
                                                ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME
                                            )
                                        )
                                    if (!givenName.isNullOrEmpty()) {
                                        vCard.givenName = givenName
                                    }

                                    val familyName: String? =
                                        cursor.getString(
                                            cursor.getColumnIndexOrThrow(
                                                ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME
                                            )
                                        )
                                    if (!familyName.isNullOrEmpty()) {
                                        vCard.familyName = familyName
                                    }
                                }
                            }
                        }

                        friends[id] = friend
                    } catch (e: Exception) {
                        Log.e("$TAG Exception: $e")
                    }
                }

                if (core.globalState == GlobalState.Shutdown || core.globalState == GlobalState.Off) {
                    Log.w("$TAG Core is being stopped or already destroyed, abort")
                } else if (friends.isEmpty()) {
                    Log.w("$TAG No friend created!")
                } else {
                    Log.i("$TAG ${friends.size} friends created")
                    val fetchedFriends = friends.values

                    val fl = core.getFriendListByName(NATIVE_ADDRESS_BOOK_FRIEND_LIST) ?: core.createFriendList()
                    if (fl.displayName.isNullOrEmpty()) {
                        Log.i(
                            "$TAG Friend list [$NATIVE_ADDRESS_BOOK_FRIEND_LIST] didn't exist yet, let's create it"
                        )
                        fl.isDatabaseStorageEnabled = false // We don't want to store local address-book in DB
                        fl.displayName = NATIVE_ADDRESS_BOOK_FRIEND_LIST
                        core.addFriendList(fl)
                    } else {
                        Log.i(
                            "$TAG Friend list [$LINPHONE_ADDRESS_BOOK_FRIEND_LIST] found, removing existing friends if any"
                        )
                        for (friend in fl.friends) {
                            fl.removeFriend(friend)
                        }
                    }

                    for (friend in fetchedFriends) {
                        fl.addLocalFriend(friend)
                    }
                    friends.clear()
                    Log.i("$TAG Friends added")

                    fl.updateSubscriptions()
                    Log.i("$TAG Subscription(s) updated")
                    coreContext.contactsManager.onNativeContactsLoaded()
                }
            } catch (sde: StaleDataException) {
                Log.e("$TAG State Data Exception: $sde")
            } catch (ise: IllegalStateException) {
                Log.e("$TAG Illegal State Exception: $ise")
            } catch (e: Exception) {
                Log.e("$TAG Exception: $e")
            }
        }
    }

    @MainThread
    override fun onLoaderReset(loader: Loader<Cursor>) {
        Log.i("$TAG Loader reset")
    }
}
