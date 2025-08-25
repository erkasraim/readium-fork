use wasm_bindgen::prelude::*;
use web_sys::*;
use serde::{Deserialize, Serialize, Deserializer};
use std::collections::HashMap;

/// CSS 길이 문자열을 f64(px)로 파싱 (예: "12px" → 12.0)
fn parse_css_px(value: &str) -> f64 {
    let trimmed = value.trim();
    if trimmed.is_empty() || trimmed == "auto" { return 0.0; }
    let no_px = trimmed.strip_suffix("px").unwrap_or(trimmed);
    no_px.parse::<f64>().unwrap_or(0.0)
}

// (removed) string_to_i32: unused
// (removed) flexible_f32: only used by removed FontMetrics

// Optional string to i32 (handles both string and int inputs)
fn opt_string_to_i32<'de, D>(deserializer: D) -> Result<i32, D::Error>
where
    D: Deserializer<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum StringOrInt {
        String(String),
        Int(i32),
    }

    match StringOrInt::deserialize(deserializer)? {
        StringOrInt::String(s) => s.parse().map_err(serde::de::Error::custom),
        StringOrInt::Int(i) => Ok(i),
    }
}

// 초기화 시 panic hook 설정
#[wasm_bindgen(start)]
pub fn main() {
    console_error_panic_hook::set_once();
}

// 유틸리티 함수들
#[wasm_bindgen]
extern "C" {
    #[wasm_bindgen(js_namespace = console)]
    fn log(s: &str);
}

macro_rules! console_log {
    ($($t:tt)*) => (log(&format_args!($($t)*).to_string()))
}

/// 고급 타이포/렌더링 설정 및 폭 지표를 로깅
fn debug_log_advanced_layout(window: &Window, document: &Document, container: &Element) {
    // devicePixelRatio
    let dpr = window.device_pixel_ratio();
    console_log!("🖥️ devicePixelRatio={}", dpr);

    // body: text-size-adjust 등과 width 지표
    if let Some(body) = document.body() {
        if let Ok(Some(cs)) = window.get_computed_style(&body) {
            let tsa_wk = cs.get_property_value("-webkit-text-size-adjust").unwrap_or_default();
            let tsa_std = cs.get_property_value("text-size-adjust").unwrap_or_default();
            let tr = cs.get_property_value("text-rendering").unwrap_or_default();
            let fk = cs.get_property_value("font-kerning").unwrap_or_default();
            let ffs = cs.get_property_value("font-feature-settings").unwrap_or_default();
            let fvl = cs.get_property_value("font-variant-ligatures").unwrap_or_default();
            console_log!(
                "🔧 [body] -webkit-text-size-adjust={}, text-size-adjust={}, text-rendering={}, font-kerning={}, font-feature-settings={}, font-variant-ligatures={}",
                tsa_wk, tsa_std, tr, fk, ffs, fvl
            );
        }
        if let Some(he) = body.dyn_ref::<HtmlElement>() {
            console_log!(
                "📐 [body widths] client={}px, scroll={}px",
                he.client_width(), he.scroll_width()
            );
        }
    }

    // container: text-size-adjust 등과 width 지표
    if let Ok(Some(cs)) = window.get_computed_style(container) {
        let tsa_wk = cs.get_property_value("-webkit-text-size-adjust").unwrap_or_default();
        let tsa_std = cs.get_property_value("text-size-adjust").unwrap_or_default();
        let tr = cs.get_property_value("text-rendering").unwrap_or_default();
        let fk = cs.get_property_value("font-kerning").unwrap_or_default();
        let ffs = cs.get_property_value("font-feature-settings").unwrap_or_default();
        let fvl = cs.get_property_value("font-variant-ligatures").unwrap_or_default();
        console_log!(
            "🔧 [container] -webkit-text-size-adjust={}, text-size-adjust={}, text-rendering={}, font-kerning={}, font-feature-settings={}, font-variant-ligatures={}",
            tsa_wk, tsa_std, tr, fk, ffs, fvl
        );
    }
    if let Some(he) = container.dyn_ref::<HtmlElement>() {
        let rect = container.get_bounding_client_rect();
        console_log!(
            "📐 [container widths] client={}px, scroll={}px, bcr.width={}px",
            he.client_width(), he.scroll_width(), rect.width()
        );
    }

    // :root: text-size-adjust 등과 width 지표
    if let Some(root) = document.document_element() {
        if let Ok(Some(cs)) = window.get_computed_style(&root) {
            let tsa_wk = cs.get_property_value("-webkit-text-size-adjust").unwrap_or_default();
            let tsa_std = cs.get_property_value("text-size-adjust").unwrap_or_default();
            let tr = cs.get_property_value("text-rendering").unwrap_or_default();
            let fk = cs.get_property_value("font-kerning").unwrap_or_default();
            let ffs = cs.get_property_value("font-feature-settings").unwrap_or_default();
            let fvl = cs.get_property_value("font-variant-ligatures").unwrap_or_default();
            console_log!(
                "🔧 [:root] -webkit-text-size-adjust={}, text-size-adjust={}, text-rendering={}, font-kerning={}, font-feature-settings={}, font-variant-ligatures={}",
                tsa_wk, tsa_std, tr, fk, ffs, fvl
            );
        }
        if let Some(root_he) = root.dyn_ref::<HtmlElement>() {
            console_log!(
                "📐 [:root widths] client={}px, scroll={}px",
                root_he.client_width(), root_he.scroll_width()
            );
        }
    }
}

macro_rules! console_log {
    ($($t:tt)*) => (log(&format_args!($($t)*).to_string()))
}

/// 주입된 CSS가 실제로 적용되었는지 확인하기 위한 디버그 로거
fn debug_log_computed_styles(window: &Window, container: &Element) {
    // 컨테이너 자체 스타일
    if let Ok(Some(cs)) = window.get_computed_style(container) {
        let bx = cs.get_property_value("box-sizing").unwrap_or_default();
        let ff = cs.get_property_value("font-family").unwrap_or_default();
        let fs = cs.get_property_value("font-size").unwrap_or_default();
        let lh = cs.get_property_value("line-height").unwrap_or_default();
        let mt = cs.get_property_value("margin-top").unwrap_or_default();
        let mb = cs.get_property_value("margin-bottom").unwrap_or_default();
        let pad_left = cs.get_property_value("padding-left").unwrap_or_default();
        let pad_right = cs.get_property_value("padding-right").unwrap_or_default();
        let hy = cs.get_property_value("hyphens").unwrap_or_default();
        let ls = cs.get_property_value("letter-spacing").unwrap_or_default();
        let ws = cs.get_property_value("word-spacing").unwrap_or_default();
        let rs_gutter = cs.get_property_value("--RS__pageGutter").unwrap_or_default();
        let rs_mll = cs.get_property_value("--RS__maxLineLength").unwrap_or_default();
        console_log!(
            "🔍 [container] box-sizing={}, font={}, font-size={}, line-height={}, mt={}, mb={}, padding-left={}, padding-right={}, hyphens={}, letter-spacing={}, word-spacing={}, --RS__pageGutter={}, --RS__maxLineLength={}",
            bx, ff, fs, lh, mt, mb, pad_left, pad_right, hy, ls, ws, rs_gutter, rs_mll
        );
    }

    // 첫 번째 in-flow 자손 엘리먼트 스타일
    if let Ok(Some(first)) = container.query_selector("h1, h2, h3, figure, tr, hr, table, img") {
        if let Some(first_el) = first.dyn_ref::<Element>() {
            if let Ok(Some(cs)) = window.get_computed_style(first_el) {
                let tag = first_el.tag_name();
                let ff = cs.get_property_value("font-family").unwrap_or_default();
                let fs = cs.get_property_value("font-size").unwrap_or_default();
                let lh = cs.get_property_value("line-height").unwrap_or_default();
                let mt = cs.get_property_value("margin-top").unwrap_or_default();
                let mb = cs.get_property_value("margin-bottom").unwrap_or_default();
                let pad_left = cs.get_property_value("padding-left").unwrap_or_default();
                let pad_right = cs.get_property_value("padding-right").unwrap_or_default();
                let hy = cs.get_property_value("hyphens").unwrap_or_default();
                let ls = cs.get_property_value("letter-spacing").unwrap_or_default();
                let ws = cs.get_property_value("word-spacing").unwrap_or_default();
                console_log!(
                    "🔍 [first:{}] font={}, font-size={}, line-height={}, mt={}, mb={}, padding-left={}, padding-right={}, hyphens={}, letter-spacing={}, word-spacing={}",
                    tag, ff, fs, lh, mt, mb, pad_left, pad_right, hy, ls, ws
                );
                let bi = cs.get_property_value("break-inside").unwrap_or_default();
                let ba = cs.get_property_value("break-after").unwrap_or_default();
                console_log!("🔎 [first:{}] break-inside={}, break-after={}, -webkit-column-break-inside={}, -webkit-column-break-after={}", tag, bi, ba, cs.get_property_value("-webkit-column-break-inside").unwrap_or_default(), cs.get_property_value("-webkit-column-break-after").unwrap_or_default());
            }
        }
    }
}

/// CSS 변수를 :root(html)에도 적용하여 상속 기반 규칙이 정상 동작하도록 함
fn apply_css_variables_to_root(document: &Document, variables: &HashMap<String, String>) {
    if let Some(root) = document.document_element() {
        if let Some(root_el) = root.dyn_ref::<HtmlElement>() {
            let root_style = root_el.style();
            for (key, value) in variables {
                let _ = root_style.set_property(key, value);
                console_log!("set {} = {}", key, value);
            }
        }
    }
}

/// :root와 body의 주요 computed style을 로깅하여 전역 CSS 적용 확인
fn debug_log_root_body(window: &Window, document: &Document) {
    if let Some(root) = document.document_element() {
        if let Ok(Some(cs)) = window.get_computed_style(&root) {
            let ff = cs.get_property_value("font-family").unwrap_or_default();
            let fs = cs.get_property_value("font-size").unwrap_or_default();
            let lh = cs.get_property_value("line-height").unwrap_or_default();
            let pad_left = cs.get_property_value("padding-left").unwrap_or_default();
            let pad_right = cs.get_property_value("padding-right").unwrap_or_default();
            let rs_gutter = cs.get_property_value("--RS__pageGutter").unwrap_or_default();
            let rs_mll = cs.get_property_value("--RS__maxLineLength").unwrap_or_default();
            console_log!("🔍 [:root] font={}, font-size={}, line-height={}, padding-left={}, padding-right={}", ff, fs, lh, pad_left, pad_right);
            console_log!("🔍 [:root] --RS__pageGutter={}, --RS__maxLineLength={}", rs_gutter, rs_mll);
        }
    }
    if let Some(body) = document.body() {
        if let Ok(Some(cs)) = window.get_computed_style(&body) {
            let ff = cs.get_property_value("font-family").unwrap_or_default();
            let fs = cs.get_property_value("font-size").unwrap_or_default();
            let lh = cs.get_property_value("line-height").unwrap_or_default();
            let pad_left = cs.get_property_value("padding-left").unwrap_or_default();
            let pad_right = cs.get_property_value("padding-right").unwrap_or_default();
            console_log!("🔍 [body] font={}, font-size={}, line-height={}, padding-left={}, padding-right={}", ff, fs, lh, pad_left, pad_right);
        }
    }
}

#[derive(Debug, Clone)]
struct RootPrevAttrs {
    prev_style: Option<String>,
    prev_lang: Option<String>,
    prev_dir: Option<String>,
    prev_writing_mode: Option<String>,
}

/// :root에 style/lang/dir/data-writing-mode를 적용하고 이전 값을 반환 (로그 포함)
fn apply_root_context_and_log(
    window: &Window,
    document: &Document,
    sampling_data: &SamplingData,
    effective_lang: &str,
) -> RootPrevAttrs {
    let mut prev = RootPrevAttrs {
        prev_style: None,
        prev_lang: None,
        prev_dir: None,
        prev_writing_mode: None,
    };
    if let Some(root) = document.document_element() {
        prev.prev_style = root.get_attribute("style");
        prev.prev_lang = root.get_attribute("lang");
        prev.prev_dir = root.get_attribute("dir");
        prev.prev_writing_mode = root.get_attribute("data-writing-mode");

        if let Some(style_attr) = &sampling_data.root_style_attr {
            let _ = root.set_attribute("style", style_attr);
            console_log!("🧭 :root style 복제 적용: {}", style_attr);
        }
        if !effective_lang.is_empty() { let _ = root.set_attribute("lang", effective_lang); }
        if let Some(dir) = sampling_data.document_dir.as_ref() { if !dir.is_empty() { let _ = root.set_attribute("dir", dir); } }
        if let Some(wm) = sampling_data.document_writing_mode.as_ref() { if !wm.is_empty() { let _ = root.set_attribute("data-writing-mode", wm); } }

        // 로깅
        let applied_root_lang = root.get_attribute("lang").unwrap_or_default();
        let applied_root_dir = root.get_attribute("dir").unwrap_or_default();
        let applied_root_wm = root.get_attribute("data-writing-mode").unwrap_or_default();
        let root_wm_computed = window
            .get_computed_style(&root)
            .ok()
            .flatten()
            .map(|cs| cs.get_property_value("writing-mode").unwrap_or_default())
            .unwrap_or_default();
        console_log!(
            "🧾 [:root attrs] lang='{}', dir='{}', data-writing-mode='{}' | computed writing-mode='{}'",
            applied_root_lang, applied_root_dir, applied_root_wm, root_wm_computed
        );
    }
    prev
}

//// Paged 모드에서 :root에 멀티컬럼과 100vh 높이를 강제 적용
fn inject_paged_root_css(sheet_el: &HtmlElement, viewport_height: i32) {
    let scoped = format!(
        r#"
    :root[data-wasm-paged] {{
        /* 높이: 100vh + px fallback (마지막 규칙 우선) */
        height: 100vh !important;
        max-height: 100vh !important;
        min-height: 100vh !important;
        height: {vh}px !important;
        max-height: {vh}px !important;
        min-height: {vh}px !important;

        /* 멀티컬럼 */
        -webkit-column-fill: auto !important;
        column-fill: auto !important;
        -webkit-column-width: var(--RS__colWidth) !important;
        column-width: var(--RS__colWidth) !important;
        -webkit-column-gap: var(--RS__colGap) !important;
        column-gap: var(--RS__colGap) !important;
        -webkit-column-count: var(--RS__colCount) !important;
        column-count: var(--RS__colCount) !important;

        /* 박스 모델 안정화 */
        box-sizing: border-box !important;
    }}
    "#,
        vh = viewport_height
    );
    let new_inner = format!("{}\n{}", sheet_el.inner_html(), scoped);
    sheet_el.set_inner_html(&new_inner);
    console_log!("🧩 paged 모드 :root 멀티컬럼+뷰포트 높이 강제 적용");
}

// Scroll 모드에서 WebView와의 폭/거터 정합성을 맞추기 위한 최소 보정을 주입
fn inject_scroll_mode_container_css(sheet_el: &HtmlElement) {
    // 컨테이너에 body의 레이아웃 핵심 속성(max-width, page gutter)을 반영
    let scoped = r#"
    div[data-wasm-container] {
        width: 100% !important;
        max-width: var(--RS__maxLineLength) !important;
        margin-left: auto !important;
        margin-right: auto !important;
        box-sizing: border-box !important;
        padding-left:  calc(var(--RS__pageGutter) * var(--USER__pageMargins, 1)) !important;
        padding-right: calc(var(--RS__pageGutter) * var(--USER__pageMargins, 1)) !important;
    }
    "#;
    let new_inner = format!("{}\n{}", sheet_el.inner_html(), scoped);
    sheet_el.set_inner_html(&new_inner);
    console_log!("🛠️ scroll 모드 컨테이너 보정(max-width, page gutter) 적용");
}

/// Paged 모드에서 컨테이너 폭/거터를 최소한으로 반영해 높이 측정 보정
fn inject_paged_mode_container_css(sheet_el: &HtmlElement) {
    // columns 는 :root 가 담당하므로 여기서는 폭/거터만 최소 반영
    // 주의: body가 이미 max-width와 page gutter를 적용하므로, 중복을 피하기 위해 padding/max-width는 주입하지 않음
    let scoped = r#"
    div[data-wasm-container] {
        width: 100% !important;
        margin-left: auto !important;
        margin-right: auto !important;
        box-sizing: border-box !important;
        padding-left: 0 !important;
        padding-right: 0 !important;
    }
    "#;
    let new_inner = format!("{}\n{}", sheet_el.inner_html(), scoped);
    sheet_el.set_inner_html(&new_inner);
    console_log!("🛠️ paged 모드 컨테이너 보정(width, center, box-sizing만) 적용");
}

/// 샘플링 데이터 구조체 (Kotlin과 동일)
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct SamplingData {
    #[serde(rename = "viewportWidth")]
    pub viewport_width: i32,
    #[serde(rename = "viewportHeight")]
    pub viewport_height: i32,
    #[serde(rename = "cssVariables")]
    pub css_variables: CssVariables,
    #[serde(rename = "bodyStyle")]
    pub body_style: Option<BodyStyle>,
    #[serde(rename = "rootStyleAttr")]
    pub root_style_attr: Option<String>,
    #[serde(rename = "documentLang")]
    pub document_lang: Option<String>,
    #[serde(rename = "documentDir")]
    pub document_dir: Option<String>,
    #[serde(rename = "documentWritingMode")]
    pub document_writing_mode: Option<String>,
    // 이미지 치수 맵 (키: src 또는 경로, 값: width/height)
    #[serde(rename = "imageDimensions")]
    pub image_dimensions: Option<HashMap<String, ImageDimension>>,
}

// (removed) FontMetrics: no longer needed

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct CssVariables {
    #[serde(flatten)]
    pub vars: HashMap<String, String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct BodyStyle {
    #[serde(rename = "contentWidth")]
    #[serde(deserialize_with = "opt_string_to_i32")]
    pub content_width: i32,
    #[serde(rename = "contentHeight")]
    #[serde(deserialize_with = "opt_string_to_i32")]
    pub content_height: i32,
    #[serde(rename = "marginTop")]
    #[serde(deserialize_with = "opt_string_to_i32")]
    pub margin_top: i32,
    #[serde(rename = "marginRight")]
    #[serde(deserialize_with = "opt_string_to_i32")]
    pub margin_right: i32,
    #[serde(rename = "marginBottom")]
    #[serde(deserialize_with = "opt_string_to_i32")]
    pub margin_bottom: i32,
    #[serde(rename = "marginLeft")]
    #[serde(deserialize_with = "opt_string_to_i32")]
    pub margin_left: i32,
    #[serde(rename = "paddingTop")]
    #[serde(deserialize_with = "opt_string_to_i32")]
    pub padding_top: i32,
    #[serde(rename = "paddingRight")]
    #[serde(deserialize_with = "opt_string_to_i32")]
    pub padding_right: i32,
    #[serde(rename = "paddingBottom")]
    #[serde(deserialize_with = "opt_string_to_i32")]
    pub padding_bottom: i32,
    #[serde(rename = "paddingLeft")]
    #[serde(deserialize_with = "opt_string_to_i32")]
    pub padding_left: i32,
}

/// EPUB 내 이미지의 고정 치수
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ImageDimension {
    pub width: i32,
    pub height: i32,
}

/// 페이지 계산 결과 구조체
#[derive(Serialize, Deserialize, Debug)]
pub struct PageCalculationResult {
    #[serde(rename = "totalPages")]
    pub total_pages: i32,
    pub status: String,
}

/// WASM에서 호출될 메인 함수 - 페이지 수 계산
#[wasm_bindgen]
pub fn calculate_pages(html: &str, sampling_data_json: &str) -> String {
    console_log!("🦀 WASM calculate_pages 호출됨");
    // Backward compatibility: no external CSS provided
    calculate_pages_with_css(html, "", sampling_data_json)
}

/// WASM에서 호출될 메인 함수 - 페이지 수 계산 (외부 CSS 포함)
#[wasm_bindgen]
pub fn calculate_pages_with_css(html: &str, css_text: &str, sampling_data_json: &str) -> String {
    console_log!("🦀 WASM calculate_pages_with_css 호출됨");
    console_log!("HTML 길이: {} chars", html.len());
    console_log!("샘플링 데이터: {}", sampling_data_json);
    console_log!("🎨 외부 CSS 길이: {} chars", css_text.len());

    // JSON 파싱
    let sampling_data: SamplingData = match serde_json::from_str(sampling_data_json) {
        Ok(data) => data,
        Err(e) => {
            console_log!("❌ JSON 파싱 실패: {}", e);
            let error_result = PageCalculationResult {
                total_pages: 0,
                status: "ERROR".to_string(),
            };
            return serde_json::to_string(&error_result).unwrap_or_default();
        }
    };

    // 페이지 계산 실행: 항상 with-CSS 경로 사용
    let result = calculate_pages_internal_with_css(html, css_text, &sampling_data);
    
    console_log!("✅ 계산 완료: {} pages", result.total_pages);

    // 결과를 JSON으로 변환
    serde_json::to_string(&result).unwrap_or_default()
}

/// 외부 CSS가 있는 경우의 페이지 계산 로직
fn calculate_pages_internal_with_css(html: &str, css_text: &str, sampling_data: &SamplingData) -> PageCalculationResult {
    console_log!("🧮 페이지 계산 시작");

    let window = match window() {
        Some(w) => w,
        None => {
            console_log!("❌ Window 객체를 찾을 수 없음");
            return PageCalculationResult {
                total_pages: 1,
                status: "ERROR".to_string(),
            };
        }
    };

    let document = match window.document() {
        Some(d) => d,
        None => {
            console_log!("❌ Document 객체를 찾을 수 없음");
            return PageCalculationResult {
                total_pages: 1,
                status: "ERROR".to_string(),
            };
        }
    };

    // 가상 컨테이너 생성
    let container = match document.create_element("div") {
        Ok(elem) => elem,
        Err(_) => {
            console_log!("❌ 컨테이너 엘리먼트 생성 실패");
            return PageCalculationResult {
                total_pages: 1,
                status: "ERROR".to_string(),
            };
        }
    };

    // 래퍼 생성 (뷰포트 폭 고정 + 오프스크린 고정 배치)
    let wrapper = match document.create_element("div") {
        Ok(elem) => elem,
        Err(_) => {
            console_log!("❌ 래퍼 엘리먼트 생성 실패");
            return PageCalculationResult {
                total_pages: 1,
                status: "ERROR".to_string(),
            };
        }
    };

    // HTML 콘텐츠 설정
    container.set_inner_html(html);

    // CSS 스타일 적용
    let html_element = container.dyn_ref::<HtmlElement>().unwrap();
    let style = html_element.style();
    // 언어 결정 (샘플링 → DOM → navigator 순으로 fallback)
    let mut effective_lang = sampling_data.document_lang.clone().unwrap_or_default();
    if effective_lang.is_empty() {
        // try existing DOM attrs first
        if let Some(root) = document.document_element() {
            if effective_lang.is_empty() {
                effective_lang = root.get_attribute("lang").unwrap_or_default();
            }
        }
        if effective_lang.is_empty() {
            if let Some(b) = document.body() {
                effective_lang = b.get_attribute("lang").unwrap_or_default();
            }
        }
        // try meta http-equiv=content-language
        if effective_lang.is_empty() {
            if let Ok(Some(meta)) = document.query_selector("meta[http-equiv='content-language']") {
                effective_lang = meta.get_attribute("content").unwrap_or_default();
            } else if let Ok(Some(meta)) = document.query_selector("meta[http-equiv='Content-Language']") {
                effective_lang = meta.get_attribute("content").unwrap_or_default();
            }
        }
    }
    // 컨테이너에는 lang/dir/data-writing-mode를 설정하지 않음 (:root만 적용)
    
    // wrapper 스타일 핸들
    let wrapper_html = wrapper.dyn_ref::<HtmlElement>().unwrap();
    let wrapper_style = wrapper_html.style();
    
    // container div에 식별 속성 추가 (CSS 선택자용)
    html_element.set_attribute("data-wasm-container", "true").unwrap_or_default();
    // wrapper에도 식별자 추가
    wrapper_html.set_attribute("data-wasm-wrapper", "true").unwrap_or_default();

    // 컨테이너는 body와 유사하게 동작하도록 최소한만 지정 (나머지는 스타일시트로 오버라이드)
    let _ = style.set_property("display", "block");
    let _ = style.set_property("box-sizing", "border-box");
    let _ = style.set_property("width", "100%");

    // 기본 레이아웃 스타일 (이전: 컨테이너에 적용하던 오프스크린 고정 → 이제 래퍼에 적용)
    // wrapper를 오프스크린 고정 + 뷰포트 폭 고정
    // 모드 판별 (wrapper 레이아웃 결정에 사용)
    let user_view_mode = sampling_data.css_variables.vars.get("--USER__view").cloned();
    let detected_is_scroll_mode = user_view_mode
        .as_ref()
        .map(|v| v.contains("readium-scroll-on"))
        .or_else(|| sampling_data.root_style_attr.as_ref().map(|s| s.contains("readium-scroll-on")))
        .unwrap_or(false);

    // 기본 레이아웃 스타일: 모드별 wrapper 배치
    if detected_is_scroll_mode {
        // scroll 모드: 오프스크린 고정 + 뷰포트 폭 고정 (기존 동작)
        wrapper_style.set_css_text(&format!(
            "position:fixed !important; \
             top:-10000px !important; \
             left:0 !important; \
             visibility:hidden !important; \
             display:block !important; \
             box-sizing:content-box !important; \
             width:{}px !important; \
             max-width:none !important; \
             min-width:0 !important; \
             overflow:visible !important;",
            sampling_data.viewport_width
        ));
    } else {
        // paged 모드: :root의 컬럼/100vh 규칙이 하위에 적용되도록 in-flow로 배치
        // 화면 영향은 visibility:hidden으로 최소화
        wrapper_style.set_css_text(
            "position:static !important; \
             visibility:hidden !important; \
             display:block !important; \
             box-sizing:content-box !important; \
             width:100% !important; \
             max-width:none !important; \
             min-width:0 !important; \
             overflow:visible !important;",
        );
    }

    // 기본값 설정 (body_style이 없을 경우 대비)
    let (
        actual_padding_left,
        actual_padding_right,
        actual_padding_top,
        actual_padding_bottom,
        actual_margin_top,
        actual_margin_bottom,
        total_width_including_padding,
        pure_content_width,
    ) = debug_compute_and_log_body_widths(&sampling_data);
    
    // 스타일 요소 생성 및 외부 CSS만 주입
    let para_style = document.create_element("style").unwrap();
    let mut css_rules = String::new();
    css_rules.push_str(css_text);
    css_rules.push('\n');
    console_log!("🎨 외부 CSS만 주입");
    
    // 생성된 CSS 규칙을 스타일 요소에 설정
    para_style.set_inner_html(&css_rules);

    // DOM에 추가하여 실제 레이아웃 계산 수행
    if let Some(body) = document.body() {
        // :root style 토글 복제는 반드시 측정 전에 적용되어야 함
        let root_el = document.document_element();
        // :root 컨텍스트 적용 및 이전값 저장
        let prev_root = apply_root_context_and_log(&window, &document, &sampling_data, &effective_lang);
        
        if let Err(_) = wrapper.append_child(&container) {
            console_log!("❌ 래퍼에 컨테이너 추가 실패");
            return PageCalculationResult {
                total_pages: 1,
                status: "ERROR".to_string(),
            };
        }
        if let Err(_) = body.append_child(&wrapper) {
            console_log!("❌ DOM 추가 실패");
            return PageCalculationResult {
                total_pages: 1,
                status: "ERROR".to_string(),
            };
        }

        // A) 베이스라인 측정: 레이아웃에 영향이 큰 속성 적용 전
        console_log!("📏 === 높이 측정 분석 시작 ===");
        let rect_a = html_element.get_bounding_client_rect();
        let width_a = rect_a.width();
        let height_a = rect_a.height();
        console_log!("📏 A 베이스라인 높이: {:.3}px, width: {:.3}px", height_a, width_a);

        // :root에도 동일 변수 적용 (전역 규칙의 var() 해석용)
        apply_css_variables_to_root(&document, &sampling_data.css_variables.vars);
        
        // D) 스타일시트 주입 + CSS 변수 적용
        let head = document.get_elements_by_tag_name("head").get_with_index(0);
        if let Some(head) = head {
            let _ = head.append_child(&para_style);
            console_log!("🎨 외부 CSS 적용 완료");
        }
        
        // 컨테이너 스코프에서 타이포그래피/바디 레이아웃 보정 규칙 추가
        if let Some(sheet_el) = para_style.dyn_ref::<HtmlElement>() {
            let is_scroll_mode = detected_is_scroll_mode;
            console_log!("🧭 감지된 모드: {}", if is_scroll_mode { "scroll" } else { "paged" });

            if is_scroll_mode {
                inject_scroll_mode_container_css(sheet_el);
            } else {
                // Paged 모드: 최소 폭/거터만 반영(컬럼은 :root가 처리)
                inject_paged_mode_container_css(sheet_el);
                // :root에 측정 전용 스코프 속성 부여 + 멀티컬럼/높이 강제
                if let Some(root) = document.document_element() {
                    let _ = root.set_attribute("data-wasm-paged", "on");
                }
                inject_paged_root_css(sheet_el, sampling_data.viewport_height);
            }
        }

        // CSS 적용 상태 디버그 로그
        debug_log_root_body(&window, &document);
        debug_log_computed_styles(&window, &container);
        debug_log_advanced_layout(&window, &document, &container);

        // 추가 디버그: :root 컬럼 지표 및 라인하이트 보정 var
        if let Some(root) = document.document_element() {
            if let Ok(Some(cs)) = window.get_computed_style(&root) {
                let cw = cs.get_property_value("column-width").unwrap_or_default();
                let cc = cs.get_property_value("column-count").unwrap_or_default();
                let cg = cs.get_property_value("column-gap").unwrap_or_default();
                let h = cs.get_property_value("height").unwrap_or_default();
                console_log!("🔎 [:root] column-width={}, column-count={}, column-gap={}, height={}", cw, cc, cg, h);
                let lhc = cs.get_property_value("--RS__lineHeightCompensation").unwrap_or_default();
                console_log!("🔎 [:root] --RS__lineHeightCompensation={}", lhc);
            }
        }

        let rect_d = html_element.get_bounding_client_rect();
        let width_d = rect_d.width();
        let height_d = rect_d.height();
        console_log!("📏 D 스타일시트/변수 적용 후: {:.3}px, 누적 ΔA→D: {:.3}px, width: {:.3}px", height_d, height_d - height_a, width_d);

        let mut measured_height = html_element.scroll_height() as f64;
        let mut measured_max = measured_height;
        // wrapper
        let candidate_wrapper = wrapper
            .dyn_ref::<HtmlElement>()
            .map(|e| e.scroll_height() as f64)
            .unwrap_or(0.0);
        measured_max = measured_max.max(candidate_wrapper);
        // body
        let candidate_body = document
            .body()
            .map(|b| b.scroll_height() as f64)
            .unwrap_or(0.0);
        measured_max = measured_max.max(candidate_body);
        // documentElement(:root)
        let candidate_root = {
            if let Some(root_el) = document.document_element() {
                if let Some(root_html) = root_el.dyn_ref::<HtmlElement>() {
                    root_html.scroll_height() as f64
                } else { 0.0 }
            } else { 0.0 }
        };
        measured_max = measured_max.max(candidate_root);
        let candidate_container = html_element.scroll_height() as f64;
        console_log!(
            "📐 scrollHeight candidates px → container={:.3}, wrapper(max)={:.3}, body={:.3}, root={:.3}",
            candidate_container,
            candidate_wrapper,
            candidate_body,
            candidate_root
        );
        measured_height = measured_max;

        console_log!("📏 최종 측정된 높이 (보정 포함): {:.3}px", measured_height);
        
        let page_height = sampling_data.viewport_height as f64;

        // cleanup
        let _ = body.remove_child(&wrapper);
        if let Some(head) = document.get_elements_by_tag_name("head").get_with_index(0) {
            let _ = head.remove_child(&para_style);
        }
        // :root attr 원복
        if let Some(root) = document.document_element() {
            match prev_root.prev_lang { Some(v) => { let _ = root.set_attribute("lang", &v); }, None => { let _ = root.remove_attribute("lang"); } }
            match prev_root.prev_dir { Some(v) => { let _ = root.set_attribute("dir", &v); }, None => { let _ = root.remove_attribute("dir"); } }
            match prev_root.prev_writing_mode { Some(v) => { let _ = root.set_attribute("data-writing-mode", &v); }, None => { let _ = root.remove_attribute("data-writing-mode"); } }
            // style은 호출 측에서 토글 전달 가능성이 있어 원복은 보류; data-wasm-paged는 제거
            let _ = root.remove_attribute("data-wasm-paged");
        }

        if page_height > 0.0 {
            let total_pages = ((measured_height as f64) / page_height).ceil() as i32;
            PageCalculationResult { total_pages: total_pages.max(1), status: "SUCCESS".to_string() }
        } else {
            console_log!("⚠️ 페이지 높이가 0 - 기본값 1 반환");
            PageCalculationResult {
                total_pages: 1,
                status: "SUCCESS".to_string(),
            }
        }
    } else {
        console_log!("❌ Document body를 찾을 수 없음");
        PageCalculationResult {
            total_pages: 1,
            status: "ERROR".to_string(),
        }
    }
}

/// 📐 body 폭/패딩 디버그 로깅 및 순수 콘텐츠 너비 계산
/// 반환: (padL, padR, padT, padB, marT, marB, totalWidthIncludingPadding, pureContentWidth)
fn debug_compute_and_log_body_widths(
    sampling_data: &SamplingData,
) -> (i32, i32, i32, i32, i32, i32, i32, i32) {
    let mut actual_padding_left = 0;
    let mut actual_padding_right = 0;
    let mut actual_padding_top = 0;
    let mut actual_padding_bottom = 0;
    let mut actual_margin_top = 0;
    let mut actual_margin_bottom = 0;
    let mut pure_content_width = sampling_data.viewport_width;
    let mut total_width_including_padding = pure_content_width;

    if let Some(body_style) = &sampling_data.body_style {
        actual_padding_left = body_style.padding_left;
        actual_padding_right = body_style.padding_right;
        actual_padding_top = body_style.padding_top;
        actual_padding_bottom = body_style.padding_bottom;
        actual_margin_top = body_style.margin_top;
        actual_margin_bottom = body_style.margin_bottom;
        
        total_width_including_padding = body_style.content_width;
        
        console_log!("📐 너비 분석:");
        console_log!("  - contentWidth (WebView 측정): {}px", total_width_including_padding);
        console_log!("  - contentHeight (WebView 측정): {}px", body_style.content_height);
        console_log!("  - paddingLeft: {}px", actual_padding_left);
        console_log!("  - paddingRight: {}px", actual_padding_right);
        console_log!("  - 총 좌우 패딩: {}px", actual_padding_left + actual_padding_right);
        
        let total_horizontal_padding = actual_padding_left + actual_padding_right;
        pure_content_width = if total_horizontal_padding > 0 {
            total_width_including_padding - total_horizontal_padding
        } else {
            total_width_including_padding
        };
        
        console_log!("📐 패딩 중복 방지 계산:");
        console_log!("  - 전체 너비 (padding 포함): {}px", total_width_including_padding);
        console_log!("  - 총 좌우 패딩: {}px", total_horizontal_padding);
        console_log!("  - 순수 콘텐츠 너비: {}px", pure_content_width);
        console_log!(
            "📐 최종 적용 너비(콘텐츠 기준): {}px (폭/박스모델은 베이스라인 측정 후 적용)",
            pure_content_width
        );
    }

    (
        actual_padding_left,
        actual_padding_right,
        actual_padding_top,
        actual_padding_bottom,
        actual_margin_top,
        actual_margin_bottom,
        total_width_including_padding,
        pure_content_width,
    )
}

/// HTML 내용을 분석하여 마진 기여분을 계산
fn analyze_html_content(document: &Document, sampling_data: &SamplingData) {
    console_log!("📊 === HTML 내용 분석 ===");
    
    // 각 요소별 개수 세기
    let h1_count = count_elements(document, "h1");
    let h2_count = count_elements(document, "h2");
    let h3_count = count_elements(document, "h3");
    let p_count = count_elements(document, "p");
    let span_count = count_elements(document, "span");
    let div_count = count_elements(document, "div");
    
    console_log!("📝 요소별 개수:");
    console_log!("  - h1: {}개", h1_count);
    console_log!("  - h2: {}개", h2_count); 
    console_log!("  - h3: {}개", h3_count);
    console_log!("  - p: {}개", p_count);
    console_log!("  - span: {}개", span_count);
    console_log!("  - div: {}개", div_count);
    
    // 실제 콘텐츠 분석
    let total_text_length = get_total_text_length(document);
    console_log!("📄 총 텍스트 길이: {} chars", total_text_length);
    
    // 이미지/미디어 요소 확인
    let img_count = count_elements(document, "img");
    let video_count = count_elements(document, "video");
    let audio_count = count_elements(document, "audio");
    
    if img_count > 0 || video_count > 0 || audio_count > 0 {
        console_log!("🎨 미디어 요소:");
        console_log!("  - 이미지: {}개", img_count);
        console_log!("  - 비디오: {}개", video_count);
        console_log!("  - 오디오: {}개", audio_count);
    }
    
    // 특수 구조 분석
    analyze_special_structures(document);
    
    console_log!("📊 === HTML 분석 완료 ===");
}

/// elementStyles에서 특정 요소의 마진을 추출하는 헬퍼 함수
fn get_element_margin_from_styles(element_styles: &HashMap<String, HashMap<String, String>>, element_name: &str, default_margin: i32) -> i32 {
    if let Some(styles) = element_styles.get(element_name) {
        // 각 방향별 margin 값을 확인하고, 상하 margin만 합산하여 반환
        let margin_top = styles.get("marginTop")
            .and_then(|s| s.parse::<i32>().ok())
            .unwrap_or(0);
        let margin_bottom = styles.get("marginBottom")
            .and_then(|s| s.parse::<i32>().ok())
            .unwrap_or(0);
        
        let total_margin = margin_top + margin_bottom;
        if total_margin > 0 {
            return total_margin;
        }
        
        // 기존 방식(margin 필드)도 fallback으로 유지
        if let Some(margin_str) = styles.get("margin") {
            if let Ok(margin_value) = margin_str.parse::<i32>() {
                return margin_value;
            }
        }
    }
    default_margin
}

/// 특정 태그의 요소 개수를 세는 함수
fn count_elements(document: &Document, tag_name: &str) -> i32 {
    let elements = document.get_elements_by_tag_name(tag_name);
    elements.length() as i32
}

/// 전체 텍스트 길이 계산
fn get_total_text_length(document: &Document) -> usize {
    if let Some(body) = document.body() {
        let text_content = body.text_content().unwrap_or_default();
        // HTML 태그 제거하고 실제 텍스트만 계산
        text_content.chars().filter(|c| !c.is_whitespace() || *c == ' ').collect::<String>().len()
    } else {
        0
    }
}

/// 특수 구조 분석 (리스트, 테이블 등)
fn analyze_special_structures(document: &Document) {
    let list_items = count_elements(document, "li");
    let tables = count_elements(document, "table");
    let blockquotes = count_elements(document, "blockquote");
    let code_blocks = count_elements(document, "pre");
    
    if list_items > 0 || tables > 0 || blockquotes > 0 || code_blocks > 0 {
        console_log!("🏗️ 특수 구조:");
        if list_items > 0 { console_log!("  - 리스트 아이템: {}개", list_items); }
        if tables > 0 { console_log!("  - 테이블: {}개", tables); }
        if blockquotes > 0 { console_log!("  - 인용구: {}개", blockquotes); }
        if code_blocks > 0 { console_log!("  - 코드 블록: {}개", code_blocks); }
    }
    
    // 네스팅 깊이 분석
    analyze_nesting_depth(document);
}

/// HTML 네스팅 깊이 분석
fn analyze_nesting_depth(document: &Document) {
    if let Some(body) = document.body() {
        let max_depth = calculate_max_depth(&body, 0);
        console_log!("📏 최대 네스팅 깊이: {} 레벨", max_depth);
        
        if max_depth > 10 {
            console_log!("⚠️ 복잡한 HTML 구조 감지 - 렌더링에 영향 가능");
        }
    }
}

/// 요소의 최대 네스팅 깊이 계산
fn calculate_max_depth(element: &Element, current_depth: i32) -> i32 {
    let children = element.children();
    let mut max_child_depth = current_depth;
    
    for i in 0..children.length() {
        if let Some(child) = children.get_with_index(i) {
            let child_depth = calculate_max_depth(&child, current_depth + 1);
            if child_depth > max_child_depth {
                max_child_depth = child_depth;
            }
        }
    }
    
    max_child_depth
}

/// 테스트용 헬퍼 함수들
#[wasm_bindgen]
pub fn get_wasm_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[wasm_bindgen]
pub fn test_wasm_connection() -> String {
    "🦀 WASM 모듈이 정상적으로 연결되었습니다!".to_string()
}

// (removed) tests module: referenced removed fields/types; keep code lean