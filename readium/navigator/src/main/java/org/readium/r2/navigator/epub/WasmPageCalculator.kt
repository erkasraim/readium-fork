/*
 * Copyright 2024 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import android.content.Context
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Result of WASM-based page calculation.
 */
public data class WasmCalculationResult(
    val totalPages: Int,
    val status: Status,
    val measuredHeight: Double = 0.0
) {
    public enum class Status {
        SUCCESS,
        ERROR,
        FALLBACK_NEEDED
    }
}

/**
 * Sampling data collected from WebView for WASM calculation.
 */
public data class SamplingData(
    val viewportWidth: Int,
    val viewportHeight: Int,
    val fontMetrics: FontMetrics,
    val layoutMetrics: LayoutMetrics,
    val elementStyles: Map<String, ElementStyle> = emptyMap(),
    val cssVariables: Map<String, String> = emptyMap(),
    val bodyStyle: BodyStyle? = null
)

/**
 * Font metrics extracted from WebView.
 */
public data class FontMetrics(
    val fontSize: Float,
    val lineHeight: Float,
    val characterWidth: Float
)

/**
 * Layout metrics from WebView sampling.
 */
public data class LayoutMetrics(
    val contentWidth: Int,
    val contentHeight: Int,
    val marginTop: Int,
    val marginBottom: Int,
    val marginLeft: Int,
    val marginRight: Int,
    val paddingTop: Int = 0,
    val paddingBottom: Int = 0,
    val paddingLeft: Int = 0,
    val paddingRight: Int = 0
)

public data class ElementStyle(
    val fontSize: Int,
    val lineHeight: Int,
    val marginTop: Int,
    val marginRight: Int,
    val marginBottom: Int,
    val marginLeft: Int,
    val paddingTop: Int,
    val paddingRight: Int,
    val paddingBottom: Int,
    val paddingLeft: Int
)

public data class BodyStyle(
    val contentWidth: Int,
    val contentHeight: Int,
    val marginTop: Int,
    val marginRight: Int,
    val marginBottom: Int,
    val marginLeft: Int,
    val paddingTop: Int,
    val paddingRight: Int,
    val paddingBottom: Int,
    val paddingLeft: Int
)

/**
 * Interface for calculating the number of pages using WASM with sampling data.
 */
internal interface WasmPageCalculator {
    /**
     * Calculate total pages for HTML content using sampling JSON from WebView.
     */
    suspend fun calculatePages(
        html: String,
        cssText: String,
        samplingJson: String
    ): WasmCalculationResult

    /**
     * Register a CSS registry once per publication.
     */
    suspend fun registerCssRegistry(registryJson: String): Boolean

    /**
     * Calculate pages using registry-based bundling.
     */
    suspend fun calculatePagesWithRegistry(
        html: String,
        hrefsJson: String,
        inlineStylesJson: String,
        samplingJson: String
    ): WasmCalculationResult

    /**
     * Initialize WASM module if needed.
     */
    suspend fun initialize(): Boolean

    /**
     * Test WASM connection for debugging.
     */
    suspend fun testWasmConnection(): String?

    /**
     * Clean up resources and destroy WebView to prevent memory leaks.
     */
    fun destroy()
}

/**
 * Default implementation of WASM page calculator.
 */
internal class DefaultWasmPageCalculator(
    private val context: Context
) : WasmPageCalculator {

    private var isInitialized = false
    private var wasmWebView: WebView? = null
    private var wasmJsCode: String? = null

    // Toggle for WASM internal debug logs
    var enableDebugLogs: Boolean = false

    override suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        try {
            Log.d("WasmPageCalculator", "🦀 WASM 모듈 초기화 시작...")

            // WASM JavaScript 파일 로드
            Log.d("WasmPageCalculator", "📄 WASM JavaScript 파일 로드 시도...")
            wasmJsCode = loadWasmJavaScript()
            if (wasmJsCode == null) {
                Log.e("WasmPageCalculator", "❌ WASM JavaScript 파일 로드 실패")
                return@withContext false
            }
            Log.d("WasmPageCalculator", "✅ WASM JavaScript 파일 로드 완료 (${wasmJsCode!!.length} chars)")

            // WASM 바이너리 파일 로드
            Log.d("WasmPageCalculator", "💾 WASM 바이너리 파일 로드 시도...")
            val wasmBinary = loadWasmBinary()
            if (wasmBinary == null) {
                Log.e("WasmPageCalculator", "❌ WASM 바이너리 파일 로드 실패")
                return@withContext false
            }
            Log.d("WasmPageCalculator", "✅ WASM 바이너리 파일 로드 완료 (${wasmBinary.length} chars base64)")

            // WebView 생성 및 설정
            Log.d("WasmPageCalculator", "🌐 WASM WebView 생성 시도...")
            wasmWebView = createWasmWebView()
            if (wasmWebView == null) {
                Log.e("WasmPageCalculator", "❌ WASM WebView 생성 실패")
                return@withContext false
            }
            Log.d("WasmPageCalculator", "✅ WASM WebView 생성 완료")

            // WASM 모듈 로드
            Log.d("WasmPageCalculator", "🔧 WASM 모듈 로드 시도...")
            val loadSuccess = loadWasmModule(wasmWebView!!, wasmJsCode!!, wasmBinary)
            if (!loadSuccess) {
                Log.e("WasmPageCalculator", "❌ WASM 모듈 로드 실패")
                return@withContext false
            }
            Log.d("WasmPageCalculator", "✅ WASM 모듈 로드 완료")

            isInitialized = true
            Log.d("WasmPageCalculator", "✅ WASM 모듈 초기화 완료")
            true
        } catch (e: Exception) {
            Log.e("WasmPageCalculator", "❌ WASM 초기화 실패", e)
            false
        }
    }

    override suspend fun calculatePages(
        html: String,
        cssText: String,
        samplingJson: String
    ): WasmCalculationResult = withContext(Dispatchers.Main) {

        if (!isInitialized || wasmWebView == null) {
            Log.w("WasmPageCalculator", "⚠️ WASM 미초기화, fallback 사용")
            return@withContext WasmCalculationResult(
                totalPages = estimatePages(html, samplingJson),
                status = WasmCalculationResult.Status.FALLBACK_NEEDED,
                measuredHeight = 0.0
            )
        }

        try {
            Log.d("WasmPageCalculator", "🦀 WASM으로 페이지 계산 시작...")

            // WASM 함수 호출
            val result = callWasmCalculatePages(wasmWebView!!, html, cssText, samplingJson)

            if (result != null) {
                val resultJson = JSONObject(result)
                val totalPages = resultJson.optInt("totalPages", 1)
                val status = resultJson.optString("status", "ERROR")
                val measuredHeight = resultJson.optDouble("measured_height", 0.0)

                Log.d("WasmPageCalculator", "✅ WASM 계산 완료: $totalPages pages")

                WasmCalculationResult(
                    totalPages = totalPages,
                    status = if (status == "SUCCESS") WasmCalculationResult.Status.SUCCESS
                    else WasmCalculationResult.Status.ERROR,
                    measuredHeight = measuredHeight
                )
            } else {
                Log.w("WasmPageCalculator", "⚠️ WASM 계산 실패, fallback 사용")
                WasmCalculationResult(
                    totalPages = estimatePages(html, samplingJson),
                    status = WasmCalculationResult.Status.FALLBACK_NEEDED,
                    measuredHeight = 0.0
                )
            }
        } catch (e: Exception) {
            Log.e("WasmPageCalculator", "❌ WASM 계산 중 오류, fallback 사용", e)
            WasmCalculationResult(
                totalPages = estimatePages(html, samplingJson),
                status = WasmCalculationResult.Status.FALLBACK_NEEDED,
                measuredHeight = 0.0
            )
        }
    }

    override suspend fun registerCssRegistry(registryJson: String): Boolean =
        withContext(Dispatchers.Main) {
            if (!isInitialized || wasmWebView == null) return@withContext false
            try {
                suspendCancellableCoroutine { cont ->
                    val arg = JSONObject.quote(registryJson)
                    val js =
                        "typeof window.register_css_registry === 'function' ? window.register_css_registry($arg) : false"
                    wasmWebView!!.evaluateJavascript(js) { result ->
                        val ok = result == "true"
                        cont.resume(ok)
                    }
                }
            } catch (t: Throwable) {
                Log.w("WasmPageCalculator", "registerCssRegistry 실패: ${t.message}")
                false
            }
        }

    override suspend fun calculatePagesWithRegistry(
        html: String,
        hrefsJson: String,
        inlineStylesJson: String,
        samplingJson: String
    ): WasmCalculationResult = withContext(Dispatchers.Main) {
        if (!isInitialized || wasmWebView == null) {
            Log.w("WasmPageCalculator", "⚠️ WASM 미초기화, fallback 사용")
            return@withContext WasmCalculationResult(
                totalPages = estimatePages(html, samplingJson),
                status = WasmCalculationResult.Status.FALLBACK_NEEDED,
                measuredHeight = 0.0
            )
        }
        try {
            val htmlJs = JSONObject.quote(html)
            val hrefsJs = JSONObject.quote(hrefsJson)
            val inlineJs = JSONObject.quote(inlineStylesJson)
            val samplingJs = JSONObject.quote(samplingJson)
            val jsCode =
                "(function(){ if(typeof window.calculate_pages_with_registry!== 'function'){ return null;} return window.calculate_pages_with_registry($htmlJs,$hrefsJs,$inlineJs,$samplingJs, ${if (enableDebugLogs) "true" else "false"}); })()"
            suspendCancellableCoroutine { cont ->
                wasmWebView!!.evaluateJavascript(jsCode) { result ->
                    if (result != null && result != "null") {
                        val cleanResult = result.removePrefix("\"").removeSuffix("\"")
                            .replace("\\\"", "\"")
                            .replace("\\n", "\n")
                        cont.resume(
                            WasmCalculationResult(
                                totalPages = try {
                                    JSONObject(cleanResult).optInt("totalPages", 1)
                                } catch (_: Throwable) {
                                    1
                                },
                                status = try {
                                    if (JSONObject(cleanResult).optString(
                                            "status",
                                            "ERROR"
                                        ) == "SUCCESS"
                                    ) WasmCalculationResult.Status.SUCCESS else WasmCalculationResult.Status.ERROR
                                } catch (_: Throwable) {
                                    WasmCalculationResult.Status.ERROR
                                },
                                measuredHeight = try {
                                    JSONObject(cleanResult).optDouble("measured_height", 0.0)
                                } catch (_: Throwable) {
                                    0.0
                                }
                            )
                        )
                    } else {
                        cont.resume(
                            WasmCalculationResult(
                                1,
                                WasmCalculationResult.Status.FALLBACK_NEEDED,
                                0.0
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("WasmPageCalculator", "calculatePagesWithRegistry 실패: ${t.message}")
            WasmCalculationResult(1, WasmCalculationResult.Status.FALLBACK_NEEDED, 0.0)
        }
    }

    override suspend fun testWasmConnection(): String? = withContext(Dispatchers.Main) {
        if (!isInitialized || wasmWebView == null) {
            return@withContext "❌ WASM 모듈이 초기화되지 않음"
        }

        try {
            suspendCancellableCoroutine<String?> { continuation ->
                wasmWebView!!.evaluateJavascript("testWasm()") { result ->
                    val cleanResult = if (result != null && result != "null") {
                        result.removePrefix("\"").removeSuffix("\"")
                            .replace("\\\"", "\"")
                            .replace("\\n", "\n")
                    } else {
                        "❌ 테스트 결과가 null"
                    }
                    continuation.resume(cleanResult)
                }
            }
        } catch (e: Exception) {
            "❌ 테스트 실행 실패: ${e.message}"
        }
    }

    override fun destroy() {
        try {
            wasmWebView?.apply {
                // WebView 정리 단계
                stopLoading()
                clearHistory()
                clearCache(true)
                loadUrl("about:blank")
                onPause()
                removeAllViews()
                destroy()
            }
            wasmWebView = null
            wasmJsCode = null
            isInitialized = false
            Log.d("WasmPageCalculator", "🧹 WASM 모듈 리소스 정리 완료")
        } catch (e: Exception) {
            Log.e("WasmPageCalculator", "리소스 정리 중 오류", e)
        }
    }

    /**
     * WASM JavaScript 파일을 assets에서 로드
     */
    private suspend fun loadWasmJavaScript(): String? = withContext(Dispatchers.IO) {
        try {
            context.assets.open("wasm/epub_page_calculator.js").bufferedReader().use { reader ->
                reader.readText()
            }
        } catch (e: Exception) {
            Log.e("WasmPageCalculator", "WASM JS 파일 로드 실패", e)
            null
        }
    }

    /**
     * WASM 바이너리 파일을 assets에서 Base64로 로드
     */
    private suspend fun loadWasmBinary(): String? = withContext(Dispatchers.IO) {
        try {
            context.assets.open("wasm/epub_page_calculator_bg.wasm").use { inputStream ->
                val bytes = inputStream.readBytes()
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e("WasmPageCalculator", "WASM 바이너리 파일 로드 실패", e)
            null
        }
    }

    /**
     * WASM 전용 WebView 생성
     */
    private fun createWasmWebView(): WebView? {
        return try {
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                }
            }
        } catch (e: Exception) {
            Log.e("WasmPageCalculator", "WebView 생성 실패", e)
            null
        }
    }

    /**
     * WASM 모듈을 WebView에 로드
     */
    private suspend fun loadWasmModule(
        webView: WebView,
        wasmJs: String,
        wasmBinary: String
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            try {
                val htmlContent = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="utf-8">
                        <title>WASM Page Calculator</title>
                    </head>
                    <body>
                        <script>
                            $wasmJs

                            // WASM 모듈 초기화 플래그
                            let wasmInitialized = false;
                            let wasmModule = null;

                            // Base64 WASM 바이너리 디코딩 함수
                            function base64ToArrayBuffer(base64) {
                                try {
                                    const binaryString = atob(base64);
                                    const bytes = new Uint8Array(binaryString.length);
                                    for (let i = 0; i < binaryString.length; i++) {
                                        bytes[i] = binaryString.charCodeAt(i);
                                    }
                                    return bytes.buffer;
                                } catch (e) {
                                    console.error('Base64 디코딩 실패:', e);
                                    return null;
                                }
                            }

                            // WASM 초기화 (직접 코드 삽입, 모듈 시스템 제거)
                            async function initializeWasm() {
                                try {
                                    console.log('🦀 WASM 모듈 로딩 시작...');

                                    // Base64 인코딩된 WASM 바이너리 디코딩
                                    const wasmBinary = base64ToArrayBuffer('$wasmBinary');
                                    if (!wasmBinary) {
                                        throw new Error('WASM 바이너리 디코딩 실패');
                                    }

                                    console.log('🔧 WASM 바이너리 크기:', wasmBinary.byteLength, 'bytes');

                                    // 직접 WASM init 함수 호출
                                    if (typeof window.__wbg_init === 'function') {
                                        wasmModule = await window.__wbg_init(wasmBinary);
                                    } else if (typeof window.initSync === 'function') {
                                        wasmModule = window.initSync(wasmBinary);
                                    } else if (typeof window.init === 'function') {
                                        wasmModule = await window.init(wasmBinary);
                                    } else {
                                        // 사용 가능한 init 함수들 확인
                                        const availableFunctions = Object.keys(window).filter(key =>
                                            typeof window[key] === 'function' && key.includes('init')
                                        );
                                        console.log('사용 가능한 init 함수들:', availableFunctions);
                                        throw new Error('WASM init 함수를 찾을 수 없음: ' + availableFunctions.join(', '));
                                    }

                                    wasmInitialized = true;
                                    console.log('✅ WASM 모듈 로딩 완료');

                                    // 테스트 함수 호출 확인
                                    if (typeof window.test_wasm_connection === 'function') {
                                        const testResult = window.test_wasm_connection();
                                        console.log('🧪 WASM 테스트 결과:', testResult);
                                    }

                                    // Kotlin에 성공 신호 전송
                                    console.log('🚀 Kotlin에 성공 신호 전송...');
                                    window.wasmReady = true;
                                    console.log('✅ window.wasmReady 설정 완료');
                                } catch (error) {
                                    console.error('❌ WASM 모듈 로딩 실패:', error);
                                    window.wasmError = error.toString();
                                }
                            }

                            // 페이지 계산 함수 (간단 모듈 참조, ES6 import 없음)
                            window.calculatePagesWasm = function(html, cssText, samplingDataJson, debugLogging) {
                                try {
                                    if (!wasmInitialized) {
                                        throw new Error('WASM 모듈이 초기화되지 않음');
                                    }

                                    if (debugLogging) console.log('🦀 WASM 페이지 계산 시작...');
                                    if (debugLogging) console.log('📄 HTML 길이:', html.length);
                                    if (debugLogging) console.log('🎨 CSS 길이:', (cssText || '').length);
                                    if (debugLogging) console.log('📊 샘플링 데이터:', samplingDataJson);

                                    const result = window.calculate_pages_with_css(html, cssText || '', samplingDataJson, !!debugLogging);
                                        
                                    if (debugLogging) console.log('✅ WASM 페이지 계산 완료:', result);

                                    return result;
                                } catch (error) {
                                    if (debugLogging) console.error('❌ WASM 페이지 계산 실패:', error);
                                    return JSON.stringify({
                                        totalPages: 1,
                                        measured_height: 0,
                                        status: 'ERROR'
                                    });
                                }
                            };

                            // 새: 레지스트리 등록/레지스트리 기반 계산 래퍼
                            window.registerCssRegistryWasm = function(registryJson) {
                                try {
                                    if (typeof window.register_css_registry !== 'function') return false;
                                    return !!window.register_css_registry(registryJson);
                                } catch (e) { return false; }
                            };
                            window.calculatePagesWithRegistryWasm = function(html, hrefsJson, inlineStylesJson, samplingDataJson, debugLogging) {
                                try {
                                    if (!wasmInitialized) throw new Error('WASM 모듈이 초기화되지 않음');
                                    if (typeof window.calculate_pages_with_registry !== 'function') return null;
                                    return window.calculate_pages_with_registry(html, hrefsJson, inlineStylesJson, samplingDataJson, !!debugLogging);
                                } catch (e) { return null; }
                            };

                            // 테스트 함수 (간단 참조)
                            window.testWasm = function() {
                                try {
                                    if (typeof window.test_wasm_connection !== 'undefined') {
                                        return window.test_wasm_connection();
                                    } else if (typeof window.get_wasm_version !== 'undefined') {
                                        return '🦀 WASM 버전: ' + window.get_wasm_version();
                                    } else {
                                        return '🦀 WASM 모듈이 로드되었지만 테스트 함수가 없음';
                                    }
                                } catch (error) {
                                    return '❌ WASM 테스트 실패: ' + error.toString();
                                }
                            };

                            // WASM 초기화 시작
                            initializeWasm();
                        </script>
                    </body>
                    </html>
                """.trimIndent()

                webView.loadDataWithBaseURL(
                    "https://readium/assets/",
                    htmlContent,
                    "text/html",
                    "UTF-8",
                    null
                )

                // 초기화 완료 대기 (최대 15초로 증가)
                var attempts = 0
                val maxAttempts = 60 // 15초 (250ms * 60)

                fun checkInitialization() {
                    Log.d("WasmPageCalculator", "🔍 초기화 상태 체크 중... (attempt: $attempts)")
                    webView.evaluateJavascript("window.wasmReady || window.wasmError || 'waiting'") { result ->
                        Log.d("WasmPageCalculator", "🔍 JavaScript 응답: '$result'")
                        when {
                            result?.contains("true") == true -> {
                                Log.d("WasmPageCalculator", "✅ WASM 모듈 초기화 완료")
                                // 테스트 함수도 실행해보기
                                webView.evaluateJavascript("testWasm()") { testResult ->
                                    Log.d("WasmPageCalculator", "🧪 WASM 테스트 결과: $testResult")
                                }
                                continuation.resume(true)
                            }

                            result?.contains("waiting") != true -> {
                                Log.e("WasmPageCalculator", "❌ WASM 모듈 초기화 실패: $result")
                                continuation.resume(false)
                            }

                            attempts >= maxAttempts -> {
                                Log.e(
                                    "WasmPageCalculator",
                                    "❌ WASM 모듈 초기화 타임아웃 (${maxAttempts * 250}ms)"
                                )
                                continuation.resume(false)
                            }

                            else -> {
                                attempts++
                                Log.d(
                                    "WasmPageCalculator",
                                    "⏳ 대기 중... (attempt: $attempts/$maxAttempts)"
                                )
                                // Handler 사용하여 다음 체크 스케줄링
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    checkInitialization()
                                }, 250)
                            }
                        }
                    }
                }

                Log.d("WasmPageCalculator", "🕒 1초 후 초기화 상태 체크 시작...")
                // 첫 번째 체크를 Handler로 스케줄링
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    checkInitialization()
                }, 1000) // TODO : 1초 안해도 되지않나??
            } catch (e: Exception) {
                Log.e("WasmPageCalculator", "WASM 모듈 로드 실패", e)
                continuation.resume(false)
            }
        }

    /**
     * WebView에서 WASM 함수 호출
     */
    private suspend fun callWasmCalculatePages(
        webView: WebView,
        html: String,
        cssText: String,
        samplingJson: String
    ): String? = suspendCancellableCoroutine { continuation ->
        try {
            val htmlJs = JSONObject.quote(html)
            val cssJs = JSONObject.quote(cssText)
            val samplingJs = JSONObject.quote(samplingJson)
            val jsCode = "calculatePagesWasm($htmlJs, $cssJs, $samplingJs, ${if (enableDebugLogs) "true" else "false"})"

            webView.evaluateJavascript(jsCode) { result ->
                if (result != null && result != "null") {
                    // JavaScript에서 반환된 결과에서 따옴표 제거
                    val cleanResult = result.removePrefix("\"").removeSuffix("\"")
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")

                    continuation.resume(cleanResult)
                } else {
                    Log.e("WasmPageCalculator", "❌ WASM 함수 호출 결과가 null")
                    continuation.resume(null)
                }
            }
        } catch (e: Exception) {
            Log.e("WasmPageCalculator", "❌ WASM 함수 호출 실패", e)
            continuation.resume(null)
        }
    }

    /**
     * Simple estimation algorithm as fallback for WASM implementation.
     */
    private fun estimatePages(html: String, samplingJson: String): Int {
        Log.e("WasmPageCalculator", "🔥 estimatePages() 폴백 함수가 호출됨!")

        // Extract title or first heading for identification
        val titleRegex = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
        val h1Regex = Regex("<h1[^>]*>([^<]*)</h1>", RegexOption.IGNORE_CASE)
        val identifier = titleRegex.find(html)?.groupValues?.get(1)?.trim()
            ?: h1Regex.find(html)?.groupValues?.get(1)?.trim()
            ?: "Unknown"

        // Strip HTML tags for rough content estimation
        val textContent = html.replace(Regex("<[^>]+>"), "")
        val contentLength = textContent.length

        // Parse sampling JSON to extract layout metrics
        val samplingData = try {
            JSONObject(samplingJson)
        } catch (e: Exception) {
            Log.w("WasmPageCalculator", "샘플링 JSON 파싱 실패, 기본값 사용", e)
            null
        }

        val viewportWidth = samplingData?.optInt("viewportWidth", 800) ?: 800
        val viewportHeight = samplingData?.optInt("viewportHeight", 600) ?: 600
        val contentWidth = samplingData?.optInt("contentWidth", 800) ?: 800
        val contentHeight = samplingData?.optInt("contentHeight", 600) ?: 600
        val fontSize = samplingData?.optDouble("fontSize", 16.0)?.toFloat() ?: 16.0f
        val lineHeight = samplingData?.optDouble("lineHeight", 24.0)?.toFloat() ?: 24.0f
        val characterWidth = samplingData?.optDouble("characterWidth", 8.0)?.toFloat() ?: 8.0f

        val charsPerLine = (contentWidth / characterWidth).toInt()
        val linesPerPage = (contentHeight / lineHeight).toInt()
        val charsPerPage = charsPerLine * linesPerPage

        Log.i("WasmPageCalculator", "=== Fallback Page Calculation for: $identifier ===")
        Log.i("WasmPageCalculator", "HTML size: ${html.length} chars")
        Log.i("WasmPageCalculator", "Text content length: $contentLength chars")
        Log.i("WasmPageCalculator", "Viewport: $viewportWidth x $viewportHeight")
        Log.i("WasmPageCalculator", "Font size: $fontSize")
        Log.i("WasmPageCalculator", "Line height: $lineHeight")
        Log.i("WasmPageCalculator", "Character width: $characterWidth")
        Log.i("WasmPageCalculator", "Content area: $contentWidth x $contentHeight")
        Log.i("WasmPageCalculator", "Chars per line: $charsPerLine")
        Log.i("WasmPageCalculator", "Lines per page: $linesPerPage")
        Log.i("WasmPageCalculator", "Chars per page: $charsPerPage")

        return if (charsPerPage > 0) {
            val estimatedPages = kotlin.math.ceil(contentLength.toDouble() / charsPerPage).toInt()
            Log.i("WasmPageCalculator", "✅ '$identifier' -> $estimatedPages pages (fallback)")
            Log.i("WasmPageCalculator", "==================================================")
            estimatedPages
        } else {
            Log.w(
                "WasmPageCalculator",
                "⚠️ '$identifier' -> 1 page (fallback - charsPerPage was 0)"
            )
            Log.i("WasmPageCalculator", "==================================================")
            1
        }
    }
}
