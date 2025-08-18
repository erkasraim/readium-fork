use epub_page_calculator::*;
use serde_json;

#[test]
fn test_sampling_data_json_compatibility() {
    // Kotlin에서 생성되는 JSON 형태 테스트
    let json_str = r#"{
        "viewportWidth": 412,
        "viewportHeight": 676,
        "fontMetrics": {
            "fontSize": 16.0,
            "lineHeight": 24.2109,
            "characterWidth": 15.0119047164917
        },
        "layoutMetrics": {
            "contentWidth": 411,
            "contentHeight": 600,
            "marginTop": 0,
            "marginBottom": 0,
            "marginLeft": 0,
            "marginRight": 0
        }
    }"#;

    let sampling_data: SamplingData = serde_json::from_str(json_str).expect("JSON 파싱 실패");
    
    assert_eq!(sampling_data.viewport_width, 412);
    assert_eq!(sampling_data.viewport_height, 676);
    assert_eq!(sampling_data.font_metrics.font_size, 16.0);
    assert_eq!(sampling_data.layout_metrics.content_width, 411);
}

#[test]
fn test_page_calculation_result_json() {
    let result = PageCalculationResult {
        total_pages: 10,
        status: "SUCCESS".to_string(),
    };

    let json = serde_json::to_string(&result).expect("JSON 직렬화 실패");
    
    // Kotlin에서 기대하는 JSON 형태인지 확인
    assert!(json.contains("\"totalPages\":10"));
    assert!(json.contains("\"status\":\"SUCCESS\""));
}

#[test]
fn test_empty_html() {
    let empty_html = "";
    let sampling_data_json = r#"{
        "viewportWidth": 800,
        "viewportHeight": 600,
        "fontMetrics": {
            "fontSize": 16.0,
            "lineHeight": 24.0,
            "characterWidth": 8.0
        },
        "layoutMetrics": {
            "contentWidth": 780,
            "contentHeight": 580,
            "marginTop": 10,
            "marginBottom": 10,
            "marginLeft": 10,
            "marginRight": 10
        }
    }"#;

    // 실제 DOM이 없는 환경에서는 에러가 예상되지만, JSON 파싱은 성공해야 함
    let result_json = calculate_pages(empty_html, sampling_data_json);
    let result: PageCalculationResult = serde_json::from_str(&result_json).expect("결과 파싱 실패");
    
    // 에러 상황이거나 최소 1페이지는 반환되어야 함
    assert!(result.total_pages >= 1 || result.status == "ERROR");
}

#[test]
fn test_basic_html() {
    let html = "<p>Hello, World!</p><p>This is a test.</p>";
    let sampling_data_json = r#"{
        "viewportWidth": 800,
        "viewportHeight": 600,
        "fontMetrics": {
            "fontSize": 16.0,
            "lineHeight": 24.0,
            "characterWidth": 8.0
        },
        "layoutMetrics": {
            "contentWidth": 780,
            "contentHeight": 580,
            "marginTop": 10,
            "marginBottom": 10,
            "marginLeft": 10,
            "marginRight": 10
        }
    }"#;

    let result_json = calculate_pages(html, sampling_data_json);
    let result: PageCalculationResult = serde_json::from_str(&result_json).expect("결과 파싱 실패");
    
    // 결과가 유효한 형태인지 확인
    assert!(result.total_pages > 0);
    assert!(result.status == "SUCCESS" || result.status == "ERROR");
}