# WASM Page Calculator

EPUB 페이지 수를 정확하게 계산하기 위한 WebAssembly 모듈입니다.

## 🚀 Quick Start

### 1. Rust 환경 설정

**macOS/Linux:**

```bash
./setup-rust.sh
```

**Windows:**

```cmd
setup-rust.bat
```

### 2. WASM 빌드

```bash
./gradlew :wasm-page-calculator:buildWasm
```

### 3. 테스트 실행

```bash
./gradlew :wasm-page-calculator:testWasm
```

## 📁 프로젝트 구조

```
wasm-page-calculator/
├── build.gradle.kts              # Gradle 빌드 설정
├── setup-rust.sh                 # Rust 환경 설정 (Unix)
├── setup-rust.bat                # Rust 환경 설정 (Windows)
├── src/
│   ├── main/
│   │   └── rust/                 # Rust 소스코드
│   │       ├── Cargo.toml
│   │       └── src/
│   │           └── lib.rs
│   └── test/
│       └── rust/                 # Rust 테스트
│           └── tests/
└── wasm-bindings/
    ├── pkg/                      # wasm-pack 빌드 결과물
    └── assets/                   # Android assets (JS, WASM 파일)
```

## 🛠 Gradle 태스크

| 태스크 | 설명 |
|--------|------|
| `buildWasm` | WASM 모듈 빌드 |
| `testWasm` | Rust 테스트 실행 |
| `checkRustEnvironment` | Rust 환경 확인 |
| `copyWasmAssets` | WASM 파일을 Android assets로 복사 |

## 🔧 개발 환경 요구사항

- **Rust 1.70+**
- **wasm-pack 0.12+**
- **Android Studio**
- **JDK 17+**

## 📱 Android 통합

WASM 모듈은 자동으로 Android assets에 포함됩니다:

- `assets/wasm/epub_page_calculator.js`
- `assets/wasm/epub_page_calculator_bg.wasm`

## 🧪 테스트

### Rust 테스트

```bash
cd src/main/rust
cargo test
```

### Android 테스트

```bash
./gradlew :wasm-page-calculator:test
```

## 📝 사용 예제

```kotlin
// DefaultWasmPageCalculator에서 사용
val result = wasmPageCalculator.calculatePages(html, samplingData)
when (result.status) {
    WasmCalculationResult.Status.SUCCESS -> {
        // 정확한 페이지 수 사용
        totalPageCount += result.totalPages
    }
    WasmCalculationResult.Status.ERROR -> {
        // 폴백 계산 사용
    }
}
```

## 🐛 문제 해결

### Rust 설치 실패

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source ~/.cargo/env
```

### wasm-pack 설치 실패

```bash
curl https://rustwasm.github.io/wasm-pack/installer/init.sh -sSf | sh
```

### WASM 빌드 실패

```bash
rustup target add wasm32-unknown-unknown
cargo install wasm-bindgen-cli
```