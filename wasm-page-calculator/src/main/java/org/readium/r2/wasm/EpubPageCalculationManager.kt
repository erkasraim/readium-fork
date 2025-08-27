/*
 * Copyright 2024 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.wasm

import android.content.Context
import android.util.Log
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.json.JSONArray
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Link
import java.net.URI
import org.readium.r2.shared.publication.filterByMediaTypes
import org.readium.r2.shared.publication.flatten
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Manager for calculating total pages in EPUB publications using WASM-based approach.
 */
@OptIn(InternalReadiumApi::class)
public class EpubPageCalculationManager(
    private val context: Context,
    private val publication: Publication,
    private val readingOrder: List<Link>
) {
    public companion object {
        private const val TAG = "EpubPageCalc"
    }

    // Debug logging flag to control log emission
    private var debugLogsEnabled: Boolean = false

    // Allows callers to toggle debug logging
    public fun setDebugLogging(enabled: Boolean) {
        debugLogsEnabled = enabled
    }

    // Logging helpers to avoid building strings when disabled
    private inline fun logD(forcePrint: Boolean = false, crossinline message: () -> String) {
        if (debugLogsEnabled || forcePrint) Log.d(TAG, message())
    }

    private inline fun logI(forcePrint: Boolean = false, crossinline message: () -> String) {
        if (debugLogsEnabled || forcePrint) Log.i(TAG, message())
    }

    private inline fun logE(forcePrint: Boolean = false, crossinline message: () -> String) {
        if (debugLogsEnabled || forcePrint) Log.e(TAG, message())
    }

    private inline fun logW(forcePrint: Boolean = false, crossinline message: () -> String) {
        if (debugLogsEnabled || forcePrint) Log.w(TAG, message())
    }

    private val wasmPageCalculator: WasmPageCalculator by lazy {
        DefaultWasmPageCalculator(context).also { calculator ->
            calculator.enableDebugLogs = debugLogsEnabled
        }
    }

    /**
     * Flow of total pages count for the entire publication.
     * Null indicates the count is still being calculated.
     */
    private val _totalPagesFlow = MutableStateFlow<Int?>(null)
    public val totalPages: StateFlow<Int?> = _totalPagesFlow

    // Exposes per-resource page counts as they are computed: href -> pages
    private val _pagesByHrefFlow = MutableStateFlow<MutableMap<String, Int>>(mutableMapOf())
    public val pagesByHref: StateFlow<Map<String, Int>> = _pagesByHrefFlow

    // Exposes per-reading-order index page counts; aligned with `readingOrder`
    private val _pagesByIndexFlow = MutableStateFlow(MutableList(readingOrder.size) { 0 })
    public val pagesByIndex: StateFlow<List<Int>> = _pagesByIndexFlow

    // Cache of image dimensions for all EPUB-internal images. Built once, reused.
    private var imageDimensionsCache: JSONObject? = null

    // Cache: whether CSS registry has been registered to WASM for this publication.
    private var cssRegistryRegistered: Boolean = false

    // Synthetic registry keys for app-asset Readium CSS
    private val REGKEY_ASSET_BEFORE = "@assets/readium/readium-css/ReadiumCSS-before.css"
    private val REGKEY_ASSET_AFTER = "@assets/readium/readium-css/ReadiumCSS-after.css"
    private val ASSET_PATH_BEFORE = "readium/readium-css/ReadiumCSS-before.css"
    private val ASSET_PATH_AFTER = "readium/readium-css/ReadiumCSS-after.css"

    /**
     * Initialize and start the page calculation process.
     */
    public suspend fun startCalculation(
        getCurrentReflowablePageFragment: () -> PageFragment?,
        getFragmentAt: (Int) -> PageFragment?
    ) {
        logE { "🔥 startCalculation() 호출됨!" }

        // First initialize WASM
        initialize()

        logE { "🔥 calculateTotalPages() 호출..." }
        // Then start the calculation process
        calculateTotalPages(
            getCurrentFragment = getCurrentReflowablePageFragment,
            getFragmentAt = getFragmentAt
        )
    }

    /**
     * Initialize WASM calculator and start total page count calculation.
     */
    public suspend fun initialize() {
        logE { "🔥 EpubPageCalculationManager.initialize() 호출됨!" }

        if (wasmPageCalculator.initialize()) {
            logE { "🔥 WASM 초기화 성공! 테스트 실행..." }

            val testResult = wasmPageCalculator.testWasmConnection()
            logE { "🧪 WASM 테스트 결과: $testResult" }

            // Step 2: Build CSS registry once and register to WASM.
            try {
                if (!cssRegistryRegistered) {
                    val registryJson = buildCssRegistryJson()
                    val ok = wasmPageCalculator.registerCssRegistry(registryJson)
                    cssRegistryRegistered = ok
                    logE {
                        "📦 CSS 레지스트리 등록 결과: $ok, size=${
                            try {
                                JSONObject(registryJson).length()
                            } catch (_: Throwable) {
                                -1
                            }
                        }"
                    }
                }
            } catch (t: Throwable) {
                logW { "⚠️ CSS 레지스트리 등록 실패: ${t.message}" }
            }
        } else {
            logE { "❌ WASM 초기화 실패!" }
        }
    }

    /**
     * Build a CSS registry JSON: { href -> { baseHref, text } } for all CSS resources in the publication.
     */
    private suspend fun buildCssRegistryJson(): String = withContext(Dispatchers.IO) {
        val registry = JSONObject()

        fun looksLikeCss(href: String?): Boolean {
            if (href.isNullOrBlank()) return false
            val lower = href.lowercase()
            return lower.endsWith(".css")
        }

        val cssLinks: List<Link> = try {
            publication.resources.filter { looksLikeCss(it.href?.toString()) }
        } catch (_: Throwable) {
            emptyList()
        }

        for (link in cssLinks) {
            try {
                val href = link.href?.toString() ?: continue
                val resource = publication.get(link) ?: continue
                val bytes = resource.read().getOrNull() ?: continue
                val text = try {
                    bytes.toString(Charsets.UTF_8)
                } catch (_: Throwable) {
                    // Fallback; may contain non-UTF8 but acceptable for most CSS
                    String(bytes)
                }
                val baseHref = href.substringBeforeLast('/', missingDelimiterValue = "")
                    .let { if (it.isNotEmpty()) "$it/" else "" }
                val obj = JSONObject().apply {
                    put("baseHref", baseHref)
                    put("text", text)
                }
                registry.put(href, obj)
            } catch (t: Throwable) {
                logW { "[CSS DEBUG] CSS 레지스트리 항목 생성 실패: ${link.href} -> ${t.message}" }
            }
        }

        // Include ReadiumCSS before/after from app assets
        fun putAssetToRegistry(regKey: String, assetPath: String) {
            try {
                val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
                val baseHref = assetPath.substringBeforeLast('/', "")
                    .let { if (it.isNotEmpty()) "$it/" else "" }
                val obj = JSONObject().apply {
                    put("baseHref", baseHref)
                    put("text", text)
                }
                registry.put(regKey, obj)
                logD { "[CSS DEBUG] 자산 CSS 등록: $regKey (${text.length} chars)" }
            } catch (t: Throwable) {
                logW { "[CSS DEBUG] 자산 CSS 로드 실패: $assetPath -> ${t.message}" }
            }
        }
        putAssetToRegistry(REGKEY_ASSET_BEFORE, ASSET_PATH_BEFORE)
        putAssetToRegistry(REGKEY_ASSET_AFTER, ASSET_PATH_AFTER)

        registry.toString()
    }

    /**
     * Calculate total pages using sampling-based approach with WASM.
     */
    public suspend fun calculateTotalPages(
        getCurrentFragment: () -> PageFragment?,
        getFragmentAt: (Int) -> PageFragment?
    ) {
        logE { "🔥 calculateTotalPages() 호출됨!" }
        logD { "[전체] 전체 EPUB 페이지 계산 시작" }

        // Wait for ViewPager and fragments to be created
        // First, try immediately without any delay
        var currentFragment: PageFragment? = getCurrentFragment()

        if (currentFragment == null) {
            logE { "🔥 첫 번째 시도 실패, 재시도 루프 시작..." }

            var attempts = 0
            val maxAttempts = 10
            var delayMs = 50L // Start with shorter delay

            while (currentFragment == null && attempts < maxAttempts) {
                attempts++
                logE { "🔥 시도 ${attempts + 1}:" }

                logE { "  - ${delayMs}ms 대기 중..." }
                delay(delayMs)

                currentFragment = getCurrentFragment()
                logE { "  - 결과 fragment: $currentFragment" }

                // Gradually increase delay for subsequent attempts (exponential backoff)
                delayMs = minOf(delayMs * 2, 1000L) // Max 1 second delay
            }
        } else {
            logE { "✅ 첫 번째 시도에서 fragment 발견!" }
        }

        if (currentFragment == null) {
            logE { "❌ 현재 fragment를 찾을 수 없음, WebView fallback 사용" }
            calculateTotalPagesWebViewFallback(getFragmentAt)
            return
        }

        logE { "✅ 현재 fragment 찾음: $currentFragment" }

        // Wait until the fragment is loaded
        logE { "🔥 fragment.isLoaded 체크 중..." }
        currentFragment.isLoaded.collect { isLoaded ->
            logE { "🔥 fragment.isLoaded = $isLoaded" }
            if (isLoaded) {
                logD { "[전체] 현재 리소스의 샘플링 데이터 수집 완료, 각 리소스별 페이지 계산 시작" }

                val samplingJson = collectSamplingData(currentFragment)
                performSamplingBasedCalculation(samplingJson, getFragmentAt)
            }
        }
    }

    /**
     * Get current reading progress as percentage (0.0 to 1.0).
     */
    public fun getCurrentReadingProgress(
        currentPagerPosition: Int,
        getCurrentFragment: () -> PageFragment?
    ): Double? {
        val total = _totalPagesFlow.value ?: return null
        if (total <= 0) return 0.0
        if (currentPagerPosition !in readingOrder.indices) return 0.0

        // Snapshot maps to avoid concurrent updates during computation
        val pagesByHrefSnapshot = _pagesByHrefFlow.value.toMap()

        // Sum known pages for resources before the current index, without instantiating fragments
        var pagesBefore = 0
        for (i in 0 until currentPagerPosition) {
            val href = readingOrder[i].href?.toString()
            pagesBefore += if (href != null) (pagesByHrefSnapshot[href] ?: 1) else 1
        }

        // Current resource page within its own pagination (0-based in WebView)
        val currentFragment = getCurrentFragment()
        val currentPageInResourceZeroBased = currentFragment?.webView?.mCurItem ?: 0

        // Total pages for the current resource from the computed map, or fall back to WebView if available
        val currentHref = readingOrder[currentPagerPosition].href?.toString()
        val currentResourceTotalPages = currentHref?.let { pagesByHrefSnapshot[it] }
            ?: currentFragment?.webView?.numPages
            ?: 1

        val currentPageOneBased =
            (currentPageInResourceZeroBased + 1).coerceIn(1, currentResourceTotalPages)
        val currentAbsolutePage = (pagesBefore + currentPageOneBased).coerceAtMost(total)

        return (currentAbsolutePage.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * Step 3: For each spine, parse stylesheet hrefs and inline <style> blocks from the HTML,
     * resolve hrefs against the document href to match registry keys, and use calculatePagesWithRegistry.
     */
    private suspend fun performSamplingBasedCalculation(
        samplingJson: String,
        getFragmentAt: (Int) -> PageFragment?
    ) {
        logD { "[전체] 샘플링 데이터 수집 중..." }
        logD { "[전체] 샘플링 데이터: $samplingJson" }

        var totalPageCount = 0

        // Reset per-resource results for a fresh run
        _pagesByHrefFlow.value = mutableMapOf()
        _pagesByIndexFlow.value = MutableList(readingOrder.size) { 0 }

        // Build a flattened list of links (includes children), filter HTML-ish, and dedupe by href
        val flatLinks: List<Link> = try {
            readingOrder.flatten()
        } catch (_: Throwable) {
            readingOrder
        }

        val linksToProcess: List<Link> =
            flatLinks.filterByMediaTypes(listOf(MediaType.HTML, MediaType.XHTML))

        // Process each resource (flattened)
        for (link in linksToProcess) {
            val resource = publication.get(link)
            val documentHref = link.href?.toString() ?: ""
            logD(forcePrint = true) { "[전체] 리소스 페이지 계산: ${link.href}" }
            if (resource != null) {
                val htmlResult = resource.read()
                htmlResult.getOrNull()?.let { bytes ->
                    val html = String(bytes)

                    // Step 3: Extract stylesheet hrefs and inline <style> from the HTML itself.
                    val hrefs = extractStylesheetHrefs(html, documentHref)
                    val inlines = extractInlineStyles(html)
                    val hrefsJson = JSONArray(hrefs).toString()
                    val inlineStylesJson = JSONArray(inlines).toString()

                    logD { "[CSS DEBUG] 레지스트리 기반 호출: hrefs=${hrefs.size}, inlineStyles=${inlines.size}" }

                    val result = wasmPageCalculator.calculatePagesWithRegistry(
                        html = html,
                        hrefsJson = hrefsJson,
                        inlineStylesJson = inlineStylesJson,
                        samplingJson = samplingJson
                    )
                    logD(forcePrint = true) { "[전체] 리소스 결과 result=${result}" }

                    when (result.status) {
                        WasmCalculationResult.Status.SUCCESS -> {
                            logD { "[전체] WASM 정상 계산, 페이지 수: ${result.totalPages}" }
                            totalPageCount += result.totalPages
                            recordPageCountFor(link, result.totalPages)
                        }

                        WasmCalculationResult.Status.FALLBACK_NEEDED -> {
                            val count = getWebViewPageCount(link, getFragmentAt)
                            logD { "[전체] WASM Fallback 필요, WebView 기반 계산 페이지 수: $count" }
                            totalPageCount += count
                            recordPageCountFor(link, count)
                        }

                        WasmCalculationResult.Status.ERROR -> {
                            val est = estimatePagesByContentSize(html, samplingJson)
                            logD { "[전체] WASM 계산 오류, 컨텐츠 길이 기반 추정 페이지 수: $est" }
                            totalPageCount += est
                            recordPageCountFor(link, est)
                        }
                    }
                }
            }
        }

        logI(forcePrint = true) { "[전체] 전체 계산 완료, totalPageCount=$totalPageCount" }
        _totalPagesFlow.value = totalPageCount
    }

    /**
     * Collect a map of image href -> {width,height} for all EPUB-internal raster images.
     * This is calculated once and cached. SVG is skipped for now (intrinsic size ambiguous).
     */
    private suspend fun collectAllImageDimensions(): JSONObject = withContext(Dispatchers.IO) {
        imageDimensionsCache?.let { return@withContext it }

        val result = JSONObject()

        fun looksLikeRasterImage(href: String?): Boolean {
            if (href.isNullOrBlank()) return false
            val lower = href.lowercase()
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".webp") || lower.endsWith(".bmp")
        }

        val candidates: List<Link> = try {
            // publication.resources usually contains non-spine assets (images, css, fonts, etc.)
            val res = try {
                publication.resources
            } catch (_: Throwable) {
                emptyList()
            }
            res.filter { looksLikeRasterImage(it.href.toString()) }
        } catch (_: Throwable) {
            emptyList()
        }

        for (link in candidates) {
            try {
                val resource = publication.get(link) ?: continue
                val bytes = resource.read().getOrNull() ?: continue
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val w = opts.outWidth
                val h = opts.outHeight
                if (w > 0 && h > 0) {
                    val dim = JSONObject().apply {
                        put("width", w)
                        put("height", h)
                    }
                    result.put(link.href.toString(), dim)
                }
            } catch (t: Throwable) {
                logW { "[IMG] 치수 추출 실패: ${link.href} -> ${t.message}" }
            }
        }

        imageDimensionsCache = result
        return@withContext result
    }

    /**
     * Collect sampling data from a loaded WebView fragment.
     */
    private suspend fun collectSamplingData(fragment: PageFragment): String {
        val webView = fragment.webView ?: throw IllegalStateException("WebView not available")

        // Collect metrics via JavaScript
        val metricsJson = webView.runJavaScriptSuspend(
            """
            (function() {
                const body = document.body;
                const html = document.documentElement;
                const csBody = window.getComputedStyle(body);
                const csHtml = window.getComputedStyle(html);
                
                // 테스트 스팬으로 문자 너비 측정
                const testSpan = document.createElement('span');
                const testText = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ";
                testSpan.textContent = testText;
                testSpan.style.visibility = 'hidden';
                testSpan.style.position = 'absolute';
                testSpan.style.whiteSpace = 'nowrap';
                body.appendChild(testSpan);
                const rect = testSpan.getBoundingClientRect();
                body.removeChild(testSpan);
                
                // Canvas 기반 폰트 메트릭 계산 (ascent, descent, lineGap)
                const fontSizePx = parseFloat(csBody.fontSize) || 16;
                const computedLineHeight = parseFloat(csBody.lineHeight) || (fontSizePx * 1.2);
                let ascent = 0, descent = 0, lineGap = 0;
                let canvas = null;
                let ctx = null;
                try {
                    canvas = document.createElement('canvas');
                    ctx = canvas.getContext('2d');
                    if (ctx) {
                        const fontStyle = (csBody.fontStyle || 'normal');
                        const fontWeight = (csBody.fontWeight || '400');
                        const fontFamily = (csBody.fontFamily || 'serif');
                        ctx.font = fontStyle + ' ' + fontWeight + ' ' + fontSizePx + 'px ' + fontFamily;
                        // 'Hg'는 대략적인 실제 어센트/디센트 측정에 적합
                        const m = ctx.measureText('Hg');
                        const a = (m.actualBoundingBoxAscent ?? m.fontBoundingBoxAscent ?? (fontSizePx * 0.8));
                        const d = (m.actualBoundingBoxDescent ?? m.fontBoundingBoxDescent ?? (fontSizePx * 0.2));
                        ascent = a;
                        descent = d;
                        lineGap = Math.max(0, computedLineHeight - a - d);
                    } else {
                        ascent = fontSizePx * 0.8;
                        descent = fontSizePx * 0.2;
                        lineGap = Math.max(0, computedLineHeight - ascent - descent);
                    }
                } catch (e) {
                    ascent = fontSizePx * 0.8;
                    descent = fontSizePx * 0.2;
                    lineGap = Math.max(0, computedLineHeight - ascent - descent);
                } finally {
                    // 명시적으로 참조 해제하여 GC를 돕는다 (DOM에 붙이지 않았지만 안전하게 정리)
                    try { if (canvas) { canvas.width = 0; canvas.height = 0; if (canvas.remove) canvas.remove(); } } catch (_) {}
                    ctx = null;
                    canvas = null;
                }
                
                // CSS에 정의된 모든 태그의 스타일을 수집 (가상 요소 생성)
                const elementStyles = {};
                const elements = ['body', 'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'div', 'span', 'blockquote', 'pre', 'code', 'em', 'strong', 'a', 'img', 'ul', 'ol', 'li'];
                
                // 안전한 문자열 처리 함수
                function safeString(str) {
                    if (!str) return '';
                    return str.replace(/["\\]/g, '').trim();
                }
                
                // 각 태그에 대해 가상 요소를 생성하여 CSS 스타일 측정
                elements.forEach(tag => {
                    let elem = document.querySelector(tag);
                    let isTemporary = false;
                    
                    // 해당 태그가 문서에 없으면 임시로 생성
                    if (!elem) {
                        elem = document.createElement(tag);
                        elem.style.visibility = 'hidden';
                        elem.style.position = 'absolute';
                        elem.style.top = '-9999px';
                        // 텍스트 요소의 경우 샘플 텍스트 추가
                        if (['p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'span', 'em', 'strong', 'a', 'code'].includes(tag)) {
                            elem.textContent = 'Sample text';
                        }
                        body.appendChild(elem);
                        isTemporary = true;
                    }
                    
                    const style = window.getComputedStyle(elem);
                    elementStyles[tag] = {
                        marginTop: (parseFloat(style.marginTop) || 0).toString(),
                        marginBottom: (parseFloat(style.marginBottom) || 0).toString(),
                        marginLeft: (parseFloat(style.marginLeft) || 0).toString(),
                        marginRight: (parseFloat(style.marginRight) || 0).toString(),
                        paddingTop: (parseFloat(style.paddingTop) || 0).toString(),
                        paddingBottom: (parseFloat(style.paddingBottom) || 0).toString(),
                        paddingLeft: (parseFloat(style.paddingLeft) || 0).toString(),
                        paddingRight: (parseFloat(style.paddingRight) || 0).toString(),
                        fontSize: (parseFloat(style.fontSize) || 16).toString(),
                        lineHeight: (parseFloat(style.lineHeight) || 24).toString(),
                        fontFamily: safeString(style.fontFamily),
                        fontWeight: safeString(style.fontWeight) || 'normal',
                        fontStyle: safeString(style.fontStyle) || 'normal',
                        textAlign: safeString(style.textAlign) || 'left',
                        textIndent: (parseFloat(style.textIndent) || 0).toString(),
                        letterSpacing: (parseFloat(style.letterSpacing) || 0).toString(),
                        wordSpacing: (parseFloat(style.wordSpacing) || 0).toString(),
                        display: safeString(style.display) || 'block',
                        whiteSpace: safeString(style.whiteSpace) || 'normal',
                        hyphens: safeString(style.hyphens) || 'manual'
                    };
                    
                    // 임시로 생성한 요소는 제거
                    if (isTemporary) {
                        body.removeChild(elem);
                    }
                });
                
                // CSS 변수들 추출 (레이아웃에 영향을 주는 것들만)
                const cssVariables = {};
                const rootStyle = window.getComputedStyle(html);
                [
                  // RS 기본
                  '--RS__baseFontSize','--RS__baseFontFamily','--RS__lineHeightCompensation','--RS__baseLineHeight',
                  '--RS__flowSpacing','--RS__paraSpacing','--RS__paraIndent',
                  '--RS__maxLineLength','--RS__pageGutter','--RS__viewportWidth',
                  // 다단/페이지 관련
                  '--RS__colWidth','--RS__colCount','--RS__colGap',
                  // 미디어/테이블 크기
                  '--RS__maxMediaWidth','--RS__maxMediaHeight','--RS__boxSizingMedia','--RS__boxSizingTable',
                  // USER 오버라이드(읽기 설정)
                  '--USER__pageMargins','--USER__fontFamily','--USER__fontSize','--USER__lineHeight',
                  '--USER__paraSpacing','--USER__paraIndent','--USER__wordSpacing','--USER__letterSpacing',
                  '--USER__colCount','--USER__backgroundColor','--USER__textColor','--USER__bodyHyphens'
                ].forEach(varName => {
                    const value = rootStyle.getPropertyValue(varName);
                    if (value) cssVariables[varName] = safeString(value);
                });
                
                // :root style 토글 복제 (e.g. readium-scroll-on, readium-advanced-on 등)
                const rootStyleAttr = html.getAttribute('style') || '';
                
                // WASM에서 기대하는 구조로 JSON 생성
                return JSON.stringify({
                    viewportWidth: document.documentElement.clientWidth,
                    viewportHeight: document.documentElement.clientHeight,
                    fontMetrics: {
                        fontSize: parseFloat(csBody.fontSize) || 16,
                        lineHeight: parseFloat(csBody.lineHeight) || (parseFloat(csBody.fontSize) * 1.2),
                        characterWidth: rect.width / testText.length,
                        ascent: ascent,
                        descent: descent,
                        lineGap: lineGap
                    },
                    cssVariables: cssVariables,
                    elementStyles: elementStyles,
                    rootStyleAttr: rootStyleAttr,
                    documentLang: (
                        html.lang ||
                        html.getAttribute('xml:lang') ||
                        body.getAttribute('lang') ||
                        (function(){
                            var m = document.querySelector("meta[http-equiv='content-language']") || document.querySelector("meta[http-equiv='Content-Language']");
                            return m ? (m.getAttribute('content')||'') : '';
                        })() ||
                        (navigator.language || '')
                    ).trim(),
                    documentDir: (html.dir || body.getAttribute('dir') || '').trim(),
                    documentWritingMode: (window.getComputedStyle(html).writingMode || '').trim(),
                    bodyStyle: {
                        contentWidth: body.scrollWidth.toString(),
                        contentHeight: body.scrollHeight.toString(),
                        marginTop: (parseFloat(csBody.marginTop) || 0).toString(),
                        marginRight: (parseFloat(csBody.marginRight) || 0).toString(),
                        marginBottom: (parseFloat(csBody.marginBottom) || 0).toString(),
                        marginLeft: (parseFloat(csBody.marginLeft) || 0).toString(),
                        paddingTop: (parseFloat(csBody.paddingTop) || 0).toString(),
                        paddingRight: (parseFloat(csBody.paddingRight) || 0).toString(),
                        paddingBottom: (parseFloat(csBody.paddingBottom) || 0).toString(),
                        paddingLeft: (parseFloat(csBody.paddingLeft) || 0).toString()
                    }
                });
            })();
            """
        )

        logD { "[샘플링] JavaScript 결과: $metricsJson" }

        // JavaScript가 JSON.stringify()로 이미 문자열로 반환한 결과를 다시 문자열로 감쌌으므로 따옴표 제거
        val cleanedJson = metricsJson.trim().removeSurrounding("\"").replace("\\\"", "\"")
        val obj = try {
            JSONObject(cleanedJson)
        } catch (e: Exception) {
            JSONObject()
        }
        try {
            val dims = collectAllImageDimensions()
            obj.put("imageDimensions", dims)
            logD { "[샘플링] 이미지 치수 맵 포함: count=${dims.length()}" }
        } catch (t: Throwable) {
            logW { "[샘플링] 이미지 치수 수집 실패: ${t.message}" }
        }
        // 폰트 메트릭 로깅
        try {
            val fm = obj.optJSONObject("fontMetrics")
            val fontSize = fm?.optDouble("fontSize")
            val lineHeight = fm?.optDouble("lineHeight")
            val ascent = fm?.optDouble("ascent")
            val descent = fm?.optDouble("descent")
            val lineGap = fm?.optDouble("lineGap")
            logD { "[샘플링] 폰트 메트릭: size=${fontSize}, lineHeight=${lineHeight}, ascent=${ascent}, descent=${descent}, lineGap=${lineGap}" }
        } catch (t: Throwable) {
            logW { "[샘플링] 폰트 메트릭 로깅 실패: ${t.message}" }
        }
        logD { "[샘플링] WASM 호환 샘플링 데이터 수집 완료" }

        return obj.toString()
    }

    /**
     * Get page count for a specific resource using WebView (fallback method).
     */
    private fun getWebViewPageCount(link: Link, getFragmentAt: (Int) -> PageFragment?): Int {
        // Find fragment by link URL
        val index = readingOrder.indexOfFirst { it.href == link.href }
        val fragment = if (index >= 0) getFragmentAt(index) else null
        val pages = fragment?.webView?.numPages ?: 1
        logD { "[WebView] ${link.href} -> $pages 페이지 (WebView 기반)" }
        return pages
    }

    /**
     * Estimate pages by content size when WASM calculation fails.
     */
    private fun estimatePagesByContentSize(html: String, samplingJson: String): Int {
        val textContent = html.replace(Regex("<[^>]+>"), "")
        val contentLength = textContent.length

        // Parse samplingJson to retrieve needed metrics
        val sampleObj = try {
            JSONObject(samplingJson)
        } catch (e: Exception) {
            null
        }

        // Use bodyStyle instead of layoutMetrics (which was removed due to duplication)
        val bodyStyle = sampleObj?.optJSONObject("bodyStyle")
        val fontMetrics = sampleObj?.optJSONObject("fontMetrics")

        val contentWidth = bodyStyle?.optDouble("contentWidth") ?: 1.0
        val contentHeight = bodyStyle?.optDouble("contentHeight") ?: 1.0
        val characterWidth = fontMetrics?.optDouble("characterWidth") ?: 8.0
        val lineHeight = fontMetrics?.optDouble("lineHeight") ?: 24.0

        val charsPerLine = (contentWidth / characterWidth).toInt()
        val linesPerPage = (contentHeight / lineHeight).toInt()
        val charsPerPage = charsPerLine * linesPerPage

        val estimatedPages = if (charsPerPage > 0) {
            kotlin.math.max(1, (contentLength / charsPerPage))
        } else {
            1
        }

        logD { "[추정] 텍스트 길이: $contentLength, 페이지당 글자 수: $charsPerPage -> $estimatedPages 페이지" }
        return estimatedPages
    }

    /**
     * Fallback to traditional WebView-based total page calculation.
     */
    private suspend fun calculateTotalPagesWebViewFallback(
        getFragmentAt: (Int) -> PageFragment?
    ) {
        logD { "[폴백] WebView 기반 전체 페이지 계산 시작" }
        var totalPages = 0
        // Reset per-resource results for fallback run
        _pagesByHrefFlow.value = mutableMapOf()
        _pagesByIndexFlow.value = MutableList(readingOrder.size) { 0 }
        for (i in readingOrder.indices) {
            val fragment = getFragmentAt(i)
            val pages = fragment?.webView?.numPages ?: 1
            logD { "[폴백] 리소스 인덱스 $i -> $pages 페이지" }
            totalPages += pages
            // Record by index and href if available
            val link = readingOrder[i]
            recordPageCountFor(link, pages)
        }
        logD { "[폴백] WebView 기반 총 페이지: $totalPages" }
        _totalPagesFlow.value = totalPages
    }

    /**
     * Clean up resources when manager is destroyed.
     */
    public fun destroy() {
        wasmPageCalculator.destroy()
    }

    /** Resolve a relative href against the EPUB document href to match registry keys. */
    private fun resolvePublicationHref(documentHref: String, relHref: String): String {
        val baseDir = documentHref.substringBeforeLast('/', "")
        // TODO : https://pub/ 이건 어디서 온 url???
        val baseUri = URI.create("https://pub/" + if (baseDir.isNotEmpty()) "$baseDir/" else "")
        val resolved = try {
            baseUri.resolve(relHref)
        } catch (_: Throwable) {
            return relHref
        }
        var path = resolved.path ?: relHref
        if (path.startsWith('/')) path = path.substring(1)
        return path
    }

    /** Extract <link rel="stylesheet" href="..."> hrefs in document order and resolve them. */
    private fun extractStylesheetHrefs(html: String, documentHref: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("<link\\s+[^>]*rel=\\\"?stylesheet\\\"?[^>]*>", RegexOption.IGNORE_CASE)
        val hrefRegex = Regex("href=\\\"([^\\\"]+)\\\"|href='([^']+)'", RegexOption.IGNORE_CASE)
        regex.findAll(html).forEach { m ->
            val tag = m.value
            val hrefMatch = hrefRegex.find(tag)
            val rawHref = hrefMatch?.groups?.get(1)?.value ?: hrefMatch?.groups?.get(2)?.value
            if (!rawHref.isNullOrBlank()) {
                val resolved = resolvePublicationHref(documentHref, rawHref)
                logD { "[CSS DEBUG] 스타일시트 추출: $resolved" }
                result.add(resolved)
            }
        }
        // Prepend before.css and append after.css (assets) using synthetic keys
        val finalList = ArrayList<String>(result.size + 2)
        finalList.add(REGKEY_ASSET_BEFORE)
        finalList.addAll(result)
        finalList.add(REGKEY_ASSET_AFTER)
        return finalList
    }

    /** Extract inline <style>...</style> blocks in document order. */
    private fun extractInlineStyles(html: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("<style[^>]*>([\\s\\S]*?)</style>", setOf(RegexOption.IGNORE_CASE))
        regex.findAll(html).forEach { m ->
            val content = m.groups[1]?.value ?: ""
            if (content.isNotBlank()) result.add(content)
        }
        return result
    }

    /**
     * Record a per-resource page count into both href and index-based stores.
     */
    private fun recordPageCountFor(link: Link, pages: Int) {
        // Update href-based map
        val href = link.href?.toString()
        if (!href.isNullOrBlank()) {
            val updated = _pagesByHrefFlow.value
            updated[href] = pages
            _pagesByHrefFlow.value = updated
        }
        // Update index-based list if this link exists in the provided reading order
        val idx = readingOrder.indexOfFirst { it.href == link.href }
        if (idx >= 0) {
            val current = _pagesByIndexFlow.value
            if (idx < current.size) {
                current[idx] = pages
                _pagesByIndexFlow.value = current
            }
        }
    }
}