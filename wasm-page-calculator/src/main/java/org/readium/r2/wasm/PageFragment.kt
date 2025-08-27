/*
 * Copyright 2024 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.wasm

import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

/**
 * Interface representing a web view that can be used for page calculations.
 * This abstraction allows the WASM page calculator to work with different web view implementations
 * without depending on specific navigator module classes.
 */
public interface PageFragmentWebView {
    /**
     * Number of pages in the current resource when paginated.
     */
    val numPages: Int

    /**
     * Current page index (0-based) when paginated.
     */
    val mCurItem: Int

    /**
     * Execute JavaScript code and get result asynchronously.
     */
    suspend fun runJavaScriptSuspend(javascript: String): String
}

/**
 * Interface representing a fragment that contains a web view for rendering EPUB pages.
 * This abstraction removes the dependency on navigator-specific fragment implementations.
 */
public interface PageFragment {
    /**
     * The web view contained in this fragment, if available.
     */
    val webView: PageFragmentWebView?

    /**
     * Flow indicating whether the fragment content is fully loaded.
     */
    val isLoaded: StateFlow<Boolean>
}

/**
 * Default implementation that wraps an existing object with the required methods.
 * This allows integration with existing navigator fragments without code changes.
 */
public class DefaultPageFragmentWebView(
    private val actualNumPages: () -> Int,
    private val actualCurItem: () -> Int,
    private val actualRunJavaScript: suspend (String) -> String
) : PageFragmentWebView {

    override val numPages: Int
        get() = actualNumPages()

    override val mCurItem: Int
        get() = actualCurItem()

    override suspend fun runJavaScriptSuspend(javascript: String): String {
        return actualRunJavaScript(javascript)
    }
}

/**
 * Default implementation that wraps an existing fragment object.
 */
public class DefaultPageFragment(
    private val actualWebView: () -> PageFragmentWebView?,
    private val actualIsLoaded: StateFlow<Boolean>
) : PageFragment {

    override val webView: PageFragmentWebView?
        get() = actualWebView()

    override val isLoaded: StateFlow<Boolean>
        get() = actualIsLoaded
}