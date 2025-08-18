/*
 * Copyright 2024 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Example usage of EPUB total page counting with WASM-based calculation.
 *
 * This demonstrates how to:
 * 1. Display total pages in UI
 * 2. Show reading progress
 * 3. Handle calculation states
 */
public class EpubPageCountUsageExample(
    private val activity: FragmentActivity,
    private val navigator: EpubNavigatorFragment,
    private val totalPagesTextView: TextView,
    private val progressTextView: TextView
) {

    public fun setupPageCountObservation() {
        // Observe total pages count
        activity.lifecycleScope.launch {
            navigator.totalPages.collectLatest { totalPages ->
                updateTotalPagesUI(totalPages)
            }
        }

        // Observe current location changes to update progress
        activity.lifecycleScope.launch {
            navigator.currentLocator.collectLatest { _ ->
                updateProgressUI()
            }
        }
    }

    private fun updateTotalPagesUI(totalPages: Int?) {
        when (totalPages) {
            null -> {
                totalPagesTextView.text = "계산 중..."
            }

            else -> {
                totalPagesTextView.text = "총 ${totalPages}페이지"
            }
        }
    }

    private fun updateProgressUI() {
        val progress = navigator.getCurrentReadingProgress()
        when (progress) {
            null -> {
                progressTextView.text = "진행률: 계산 중..."
            }

            else -> {
                val percentage = (progress * 100).toInt()
                progressTextView.text = "진행률: ${percentage}%"
            }
        }
    }

    /**
     * Example of how to format page information for display.
     */
    public fun getFormattedPageInfo(): String {
        val totalPages = navigator.getCurrentTotalPages()
        val progress = navigator.getCurrentReadingProgress()

        return when {
            totalPages == null -> "페이지 계산 중..."
            progress == null -> "총 ${totalPages}페이지"
            else -> {
                val currentPage = (progress * totalPages).toInt() + 1
                "${currentPage} / ${totalPages}페이지 (${(progress * 100).toInt()}%)"
            }
        }
    }

    /**
     * Check if page calculation is complete.
     */
    public fun isPageCalculationComplete(): Boolean {
        return navigator.getCurrentTotalPages() != null
    }

    /**
     * Alternative method using extension function.
     */
    private fun setupPageCountWithExtensions() {
        activity.observeTotalPages(navigator) { totalPages ->
            updateTotalPagesUI(totalPages)
        }
    }
}