/**
 * The page reader the app injects into its WebView: the browser extension's own extractor
 * (kroshtan/unfret_extension, src/extract, bundled by build.mjs), behind a small polling interface.
 *
 * `evaluateJavascript` can't wait for a promise, so `start()` begins the extraction (the same call the
 * extension makes, `extractPage(document, location.href, { live: true })`) and `poll()` answers, as a JSON
 * string, whether it is still running, its result (`{facts, text}`, the extension's `Extraction`), or why it
 * failed. Nothing is sent from here: the app reads the result and sends it to the Unfret API itself. The page is
 * never changed (the extractor and the sanitiser work on a copy).
 */

import { extractPage } from "@unfret/extract";

type State = { status: "running" } | { status: "done"; result: unknown } | { status: "error"; message: string };

interface UnfretAndroid {
  start(options?: { live?: boolean; timerWaitMs?: number }): void;
  poll(): string;
}

declare global {
  interface Window {
    __unfretAndroid?: UnfretAndroid;
  }
}

let state: State | null = null;

window.__unfretAndroid = {
  start(options = { live: true }) {
    state = { status: "running" };
    extractPage(document, location.href, options).then(
      (result) => {
        state = { status: "done", result };
      },
      (error: unknown) => {
        state = { status: "error", message: error instanceof Error ? error.message : String(error) };
      },
    );
  },
  poll() {
    return JSON.stringify(state);
  },
};
