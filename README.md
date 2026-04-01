# Harper Grammar Engine for Aido

This repository contains the standalone Harper Grammar Checking integration for the Aido accessibility service.

## 🚀 Features
- **Local Grammar Checking**: Uses Harper's WASM-based engine for high-performance, offline grammar and style analysis.
- **W wavy Underlines**: Real-time visual feedback for errors in any text field via Android's Accessibility Overlays.
- **Contextual Actions**: Popup cards providing explanations and one-tap corrections.
- **Headless WebView Bridge**: Executes complex JS-based grammar logic within a hidden WebView for maximum accuracy and speed.

## 📂 Project Structure
- **/app/src/main/java/com/rr/aido/harper/**: Kotlin implementation of the grammar service and UI overlays.
- **/app/src/main/assets/harper/**: The JS bridge and WASM engine artifacts.
  - `engine.js`: Source logic for the WASM bridge.
  - `bundle.js`: Compiled Harper engine (ESM format).
  - `engine.html`: Entry point for the headless WebView.
  - `node_modules/`: Local dependencies (harper.js).

## 🛠️ Requirements
- Android SDK 33+ (Recommended)
- Node.js (for rebuilding the JS bundle if needed)

## 🏗️ Rebuilding the Engine
If you modify `engine.js`, you must re-bundle it:
```bash
npx esbuild engine.js --bundle --outfile=bundle.js --format=esm --platform=browser --external:fs --external:path
```

## 📜 Integration
The engine is initialized via `HarperManager`. Use `HarperOverlayManager` to render the grammar UI over any accessibility node.

## 📦 External Source
- **/harper/**: Original source code of the Harper grammar engine (Rust/WASM). This is included for reference and advanced modifications to the core engine.
