/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.gesturerecognizer.fragment

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mediapipe.examples.gesturerecognizer.GestureRecognizerHelper
import com.google.mediapipe.examples.gesturerecognizer.MainViewModel
import com.google.mediapipe.examples.gesturerecognizer.databinding.FragmentGalleryBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class GalleryFragment : Fragment(),
    GestureRecognizerHelper.GestureRecognizerListener {

    enum class MediaType {
        IMAGE,
        UNKNOWN
    }

    private var _fragmentGalleryBinding: FragmentGalleryBinding? = null
    private val fragmentGalleryBinding
        get() = _fragmentGalleryBinding!!
    private lateinit var gestureRecognizerHelper: GestureRecognizerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var defaultNumResults = 1
    private val gestureRecognizerResultsAdapter by lazy {
        GestureRecognizerResultsAdapter().apply {
            updateAdapterSize(defaultNumResults)
        }
    }

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ScheduledExecutorService

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            // Handle the returned Uri
            uri?.let { mediaUri ->
                when (val mediaType = loadMediaType(mediaUri)) {
                    MediaType.IMAGE -> runGestureRecognitionOnImage(mediaUri)
                    MediaType.UNKNOWN -> {
                        updateDisplayView(mediaType)
                        Toast.makeText(
                            requireContext(),
                            "Unsupported data type.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentGalleryBinding =
            FragmentGalleryBinding.inflate(inflater, container, false)

        return fragmentGalleryBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentGalleryBinding.fabGetContent.setOnClickListener {
            getContent.launch(arrayOf("image/*"))
        }

        with(fragmentGalleryBinding.recyclerviewResults) {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = gestureRecognizerResultsAdapter
        }
    }

    override fun onPause() {
        fragmentGalleryBinding.overlay.clear()
        super.onPause()
    }

    // Update the values displayed in the bottom sheet. Reset detector.
    @SuppressLint("NotifyDataSetChanged")
    private fun updateControlsUi() {
        fragmentGalleryBinding.imageResult.visibility = View.GONE
        fragmentGalleryBinding.overlay.clear()

        fragmentGalleryBinding.overlay.clear()
        fragmentGalleryBinding.tvPlaceholder.visibility = View.VISIBLE
        gestureRecognizerResultsAdapter.updateResults(null)
        gestureRecognizerResultsAdapter.notifyDataSetChanged()
    }

    // Load and display the image.
    private fun runGestureRecognitionOnImage(uri: Uri) {
        setUiEnabled(false)
        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        updateDisplayView(MediaType.IMAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(
                requireActivity().contentResolver,
                uri
            )
            ImageDecoder.decodeBitmap(source)
        } else {
            MediaStore.Images.Media.getBitmap(
                requireActivity().contentResolver,
                uri
            )
        }
            .copy(Bitmap.Config.ARGB_8888, true)
            ?.let { bitmap ->
                fragmentGalleryBinding.imageResult.setImageBitmap(bitmap)

                // Run gesture recognizer on the input image
                backgroundExecutor.execute {

                    gestureRecognizerHelper =
                        GestureRecognizerHelper(
                            context = requireContext(),
                            runningMode = RunningMode.IMAGE,
                            minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                            minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                            minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                            currentDelegate = viewModel.currentDelegate
                        )

                    gestureRecognizerHelper.recognizeImage(bitmap)
                        ?.let { resultBundle ->
                            activity?.runOnUiThread {

                                    fragmentGalleryBinding.overlay.setResults(
                                        resultBundle.results[0],
                                        bitmap.height,
                                        bitmap.width,
                                        RunningMode.IMAGE
                                    )

                                    // This will return an empty list if there are no gestures detected
                                    if(!resultBundle.results.first().gestures().isEmpty()) {
                                        gestureRecognizerResultsAdapter.updateResults(
                                            resultBundle.results.first()
                                                .gestures().first()
                                        )
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Hands not detected",
                                            Toast.LENGTH_SHORT).show()
                                    }

                                setUiEnabled(true)
                            }
                        } ?: run {
                        Log.e(
                            TAG, "Error running gesture recognizer."
                        )
                    }

                    gestureRecognizerHelper.clearGestureRecognizer()
                }
            }
    }



    private fun updateDisplayView(mediaType: MediaType) {
        fragmentGalleryBinding.imageResult.visibility =
            if (mediaType == MediaType.IMAGE) View.VISIBLE else View.GONE

        fragmentGalleryBinding.tvPlaceholder.visibility =
            if (mediaType == MediaType.UNKNOWN) View.VISIBLE else View.GONE
    }

    // Check the type of media that user selected.
    private fun loadMediaType(uri: Uri): MediaType {
        val mimeType = context?.contentResolver?.getType(uri)
        mimeType?.let {
            if (mimeType.startsWith("image")) return MediaType.IMAGE
        }

        return MediaType.UNKNOWN
    }

    private fun setUiEnabled(enabled: Boolean) {
        fragmentGalleryBinding.fabGetContent.isEnabled = enabled
    }

    private fun recognitionError() {
        activity?.runOnUiThread {
            fragmentGalleryBinding.progress.visibility = View.GONE
            setUiEnabled(true)
            updateDisplayView(MediaType.UNKNOWN)
        }
    }

    override fun onError(error: String, errorCode: Int) {
        recognitionError()
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == GestureRecognizerHelper.GPU_ERROR) {
                fragmentGalleryBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    GestureRecognizerHelper.DELEGATE_CPU,
                    false
                )
            }
        }
    }

    override fun onResults(resultBundle: GestureRecognizerHelper.ResultBundle) {
        // no-op
    }

    companion object {
        private const val TAG = "GalleryFragment"
    }
}
