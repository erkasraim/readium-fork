declare namespace wasm_bindgen {
	/* tslint:disable */
	/* eslint-disable */
	export function main(): void;
	/**
	 * WASM에서 호출될 메인 함수 - 페이지 수 계산
	 */
	export function calculate_pages(html: string, sampling_data_json: string): string;
	/**
	 * WASM에서 호출될 메인 함수 - 페이지 수 계산 (외부 CSS 포함)
	 */
	export function calculate_pages_with_css(html: string, css_text: string, sampling_data_json: string, debug_logging: boolean): string;
	/**
	 * 테스트용 헬퍼 함수들
	 */
	export function get_wasm_version(): string;
	export function test_wasm_connection(): string;
	export function register_css_registry(registry_json: string): boolean;
	export function calculate_pages_with_registry(html: string, hrefs_json: string, inline_styles_json: string, sampling_data_json: string, debug_logging: boolean): string;
	
}

declare type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

declare interface InitOutput {
  readonly memory: WebAssembly.Memory;
  readonly main: () => void;
  readonly calculate_pages: (a: number, b: number, c: number, d: number) => [number, number];
  readonly calculate_pages_with_css: (a: number, b: number, c: number, d: number, e: number, f: number, g: number) => [number, number];
  readonly get_wasm_version: () => [number, number];
  readonly test_wasm_connection: () => [number, number];
  readonly register_css_registry: (a: number, b: number) => number;
  readonly calculate_pages_with_registry: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number) => [number, number];
  readonly __wbindgen_exn_store: (a: number) => void;
  readonly __externref_table_alloc: () => number;
  readonly __wbindgen_export_2: WebAssembly.Table;
  readonly __wbindgen_free: (a: number, b: number, c: number) => void;
  readonly __wbindgen_malloc: (a: number, b: number) => number;
  readonly __wbindgen_realloc: (a: number, b: number, c: number, d: number) => number;
  readonly __wbindgen_start: () => void;
}

/**
* If `module_or_path` is {RequestInfo} or {URL}, makes a request and
* for everything else, calls `WebAssembly.instantiate` directly.
*
* @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
*
* @returns {Promise<InitOutput>}
*/
declare function wasm_bindgen (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
