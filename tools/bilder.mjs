// Renders the repo images from the app screenshots in docs/bilder/app. Needs Playwright:
//   PLAYWRIGHT=/path/to/node_modules/playwright/index.mjs node tools/bilder.mjs
import path from "node:path";
import { fileURLToPath } from "node:url";
const { chromium } = await import(process.env.PLAYWRIGHT ?? "playwright");
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const app = (f) => `file://${root}/docs/bilder/app/${f}`;
const images = [
  ["1-waiting.png", 1080, 1920, "tall", "Read on WhatsApp,|never answered", "the list fills itself", app("liste.png")],
  ["2-check-log.png", 1080, 1920, "tall", "You see why|it counts as read", "check log with Android's reason codes", app("protokoll.png")],
  ["social-preview.png", 1280, 640, "wide", "Who is waiting|for your reply", "Android · WhatsApp · offline · ADHD-friendly", app("liste.png")],
];
const browser = await chromium.launch();
for (const [name, w, h, art, t, s, img] of images) {
  const page = await browser.newPage({ viewport: { width: w, height: h } });
  const u = new URL(`file://${root}/tools/rahmen.html`);
  for (const [k, v] of Object.entries({ w, h, art, t, s, img })) u.searchParams.set(k, v);
  await page.goto(u.href, { waitUntil: "networkidle" });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: `${root}/docs/bilder/${name}` });
  await page.close();
}
await browser.close();
