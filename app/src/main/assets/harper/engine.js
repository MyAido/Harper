import { LocalLinter, binaryInlined } from 'harper.js';

let linter = null;

async function init() {
    try {
        console.log("Initializing Harper WASM...");
        linter = new LocalLinter({ binary: binaryInlined });
        await linter.setup();
        console.log("Harper initialized successfully.");

        if (window.Android && window.Android.onEngineReady) {
            window.Android.onEngineReady();
        }
    } catch (e) {
        console.error("Harper initialization failed:", e);
        if (window.Android && window.Android.onEngineError) {
            window.Android.onEngineError(String(e));
        }
    }
}

window.lintText = async function(requestId, text) {
    if (!linter) {
        if (window.Android) window.Android.onError(requestId, "Linter not initialized yet");
        return;
    }
    try {
        const rawLints = await linter.lint(text);

        // Lint objects are WASM proxies — manually extract each field
        const results = rawLints.map(lint => {
            const span = lint.span();
            // suggestions() returns array of Suggestion WASM proxies
            const suggestions = lint.suggestions();
            const firstSuggestion = suggestions.length > 0
                ? suggestions[0].get_replacement_text()
                : null;

            const item = {
                span: { start: span.start, end: span.end },
                lintKind: lint.lint_kind() || "Unknown",
                isCertain: true,
                suggestion: firstSuggestion,
                message: lint.message() || "Grammar issue"
            };

            // Free WASM memory
            try { lint.free(); } catch(_) {}

            return item;
        });

        if (window.Android) {
            window.Android.onLintResult(requestId, JSON.stringify(results));
        }
    } catch (e) {
        console.error("lintText error:", e);
        if (window.Android) window.Android.onError(requestId, String(e));
    }
};

init();
