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
package org.linphone.ui.main.contacts.fragment

import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.core.content.FileProvider
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import java.io.File
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.ContactsListFragmentBinding
import org.linphone.ui.main.contacts.adapter.ContactsListAdapter
import org.linphone.ui.main.contacts.viewmodel.ContactsListViewModel
import org.linphone.ui.main.fragment.AbstractTopBarFragment
import org.linphone.utils.Event
import org.linphone.utils.hideKeyboard
import org.linphone.utils.showKeyboard

@UiThread
class ContactsListFragment : AbstractTopBarFragment() {
    companion object {
        private const val TAG = "[Contacts List Fragment]"
    }

    private lateinit var binding: ContactsListFragmentBinding

    private lateinit var listViewModel: ContactsListViewModel

    private lateinit var adapter: ContactsListAdapter
    private lateinit var favouritesAdapter: ContactsListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ContactsListFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        postponeEnterTransition()

        listViewModel = requireActivity().run {
            ViewModelProvider(this)[ContactsListViewModel::class.java]
        }

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = listViewModel

        adapter = ContactsListAdapter(viewLifecycleOwner)
        binding.contactsList.setHasFixedSize(true)
        binding.contactsList.adapter = adapter
        configureAdapter(adapter)

        val layoutManager = LinearLayoutManager(requireContext())
        binding.contactsList.layoutManager = layoutManager

        favouritesAdapter = ContactsListAdapter(viewLifecycleOwner, favourites = true)
        binding.favouritesContactsList.setHasFixedSize(true)
        binding.favouritesContactsList.adapter = favouritesAdapter
        configureAdapter(favouritesAdapter)

        val favouritesLayoutManager = LinearLayoutManager(requireContext())
        favouritesLayoutManager.orientation = LinearLayoutManager.HORIZONTAL
        binding.favouritesContactsList.layoutManager = favouritesLayoutManager

        listViewModel.contactsList.observe(
            viewLifecycleOwner
        ) {
            val currentCount = adapter.itemCount
            adapter.submitList(it)
            Log.i("$TAG Contacts list updated with [${it.size}] items")

            if (currentCount == 0) {
                (view.parent as? ViewGroup)?.doOnPreDraw {
                    startPostponedEnterTransition()
                    sharedViewModel.contactsListReadyToBeDisplayedEvent.value = Event(true)
                }
            } else if (currentCount < it.size) {
                Log.i("$TAG Contacts list updated with new items, scrolling to top")
                binding.contactsList.smoothScrollToPosition(0)
            }
        }

        listViewModel.favourites.observe(
            viewLifecycleOwner
        ) {
            favouritesAdapter.submitList(it)
            Log.i("$TAG Favourites contacts list updated with [${it.size}] items")
        }

        listViewModel.vCardTerminatedEvent.observe(viewLifecycleOwner) {
            it.consume { pair ->
                val contactName = pair.first
                val file = pair.second
                Log.i(
                    "$TAG Friend [$contactName] was exported as vCard file [${file.absolutePath}], sharing it"
                )
                shareContact(contactName, file)
            }
        }

        binding.setOnNewContactClicked {
            sharedViewModel.showNewContactEvent.value = Event(true)
        }

        binding.setFilterClickListener {
            // TODO FIXME: show context menu first to let user decides which filter to use
            listViewModel.toggleContactsFilter()
        }

        sharedViewModel.defaultAccountChangedEvent.observe(viewLifecycleOwner) {
            it.consume {
                Log.i(
                    "$TAG Default account changed, updating avatar in top bar & refreshing contacts list"
                )
                listViewModel.update()
                listViewModel.applyCurrentDefaultAccountFilter()
            }
        }

        // TopBarFragment related

        setViewModelAndTitle(listViewModel, "Contacts")

        listViewModel.searchFilter.observe(viewLifecycleOwner) { filter ->
            listViewModel.applyFilter(filter.trim())
        }

        listViewModel.focusSearchBarEvent.observe(viewLifecycleOwner) {
            it.consume { show ->
                if (show) {
                    // To automatically open keyboard
                    binding.topBar.search.showKeyboard(requireActivity().window)
                } else {
                    binding.topBar.search.hideKeyboard()
                }
            }
        }
    }

    private fun configureAdapter(adapter: ContactsListAdapter) {
        adapter.contactLongClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                val modalBottomSheet = ContactsListMenuDialogFragment(
                    model.starred,
                    { // onDismiss
                        adapter.resetSelection()
                    },
                    { // onFavourite
                        coreContext.postOnCoreThread {
                            model.friend.edit()
                            val starred = !model.friend.starred
                            Log.i(
                                "$TAG Friend [${model.name.value}] will be ${if (starred) "added" else "removed"} from favourites"
                            )
                            model.friend.starred = starred
                            model.friend.done()
                            coreContext.contactsManager.notifyContactsListChanged()
                        }
                    },
                    { // onShare
                        Log.i(
                            "$TAG Sharing friend [${model.name.value}], exporting it as vCard file first"
                        )
                        listViewModel.exportContactAsVCard(model.friend)
                    },
                    { // onDelete
                        coreContext.postOnCoreThread {
                            Log.w("$TAG Removing friend [${model.name.value}]")
                            model.friend.remove()
                            coreContext.contactsManager.notifyContactsListChanged()
                        }
                    }
                )
                modalBottomSheet.show(parentFragmentManager, ContactsListMenuDialogFragment.TAG)
            }
        }

        adapter.contactClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                sharedViewModel.showContactEvent.value = Event(model.id ?: "")
            }
        }
    }

    private fun shareContact(name: String, file: File) {
        val publicUri = FileProvider.getUriForFile(
            requireContext(),
            requireContext().getString(R.string.file_provider),
            file
        )
        Log.i("$TAG Public URI for vCard file is [$publicUri], starting intent chooser")

        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, publicUri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            type = ContactsContract.Contacts.CONTENT_VCARD_TYPE
        }

        val shareIntent = Intent.createChooser(sendIntent, null)
        startActivity(shareIntent)
    }
}