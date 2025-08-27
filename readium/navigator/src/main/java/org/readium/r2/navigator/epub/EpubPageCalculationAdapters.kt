/*
 * Copyright 2024 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import org.readium.r2.navigator.pager.R2EpubPageFragment
import org.readium.r2.wasm.DefaultPageFragment
import org.readium.r2.wasm.DefaultPageFragmentWebView
import org.readium.r2.wasm.PageFragment
import org.readium.r2.wasm.PageFragmentWebView
import org.readium.r2.shared.InternalReadiumApi

/**
 * Extension function to convert R2EpubPageFragment to PageFragment for WASM calculator.
 */
@OptIn(InternalReadiumApi::class)
internal fun R2EpubPageFragment.toPageFragment(): PageFragment {
    return DefaultPageFragment(
        actualWebView = {
            this.webView?.toPageFragmentWebView()
        },
        actualIsLoaded = isLoaded
    )
}

/**
 * Extension function to convert R2WebView to PageFragmentWebView for WASM calculator.
 */
private fun org.readium.r2.navigator.R2WebView.toPageFragmentWebView(): PageFragmentWebView =
    DefaultPageFragmentWebView(
        actualNumPages = { this.numPages },
        actualCurItem = { this.mCurItem },
        actualRunJavaScript = { script -> this.runJavaScriptSuspend(script) }
    )