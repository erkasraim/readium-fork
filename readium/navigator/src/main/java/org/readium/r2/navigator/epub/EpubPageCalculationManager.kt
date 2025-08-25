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
        } else {
            Log.e("EpubPageCalc", "❌ WASM 초기화 실패!")
        }
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
                // 1단계 1번: 현재 리소스(프래그먼트)의 HTML에서 CSS 경로 추출 (최초 한 번)
                val cssPaths = extractCssLinksFromCurrentFragment(currentFragment)
                Log.d("EpubPageCalc", "[CSS DEBUG] 추출된 CSS 경로: $cssPaths")

                // 1단계 2번: CSS 콘텐츠 읽기 및 캐싱
                try {
                    cacheCssContents(cssPaths, currentFragment)
                } catch (t: Throwable) {
                    Log.w("EpubPageCalc", "[CSS DEBUG] CSS 캐싱 중 오류: ${t.message}")
                }

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
     * Perform sampling-based page calculation using first loaded resource.
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
            Log.d("EpubPageCalc", "[전체] 리소스 페이지 계산: ${link.href}")
            if (resource != null) {
                val htmlResult = resource.read()
                htmlResult.getOrNull()?.let { bytes ->
                    val html = String(bytes)
                    val cssParts = cssCache.values.toList()
                    val cssText = cssParts.joinToString("\n\n")
                    Log.d(
                        "EpubPageCalc",
                        "[CSS DEBUG] WASM 전달 CSS: parts=${cssParts.size}, length=${cssText.length}"
                    )
                    val result = wasmPageCalculator.calculatePages(html, cssText, samplingJson)
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

    /**
     * Debug helper: dump important layout metrics and bottom gap analysis from WebView.
     */
    private suspend fun debugDumpLayoutMetrics(fragment: R2EpubPageFragment?) {
        val webView = fragment?.webView ?: return
        val result = webView.runJavaScriptSuspend(
            """
            (function() {
                const body = document.body;
                const html = document.documentElement;
                const csBody = window.getComputedStyle(body);
                const csHtml = window.getComputedStyle(html);

                function lastContentChild() {
                    const kids = Array.from(body.children);
                    for (let i = kids.length - 1; i >= 0; i--) {
                        const el = kids[i];
                        const tag = el.tagName;
                        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'LINK') continue;
                        return el;
                    }
                    return null;
                }

                const last = lastContentChild();
                const rectLast = last ? last.getBoundingClientRect() : null;
                const rectBody = body.getBoundingClientRect();
                const csLast = last ? window.getComputedStyle(last) : null;

                // Collect all --RS__* variables
                const rsVars = {};
                for (let i = 0; i < csHtml.length; i++) {
                    const prop = csHtml[i];
                    if (prop.indexOf('--RS__') === 0) {
                        rsVars[prop] = csHtml.getPropertyValue(prop).trim();
                    }
                }

                const data = {
                    viewport: { innerWidth: window.innerWidth, innerHeight: window.innerHeight },
                    html: {
                        marginTop: parseFloat(csHtml.marginTop) || 0,
                        marginBottom: parseFloat(csHtml.marginBottom) || 0,
                        paddingTop: parseFloat(csHtml.paddingTop) || 0,
                        paddingBottom: parseFloat(csHtml.paddingBottom) || 0,
                        lineHeight: csHtml.lineHeight,
                        fontSize: csHtml.fontSize
                    },
                    body: {
                        marginTop: parseFloat(csBody.marginTop) || 0,
                        marginBottom: parseFloat(csBody.marginBottom) || 0,
                        paddingTop: parseFloat(csBody.paddingTop) || 0,
                        paddingBottom: parseFloat(csBody.paddingBottom) || 0,
                        borderTop: parseFloat(csBody.borderTopWidth) || 0,
                        borderBottom: parseFloat(csBody.borderBottomWidth) || 0,
                        lineHeight: csBody.lineHeight,
                        fontSize: csBody.fontSize
                    },
                    sizes: {
                        bodyScrollHeight: body.scrollHeight,
                        bodyOffsetHeight: body.offsetHeight,
                        bodyClientHeight: body.clientHeight,
                        docScrollHeight: Math.max(body.scrollHeight, html.scrollHeight),
                        rectBodyHeight: rectBody.height
                    },
                    last: last ? {
                        tag: last.tagName.toLowerCase(),
                        marginBottom: parseFloat(csLast.marginBottom) || 0,
                        paddingBottom: parseFloat(csLast.paddingBottom) || 0,
                        rectBottom: rectLast.bottom,
                        gapToViewportBottom: window.innerHeight - rectLast.bottom
                    } : null,
                    rsVars: rsVars
                };

                return JSON.stringify(data);
            })();
            """
        )

        val json = try {
            JSONObject(result)
        } catch (e: Exception) {
            null
        }
        Log.e("EpubPageCalc", "[디버그] 레이아웃 덤프: ${json ?: result}")
    }

    /**
     * Debug helper: dump WebView and ancestor view hierarchy metrics (padding/margin/size, insets).
     */
    @Suppress("DEPRECATION")
    private fun debugDumpViewHierarchy(fragment: R2EpubPageFragment?) {
        val webView = fragment?.webView ?: return
        Log.e("EpubPageCalc", "[디버그] ===== View 계층 덤프 시작 =====")

        fun lpInfo(view: android.view.View): String {
            val lp = view.layoutParams
            val size = "lp=${lp?.width}x${lp?.height}"
            val margin = if (lp is android.view.ViewGroup.MarginLayoutParams) {
                ", margin=[l=${lp.leftMargin}, t=${lp.topMargin}, r=${lp.rightMargin}, b=${lp.bottomMargin}]"
            } else ""
            return size + margin
        }

        fun viewInfo(prefix: String, view: android.view.View) {
            val cls = view.javaClass.simpleName
            val pads =
                "pad=[l=${view.paddingLeft}, t=${view.paddingTop}, r=${view.paddingRight}, b=${view.paddingBottom}]"
            val dims =
                "measured=${view.measuredWidth}x${view.measuredHeight}, actual=${view.width}x${view.height}"
            val rootInsets = view.rootWindowInsets
            Log.e(
                "EpubPageCalc",
                "$prefix$cls: $pads, $dims, ${lpInfo(view)}, insets=${rootInsets}"
            )
        }

        // Dump WebView specifics
        val density = webView.resources.displayMetrics.density
        val scale = try {
            webView.scale
        } catch (t: Throwable) {
            1.0f
        }
        val contentHeightCss = webView.contentHeight // CSS px
        val contentHeightViewPx = contentHeightCss * scale
        val contentHeightDevicePx = contentHeightViewPx * density
        Log.e(
            "EpubPageCalc",
            "[디버그] WebView contentHeight: css=${contentHeightCss}, scale=${"%.3f".format(scale)}, " +
                "viewPx=${"%.1f".format(contentHeightViewPx)}, devicePx≈${
                    "%.1f".format(
                        contentHeightDevicePx
                    )
                } density=${"%.2f".format(density)}"
        )

        // Walk up the parent chain (max 8 levels)
        var v: android.view.View? = webView
        var depth = 0
        while (v != null && depth < 8) {
            viewInfo(prefix = "[디버그] V$depth ", view = v)
            v = (v.parent as? android.view.View)
            depth++
        }

        Log.e("EpubPageCalc", "[디버그] ===== View 계층 덤프 끝 =====")
    }

    /**
     * 현재 리소스(프래그먼트)의 HTML에서 <link rel="stylesheet">의 href(EPUB 상대 경로) 리스트 추출
     * @return List<String>
     */
    private suspend fun extractCssLinksFromCurrentFragment(fragment: R2EpubPageFragment?): List<String> {
        val webView = fragment?.webView ?: return emptyList()
        val js = (
            """
            (function() {
                try {
                    var nodes = Array.prototype.slice.call(document.querySelectorAll('link[rel="stylesheet"]'));
                    var hrefs = nodes.map(function(n){ return n.getAttribute('href') || ''; }).filter(function(h){ return h && h.length > 0; });
                    // Fallback: also scan inline <style> for @import url(...)
                    try {
                        var styleTexts = Array.prototype.slice.call(document.querySelectorAll('style')).map(function(s){ return s.textContent || ''; }).join('\n');
                        var importHrefs = [];
                        var re = /@import\s+url\(([^)]+)\)/gi;
                        var m;
                        while ((m = re.exec(styleTexts)) !== null) {
                            var ref = (m[1] || '').replace(/['\"]/g, '').trim();
                            if (ref) importHrefs.push(ref);
                        }
                        hrefs = hrefs.concat(importHrefs);
                    } catch(e2) { /* ignore */ }
                    return hrefs;
                } catch (e) {
                    return [];
                }
            })();
            """
            ).trim()
        return try {
            val raw = webView.runJavaScriptSuspend(js)
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val href = arr.optString(i)?.trim()
                    if (!href.isNullOrEmpty()) add(href)
                }
            }.distinct()
        } catch (t: Throwable) {
            Log.w("EpubPageCalc", "[CSS DEBUG] CSS 추출 파싱 실패: ${t.message}")
            emptyList()
        }
    }

    // CSS 콘텐츠 캐시: key = 원본 href, value = css 텍스트
    private val cssCache: MutableMap<String, String> = mutableMapOf()

    /**
     * CSS 경로 리스트를 받아 캐시에 CSS 텍스트를 저장합니다. 이미 캐시에 있으면 재사용합니다.
     */
    private suspend fun cacheCssContents(paths: List<String>, fragment: R2EpubPageFragment) {
        for (path in paths.distinct()) {
            if (cssCache.containsKey(path)) {
                Log.d("EpubPageCalc", "[CSS DEBUG] 캐시 히트: $path (length=${cssCache[path]?.length})")
                continue
            }

            // WebViewServer를 통해 모든 EPUB/asset 리소스를 제공하므로,
            // 현재 리소스의 document.baseURI를 기준으로 XHR로 가져온다.
            val escaped = path.replace("\\", "\\\\").replace("\"", "\\\"")
            val js = (
                """
                (function(){
                  try {
                    var url = (new URL("$escaped", document.baseURI)).href;
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', url, false);
                    xhr.send(null);
                    if (xhr.status >= 200 && xhr.status < 400) {
                      return {href:url, text:xhr.responseText};
                    } else {
                      return {href:url, text:''};
                    }
                  } catch (e) {
                    return {href:"$escaped", text:''};
                  }
                })();
                """
                ).trim()

            runCatching {
                val json = fragment.webView?.runJavaScriptSuspend(js) ?: "{}"
                val obj = try {
                    JSONObject(json)
                } catch (_: Throwable) {
                    // 일부 WebView 구현에서 문자열로 감싸져 반환될 수 있음
                    val unwrapped = json.trim().removeSurrounding("\"")
                    JSONObject(unwrapped)
                }
                val text = obj.optString("text", "")
                if (text.isNotEmpty()) {
                    cssCache[path] = text
                    Log.d(
                        "EpubPageCalc",
                        "[CSS DEBUG] 캐시 저장(XHR): ${obj.optString("href")} (length=${text.length})"
                    )
                } else {
                    Log.w("EpubPageCalc", "[CSS DEBUG] XHR 결과 비어있음: $path")
                }
            }.onFailure {
                Log.w("EpubPageCalc", "[CSS DEBUG] XHR 읽기 실패: $path -> ${it.message}")
            }
        }
    }
}