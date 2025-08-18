@echo off
:: Rust 개발 환경 설정 스크립트 for Windows

echo 🦀 Setting up Rust environment for WASM development...

:: Rust 설치 확인
where rustc >nul 2>nul
if %errorlevel% neq 0 (
    echo Rust not found. Installing Rust...
    curl --proto "=https" --tlsv1.2 -sSf https://win.rustup.rs/x86_64 -o rustup-init.exe
    rustup-init.exe -y
    del rustup-init.exe
    call "%USERPROFILE%\.cargo\env.bat"
    echo ✅ Rust installed successfully
) else (
    echo ✅ Rust is already installed
    rustc --version
)

:: wasm-pack 설치 확인
where wasm-pack >nul 2>nul
if %errorlevel% neq 0 (
    echo wasm-pack not found. Installing wasm-pack...
    curl -LO https://github.com/rustwasm/wasm-pack/releases/latest/download/wasm-pack-init.exe
    wasm-pack-init.exe
    del wasm-pack-init.exe
    echo ✅ wasm-pack installed successfully
) else (
    echo ✅ wasm-pack is already installed
    wasm-pack --version
)

:: WASM 타겟 추가
echo Adding wasm32-unknown-unknown target...
rustup target add wasm32-unknown-unknown
echo ✅ WASM target added

:: wasm-bindgen-cli 설치 (선택적)
where wasm-bindgen >nul 2>nul
if %errorlevel% neq 0 (
    echo Installing wasm-bindgen-cli...
    cargo install wasm-bindgen-cli
    echo ✅ wasm-bindgen-cli installed
) else (
    echo ✅ wasm-bindgen-cli is already installed
)

:: 환경 확인
echo.
echo 🎉 Rust environment setup complete!
echo.
echo 📋 Environment Summary:
rustc --version
cargo --version
wasm-pack --version

:: 사용법 안내
echo.
echo 📝 Usage:
echo   Build WASM: .\gradlew :wasm-page-calculator:buildWasm
echo   Run tests: .\gradlew :wasm-page-calculator:testWasm
echo   Check env: .\gradlew :wasm-page-calculator:checkRustEnvironment
echo.
echo 🔧 To use Rust commands directly:
echo   cd wasm-page-calculator\src\main\rust
echo   cargo build
echo   cargo test

pause