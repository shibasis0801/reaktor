// Node runs this module's tests, and Compose (reached through reaktor-auth → reaktor-ui) imports
// Skia's wasm bindings, which only exist in a browser. Nothing a Worker test exercises draws, so an
// empty API satisfies the import; anything that did draw would fail loudly here, as it should.
export const skikoApi = {};
