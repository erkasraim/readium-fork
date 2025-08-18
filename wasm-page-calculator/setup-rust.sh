#!/bin/bash
# Rust 개발 환경 설정 스크립트 for macOS/Linux

set -e  # 에러 발생시 스크립트 중단

echo "🦀 Setting up Rust environment for WASM development..."

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Rust 설치 확인
if ! command -v rustc &> /dev/null; then
    echo -e "${YELLOW}Rust not found. Installing Rust...${NC}"
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    source ~/.cargo/env
    echo -e "${GREEN}✅ Rust installed successfully${NC}"
else
    echo -e "${GREEN}✅ Rust is already installed: $(rustc --version)${NC}"
fi

# wasm-pack 설치 확인
if ! command -v wasm-pack &> /dev/null; then
    echo -e "${YELLOW}wasm-pack not found. Installing wasm-pack...${NC}"
    curl https://rustwasm.github.io/wasm-pack/installer/init.sh -sSf | sh
    echo -e "${GREEN}✅ wasm-pack installed successfully${NC}"
else
    echo -e "${GREEN}✅ wasm-pack is already installed: $(wasm-pack --version)${NC}"
fi

# WASM 타겟 추가
echo -e "${YELLOW}Adding wasm32-unknown-unknown target...${NC}"
rustup target add wasm32-unknown-unknown
echo -e "${GREEN}✅ WASM target added${NC}"

# 개발에 유용한 도구들 설치
echo -e "${YELLOW}Installing useful development tools...${NC}"

# wasm-bindgen-cli 설치 (선택적)
if ! command -v wasm-bindgen &> /dev/null; then
    cargo install wasm-bindgen-cli
    echo -e "${GREEN}✅ wasm-bindgen-cli installed${NC}"
else
    echo -e "${GREEN}✅ wasm-bindgen-cli is already installed${NC}"
fi

# 환경 확인
echo -e "\n${GREEN}🎉 Rust environment setup complete!${NC}"
echo -e "\n📋 Environment Summary:"
echo -e "  Rust: $(rustc --version)"
echo -e "  Cargo: $(cargo --version)"
echo -e "  wasm-pack: $(wasm-pack --version)"

# 사용법 안내
echo -e "\n📝 Usage:"
echo -e "  Build WASM: ${YELLOW}./gradlew :wasm-page-calculator:buildWasm${NC}"
echo -e "  Run tests: ${YELLOW}./gradlew :wasm-page-calculator:testWasm${NC}"
echo -e "  Check env: ${YELLOW}./gradlew :wasm-page-calculator:checkRustEnvironment${NC}"

echo -e "\n🔧 To use Rust commands directly:"
echo -e "  cd wasm-page-calculator/src/main/rust"
echo -e "  cargo build"
echo -e "  cargo test"