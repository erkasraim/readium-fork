/*
 * Copyright 2024 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Extension functions for working with EPUB total page counts.
 */

/**
 * Observes total pages count in the given [EpubNavigatorFragment].
 *
 * @param onPageCountUpdate Callback invoked when page count is available or changes.
 *                         Receives null while calculation is in progress.
 */
public fun LifecycleOwner.observeTotalPages(
    navigator: EpubNavigatorFragment,
    onPageCountUpdate: (totalPages: Int?) -> Unit
) {
    lifecycleScope.launch {
        navigator.totalPages.collectLatest { totalPages ->
            onPageCountUpdate(totalPages)
        }
    }
}

/**
 * Gets the current total page count if available.
 *
 * @return Current total pages or null if still calculating.
 */
public fun EpubNavigatorFragment.getCurrentTotalPages(): Int? = totalPages.value

/**
 * Calculates the current reading progress as a percentage.
 *
 * @return Progress from 0.0 to 1.0, or null if total pages not yet calculated.
 */
public fun EpubNavigatorFragment.getReadingProgress(): Double? = getCurrentReadingProgress()