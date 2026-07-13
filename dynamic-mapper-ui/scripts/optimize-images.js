#!/usr/bin/env node
/**
 * Build step: compresses the PNG screenshots referenced in cumulocity.config.ts
 * into resources/image-optimized/ (generated, gitignored) without touching the
 * originals in resources/image/. cumulocity.config.ts's buildTime.copy list
 * points at the optimized directory, so `npm run build` picks these up.
 *
 * Runs automatically via the "prebuild" npm script. Source files are skipped
 * if an up-to-date optimized copy already exists (mtime comparison).
 */
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const UI_ROOT = path.resolve(__dirname, '..');
const CONFIG_PATH = path.join(UI_ROOT, 'cumulocity.config.ts');
const SOURCE_DIR = path.resolve(UI_ROOT, '../resources/image');
const OUTPUT_DIR = path.resolve(UI_ROOT, '../resources/image-optimized');

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
    if (outStat.mtimeMs >= srcStat.mtimeMs) {
      return { filename, skipped: true, before: srcStat.size, after: outStat.size };
    }
  }

  await sharp(srcPath)
    .png({ quality: 80, palette: true, compressionLevel: 9 })
    .toFile(outPath);

  const outStat = fs.statSync(outPath);
  return { filename, skipped: false, before: srcStat.size, after: outStat.size };
}

async function main() {
  const configSource = fs.readFileSync(CONFIG_PATH, 'utf8');
  const filenames = extractImageFilenames(configSource);

  if (filenames.length === 0) {
    console.log('[optimize-images] no images referenced in cumulocity.config.ts, skipping');
    return;
  }

  fs.mkdirSync(OUTPUT_DIR, { recursive: true });

  const results = [];
  for (const filename of filenames) {
    results.push(await optimizeOne(filename));
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
