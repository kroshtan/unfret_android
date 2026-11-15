// The bundle the app injects, run the way the app runs it (evaluate the file in the page, start, poll), over the
// extension's own fixture pages: it must give the extension's expected PageFacts, keep the visitor's details out,
// and give the shape the app's request builder reads.
//
//   node --test test.mjs     (needs extractor/.extension: ./fetch-extension.sh)
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

import { JSDOM } from "jsdom";

const here = dirname(fileURLToPath(import.meta.url));
const BUNDLE = readFileSync(join(here, "..", "app", "src", "main", "assets", "unfret-extract.js"), "utf8");
const FIXTURES = join(here, ".extension", "test", "fixtures");
const INDEX = JSON.parse(readFileSync(join(FIXTURES, "index.json"), "utf8"));

// The visitor's details planted in the fixtures (the extension's test/privacy.test.ts).
const PERSONAL = /Jane|jane\.doe|Kerkstraat|Elm Street|Springfield|0612345678|98765432|555-0142|3511 AB/u;

/** Load a fixture as the WebView would show it, inject the bundle, run it and wait for the result. */
async function run(name, options) {
  const html = readFileSync(join(FIXTURES, `${name}.html`), "utf8");
  const dom = new JSDOM(html, { url: INDEX[name], runScripts: "outside-only", pretendToBeVisual: true });
  try {
    dom.window.eval(BUNDLE);
    dom.window.__unfretAndroid.start(options);
    for (let i = 0; i < 200; i++) {
      const state = JSON.parse(dom.window.__unfretAndroid.poll());
      if (state.status === "done") return state.result;
      if (state.status === "error") throw new Error(state.message);
      await new Promise((r) => setTimeout(r, 25));
    }
    throw new Error("the extraction did not finish");
  } finally {
    dom.window.close();
  }
}

const PARITY = [
  "amazon-de",
  "bolcom",
  "shopify-glow",
  "logged-in-shop",
  "service-site-nl",
  "p2p-marktplaats-iphone",
  "p2p-ebay-camera",
];

describe("the bundled extractor", () => {
  it("defines nothing but its own entry point", () => {
    const dom = new JSDOM("<p>x</p>", { url: "https://shop.test/", runScripts: "outside-only" });
    const before = new Set(Object.keys(dom.window));
    dom.window.eval(BUNDLE);
    const added = Object.keys(dom.window).filter((k) => !before.has(k));
    assert.deepEqual(added, ["__unfretAndroid"]);
    assert.equal(JSON.parse(dom.window.__unfretAndroid.poll()), null);
  });

  for (const name of PARITY) {
    it(`${name}: the extension's PageFacts`, async () => {
      const expected = JSON.parse(readFileSync(join(FIXTURES, `${name}.expected.json`), "utf8"));
      const { facts, text } = await run(name, { live: false });
      assert.deepEqual(facts, expected);
      assert.equal(typeof text, "string");
    });
  }

  for (const name of ["logged-in-shop", "service-site-nl", "service-site-us", "marktplaats-ad"]) {
    it(`${name}: nothing about the visitor in what is sent`, async () => {
      const { facts, text } = await run(name, { live: true, timerWaitMs: 10 });
      assert.doesNotMatch(JSON.stringify(facts), PERSONAL);
      assert.doesNotMatch(text, PERSONAL);
    });
  }

  it("runs live, as the app does, and reads the page address from the page", async () => {
    const { facts, text } = await run("shopify-glow", { live: true, timerWaitMs: 10 });
    assert.equal(facts.url, INDEX["shopify-glow"]);
    assert.ok(facts.title.length > 0);
    assert.ok(text.length > 0);
  });

  it("reports a failure instead of hanging", async () => {
    const dom = new JSDOM("<p>x</p>", { url: "https://shop.test/", runScripts: "outside-only" });
    dom.window.eval(BUNDLE);
    // A page that broke the DOM API the extractor needs.
    dom.window.eval("Document.prototype.querySelectorAll = () => { throw new Error('broken page'); }");
    dom.window.__unfretAndroid.start({ live: false });
    await new Promise((r) => setTimeout(r, 50));
    const state = JSON.parse(dom.window.__unfretAndroid.poll());
    assert.equal(state.status, "error");
    dom.window.close();
  });
});
