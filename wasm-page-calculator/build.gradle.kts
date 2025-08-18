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
    doLast {
        val jsFile = File(project.projectDir, "wasm-bindings/pkg/epub_page_calculator.js")
        if (jsFile.exists()) {
            val content = jsFile.readText()
            val processedContent = content +
                """
                
                // WebView 호환성을 위한 글로벌 변수 할당
                if (typeof window !== 'undefined') {
                    window.main = wasm_bindgen.main || wasm_bindgen.__exports?.main;
                    window.calculate_pages = wasm_bindgen.calculate_pages || wasm_bindgen.__exports?.calculate_pages;
                    window.get_wasm_version = wasm_bindgen.get_wasm_version || wasm_bindgen.__exports?.get_wasm_version;
                    window.test_wasm_connection = wasm_bindgen.test_wasm_connection || wasm_bindgen.__exports?.test_wasm_connection;
                    window.initSync = wasm_bindgen.initSync || wasm_bindgen.initSync;
                    window.__wbg_init = wasm_bindgen || wasm_bindgen;
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