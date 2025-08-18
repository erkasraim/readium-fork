use wasm_bindgen::prelude::*;
use web_sys::*;
use serde::{Deserialize, Serialize};

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

/// 샘플링 데이터 구조체 (Kotlin과 동일)
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct SamplingData {
    #[serde(rename = "viewportWidth")]
    pub viewport_width: i32,
    #[serde(rename = "viewportHeight")]
    pub viewport_height: i32,
    #[serde(rename = "fontMetrics")]
    pub font_metrics: FontMetrics,
    #[serde(rename = "layoutMetrics")]
    pub layout_metrics: LayoutMetrics,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct FontMetrics {
    #[serde(rename = "fontSize")]
    pub font_size: f32,
    #[serde(rename = "lineHeight")]
    pub line_height: f32,
    #[serde(rename = "characterWidth")]
    pub character_width: f32,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct LayoutMetrics {
    #[serde(rename = "contentWidth")]
    pub content_width: i32,
    #[serde(rename = "contentHeight")]
    pub content_height: i32,
    #[serde(rename = "marginTop")]
    pub margin_top: i32,
    #[serde(rename = "marginBottom")]
    pub margin_bottom: i32,
    #[serde(rename = "marginLeft")]
    pub margin_left: i32,
    #[serde(rename = "marginRight")]
    pub margin_right: i32,
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
    console_log!("HTML 길이: {} chars", html.len());
    console_log!("샘플링 데이터: {}", sampling_data_json);

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

    // 페이지 계산 실행
    let result = calculate_pages_internal(html, &sampling_data);
    
    console_log!("✅ 계산 완료: {} pages", result.total_pages);

    // 결과를 JSON으로 변환
    serde_json::to_string(&result).unwrap_or_default()
}

/// 실제 페이지 계산 로직
fn calculate_pages_internal(html: &str, sampling_data: &SamplingData) -> PageCalculationResult {
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

    // HTML 콘텐츠 설정
    container.set_inner_html(html);

    // CSS 스타일 적용
    let html_element = container.dyn_ref::<HtmlElement>().unwrap();
    let style = html_element.style();

    // 기본 레이아웃 스타일
    let _ = style.set_property("position", "absolute");
    let _ = style.set_property("visibility", "hidden");
    let _ = style.set_property("top", "-9999px");
    let _ = style.set_property("left", "-9999px");
    let _ = style.set_property("width", &format!("{}px", sampling_data.layout_metrics.content_width));
    let _ = style.set_property("height", "auto");
    let _ = style.set_property("overflow", "visible");

    // Readium CSS 기본 스타일 적용
    let _ = style.set_property("font-family", "\"Iowan Old Style\",\"Sitka Text\",Palatino,\"Book Antiqua\",serif");
    let _ = style.set_property("font-size", &format!("{}px", sampling_data.font_metrics.font_size));
    let _ = style.set_property("line-height", &format!("{}px", sampling_data.font_metrics.line_height));
    let _ = style.set_property("text-rendering", "optimizeLegibility");
    let _ = style.set_property("color", "#121212");
    let _ = style.set_property("background-color", "#FFFFFF");
    
    // 마진/패딩 적용
    if sampling_data.layout_metrics.margin_top > 0 {
        let _ = style.set_property("padding-top", &format!("{}px", sampling_data.layout_metrics.margin_top));
    }
    if sampling_data.layout_metrics.margin_bottom > 0 {
        let _ = style.set_property("padding-bottom", &format!("{}px", sampling_data.layout_metrics.margin_bottom));
    }
    if sampling_data.layout_metrics.margin_left > 0 {
        let _ = style.set_property("padding-left", &format!("{}px", sampling_data.layout_metrics.margin_left));
    }
    if sampling_data.layout_metrics.margin_right > 0 {
        let _ = style.set_property("padding-right", &format!("{}px", sampling_data.layout_metrics.margin_right));
    }

    // 박스 모델 설정
    let _ = style.set_property("box-sizing", "border-box");
    let _ = style.set_property("word-wrap", "break-word");

    // DOM에 추가하여 실제 레이아웃 계산 수행
    if let Some(body) = document.body() {
        if let Err(_) = body.append_child(&container) {
            console_log!("❌ DOM 추가 실패");
            return PageCalculationResult {
                total_pages: 1,
                status: "ERROR".to_string(),
            };
        }

        // 실제 높이 측정
        let actual_height = html_element.offset_height();
        console_log!("📏 실제 높이: {}px", actual_height);
        
        let page_height = sampling_data.viewport_height as f64;
        console_log!("📄 페이지 높이: {}px (뷰포트 높이 사용)", page_height);

        // DOM에서 제거
        let _ = body.remove_child(&container);

        // 페이지 수 계산 (올림 처리)
        if page_height > 0.0 {
            let total_pages = ((actual_height as f64) / page_height).ceil() as i32;
            let final_pages = total_pages.max(1);
            
            console_log!("✅ 계산된 페이지 수: {}", final_pages);
            
            PageCalculationResult {
                total_pages: final_pages,
                status: "SUCCESS".to_string(),
            }
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

/// 테스트용 헬퍼 함수들
#[wasm_bindgen]
pub fn get_wasm_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[wasm_bindgen]
pub fn test_wasm_connection() -> String {
    "🦀 WASM 모듈이 정상적으로 연결되었습니다!".to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sampling_data_serialization() {
        let sampling_data = SamplingData {
            viewport_width: 800,
            viewport_height: 600,
            font_metrics: FontMetrics {
                font_size: 16.0,
                line_height: 24.0,
                character_width: 8.0,
            },
            layout_metrics: LayoutMetrics {
                content_width: 780,
                content_height: 580,
                margin_top: 10,
                margin_bottom: 10,
                margin_left: 10,
                margin_right: 10,
            },
        };

        let json = serde_json::to_string(&sampling_data).unwrap();
        let deserialized: SamplingData = serde_json::from_str(&json).unwrap();
        
        assert_eq!(sampling_data.viewport_width, deserialized.viewport_width);
        assert_eq!(sampling_data.font_metrics.font_size, deserialized.font_metrics.font_size);
    }

    #[test]
    fn test_page_calculation_result() {
        let result = PageCalculationResult {
            total_pages: 5,
            status: "SUCCESS".to_string(),
        };

        let json = serde_json::to_string(&result).unwrap();
        assert!(json.contains("totalPages"));
        assert!(json.contains("5"));
        assert!(json.contains("SUCCESS"));
    }
}