#!/usr/bin/env node
/**
 * Build step: compresses the PNG screenshots referenced in cumulocity.config.ts
 * into resources/image-optimized/ (generated, gitignored) without touching the
 * originals in resources/image/. cumulocity.config.ts's buildTime.copy list
 * points at the optimized directory, so `npm run build` picks these up.
 *
 * Runs automatically via the "prebuild" npm script. Source files are skipped
 * if an up-to-date optimized copy already exists (mtime comparison).
 *
 * Screenshots are also downscaled to MAX_WIDTH. They are captured on a retina display at
 * ~3400px wide, but the documentation viewer renders them inside `.doc-content`, which is capped
 * at 1200px — so the extra pixels are never shown and only inflate the deployable zip.
 * Re-quantizing at full width saves almost nothing (4.94MB -> 4.89MB); downscaling first takes
 * the same set to 2.70MB, which is ~2.2MB off dynamic-mapper.zip.
 */
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const UI_ROOT = path.resolve(__dirname, '..');
const CONFIG_PATH = path.join(UI_ROOT, 'cumulocity.config.ts');
const SOURCE_DIR = path.resolve(UI_ROOT, '../resources/image');
const DOCS_DIR = path.join(UI_ROOT, 'public/docs');
const OUTPUT_DIR = path.resolve(UI_ROOT, '../resources/image-optimized');

/** Widest a screenshot is ever rendered is 1200px (.doc-content); 1600 leaves headroom for
 *  zooming and higher-density displays without carrying full retina capture width.
 *  Images narrower than this are left alone (withoutEnlargement). */
const MAX_WIDTH = 1600;

function extractImageFilenames(configSource) {
  const regex = /['"]\.\.\/resources\/image-optimized\/([^'"]+\.png)['"]/g;
  const names = new Set();
  let match;
  while ((match = regex.exec(configSource)) !== null) {
    names.add(match[1]);
  }
  return [...names];
}

async function optimizeOne(filename) {
  const srcPath = path.join(SOURCE_DIR, filename);
  const outPath = path.join(OUTPUT_DIR, filename);

  const srcStat = fs.statSync(srcPath);
  if (fs.existsSync(outPath)) {
    const outStat = fs.statSync(outPath);
    // mtime alone would also skip copies produced before MAX_WIDTH existed (or by hand), so an
    // existing copy is only reused when it is actually no wider than the current limit.
    const width = (await sharp(outPath).metadata()).width ?? 0;
    if (outStat.mtimeMs >= srcStat.mtimeMs && width <= MAX_WIDTH) {
      return { filename, skipped: true, before: srcStat.size, after: outStat.size };
    }
  }

  await sharp(srcPath)
    .resize({ width: MAX_WIDTH, withoutEnlargement: true })
    .png({ quality: 80, palette: true, compressionLevel: 9 })
    .toFile(outPath);

  const outStat = fs.statSync(outPath);
  return { filename, skipped: false, before: srcStat.size, after: outStat.size };
}

/**
 * Every screenshot used by the in-app documentation needs a buildTime.copy entry, otherwise it is
 * simply absent from the bundle and 404s at runtime — silently, because the build still succeeds.
 * That has happened more than once, so it is checked here rather than discovered in a browser.
 *
 * Note this deliberately only covers `public/docs`, the pages the deployed app renders. The
 * repo's own `docs/**` markdown is read on GitHub and points at the full-size originals in
 * `resources/image/`, which are never bundled and never optimized.
 */
function checkDocsReferences(expected) {
  if (!fs.existsSync(DOCS_DIR)) return;
  const referenced = new Map();
  for (const file of fs.readdirSync(DOCS_DIR).filter((f) => f.endsWith('.md'))) {
    const body = fs.readFileSync(path.join(DOCS_DIR, file), 'utf8');
    for (const m of body.matchAll(/resources\/image\/([A-Za-z0-9_.-]+\.png)/g)) {
      if (!referenced.has(m[1])) referenced.set(m[1], file);
    }
  }

  const problems = [];
  for (const [name, file] of referenced) {
    if (!expected.includes(name)) {
      problems.push(`${name} (used by ${file}) has no buildTime.copy entry in cumulocity.config.ts`);
    } else if (!fs.existsSync(path.join(SOURCE_DIR, name))) {
      problems.push(`${name} (used by ${file}) is missing from resources/image/`);
    }
  }
  if (problems.length > 0) {
    console.error('[optimize-images] documentation images would not ship:');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
}

async function main() {
  const configSource = fs.readFileSync(CONFIG_PATH, 'utf8');
  const filenames = extractImageFilenames(configSource);
  checkDocsReferences(filenames);

  if (filenames.length === 0) {
    console.log('[optimize-images] no images referenced in cumulocity.config.ts, skipping');
    return;
  }

  fs.mkdirSync(OUTPUT_DIR, { recursive: true });

  const results = [];
  for (const filename of filenames) {
    results.push(await optimizeOne(filename));
  }

  // OUTPUT_DIR is generated, so anything in it that the config no longer references is a leftover
  // from a renamed or deleted screenshot. The packaging step copies the whole directory, so those
  // leftovers would otherwise keep shipping inside dynamic-mapper.zip indefinitely.
  const expected = new Set(filenames);
  const pruned = fs
    .readdirSync(OUTPUT_DIR)
    .filter((f) => f.toLowerCase().endsWith('.png') && !expected.has(f));
  for (const filename of pruned) {
    fs.unlinkSync(path.join(OUTPUT_DIR, filename));
  }
  if (pruned.length > 0) {
    console.log(`[optimize-images] pruned ${pruned.length} unreferenced: ${pruned.join(', ')}`);
  }

  const optimized = results.filter((r) => !r.skipped);
  const totalBefore = results.reduce((sum, r) => sum + r.before, 0);
  const totalAfter = results.reduce((sum, r) => sum + r.after, 0);
  const pct = totalBefore > 0 ? Math.round((1 - totalAfter / totalBefore) * 100) : 0;

  console.log(
    `[optimize-images] ${optimized.length} optimized, ${results.length - optimized.length} cached ` +
      `(${(totalBefore / 1024 / 1024).toFixed(1)}MB -> ${(totalAfter / 1024 / 1024).toFixed(1)}MB, -${pct}%)`
  );
}

main().catch((err) => {
  console.error('[optimize-images] failed:', err);
  process.exit(1);
});
