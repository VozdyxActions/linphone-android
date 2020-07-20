/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
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
package org.linphone.activities.assistant.fragments

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.activities.assistant.viewmodels.QrCodeViewModel
import org.linphone.activities.assistant.viewmodels.SharedAssistantViewModel
import org.linphone.core.tools.Log
import org.linphone.databinding.AssistantQrCodeFragmentBinding
import org.linphone.utils.PermissionHelper

class QrCodeFragment : Fragment() {
    private lateinit var binding: AssistantQrCodeFragmentBinding
    private lateinit var sharedViewModel: SharedAssistantViewModel
    private lateinit var viewModel: QrCodeViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = AssistantQrCodeFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        binding.lifecycleOwner = this

        sharedViewModel = activity?.run {
            ViewModelProvider(this).get(SharedAssistantViewModel::class.java)
        } ?: throw Exception("Invalid Activity")

        viewModel = ViewModelProvider(this).get(QrCodeViewModel::class.java)
        binding.viewModel = viewModel

        viewModel.qrCodeFoundEvent.observe(viewLifecycleOwner, Observer {
            it.consume { url ->
                sharedViewModel.remoteProvisioningUrl.value = url
                findNavController().navigateUp()
            }
        })
        viewModel.setBackCamera()

        if (!PermissionHelper.required(requireContext()).hasRecordAudioPermission()) {
            Log.i("[QR Code] Asking for CAMERA permission")
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 0)
        }
    }

    override fun onResume() {
        super.onResume()

        coreContext.core.nativePreviewWindowId = binding.qrCodeCaptureTexture
        coreContext.core.enableQrcodeVideoPreview(true)
        coreContext.core.enableVideoPreview(true)
    }

    override fun onPause() {
        coreContext.core.nativePreviewWindowId = null
        coreContext.core.enableQrcodeVideoPreview(false)
        coreContext.core.enableVideoPreview(false)

        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        val granted = grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Log.i("[QR Code] CAMERA permission granted")
        } else {
            Log.w("[QR Code] CAMERA permission denied")
            findNavController().navigateUp()
        }
    }
}