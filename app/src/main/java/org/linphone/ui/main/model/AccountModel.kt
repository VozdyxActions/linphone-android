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
package org.linphone.ui.main.model

import android.view.View
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.contacts.AbstractAvatarModel
import org.linphone.core.Account
import org.linphone.core.AccountListenerStub
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom
import org.linphone.core.ConsolidatedPresence
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.MessageWaitingIndication
import org.linphone.core.RegistrationState
import org.linphone.core.SecurityLevel
import org.linphone.core.tools.Log
import org.linphone.utils.AppUtils
import org.linphone.utils.LinphoneUtils

class AccountModel @WorkerThread constructor(
    val account: Account,
    private val onMenuClicked: ((view: View, account: Account) -> Unit)? = null
) : AbstractAvatarModel() {
    companion object {
        private const val TAG = "[Account Model]"
    }

    val displayName = MutableLiveData<String>()

    val registrationState = MutableLiveData<RegistrationState>()

    val registrationStateLabel = MutableLiveData<String>()

    val registrationStateSummary = MutableLiveData<String>()

    val isDefault = MutableLiveData<Boolean>()

    val notificationsCount = MutableLiveData<Int>()

    private val accountListener = object : AccountListenerStub() {
        @WorkerThread
        override fun onRegistrationStateChanged(
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            Log.i(
                "$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] registration state changed: [$state]($message)"
            )
            update()
        }

        override fun onMessageWaitingIndicationChanged(
            account: Account,
            mwi: MessageWaitingIndication
        ) {
            Log.i(
                "$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] has received a MWI NOTIFY. ${if (mwi.hasMessageWaiting()) "Message(s) are waiting." else "No message is waiting."}}"
            )
            for (summary in mwi.summaries) {
                val context = summary.contextClass
                val nbNew = summary.nbNew
                val nbNewUrgent = summary.nbNewUrgent
                val nbOld = summary.nbOld
                val nbOldUrgent = summary.nbOldUrgent
                Log.i(
                    "$TAG [MWI] [$context]: new [$nbNew] urgent ($nbNewUrgent), old [$nbOld] urgent ($nbOldUrgent)"
                )
            }
        }
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onChatRoomRead(core: Core, chatRoom: ChatRoom) {
            computeNotificationsCount()
        }

        @WorkerThread
        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage>
        ) {
            computeNotificationsCount()
        }
    }

    init {
        account.addListener(accountListener)
        coreContext.core.addListener(coreListener)

        presenceStatus.postValue(ConsolidatedPresence.Offline)

        update()
    }

    @WorkerThread
    fun destroy() {
        coreContext.core.removeListener(coreListener)
        account.removeListener(accountListener)
    }

    @UiThread
    fun setAsDefault() {
        coreContext.postOnCoreThread { core ->
            if (core.defaultAccount != account) {
                core.defaultAccount = account

                for (friendList in core.friendsLists) {
                    if (friendList.isSubscriptionsEnabled) {
                        Log.i(
                            "$TAG Default account has changed, refreshing friend list [${friendList.displayName}] subscriptions"
                        )
                        // friendList.updateSubscriptions() won't trigger a refresh unless a friend has changed
                        friendList.isSubscriptionsEnabled = false
                        friendList.isSubscriptionsEnabled = true
                    }
                }
            }
        }

        isDefault.value = true
    }

    @UiThread
    fun openMenu(view: View) {
        onMenuClicked?.invoke(view, account)
    }

    @UiThread
    fun refreshRegister() {
        coreContext.postOnCoreThread { core ->
            core.refreshRegisters()
        }
    }

    @WorkerThread
    private fun update() {
        Log.i(
            "$TAG Refreshing info for account [${account.params.identityAddress?.asStringUriOnly()}]"
        )

        trust.postValue(SecurityLevel.EndToEndEncryptedAndVerified)
        showTrust.postValue(account.isEndToEndEncryptionMandatory())

        val name = LinphoneUtils.getDisplayName(account.params.identityAddress)
        displayName.postValue(name)

        initials.postValue(AppUtils.getInitials(name))

        val pictureUri = account.params.pictureUri.orEmpty()
        if (pictureUri != picturePath.value.orEmpty()) {
            picturePath.postValue(pictureUri)
            Log.d("$TAG Account picture URI is [$pictureUri]")
        }

        isDefault.postValue(coreContext.core.defaultAccount == account)
        computeNotificationsCount()

        val state = account.state
        registrationState.postValue(state)

        val label = when (state) {
            RegistrationState.None, RegistrationState.Cleared -> {
                AppUtils.getString(
                    R.string.drawer_menu_account_connection_status_cleared
                )
            }
            RegistrationState.Progress -> AppUtils.getString(
                R.string.drawer_menu_account_connection_status_progress
            )
            RegistrationState.Failed -> {
                AppUtils.getString(
                    R.string.drawer_menu_account_connection_status_failed
                )
            }
            RegistrationState.Ok -> {
                AppUtils.getString(
                    R.string.drawer_menu_account_connection_status_connected
                )
            }
            RegistrationState.Refreshing -> AppUtils.getString(
                R.string.drawer_menu_account_connection_status_refreshing
            )
            else -> "${account.state}"
        }
        registrationStateLabel.postValue(label)

        val summary = when (account.state) {
            RegistrationState.None, RegistrationState.Cleared -> AppUtils.getString(
                R.string.manage_account_status_cleared_summary
            )
            RegistrationState.Refreshing, RegistrationState.Progress -> AppUtils.getString(
                R.string.manage_account_status_progress_summary
            )
            RegistrationState.Failed -> AppUtils.getString(
                R.string.manage_account_status_failed_summary
            )
            RegistrationState.Ok -> AppUtils.getString(
                R.string.manage_account_status_connected_summary
            )
            else -> "${account.state}"
        }
        registrationStateSummary.postValue(summary)
    }

    @WorkerThread
    private fun computeNotificationsCount() {
        notificationsCount.postValue(account.unreadChatMessageCount + account.missedCallsCount)
    }
}

@WorkerThread
fun Account.isEndToEndEncryptionMandatory(): Boolean {
    val defaultDomain = params.identityAddress?.domain == corePreferences.defaultDomain
    // TODO FIXME: use API when available
    // val encryption = params.mediaEncryption == MediaEncryption.ZRTP && params.mediaEncryptionMandatory && params.instantMessagingEncryptionMandatory
    val encryption = corePreferences.config.getBool("test", "account_e2e_mode", false)
    return defaultDomain && encryption
}

@WorkerThread
fun Account.setEndToEndEncryptionMandatory() {
    /*
    TODO FIXME: use API when available
    val clone = params.clone()
    clone.mediaEncryption = MediaEncryption.ZRTP
    clone.mediaEncryptionMandatory = true
    clone.instantMessagingEncryptionMandatory = true
    params = clone
    */
    corePreferences.config.setBool("test", "account_e2e_mode", true)

    if (this == core.defaultAccount) {
        coreContext.contactsManager.updateContactsModelDependingOnDefaultAccountMode()
    }
    Log.i("[Account] End-to-end encryption set mandatory on account")
}

@WorkerThread
fun Account.setInteroperabilityMode() {
    /*
    TODO FIXME: use API when available
    val clone = params.clone()
    clone.mediaEncryption = MediaEncryption.SRTP
    clone.mediaEncryptionMandatory = false
    clone.instantMessagingEncryptionMandatory = false
    params = clone
    */
    corePreferences.config.setBool("test", "account_e2e_mode", false)

    if (this == core.defaultAccount) {
        coreContext.contactsManager.updateContactsModelDependingOnDefaultAccountMode()
    }
    Log.i("[Account] Account configured in interoperable mode")
}
