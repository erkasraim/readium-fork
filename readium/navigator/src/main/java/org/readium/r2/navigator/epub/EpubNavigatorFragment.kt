/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(InternalReadiumApi::class)

package org.readium.r2.navigator.epub

import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.LayoutDirection
import android.util.Log
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.collection.forEach
import androidx.collection.size
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.reflect.KClass
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorationId
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.NavigatorFragment
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.R
import org.readium.r2.navigator.R2BasicWebView
import org.readium.r2.navigator.RestorationNotSupportedException
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.Selection
import org.readium.r2.navigator.databinding.ReadiumNavigatorViewpagerBinding
import org.readium.r2.navigator.dummyPublication
import org.readium.r2.navigator.epub.EpubNavigatorViewModel.RunScriptCommand
import org.readium.r2.navigator.epub.css.FontFamilyDeclaration
import org.readium.r2.navigator.epub.css.MutableFontFamilyDeclaration
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.epub.css.buildFontFamilyDeclaration
import org.readium.r2.navigator.extensions.normalizeLocator
import org.readium.r2.navigator.extensions.optRectF
import org.readium.r2.navigator.extensions.positionsByResource
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.input.CompositeInputListener
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.KeyInterceptorView
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pager.R2EpubPageFragment
import org.readium.r2.navigator.pager.R2PagerAdapter
import org.readium.r2.navigator.pager.R2PagerAdapter.PageResource
import org.readium.r2.navigator.pager.R2ViewPager
import org.readium.r2.navigator.preferences.Configurable
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.util.createFragmentFactory
import org.readium.r2.shared.DelicateReadiumApi
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.extensions.tryOrLog
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.toAbsoluteUrl

/**
 * Factory for a [JavascriptInterface] which will be injected in the web views.
 *
 * Return `null` if you don't want to inject the interface for the given resource.
 */
public typealias JavascriptInterfaceFactory = (resource: Link) -> Any?

/**
 * Navigator for EPUB publications.
 *
 * To use this [Fragment], create a factory with `EpubNavigatorFragment.createFactory()`.
 */
@OptIn(ExperimentalReadiumApi::class, DelicateReadiumApi::class)
public class EpubNavigatorFragment internal constructor(
    publication: Publication,
    private val initialLocator: Locator?,
    readingOrder: List<Link>?,
    private val initialPreferences: EpubPreferences,
    internal val listener: Listener?,
    internal val paginationListener: PaginationListener?,
    epubLayout: EpubLayout,
    private val defaults: EpubDefaults,
    configuration: Configuration,
) : NavigatorFragment(publication),
    OverflowableNavigator,
    SelectableNavigator,
    DecorableNavigator,
    HyperlinkNavigator,
    Configurable<EpubSettings, EpubPreferences> {

    // Make a copy to prevent the user from modifying the configuration after initialization.
    internal val config: Configuration = configuration.copy().apply {
        servedAssets += "readium/.*"

        addFontFamilyDeclaration(FontFamily.OPEN_DYSLEXIC) {
            addFontFace {
                addSource("readium/fonts/OpenDyslexic-Regular.otf")
            }
        }
    }

    // WASM-based page calculation
    private val wasmPageCalculator: WasmPageCalculator by lazy {
        DefaultWasmPageCalculator(requireContext())
    }
    private val _totalPagesFlow = MutableStateFlow<Int?>(null)

    /**
     * Flow of total pages count for the entire publication.
     * Null indicates the count is still being calculated.
     */
    public val totalPages: StateFlow<Int?> = _totalPagesFlow

    /**
     * Get current reading progress as percentage (0.0 to 1.0).
     * Returns null if total pages not yet calculated.
     */
    public fun getCurrentReadingProgress(): Double? {
        val total = _totalPagesFlow.value ?: return null
        if (total <= 0) return 0.0

        val currentFragment = currentReflowablePageFragment
        val currentPageInResource = currentFragment?.webView?.mCurItem ?: 0

        // Calculate pages before current resource
        var pagesBefore = 0
        for (i in 0 until currentPagerPosition) {
            val fragment = fragmentAt(i) as? R2EpubPageFragment
            pagesBefore += fragment?.webView?.numPages ?: 1
        }

        val currentAbsolutePage = pagesBefore + currentPageInResource + 1
        return (currentAbsolutePage.toDouble() / total).coerceIn(0.0, 1.0)
    }

    public data class Configuration internal constructor(

        /**
         * Patterns for asset paths which will be available to EPUB resources under
         * https://readium/assets/.
         *
         * The patterns can use simple glob wildcards, see:
         * https://developer.android.com/reference/android/os/PatternMatcher#PATTERN_SIMPLE_GLOB
         *
         * Use .* to serve all app assets.
         */
        @ExperimentalReadiumApi
        var servedAssets: List<String>,

        /**
         * Readium CSS reading system settings.
         *
         * See https://readium.org/readium-css/docs/CSS19-api.html#reading-system-styles
         */
        @ExperimentalReadiumApi
        var readiumCssRsProperties: RsProperties,

        /**
         * When disabled, the Android web view's `WebSettings.textZoom` will be used to adjust the
         * font size, instead of using the Readium CSS's `--USER__fontSize` variable.
         *
         * `WebSettings.textZoom` will work with more publications than `--USER__fontSize`, even the
         * ones poorly authored. However, the page width is not adjusted when changing the font
         * size to keep the optimal line length.
         *
         * See:
         *   - https://github.com/readium/mobile/issues/5
         *   - https://github.com/readium/mobile/issues/1#issuecomment-652431984
         */
        @ExperimentalReadiumApi
        @DelicateReadiumApi
        var useReadiumCssFontSize: Boolean = true,

        /**
         * Supported HTML decoration templates.
         */
        var decorationTemplates: HtmlDecorationTemplates,

        /**
         * Indicates if a user can swipe to change resources when scroll is enabled.
         */
        var disablePageTurnsWhileScrolling: Boolean,

        /**
         * Custom [ActionMode.Callback] to be used when the user selects content.
         *
         * Provide one if you want to customize the selection context menu items.
         */
        var selectionActionModeCallback: ActionMode.Callback?,

        /**
         * Whether padding accounting for display cutouts should be applied.
         */
        var shouldApplyInsetsPadding: Boolean?,

        /**
         * Disable user selection if the publication is protected by a DRM (e.g. with LCP).
         *
         * WARNING: If you choose to disable this, you MUST remove the Copy and Share selection
         * menu items in your app. Otherwise, you will void the EDRLab certification for your
         * application. If you need help, follow up on:
         * https://github.com/readium/kotlin-toolkit/issues/299#issuecomment-1315643577
         */
        @DelicateReadiumApi
        var disableSelectionWhenProtected: Boolean,

        internal var fontFamilyDeclarations: List<FontFamilyDeclaration>,
        internal var javascriptInterfaces: Map<String, JavascriptInterfaceFactory>,
    ) {
        public constructor(
            servedAssets: List<String> = emptyList(),
            readiumCssRsProperties: RsProperties = RsProperties(),
            decorationTemplates: HtmlDecorationTemplates = HtmlDecorationTemplates.defaultTemplates(),
            disablePageTurnsWhileScrolling: Boolean = false,
            selectionActionModeCallback: ActionMode.Callback? = null,
            shouldApplyInsetsPadding: Boolean? = true,
        ) : this(
            servedAssets = servedAssets,
            readiumCssRsProperties = readiumCssRsProperties,
            decorationTemplates = decorationTemplates,
            disablePageTurnsWhileScrolling = disablePageTurnsWhileScrolling,
            selectionActionModeCallback = selectionActionModeCallback,
            shouldApplyInsetsPadding = shouldApplyInsetsPadding,
            disableSelectionWhenProtected = true,
            fontFamilyDeclarations = emptyList(),
            javascriptInterfaces = emptyMap()
        )

        /**
         * Registers a new factory for the [JavascriptInterface] named [name].
         *
         * Return `null` in [factory] to prevent adding the Javascript interface for a given
         * resource.
         */
        public fun registerJavascriptInterface(name: String, factory: JavascriptInterfaceFactory) {
            javascriptInterfaces += name to factory
        }

        /**
         * Adds a declaration for [fontFamily] using [builderAction].
         *
         * @param alternates Specifies a list of alternative font families used as fallbacks when
         * symbols are missing from [fontFamily].
         */
        @ExperimentalReadiumApi
        public fun addFontFamilyDeclaration(
            fontFamily: FontFamily,
            alternates: List<FontFamily> = emptyList(),
            builderAction: (MutableFontFamilyDeclaration).() -> Unit,
        ) {
            fontFamilyDeclarations += buildFontFamilyDeclaration(
                fontFamily = fontFamily.name,
                alternates = alternates.map { it.name },
                builderAction = builderAction
            )
        }

        public companion object {
            public operator fun invoke(builder: Configuration.() -> Unit): Configuration =
                Configuration().apply(builder)
        }
    }

    public interface PaginationListener {
        public fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {}
        public fun onPageLoaded() {}
    }

    public interface Listener : OverflowableNavigator.Listener, HyperlinkNavigator.Listener

    private sealed class State {
        /** The navigator just started and didn't load any resource yet. */
        data object Initializing : State()

        /** The navigator is loading the first resource at `initialResourceHref`. */
        data class Loading(val initialResourceHref: Url) : State()

        /** The navigator is fully initialized and ready for action. */
        data object Ready : State()
    }

    private var state: State = State.Initializing

    // Configurable

    override val settings: StateFlow<EpubSettings> get() = viewModel.settings

    override fun submitPreferences(preferences: EpubPreferences) {
        viewModel.submitPreferences(preferences)
    }

    /**
     * Evaluates the given JavaScript on the currently visible HTML resource.
     *
     * Note that this only work with reflowable resources.
     */
    public suspend fun evaluateJavascript(script: String): String? {
        val page = currentReflowablePageFragment ?: return null
        page.awaitLoaded()
        return page.runJavaScriptSuspend(script)
    }

    private val viewModel: EpubNavigatorViewModel by viewModels {
        EpubNavigatorViewModel.createFactory(
            requireActivity().application,
            publication,
            config = this.config,
            initialPreferences = initialPreferences,
            listener = listener,
            layout = epubLayout,
            defaults = defaults
        )
    }

    private val readingOrder: List<Link> = readingOrder ?: publication.readingOrder

    private val positionsByReadingOrder: List<List<Locator>> =
        if (readingOrder != null) {
            emptyList()
        } else {
            runBlocking { publication.positionsByReadingOrder() }
        }

    internal lateinit var positions: List<Locator>

    internal lateinit var resourcePager: R2ViewPager

    private lateinit var resourcesSingle: List<PageResource>
    private lateinit var resourcesDouble: List<PageResource>

    internal var currentPagerPosition: Int = 0
    internal lateinit var adapter: R2PagerAdapter
    private lateinit var currentActivity: FragmentActivity

    private var _binding: ReadiumNavigatorViewpagerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        currentActivity = requireActivity()
        _binding = ReadiumNavigatorViewpagerBinding.inflate(inflater, container, false)
        var view: View = binding.root

        positions = positionsByReadingOrder.flatten()

        when (viewModel.layout) {
            EpubLayout.REFLOWABLE -> {
                resourcesSingle = readingOrder.mapIndexed { index, link ->
                    PageResource.EpubReflowable(
                        link = link,
                        url = viewModel.urlTo(link),
                        positionCount = positionsByReadingOrder.getOrNull(index)?.size ?: 0
                    )
                }
            }

            EpubLayout.FIXED -> {
                val resourcesSingle = mutableListOf<PageResource>()
                val resourcesDouble = mutableListOf<PageResource>()

                // TODO needs work, currently showing two resources for fxl, needs to understand which two resources, left & right, or only right etc.
                var doublePageLeft: Link? = null
                var doublePageRight: Link?

                for ((index, link) in readingOrder.withIndex()) {
                    val url = viewModel.urlTo(link)
                    resourcesSingle.add(PageResource.EpubFxl(leftLink = link, leftUrl = url))

                    // add first page to the right,
                    if (index == 0) {
                        resourcesDouble.add(PageResource.EpubFxl(rightLink = link, rightUrl = url))
                    } else {
                        // add double pages, left & right
                        if (doublePageLeft == null) {
                            doublePageLeft = link
                        } else {
                            doublePageRight = link
                            resourcesDouble.add(
                                PageResource.EpubFxl(
                                    leftLink = doublePageLeft,
                                    leftUrl = viewModel.urlTo(doublePageLeft),
                                    rightLink = doublePageRight,
                                    rightUrl = viewModel.urlTo(doublePageRight)
                                )
                            )
                            doublePageLeft = null
                        }
                    }
                }
                // add last page if there is only a left page remaining
                if (doublePageLeft != null) {
                    resourcesDouble.add(
                        PageResource.EpubFxl(
                            leftLink = doublePageLeft,
                            leftUrl = viewModel.urlTo(doublePageLeft)
                        )
                    )
                }

                this.resourcesSingle = resourcesSingle
                this.resourcesDouble = resourcesDouble
            }
        }

        resourcePager = binding.resourcePager
        resetResourcePager()

        // Fixed layout publications cannot intercept JS events yet.
        if (publication.metadata.presentation.layout == EpubLayout.FIXED) {
            view = KeyInterceptorView(view, inputListener)
        }

        return view
    }

    private fun resetResourcePager() {
        val parent = requireNotNull(resourcePager.parent as? ConstraintLayout) {
            "The parent view of the EPUB `resourcePager` must be a ConstraintLayout"
        }
        // We need to null out the adapter explicitly, otherwise the page fragments will leak.
        resourcePager.setAdapter(null)
        parent.removeView(resourcePager)

        resourcePager = R2ViewPager(requireContext())
        resourcePager.id = R.id.resourcePager
        resourcePager.publicationType = when (publication.metadata.presentation.layout) {
            EpubLayout.REFLOWABLE, null -> R2ViewPager.PublicationType.EPUB
            EpubLayout.FIXED -> R2ViewPager.PublicationType.FXL
        }

        // Configure ViewPager orientation based on scroll settings
        if (viewModel.layout == EpubLayout.REFLOWABLE && viewModel.settings.value.scroll) {
            resourcePager.configureForVerticalScrolling()
        } else {
            resourcePager.configureForHorizontalPaging()
        }

        resourcePager.setBackgroundColor(viewModel.settings.value.effectiveBackgroundColor)
        // Let the page views handle the keyboard events.
        resourcePager.isFocusable = false
        resourcePager.registerOnPageChangeCallback(PageChangeListener())

        // Set proper ConstraintLayout parameters
        val layoutParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
            ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        ).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
        resourcePager.layoutParams = layoutParams

        parent.addView(resourcePager)

        resetResourcePagerAdapter()
    }

    private inner class PageChangeListener : ViewPager2.OnPageChangeCallback() {
        private var hasPresetScrollPosition = false

        override fun onPageScrollStateChanged(state: Int) {
            when (state) {
                ViewPager2.SCROLL_STATE_IDLE -> {
                    // Reset flag when swipe ends
                    hasPresetScrollPosition = false
                }
                else -> Unit
            }
        }

        override fun onPageScrolled(
            position: Int,
            positionOffset: Float,
            positionOffsetPixels: Int,
        ) {
            // Only process when swipe has started and scroll position hasn't been set yet
            if (positionOffset > 0f && !hasPresetScrollPosition) {
                val currentPosition = resourcePager.currentItem

                // When swiping to previous page (position is less than current)
                if (position < currentPosition) {
                    // Find target page fragment and set to last page
                    val targetFragment = fragmentAt(position) as? R2EpubPageFragment
                    targetFragment?.webView?.let { webView ->
                        if (viewModel.isScrollEnabled.value) {
                            webView.scrollToEnd()
                        } else if (webView.numPages > 1) {
                            if (resourcePager.isRtl()) {
                                webView.setCurrentItem(0, false)
                            } else {
                                webView.setCurrentItem(webView.numPages - 1, false)
                            }
                        }
                    }
                    hasPresetScrollPosition = true
                }
                // When swiping to next page (position + 1 is greater than current)
                else if (position + 1 > currentPosition) {
                    // Find target page fragment and set to first page
                    val targetFragment = fragmentAt(position + 1) as? R2EpubPageFragment
                    targetFragment?.webView?.let { webView ->
                        if (viewModel.isScrollEnabled.value) {
                            webView.scrollToStart()
                        } else if (webView.numPages > 1) {
                            if (resourcePager.isRtl()) {
                                webView.setCurrentItem(webView.numPages - 1, false)
                            } else {
                                webView.setCurrentItem(0, false)
                            }
                        }
                    }

                    hasPresetScrollPosition = true
                }
            }
        }

        override fun onPageSelected(position: Int) {
            currentReflowablePageFragment?.webView?.let { webView ->
                if (viewModel.isScrollEnabled.value) {
                    if (currentPagerPosition < position) {
                        // handle swipe LEFT
                        webView.scrollToStart()
                    } else if (currentPagerPosition > position) {
                        // handle swipe RIGHT
                        webView.scrollToEnd()
                    }
                } else {
                    if (!hasPresetScrollPosition) {
                        if (currentPagerPosition < position) {
                            webView.setCurrentItem(0, false)
                        } else if (currentPagerPosition > position) {
                            webView.setCurrentItem(webView.numPages - 1, false)
                        }
                    }
                }
            }

            currentPagerPosition = position

            notifyCurrentLocation()
        }
    }

    private fun resetResourcePagerAdapter() {
        adapter = when (publication.metadata.presentation.layout) {
            EpubLayout.REFLOWABLE, null -> {
                R2PagerAdapter(this, resourcesSingle)
            }
            EpubLayout.FIXED -> {
                when (viewModel.dualPageMode) {
                    // FIXME: Properly implement DualPage.AUTO depending on the device orientation.
                    DualPage.OFF, DualPage.AUTO -> {
                        R2PagerAdapter(this, resourcesSingle)
                    }
                    DualPage.ON -> {
                        R2PagerAdapter(this, resourcesDouble)
                    }
                }
            }
        }
        adapter.listener = PagerAdapterListener()
        resourcePager.setAdapter(adapter)
        resourcePager.readingProgression = overflow.value.readingProgression
        resourcePager.layoutDirection = when (settings.value.readingProgression) {
            ReadingProgression.RTL -> LayoutDirection.RTL
            ReadingProgression.LTR -> LayoutDirection.LTR
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events
                    .onEach(::handleEvent)
                    .launchIn(this)

                var previousSettings = viewModel.settings.value
                viewModel.settings
                    .onEach {
                        onSettingsChange(previousSettings, it)
                        previousSettings = it
                    }
                    .launchIn(this)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.withStarted {
                // Restore the last locator before a configuration change (e.g. screen rotation), or the
                // initial locator when given.
                val locator = savedInstanceState?.let {
                    BundleCompat.getParcelable(
                        it,
                        "locator",
                        Locator::class.java
                    )
                }
                    ?: initialLocator
                if (locator != null) {
                    go(locator)
                }
            }
        }

        // Initialize WASM calculator and start page calculation
        Log.e("EpubPageCalc", "🔥 onViewCreated에서 페이지 계산 시작!")
        viewLifecycleOwner.lifecycleScope.launch {
            Log.e("EpubPageCalc", "🔥 wasmPageCalculator.initialize() 호출 중...")
            if (wasmPageCalculator.initialize()) {
                Log.e("EpubPageCalc", "🔥 초기화 성공! WASM 테스트 실행...")

                // WASM 연결 테스트
                val testResult = wasmPageCalculator.testWasmConnection()
                Log.e("EpubPageCalc", "🧪 WASM 테스트 결과: $testResult")

                Log.e("EpubPageCalc", "🔥 calculateTotalPages() 호출...")
                calculateTotalPages()
            } else {
                Log.e("EpubPageCalc", "❌ WASM 초기화 실패!")
            }
        }
    }

    /**
     * Initialize WASM page calculator and start total page count calculation.
     */
    private fun initializePageCalculation() {
        Log.e("EpubPageCalc", "🔥 initializePageCalculation() 호출됨!")
        viewLifecycleOwner.lifecycleScope.launch {
            Log.e("EpubPageCalc", "🔥 wasmPageCalculator.initialize() 호출 중...")
            if (wasmPageCalculator.initialize()) {
                Log.e("EpubPageCalc", "🔥 초기화 성공! calculateTotalPages() 호출...")
                calculateTotalPages()
            } else {
                Log.e("EpubPageCalc", "❌ WASM 초기화 실패!")
            }
        }
    }

    /**
     * Calculate total pages using sampling-based approach with WASM.
     */
    private suspend fun calculateTotalPages() {
        Log.e("EpubPageCalc", "🔥 calculateTotalPages() 호출됨!")
        Log.d("EpubPageCalc", "[전체] 전체 EPUB 페이지 계산 시작")

        // Wait for ViewPager and fragments to be created
        var currentFragment: R2EpubPageFragment? = null
        var attempts = 0
        while (currentFragment == null && attempts < 20) {
            delay(500) // Wait 500ms

            // Debug ViewPager state
            val pagerAdapter = r2PagerAdapter
            val hasResourcePager = ::resourcePager.isInitialized
            val adapterItemCount = pagerAdapter?.itemCount ?: -1
            var fragmentsCount = pagerAdapter?.mFragments?.size
            val currentItem = if (hasResourcePager) resourcePager.currentItem else -1

            Log.e("EpubPageCalc", "🔥 시도 ${attempts + 1}:")
            Log.e("EpubPageCalc", "  - resourcePager 초기화됨: $hasResourcePager")
            Log.e("EpubPageCalc", "  - r2PagerAdapter: $pagerAdapter")
            Log.e("EpubPageCalc", "  - adapter.itemCount: $adapterItemCount")
            Log.e("EpubPageCalc", "  - fragments 개수: $fragmentsCount")
            Log.e("EpubPageCalc", "  - currentItem: $currentItem")

            if (hasResourcePager && pagerAdapter != null && currentItem >= 0) {
                val itemId = pagerAdapter.getItemId(currentItem)
                Log.e("EpubPageCalc", "  - itemId($currentItem): $itemId")

                pagerAdapter.mFragments.forEach { key, fragment ->
                    Log.e("EpubPageCalc", "  - fragment[$key]: $fragment")
                }

                currentFragment = pagerAdapter.mFragments[itemId] as? R2EpubPageFragment
            }

            Log.e("EpubPageCalc", "  - 결과 fragment: $currentFragment")
            attempts++
        }

        if (currentFragment == null) {
            Log.e("EpubPageCalc", "❌ 현재 fragment를 찾을 수 없음, WebView fallback 사용")
            calculateTotalPagesWebViewFallback()
            return
        }

        Log.e("EpubPageCalc", "✅ 현재 fragment 찾음: $currentFragment")

        // Wait until the fragment is loaded
        Log.e("EpubPageCalc", "🔥 fragment.isLoaded 체크 중...")
        currentFragment.isLoaded.collect { isLoaded ->
            Log.e("EpubPageCalc", "🔥 fragment.isLoaded = $isLoaded")
            if (isLoaded) {
                Log.d("EpubPageCalc", "[전체] 현재 리소스의 샘플링 데이터 수집 완료, 각 리소스별 페이지 계산 시작")
                performSamplingBasedCalculation(currentFragment)
            }
        }
    }

    /**
     * Perform sampling-based page calculation using first loaded resource.
     */
    private suspend fun performSamplingBasedCalculation(samplingFragment: R2EpubPageFragment) {
        try {
            Log.d("EpubPageCalc", "[전체] 샘플링 데이터 수집 중...")
            // Collect sampling data from the first loaded WebView
            val samplingData = collectSamplingData(samplingFragment)
            Log.d("EpubPageCalc", "[전체] 샘플링 데이터: $samplingData")

            var totalPageCount = 0

            // Process each resource in reading order
            for (link in readingOrder) {
                val resource = publication.get(link)
                Log.d("EpubPageCalc", "[전체] 리소스 페이지 계산: ${link.href}")
                if (resource != null) {
                    val htmlResult = resource.read()
                    htmlResult.getOrNull()?.let { bytes ->
                        val html = String(bytes)
                        val result = wasmPageCalculator.calculatePages(html, samplingData)
                        Log.d(
                            "EpubPageCalc",
                            "[전체] 리소스 결과 status=${result.status} totalPages=${result.totalPages}"
                        )

                        when (result.status) {
                            WasmCalculationResult.Status.SUCCESS -> {
                                Log.d(
                                    "EpubPageCalc",
                                    "[전체] WASM 정상 계산, 페이지 수: ${result.totalPages}"
                                )
                                totalPageCount += result.totalPages
                            }

                            WasmCalculationResult.Status.FALLBACK_NEEDED -> {
                                // Use WebView-based calculation as fallback
                                val count = getWebViewPageCount(link)
                                Log.d(
                                    "EpubPageCalc",
                                    "[전체] WASM Fallback 필요, WebView 기반 계산 페이지 수: $count"
                                )
                                totalPageCount += count
                            }

                            WasmCalculationResult.Status.ERROR -> {
                                val est = estimatePagesByContentSize(html, samplingData)
                                Log.d("EpubPageCalc", "[전체] WASM 계산 오류, 컨텐츠 길이 기반 추정 페이지 수: $est")
                                totalPageCount += est
                            }
                        }
                    }
                }
            }

            Log.d("EpubPageCalc", "[전체] 전체 계산 완료, totalPageCount=$totalPageCount")
            _totalPagesFlow.value = totalPageCount

        } catch (e: Exception) {
            Log.e("EpubPageCalc", "[전체] 예외 발생, WebView 기반 전체 페이지 계산으로 대체", e)
            // Fallback to traditional WebView-based calculation
            calculateTotalPagesWebViewFallback()
        }
    }

    /**
     * Collect sampling data from a loaded WebView fragment.
     */
    private suspend fun collectSamplingData(fragment: R2EpubPageFragment): SamplingData {
        val webView = fragment.webView ?: throw IllegalStateException("WebView not available")

        // Collect metrics via JavaScript
        val metricsJson = webView.runJavaScriptSuspend(
            """
            (function() {
                const style = window.getComputedStyle(document.body);
                const testSpan = document.createElement('span');
                const testText = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ";
                testSpan.textContent = testText;
                testSpan.style.visibility = 'hidden';
                document.body.appendChild(testSpan);
                const rect = testSpan.getBoundingClientRect();
                document.body.removeChild(testSpan);
                
                return JSON.stringify({
                    viewportWidth: window.innerWidth,
                    viewportHeight: window.innerHeight,
                    fontSize: parseFloat(style.fontSize),
                    lineHeight: parseFloat(style.lineHeight) || parseFloat(style.fontSize) * 1.2,
                    characterWidth: rect.width / testText.length,
                    contentWidth: document.body.scrollWidth,
                    contentHeight: document.body.scrollHeight,
                    marginTop: parseFloat(style.marginTop) || 0,
                    marginBottom: parseFloat(style.marginBottom) || 0,
                    marginLeft: parseFloat(style.marginLeft) || 0,
                    marginRight: parseFloat(style.marginRight) || 0
                });
            })();
            """
        )

        Log.d("EpubPageCalc", "[샘플링] JavaScript 결과: $metricsJson")

        // JavaScript가 JSON.stringify()로 이미 문자열로 반환한 결과를 다시 문자열로 감쌌으므로 따옴표 제거
        val cleanedJson = metricsJson.trim().removeSurrounding("\"").replace("\\\"", "\"")
        Log.d("EpubPageCalc", "[샘플링] 정리된 JSON: $cleanedJson")

        val metrics = JSONObject(cleanedJson)

        return SamplingData(
            viewportWidth = metrics.optInt("viewportWidth", 800),
            viewportHeight = metrics.optInt("viewportHeight", 600),
            fontMetrics = FontMetrics(
                fontSize = metrics.optDouble("fontSize", 16.0).toFloat(),
                lineHeight = metrics.optDouble("lineHeight", 19.2).toFloat(),
                characterWidth = metrics.optDouble("characterWidth", 8.0).toFloat()
            ),
            layoutMetrics = LayoutMetrics(
                contentWidth = metrics.optInt("contentWidth", 800),
                contentHeight = metrics.optInt("contentHeight", 600),
                marginTop = metrics.optInt("marginTop", 0),
                marginBottom = metrics.optInt("marginBottom", 0),
                marginLeft = metrics.optInt("marginLeft", 0),
                marginRight = metrics.optInt("marginRight", 0)
            )
        )
    }

    /**
     * Get page count for a specific resource using WebView (fallback method).
     */
    private fun getWebViewPageCount(link: Link): Int {
        val fragment = loadedFragmentForHref(link.url())
        val pages = fragment?.webView?.numPages ?: 1
        Log.d("EpubPageCalc", "[WebView] ${link.href} -> $pages 페이지 (WebView 기반)")
        return pages
    }

    /**
     * Estimate pages by content size when WASM calculation fails.
     */
    private fun estimatePagesByContentSize(html: String, samplingData: SamplingData): Int {
        val textContent = html.replace(Regex("<[^>]+>"), "")
        val contentLength = textContent.length

        val charsPerLine =
            (samplingData.layoutMetrics.contentWidth / samplingData.fontMetrics.characterWidth).toInt()
        val linesPerPage =
            (samplingData.layoutMetrics.contentHeight / samplingData.fontMetrics.lineHeight).toInt()
        val charsPerPage = charsPerLine * linesPerPage

        val estimatedPages = if (charsPerPage > 0) {
            kotlin.math.max(1, (contentLength / charsPerPage))
        } else {
            1
        }

        Log.d(
            "EpubPageCalc",
            "[추정] 텍스트 길이: $contentLength, 페이지당 글자 수: $charsPerPage -> $estimatedPages 페이지"
        )
        return estimatedPages
    }

    /**
     * Fallback to traditional WebView-based total page calculation.
     */
    private suspend fun calculateTotalPagesWebViewFallback() {
        Log.d("EpubPageCalc", "[폴백] WebView 기반 전체 페이지 계산 시작")
        // Traditional approach - sum up all WebView page counts
        var totalPages = 0
        for (i in readingOrder.indices) {
            val fragment = fragmentAt(i) as? R2EpubPageFragment
            val pages = fragment?.webView?.numPages ?: 1
            Log.d("EpubPageCalc", "[폴백] 리소스 인덱스 $i -> $pages 페이지")
            totalPages += pages
        }
        Log.d("EpubPageCalc", "[폴백] WebView 기반 총 페이지: $totalPages")
        _totalPagesFlow.value = totalPages
    }

    private fun handleEvent(event: EpubNavigatorViewModel.Event) {
        when (event) {
            is EpubNavigatorViewModel.Event.RunScript -> {
                run(event.command)
            }
            is EpubNavigatorViewModel.Event.OpenInternalLink -> {
                go(event.target)
            }
            EpubNavigatorViewModel.Event.InvalidateViewPager -> {
                invalidateResourcePager()
            }
        }
    }

    private fun invalidateResourcePager() {
        val locator = currentLocator.value
        resetResourcePager()
        go(locator)
    }

    private fun onSettingsChange(previous: EpubSettings, new: EpubSettings) {
        if (previous.effectiveBackgroundColor != new.effectiveBackgroundColor) {
            resourcePager.setBackgroundColor(new.effectiveBackgroundColor)
        }

        if (viewModel.layout == EpubLayout.REFLOWABLE) {
            if (previous.fontSize != new.fontSize) {
                r2PagerAdapter?.setFontSize(new.fontSize)
            }
        }
    }

    private fun R2PagerAdapter.setFontSize(fontSize: Double) {
        if (config.useReadiumCssFontSize) return

        mFragments.forEach { _, fragment ->
            (fragment as? R2EpubPageFragment)?.setFontSize(fontSize)
        }
    }

    private inner class PagerAdapterListener : R2PagerAdapter.Listener {
        override fun onCreatePageFragment(fragment: Fragment) {
            if (viewModel.layout == EpubLayout.REFLOWABLE) {
                if (!config.useReadiumCssFontSize) {
                    (fragment as? R2EpubPageFragment)?.setFontSize(settings.value.fontSize)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("locator", currentLocator.value)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()

        if (publication == dummyPublication) {
            throw RestorationNotSupportedException
        }

        notifyCurrentLocation()
    }

    @OptIn(DelicateReadiumApi::class)
    override fun go(locator: Locator, animated: Boolean): Boolean {
        @Suppress("NAME_SHADOWING")
        val locator = publication.normalizeLocator(locator)

        if (state == State.Initializing) {
            state = State.Loading(locator.href)
        }

        listener?.onJumpToLocator(locator)

        val href = locator.href.removeFragment()

        fun setCurrent(resources: List<PageResource>) {
            val page = resources.withIndex().firstOrNull { (_, res) ->
                when (res) {
                    is PageResource.EpubReflowable ->
                        res.link.url().isEquivalent(href)
                    is PageResource.EpubFxl ->
                        res.leftUrl?.toString()?.endsWith(href.toString()) == true || res.rightUrl?.toString()?.endsWith(
                            href.toString()
                        ) == true
                    else -> false
                }
            } ?: return

            val (index, _) = page

            if (resourcePager.currentItem != index) {
                resourcePager.setCurrentItem(index, false)
            }

            r2PagerAdapter?.loadLocatorAt(index, locator)
        }

        if (publication.metadata.presentation.layout != EpubLayout.FIXED) {
            setCurrent(resourcesSingle)
        } else {
            when (viewModel.dualPageMode) {
                // FIXME: Properly implement DualPage.AUTO depending on the device orientation.
                DualPage.OFF, DualPage.AUTO -> {
                    setCurrent(resourcesSingle)
                }
                DualPage.ON -> {
                    setCurrent(resourcesDouble)
                }
            }
        }

        return true
    }

    override fun go(link: Link, animated: Boolean): Boolean {
        val locator = publication.locatorFromLink(link) ?: return false
        return go(locator, animated)
    }

    private fun run(commands: List<RunScriptCommand>) {
        commands.forEach { run(it) }
    }

    private fun run(command: RunScriptCommand) {
        when (command.scope) {
            RunScriptCommand.Scope.CurrentResource -> {
                currentReflowablePageFragment
                    ?.runJavaScript(command.script)
            }
            RunScriptCommand.Scope.LoadedResources -> {
                r2PagerAdapter?.mFragments?.forEach { _, fragment ->
                    (fragment as? R2EpubPageFragment)
                        ?.takeIf { it.isLoaded.value }
                        ?.runJavaScript(command.script)
                }
            }
            is RunScriptCommand.Scope.LoadedResource -> {
                loadedFragmentForHref(command.scope.href)
                    ?.takeIf { it.isLoaded.value }
                    ?.runJavaScript(command.script)
            }
            is RunScriptCommand.Scope.WebView -> {
                command.scope.webView.runJavaScript(command.script)
            }
        }
    }

    // VisualNavigator

    override val publicationView: View
        get() = requireView()

    override val overflow: StateFlow<OverflowableNavigator.Overflow>
        get() = viewModel.overflow

    private val inputListener = CompositeInputListener()

    override fun addInputListener(listener: InputListener) {
        inputListener.add(listener)
    }

    override fun removeInputListener(listener: InputListener) {
        inputListener.remove(listener)
    }

    // SelectableNavigator

    override suspend fun currentSelection(): Selection? {
        val fragment = currentReflowablePageFragment ?: return null
        val json =
            fragment.runJavaScriptSuspend("readium.getCurrentSelection();")
                .takeIf { it != "null" }
                ?.let { tryOrLog { JSONObject(it) } }
                ?: return null

        val rect = json.optRectF("rect")
            ?.run { adjustedToViewport() }

        return Selection(
            locator = currentLocator.value.copy(
                text = Locator.Text.fromJSON(json.optJSONObject("text"))
            ),
            rect = rect
        )
    }

    override fun clearSelection() {
        run(viewModel.clearSelection())
    }

    private fun PointF.adjustedToViewport(): PointF =
        currentReflowablePageFragment?.paddingTop?.let { top ->
            PointF(x, y + top)
        } ?: this

    private fun RectF.adjustedToViewport(): RectF =
        currentReflowablePageFragment?.paddingTop?.let { topOffset ->
            RectF(left, top + topOffset, right, bottom)
        } ?: this

    // DecorableNavigator

    override fun <T : Decoration.Style> supportsDecorationStyle(style: KClass<T>): Boolean =
        viewModel.supportsDecorationStyle(style)

    override fun addDecorationListener(group: String, listener: DecorableNavigator.Listener) {
        viewModel.addDecorationListener(group, listener)
    }

    override fun removeDecorationListener(listener: DecorableNavigator.Listener) {
        viewModel.removeDecorationListener(listener)
    }

    @OptIn(DelicateReadiumApi::class)
    override suspend fun applyDecorations(decorations: List<Decoration>, group: String) {
        @Suppress("NAME_SHADOWING")
        val decorations = decorations
            .map { it.copy(locator = publication.normalizeLocator(it.locator)) }

        run(viewModel.applyDecorations(decorations, group))
    }

    // R2BasicWebView.Listener

    internal val webViewListener: R2BasicWebView.Listener = WebViewListener()

    private inner class WebViewListener : R2BasicWebView.Listener {

        override val readingProgression: ReadingProgression
            get() = viewModel.readingProgression

        override val verticalText: Boolean
            get() = viewModel.verticalText

        override fun onResourceLoaded(webView: R2BasicWebView, link: Link) {
            run(viewModel.onResourceLoaded(webView, link))
        }

        override fun onPageLoaded(webView: R2BasicWebView, link: Link) {
            paginationListener?.onPageLoaded()

            val href = link.url()
            if (state is State.Initializing || (state as? State.Loading)?.initialResourceHref?.isEquivalent(
                    href
                ) == true
            ) {
                state = State.Ready
            }

            notifyCurrentLocation()
        }

        override fun javascriptInterfacesForResource(link: Link): Map<String, Any?> =
            config.javascriptInterfaces.mapValues { (_, factory) -> factory(link) }

        override fun onTap(point: PointF): Boolean =
            inputListener.onTap(TapEvent(point))

        override fun onDragStart(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.Start, event)

        override fun onDragMove(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.Move, event)

        override fun onDragEnd(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.End, event)

        private fun onDrag(type: DragEvent.Type, event: R2BasicWebView.DragEvent): Boolean =
            inputListener.onDrag(
                DragEvent(
                    type = type,
                    start = event.startPoint.adjustedToViewport(),
                    offset = event.offset
                )
            )

        override fun onKey(event: KeyEvent): Boolean =
            inputListener.onKey(event)

        override fun onDecorationActivated(
            id: DecorationId,
            group: String,
            rect: RectF,
            point: PointF,
        ): Boolean =
            viewModel.onDecorationActivated(
                id = id,
                group = group,
                rect = rect.adjustedToViewport(),
                point = point.adjustedToViewport()
            )

        override fun onProgressionChanged() {
            notifyCurrentLocation()
        }

        override fun goToPreviousResource(jump: Boolean, animated: Boolean): Boolean {
            return this@EpubNavigatorFragment.goToPreviousResource(jump = jump, animated = animated)
        }

        override fun goToNextResource(jump: Boolean, animated: Boolean): Boolean {
            return this@EpubNavigatorFragment.goToNextResource(jump = jump, animated = animated)
        }

        override val selectionActionModeCallback: ActionMode.Callback?
            get() = config.selectionActionModeCallback

        /**
         * Prevents opening external links in the web view and handles internal links.
         */
        override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toAbsoluteUrl() ?: return false
            viewModel.navigateToUrl(url)
            return true
        }

        override fun onFootnoteLinkActivated(
            url: AbsoluteUrl,
            context: HyperlinkNavigator.FootnoteContext,
        ) {
            viewModel.navigateToUrl(url, context)
        }

        override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? =
            viewModel.shouldInterceptRequest(request)

        override fun resourceAtUrl(url: Url): Resource? =
            viewModel.internalLinkFromUrl(url)
                ?.let { publication.get(it) }
    }

    override fun goForward(animated: Boolean): Boolean {
        if (publication.metadata.presentation.layout == EpubLayout.FIXED) {
            return goToNextResource(jump = false, animated = animated)
        }

        val webView = currentReflowablePageFragment?.webView ?: return false

        when (settings.value.readingProgression) {
            ReadingProgression.LTR ->
                webView.scrollRight(animated)

            ReadingProgression.RTL ->
                webView.scrollLeft(animated)
        }
        return true
    }

    override fun goBackward(animated: Boolean): Boolean {
        if (publication.metadata.presentation.layout == EpubLayout.FIXED) {
            return goToPreviousResource(jump = false, animated = animated)
        }

        val webView = currentReflowablePageFragment?.webView ?: return false

        when (settings.value.readingProgression) {
            ReadingProgression.LTR ->
                webView.scrollLeft(animated)

            ReadingProgression.RTL ->
                webView.scrollRight(animated)
        }
        return true
    }

    private fun goToNextResource(jump: Boolean, animated: Boolean): Boolean {
        val adapter = resourcePager.adapter ?: return false
        if (resourcePager.currentItem >= adapter.itemCount - 1) {
            return false
        }

        if (jump) {
            locatorToNextResource()?.let { listener?.onJumpToLocator(it) }
        }

        resourcePager.setCurrentItem(resourcePager.currentItem + 1, animated)

        currentReflowablePageFragment?.webView?.let { webView ->
            if (settings.value.readingProgression == ReadingProgression.RTL) {
                webView.setCurrentItem(webView.numPages - 1, false)
            } else {
                webView.setCurrentItem(0, false)
            }
        }

        return true
    }

    private fun goToPreviousResource(jump: Boolean, animated: Boolean): Boolean {
        if (resourcePager.currentItem <= 0) {
            return false
        }

        if (jump) {
            locatorToPreviousResource()?.let { listener?.onJumpToLocator(it) }
        }

        resourcePager.setCurrentItem(resourcePager.currentItem - 1, animated)

        currentReflowablePageFragment?.webView?.let { webView ->
            if (settings.value.readingProgression == ReadingProgression.RTL) {
                webView.setCurrentItem(0, false)
            } else {
                webView.setCurrentItem(webView.numPages - 1, false)
            }
        }

        return true
    }

    private fun locatorToPreviousResource(): Locator? =
        locatorToResourceAtIndex(resourcePager.currentItem - 1)

    private fun locatorToNextResource(): Locator? =
        locatorToResourceAtIndex(resourcePager.currentItem + 1)

    private fun locatorToResourceAtIndex(index: Int): Locator? =
        readingOrder.getOrNull(index)
            ?.let { publication.locatorFromLink(it) }

    private val r2PagerAdapter: R2PagerAdapter?
        get() = if (::resourcePager.isInitialized) {
            resourcePager.adapter as? R2PagerAdapter
        } else {
            null
        }

    private val currentReflowablePageFragment: R2EpubPageFragment? get() =
        currentFragment as? R2EpubPageFragment

    private val currentFragment: Fragment? get() =
        fragmentAt(resourcePager.currentItem)

    private fun fragmentAt(index: Int): Fragment? =
        r2PagerAdapter?.mFragments?.get(adapter.getItemId(index))

    /**
     * Returns the reflowable page fragment matching the given href, if it is already loaded in the
     * view pager.
     */
    private fun loadedFragmentForHref(href: Url): R2EpubPageFragment? {
        val adapter = r2PagerAdapter ?: return null
        adapter.mFragments.forEach { _, fragment ->
            val pageFragment = fragment as? R2EpubPageFragment ?: return@forEach
            val link = pageFragment.link ?: return@forEach
            if (link.url() == href) {
                return pageFragment
            }
        }
        return null
    }

    override val currentLocator: StateFlow<Locator> get() = _currentLocator
    private val _currentLocator = MutableStateFlow(
        initialLocator
            ?: requireNotNull(publication.locatorFromLink(this.readingOrder.first()))
    )

    /**
     * Returns the [Locator] to the first HTML *block* element that is visible on the screen, even
     * if it begins on previous screen pages.
     */
    @ExperimentalReadiumApi
    override suspend fun firstVisibleElementLocator(): Locator? {
        if (!::resourcePager.isInitialized) return null

        return when (viewModel.layout) {
            EpubLayout.FIXED ->
                currentLocator.value

            EpubLayout.REFLOWABLE -> {
                val resource = readingOrder[resourcePager.currentItem]
                currentReflowablePageFragment?.webView?.findFirstVisibleLocator()
                    ?.copy(
                        href = resource.url(),
                        mediaType = resource.mediaType ?: MediaType.XHTML
                    )
            }
        }
    }

    /**
     * While scrolling we receive a lot of new current locations, so we use a coroutine job to
     * debounce the notification.
     */
    private var debounceLocationNotificationJob: Job? = null

    /**
     * Mapping between reading order hrefs and the table of contents title.
     */
    private val tableOfContentsTitleByHref: Map<Href, String> by lazy {
        fun fulfill(linkList: List<Link>): MutableMap<Href, String> {
            var result: MutableMap<Href, String> = mutableMapOf()

            for (link in linkList) {
                val title = link.title ?: ""

                if (title.isNotEmpty()) {
                    result[link.href] = title
                }

                val subResult = fulfill(link.children)

                result = (subResult + result) as MutableMap<Href, String>
            }

            return result
        }

        fulfill(publication.tableOfContents).toMap()
    }

    private fun notifyCurrentLocation() {
        // Make sure viewLifecycleOwner is accessible.
        view ?: return

        debounceLocationNotificationJob?.cancel()
        debounceLocationNotificationJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(100L)

            // We don't want to notify the current location if the navigator is still loading a
            // locator, to avoid notifying intermediate locations.
            if (currentReflowablePageFragment?.isLoaded?.value == false || state != State.Ready) {
                return@launch
            }

            val reflowableWebView = currentReflowablePageFragment?.webView
            val progression = reflowableWebView?.run {
                // The transition has stabilized, so we can ask the web view to refresh its current
                // item to reflect the current scroll position.
                updateCurrentItem()
                progression.coerceIn(0.0, 1.0)
            } ?: 0.0

            val link = when (val pageResource = adapter.getResource(resourcePager.currentItem)) {
                is PageResource.EpubFxl -> checkNotNull(
                    pageResource.leftLink ?: pageResource.rightLink
                )
                is PageResource.EpubReflowable -> pageResource.link
                else -> throw IllegalStateException(
                    "Expected EpubFxl or EpubReflowable page resources"
                )
            }
            val positionLocator = publication.positionsByResource[link.url()]?.let { positions ->
                val index = ceil(progression * (positions.size - 1)).toInt()
                positions.getOrNull(index)
            }

            val currentLocator = Locator(
                href = link.url(),
                mediaType = link.mediaType ?: MediaType.XHTML,
                title = tableOfContentsTitleByHref[link.href] ?: positionLocator?.title ?: link.title,
                locations = (positionLocator?.locations ?: Locator.Locations()).copy(
                    progression = progression
                ),
                text = positionLocator?.text ?: Locator.Text()
            )

            _currentLocator.value = currentLocator

            // Deprecated notifications
            reflowableWebView?.let {
                paginationListener?.onPageChanged(
                    pageIndex = it.mCurItem,
                    totalPages = it.numPages,
                    locator = currentLocator
                )
            }
        }
    }

    public companion object {

        /**
         * Creates a factory for a dummy [EpubNavigatorFragment].
         *
         * Used when Android restore the [EpubNavigatorFragment] after the process was killed. You
         * need to make sure the fragment is removed from the screen before [onResume] is called.
         */
        public fun createDummyFactory(): FragmentFactory = createFragmentFactory {
            EpubNavigatorFragment(
                publication = dummyPublication,
                initialLocator = Locator(href = Url("#")!!, mediaType = MediaType.XHTML),
                readingOrder = null,
                initialPreferences = EpubPreferences(),
                listener = null,
                paginationListener = null,
                epubLayout = EpubLayout.REFLOWABLE,
                defaults = EpubDefaults(),
                configuration = Configuration()
            )
        }

        /**
         * Returns a URL to the application asset at [path], served in the web views.
         *
         * Returns null if the given [path] is not valid or an absolute URL.
         */
        public fun assetUrl(path: String): Url? =
            WebViewServer.assetUrl(path)
    }

    override fun onDestroyView() {
        // WASM 모듈 리소스 정리
        wasmPageCalculator.destroy()

        super.onDestroyView()
    }
}

@ExperimentalReadiumApi
private val EpubSettings.effectiveBackgroundColor: Int get() =
    backgroundColor?.int ?: theme.backgroundColor
