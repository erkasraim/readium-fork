plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.readium.r2.navigator.wasm"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("wasm-bindings/assets")
        }
    }
}

// IMPORTANT:
// - We DO NOT edit generated wasm-bindgen JS files by hand.
// - Any changes to global exposure (e.g., window.calculate_pages_with_css) MUST be applied here
//   in the Gradle post-processing step, so future builds regenerate glue correctly.
// - If new Rust exports are added, expose them here by extending the snippet appended below.
// - This comment is intentionally verbose so automated tools (including AI) can discover and
//   maintain the exposure list in one place.

// Rust/WASM 빌드 태스크 정의
tasks.register<Exec>("buildWasm") {
    description = "Build WASM module using wasm-pack"
    group = "build"

    workingDir = file("src/main/rust")

    val wasmPackPath = System.getProperty("user.home") + "/.cargo/bin/wasm-pack"

    // OS에 따른 명령어 설정
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        commandLine(
            "cmd",
            "/c",
            wasmPackPath,
            "build",
            "--target",
            "no-modules",
            "--out-dir",
            "../../../wasm-bindings/pkg"
        )
    } else {
        commandLine(
            wasmPackPath,
            "build",
            "--target",
            "no-modules",
            "--out-dir",
            "../../../wasm-bindings/pkg"
        )
    }

    inputs.dir("src/main/rust/src")
    inputs.file("src/main/rust/Cargo.toml")
    outputs.dir("wasm-bindings/pkg")

    // 빌드 후 바로 JavaScript 파일 후처리
    // NOTE:
    // - JS glue는 wasm-bindgen가 생성합니다. 여기서 window.* 글로벌에 노출할 항목을 추가합니다.
    // - 절대 수동으로 assets의 JS를 편집하지 마세요. (이 블록만 수정)
    // - 새 export를 추가했다면 아래 노출 목록에 포함시키세요: calculate_pages_with_css 등.
    doLast {
        val jsFile = File(project.projectDir, "wasm-bindings/pkg/epub_page_calculator.js")
        if (jsFile.exists()) {
            val content = jsFile.readText()
            val processedContent = content +
                """
                
                // WebView 호환성을 위한 글로벌 변수 할당
                // NOTE: 이 블록을 통해 wasm-bindgen export를 window.*에 노출합니다.
                // - 새 export 추가 시 아래 목록에 추가하세요.
                if (typeof window !== 'undefined') {
                    window.main = wasm_bindgen.main || wasm_bindgen.__exports?.main;
                    window.calculate_pages = wasm_bindgen.calculate_pages || wasm_bindgen.__exports?.calculate_pages;
                    // 외부 CSS를 함께 전달하는 확장 API (Rust에서 추가된 export)
                    window.calculate_pages_with_css = wasm_bindgen.calculate_pages_with_css || wasm_bindgen.__exports?.calculate_pages_with_css;
                    window.get_wasm_version = wasm_bindgen.get_wasm_version || wasm_bindgen.__exports?.get_wasm_version;
                    window.test_wasm_connection = wasm_bindgen.test_wasm_connection || wasm_bindgen.__exports?.test_wasm_connection;
                    window.initSync = wasm_bindgen.initSync || wasm_bindgen.initSync;
                    window.__wbg_init = wasm_bindgen || wasm_bindgen;

                    // 새 레지스트리 기반 API 노출
                    window.register_css_registry = wasm_bindgen.register_css_registry || wasm_bindgen.__exports?.register_css_registry;
                    window.calculate_pages_with_registry = wasm_bindgen.calculate_pages_with_registry || wasm_bindgen.__exports?.calculate_pages_with_registry;
                }
                """.trimIndent()

            jsFile.writeText(processedContent)
            println("✅ WASM JavaScript 파일에 WebView 호환 코드를 추가했습니다.")
        }
    }
}

// WASM 파일을 assets로 복사하는 태스크
tasks.register<Copy>("copyWasmAssets") {
    description = "Copy WASM files to Android assets"
    group = "build"

    dependsOn("buildWasm")

    from("wasm-bindings/pkg") {
        include("*.js")
        include("*.wasm")
        include("*.d.ts")
    }
    into("wasm-bindings/assets/wasm")
    doLast {
        println("✅ WASM pkg -> assets/wasm 복사 완료")
    }
}

// Android 빌드 전에 WASM 빌드 실행 (조건부)
tasks.named("preBuild") {
    // WASM 빌드는 선택적으로 실행 (CI/CD에서는 스킵 가능)
    if (project.hasProperty("buildWasm") && project.property("buildWasm") == "true") {
        dependsOn("copyWasmAssets")
    }
}

// 테스트 태스크
tasks.register<Exec>("testWasm") {
    description = "Run Rust tests for WASM module"
    group = "verification"

    workingDir = file("src/main/rust")

    val cargoPath = System.getProperty("user.home") + "/.cargo/bin/cargo"

    if (System.getProperty("os.name").lowercase().contains("windows")) {
        commandLine("cmd", "/c", cargoPath, "test")
    } else {
        commandLine(cargoPath, "test")
    }
}

// Rust 환경 체크 태스크
tasks.register<Exec>("checkRustEnvironment") {
    description = "Check if Rust and wasm-pack are installed"
    group = "setup"

    val rustcPath = System.getProperty("user.home") + "/.cargo/bin/rustc"

    if (System.getProperty("os.name").toLowerCase().contains("windows")) {
        commandLine("cmd", "/c", "$rustcPath", "--version")
    } else {
        commandLine(rustcPath, "--version")
    }

    doLast {
        println("✅ Rust environment is ready")

        // wasm-pack 버전도 확인
        val wasmPackPath = System.getProperty("user.home") + "/.cargo/bin/wasm-pack"
        try {
            val result = ProcessBuilder(wasmPackPath, "--version")
                .start()
                .inputStream
                .bufferedReader()
                .readText()
            println("✅ wasm-pack: $result")
        } catch (e: Exception) {
            println("⚠️ wasm-pack not found: ${e.message}")
        }
    }
}

tasks.named("check") {
    dependsOn("testWasm")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}