// The Google Play listing's images (store/play/README.md), rendered in headless Chromium (Playwright):
//   icon-512.png                 the app icon: the launcher icon's tile and mark, full bleed (Play rounds it)
//   feature-graphic-1024x500.png the feature graphic, in the house style of the extension's promo tiles
//   screenshots/N-*.png          the emulator's captures (tools/shoot.sh), as 24-bit PNGs (no alpha, as Play asks)
//
// Usage: node store/play/graphics.mjs [RAW_DIR]   (RAW_DIR: the captures; without it only the icon and the graphic)
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const here = dirname(fileURLToPath(import.meta.url));
const raw = process.argv[2];

// The brand: the launcher icon's colours (app/src/main/res/values/colors.xml: icon_background, brand_ink) and its
// mark (drawable/ic_launcher_foreground.xml, the extension's brand/unfret.svg), on the 128-unit grid of the brand SVG.
const BRAND = "#1f8a70";
const MARK_PATHS = `<circle cx="45" cy="44" r="7.5" fill="#fff"/><circle cx="79" cy="44" r="7.5" fill="#fff"/>
  <path d="M31 70 L50 89 Q58 96 66 89 L99 57" fill="none" stroke="#fff" stroke-width="11.5" stroke-linecap="round"
  stroke-linejoin="round"/>`;
// The tile, square and full bleed (no rounding: Play applies its own mask and shadow).
const ICON = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="4 4 120 120"><rect x="4" y="4" width="120" height="120"
  fill="${BRAND}"/>${MARK_PATHS}</svg>`;
// The rounded tile, as on the website and the extension's promo tiles.
const MARK = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128"><rect x="4" y="4" width="120" height="120"
  rx="30" fill="${BRAND}"/>${MARK_PATHS}</svg>`;
const svgUrl = (svg) => `data:image/svg+xml;base64,${Buffer.from(svg).toString("base64")}`;
const FONT = "system-ui, -apple-system, 'Segoe UI', Roboto, 'Noto Sans', 'DejaVu Sans', Arial, sans-serif";

// The captures, in the order the listing shows them.
const SCREENSHOTS = [
  ["home", "1-home.png"],
  ["shared-page", "2-shared-page.png"],
  ["quick-result", "3-quick-result.png"],
  ["check-running", "4-check-running.png"],
  ["deep-result", "5-deep-result.png"],
  ["deep-signals", "6-deep-signals.png"],
  ["deep-checklist", "7-deep-checklist.png"],
];

const featureGraphic = `<body style="margin:0;width:1024px;height:500px;display:flex;align-items:center;
  justify-content:center;gap:52px;background:linear-gradient(135deg,#f3faf7,#dff1ea);font-family:${FONT};color:#123b32">
  <img src="${svgUrl(MARK)}" width="200" height="200">
  <div><div style="font-size:104px;font-weight:700;letter-spacing:-0.02em;line-height:1">Unfret</div>
  <div style="font-size:40px;margin-top:20px;color:#1f5f50">Check before you pay.</div>
  <div style="font-size:24px;margin-top:16px;color:#3d6e62;max-width:520px;line-height:1.35">Fake sellers, fake shops
  and fake discounts, spotted on your phone.</div></div></body>`;

const browser = await chromium.launch();
try {
  // Drawn on a canvas: its PNG keeps the alpha channel (Play asks for 32-bit PNG), where a screenshot of an opaque
  // page drops it.
  const icon = await browser.newPage();
  const iconPng = await icon.evaluate(async (src) => {
    const img = new Image();
    img.src = src;
    await img.decode();
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 512;
    canvas.getContext("2d").drawImage(img, 0, 0, 512, 512);
    return canvas.toDataURL("image/png");
  }, svgUrl(ICON));
  writeFileSync(join(here, "icon-512.png"), Buffer.from(iconPng.split(",")[1], "base64"));
  await icon.close();

  const feature = await browser.newPage({ viewport: { width: 1024, height: 500 } });
  await feature.setContent(featureGraphic);
  await feature.screenshot({ path: join(here, "feature-graphic-1024x500.png") });
  await feature.close();

  if (raw) {
    const out = join(here, "screenshots");
    rmSync(out, { recursive: true, force: true });
    mkdirSync(out, { recursive: true });
    const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
    for (const [name, file] of SCREENSHOTS) {
      const source = join(raw, `${name}.png`);
      if (!existsSync(source)) throw new Error(`no capture ${source}`);
      const data = `data:image/png;base64,${readFileSync(source).toString("base64")}`;
      await page.setContent(`<body style="margin:0;background:#fff"><img src="${data}" width="1080" height="1920"
        style="display:block"></body>`);
      await page.screenshot({ path: join(out, file) });
    }
    await page.close();
    console.log(`screenshots: ${readdirSync(out).join(", ")}`);
  }
} finally {
  await browser.close();
}
console.log("rendered store/play/icon-512.png and store/play/feature-graphic-1024x500.png");
