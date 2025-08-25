/*
 * Copyright 2024 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

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
import org.readium.r2.navigator.pager.R2EpubPageFragment
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Link
import java.net.URI

/**
 * Manager for calculating total pages in EPUB publications using WASM-based approach.
 */
@OptIn(InternalReadiumApi::class)
internal class EpubPageCalculationManager(
    private val context: Context,
    private val publication: Publication,
    private val readingOrder: List<Link>
) {

    private val wasmPageCalculator: WasmPageCalculator by lazy {
        DefaultWasmPageCalculator(context)
    }

    private val _totalPagesFlow = MutableStateFlow<Int?>(null)

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
     * Flow of total pages count for the entire publication.
     * Null indicates the count is still being calculated.
     */
    val totalPages: StateFlow<Int?> = _totalPagesFlow

    /**
     * Initialize and start the page calculation process.
     */
    suspend fun startCalculation(
        getCurrentReflowablePageFragment: () -> R2EpubPageFragment?,
        getFragmentAt: (Int) -> R2EpubPageFragment?
    ) {
        Log.e("EpubPageCalc", "🔥 startCalculation() 호출됨!")

        // First initialize WASM
        initialize()

        Log.e("EpubPageCalc", "🔥 calculateTotalPages() 호출...")
        // Then start the calculation process
        calculateTotalPages(
            getCurrentFragment = getCurrentReflowablePageFragment,
            getFragmentAt = getFragmentAt
        )
    }

    /**
     * Initialize WASM calculator and start total page count calculation.
     */
    suspend fun initialize() {
        Log.e("EpubPageCalc", "🔥 EpubPageCalculationManager.initialize() 호출됨!")

        if (wasmPageCalculator.initialize()) {
            Log.e("EpubPageCalc", "🔥 WASM 초기화 성공! 테스트 실행...")

            val testResult = wasmPageCalculator.testWasmConnection()
            Log.e("EpubPageCalc", "🧪 WASM 테스트 결과: $testResult")

            // Step 2: Build CSS registry once and register to WASM.
            try {
                if (!cssRegistryRegistered) {
                    val registryJson = buildCssRegistryJson()
                    val ok = wasmPageCalculator.registerCssRegistry(registryJson)
                    cssRegistryRegistered = ok
                    Log.e(
                        "EpubPageCalc", "📦 CSS 레지스트리 등록 결과: $ok, size=${
                            try {
                                JSONObject(registryJson).length()
                            } catch (_: Throwable) {
                                -1
                            }
                        }"
                    )
                }
            } catch (t: Throwable) {
                Log.w("EpubPageCalc", "⚠️ CSS 레지스트리 등록 실패: ${t.message}")
            }
        } else {
            Log.e("EpubPageCalc", "❌ WASM 초기화 실패!")
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
                Log.w(
                    "EpubPageCalc",
                    "[CSS DEBUG] CSS 레지스트리 항목 생성 실패: ${link.href} -> ${t.message}"
                )
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
                Log.d("EpubPageCalc", "[CSS DEBUG] 자산 CSS 등록: $regKey (${text.length} chars)")
            } catch (t: Throwable) {
                Log.w("EpubPageCalc", "[CSS DEBUG] 자산 CSS 로드 실패: $assetPath -> ${t.message}")
            }
        }
        putAssetToRegistry(REGKEY_ASSET_BEFORE, ASSET_PATH_BEFORE)
        putAssetToRegistry(REGKEY_ASSET_AFTER, ASSET_PATH_AFTER)

        registry.toString()
    }

    /**
     * Calculate total pages using sampling-based approach with WASM.
     */
    suspend fun calculateTotalPages(
        getCurrentFragment: () -> R2EpubPageFragment?,
        getFragmentAt: (Int) -> R2EpubPageFragment?
    ) {
        Log.e("EpubPageCalc", "🔥 calculateTotalPages() 호출됨!")
        Log.d("EpubPageCalc", "[전체] 전체 EPUB 페이지 계산 시작")

        // Wait for ViewPager and fragments to be created
        // First, try immediately without any delay
        var currentFragment: R2EpubPageFragment? = getCurrentFragment()

        if (currentFragment == null) {
            Log.e("EpubPageCalc", "🔥 첫 번째 시도 실패, 재시도 루프 시작...")

            var attempts = 0
            val maxAttempts = 10
            var delayMs = 50L // Start with shorter delay

            while (currentFragment == null && attempts < maxAttempts) {
                attempts++
                Log.e("EpubPageCalc", "🔥 시도 ${attempts + 1}:")

                Log.e("EpubPageCalc", "  - ${delayMs}ms 대기 중...")
                delay(delayMs)

                currentFragment = getCurrentFragment()
                Log.e("EpubPageCalc", "  - 결과 fragment: $currentFragment")

                // Gradually increase delay for subsequent attempts (exponential backoff)
                delayMs = minOf(delayMs * 2, 1000L) // Max 1 second delay
            }
        } else {
            Log.e("EpubPageCalc", "✅ 첫 번째 시도에서 fragment 발견!")
        }

        if (currentFragment == null) {
            Log.e("EpubPageCalc", "❌ 현재 fragment를 찾을 수 없음, WebView fallback 사용")
            calculateTotalPagesWebViewFallback(getFragmentAt)
            return
        }

        Log.e("EpubPageCalc", "✅ 현재 fragment 찾음: $currentFragment")

        // Wait until the fragment is loaded
        Log.e("EpubPageCalc", "🔥 fragment.isLoaded 체크 중...")
        currentFragment.isLoaded.collect { isLoaded ->
            Log.e("EpubPageCalc", "🔥 fragment.isLoaded = $isLoaded")
            if (isLoaded) {
                Log.d("EpubPageCalc", "[전체] 현재 리소스의 샘플링 데이터 수집 완료, 각 리소스별 페이지 계산 시작")
//                // 디버그: 레이아웃/변수/하단 갭 분석 덤프
//                try {
//                    debugDumpLayoutMetrics(currentFragment)
//                    debugDumpViewHierarchy(currentFragment)
//                } catch (t: Throwable) {
//                    Log.w("EpubPageCalc", "[디버그] 레이아웃 덤프 실패: ${t.message}")
//                }

                val samplingJson = collectSamplingData(currentFragment)
                performSamplingBasedCalculation(samplingJson, getFragmentAt)
            }
        }
    }

    /**
     * Get current reading progress as percentage (0.0 to 1.0).
     */
    fun getCurrentReadingProgress(
        currentPagerPosition: Int,
        getCurrentFragment: () -> R2EpubPageFragment?,
        getFragmentAt: (Int) -> R2EpubPageFragment?
    ): Double? {
        val total = _totalPagesFlow.value ?: return null
        if (total <= 0) return 0.0

        val currentFragment = getCurrentFragment()
        val currentPageInResource = currentFragment?.webView?.mCurItem ?: 0

        // Calculate pages before current resource
        var pagesBefore = 0
        for (i in 0 until currentPagerPosition) {
            val fragment = getFragmentAt(i)
            pagesBefore += fragment?.webView?.numPages ?: 1
        }

        val currentAbsolutePage = pagesBefore + currentPageInResource + 1
        return (currentAbsolutePage.toDouble() / total).coerceIn(0.0, 1.0)
    }

    /**
     * Step 3: For each spine, parse stylesheet hrefs and inline <style> blocks from the HTML,
     * resolve hrefs against the document href to match registry keys, and use calculatePagesWithRegistry.
     */
    private suspend fun performSamplingBasedCalculation(
        samplingJson: String,
        getFragmentAt: (Int) -> R2EpubPageFragment?
    ) {
        Log.d("EpubPageCalc", "[전체] 샘플링 데이터 수집 중...")
        Log.d("EpubPageCalc", "[전체] 샘플링 데이터: $samplingJson")

        var totalPageCount = 0

        // Process each resource in reading order
        for (link in readingOrder) {
            val resource = publication.get(link)
            val documentHref = link.href?.toString() ?: ""
            Log.d("EpubPageCalc", "[전체] 리소스 페이지 계산: ${link.href}")
            if (resource != null) {
                val htmlResult = resource.read()
                htmlResult.getOrNull()?.let { bytes ->
                    val html = String(bytes)

                    // Step 3: Extract stylesheet hrefs and inline <style> from the HTML itself.
                    val hrefs = extractStylesheetHrefs(html, documentHref)
                    val inlines = extractInlineStyles(html)
                    val hrefsJson = JSONArray(hrefs).toString()
                    val inlineStylesJson = JSONArray(inlines).toString()

                    Log.d(
                        "EpubPageCalc",
                        "[CSS DEBUG] 레지스트리 기반 호출: hrefs=${hrefs.size}, inlineStyles=${inlines.size}"
                    )

                    val result = wasmPageCalculator.calculatePagesWithRegistry(
                        html = html,
                        hrefsJson = hrefsJson,
                        inlineStylesJson = inlineStylesJson,
                        samplingJson = samplingJson
                    )
                    Log.d(
                        "EpubPageCalc",
                        "[전체] 리소스 결과 result=${result}"
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
                            val count = getWebViewPageCount(link, getFragmentAt)
                            Log.d(
                                "EpubPageCalc",
                                "[전체] WASM Fallback 필요, WebView 기반 계산 페이지 수: $count"
                            )
                            totalPageCount += count
                        }

                        WasmCalculationResult.Status.ERROR -> {
                            val est = estimatePagesByContentSize(html, samplingJson)
                            Log.d("EpubPageCalc", "[전체] WASM 계산 오류, 컨텐츠 길이 기반 추정 페이지 수: $est")
                            totalPageCount += est
                        }
                    }
                }
            }
        }

        Log.d("EpubPageCalc", "[전체] 전체 계산 완료, totalPageCount=$totalPageCount")
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
                Log.w("EpubPageCalc", "[IMG] 치수 추출 실패: ${link.href} -> ${t.message}")
            }
        }

        imageDimensionsCache = result
        return@withContext result
    }

    /**
     * Collect sampling data from a loaded WebView fragment.
     */
    private suspend fun collectSamplingData(fragment: R2EpubPageFragment): String {
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
                        characterWidth: rect.width / testText.length
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

        Log.d("EpubPageCalc", "[샘플링] JavaScript 결과: $metricsJson")

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
            Log.d("EpubPageCalc", "[샘플링] 이미지 치수 맵 포함: count=${dims.length()}")
        } catch (t: Throwable) {
            Log.w("EpubPageCalc", "[샘플링] 이미지 치수 수집 실패: ${t.message}")
        }
        Log.d("EpubPageCalc", "[샘플링] WASM 호환 샘플링 데이터 수집 완료")

        return obj.toString()
    }

    /**
     * Get page count for a specific resource using WebView (fallback method).
     */
    private fun getWebViewPageCount(link: Link, getFragmentAt: (Int) -> R2EpubPageFragment?): Int {
        // Find fragment by link URL
        val index = readingOrder.indexOfFirst { it.href == link.href }
        val fragment = if (index >= 0) getFragmentAt(index) else null
        val pages = fragment?.webView?.numPages ?: 1
        Log.d("EpubPageCalc", "[WebView] ${link.href} -> $pages 페이지 (WebView 기반)")
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

        Log.d(
            "EpubPageCalc",
            "[추정] 텍스트 길이: $contentLength, 페이지당 글자 수: $charsPerPage -> $estimatedPages 페이지"
        )
        return estimatedPages
    }

    /**
     * Fallback to traditional WebView-based total page calculation.
     */
    private suspend fun calculateTotalPagesWebViewFallback(
        getFragmentAt: (Int) -> R2EpubPageFragment?
    ) {
        Log.d("EpubPageCalc", "[폴백] WebView 기반 전체 페이지 계산 시작")
        var totalPages = 0
        for (i in readingOrder.indices) {
            val fragment = getFragmentAt(i)
            val pages = fragment?.webView?.numPages ?: 1
            Log.d("EpubPageCalc", "[폴백] 리소스 인덱스 $i -> $pages 페이지")
            totalPages += pages
        }
        Log.d("EpubPageCalc", "[폴백] WebView 기반 총 페이지: $totalPages")
        _totalPagesFlow.value = totalPages
    }

    /**
     * Clean up resources when manager is destroyed.
     */
    fun destroy() {
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
                Log.d("EpubPageCalc", "[CSS DEBUG] 스타일시트 추출: $resolved")
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
}
