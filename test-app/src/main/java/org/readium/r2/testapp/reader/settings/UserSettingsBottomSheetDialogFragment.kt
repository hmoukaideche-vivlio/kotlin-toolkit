/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(ExperimentalReadiumApi::class)

package org.readium.r2.testapp.reader.settings

import android.app.Dialog
import androidx.compose.runtime.Composable
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.testapp.reader.ReaderViewModel
import org.readium.r2.testapp.utils.compose.ComposeBottomSheetDialogFragment

class UserSettingsBottomSheetDialogFragment : ComposeBottomSheetDialogFragment(
    isScrollable = true
) {
    private val model: ReaderViewModel by activityViewModels()

    override fun setupDialog(dialog: Dialog, style: Int) {
        super.setupDialog(dialog, style)

        checkNotNull(dialog as BottomSheetDialog).apply {
            // Reduce the dim to see the impact of the settings on the page.
            window?.setDimAmount(0.1f)

            behavior.apply {
                peekHeight = 1000
                maxHeight = 1000
            }
        }
    }

    @Composable
    override fun Content() {
        UserSettings(model)
    }
}
