# Documentation Quality Assurance

Audit all project documentation for broken image references, stale cross-links, and content quality issues. Report every problem with a concrete fix.

## Scope

Markdown files to audit (search recursively from repo root, skip `node_modules`, `.git`, `target`):
- `docs/**/*.md`
- `*.md` at repo root (README.md, ARCHITECTURE.md, EXTENSIONS.md, CHANGELOG.md, etc.)

Image registries:
- **Filesystem:** `resources/image/` — source-of-truth for image files
- **Build config:** `dynamic-mapper-ui/cumulocity.config.ts` `buildTime.copy` array — images bundled into the Angular app

**Config requirement rule:** Only images referenced from these two sources need to be in `cumulocity.config.ts`:
1. `dynamic-mapper-ui/src/introduction/**/*.html` — Angular in-app documentation components
2. `dynamic-mapper-ui/README.md` — the README bundled with the UI plugin

Images referenced only in other docs (USERGUIDE.md, ARCHITECTURE.md, EXTENSIONS.md, docs/**/*.md, etc.) are served from GitHub/external and do NOT need a config entry.

---

## Step 1 — Collect all image references

**1a. App-facing references (must be in config)**
Scan `dynamic-mapper-ui/src/introduction/**/*.html` and `dynamic-mapper-ui/README.md`.
Extract every `<img src="...">` and `![alt](...)`.
For each: record source file, line number, image filename (basename only).

**1b. Other doc references (config not required)**
Scan all other markdown files in scope (including `USERGUIDE.md`, `ARCHITECTURE.md`, `EXTENSIONS.md`, `docs/**/*.md`).
Extract image references the same way — these are tracked for file-existence only, not config registration.

## Step 2 — Collect all known images

A. List every file in `resources/image/` (all extensions: `.png`, `.jpg`, `.gif`, `.svg`).

B. Parse `dynamic-mapper-ui/cumulocity.config.ts` and extract every filename from the `buildTime.copy` array `from` paths (strip the `../resources/image/` prefix to get bare filenames).

## Step 3 — Run the image audit

**A. Referenced in app sources but file missing from `resources/image/`**
For each image from Step 1a: check if the file exists in `resources/image/`.
List: source file + line, image name. Fix = "add the PNG to resources/image/".

**B. Referenced in app sources but not in `cumulocity.config.ts`**
For each image from Step 1a that exists in `resources/image/`: check if it appears in the config copy array.
List: source file + line, image name. Fix = "add a copy entry to cumulocity.config.ts buildTime.copy".

**C. In `cumulocity.config.ts` but missing from `resources/image/`**
For each filename in the copy array: check if the file exists in `resources/image/`.
List: config line, image name. Fix = "add the missing PNG or remove the copy entry".

**D. Referenced in other docs but file missing from `resources/image/`**
For each image from Step 1b: check if the file exists in `resources/image/`.
List: source file + line, image name. Fix = "add the PNG or fix the reference".

**E. Orphaned images in `resources/image/`**
For each file in `resources/image/`: check if it appears in Step 1a, Step 1b, or the config copy array.
List images with no reference anywhere. Fix = "delete the file or add a reference in a doc".

If all five categories are empty, print: **Image audit fully in sync — no gaps found.**

## Step 4 — Cross-link audit

Scan every markdown file in scope for internal links: `[text](relative/path.md)` and `[text](relative/path.md#anchor)`.

For each link:
- Resolve the path relative to the markdown file's directory.
- Check that the target file exists on disk.
- Anchors: check that the heading exists in the target file (convert heading text to GitHub-style anchor — lowercase, spaces → hyphens, strip punctuation).

Report: doc file + line number, broken link target. Fix = "update or remove the link".

## Step 5 — Content quality pass

Read the following key docs in full and check for:

- **Phantom references** — mentions of classes, methods, files, or flags that no longer exist in the codebase (grep to verify).
- **Missing coverage** — major features visible in the code that the docs don't mention at all.
- **Stale version pins** — hardcoded version numbers that differ from `pom.xml` or `package.json`.
- **Deprecated patterns** — code examples using APIs marked `@Deprecated` in Java or `@deprecated` in TypeScript.

Docs to read for this pass:
- `docs/backend/architecture.md`
- `docs/backend/conventions.md`
- `docs/ui/architecture.md`
- `docs/smart-functions.md`
- `README.md`
- `ARCHITECTURE.md`

For each finding: file + line, issue description, suggested fix. Skip minor style/grammar — focus on factual correctness.

## Step 6 — Produce the report

```
## Image Audit
### A. App-facing: file missing from resources/image/     (N issues)
### B. App-facing: not in cumulocity.config.ts            (N issues)
### C. In config but file missing                         (N issues)
### D. Other docs: file missing from resources/image/     (N issues)
### E. Orphaned images                                    (N issues)

## Broken Cross-links                                     (N issues)

## Content Quality                                        (N issues)

## Summary
Total issues: N
Priority fixes: [most impactful first]
```

If there are no issues in a section, print "None found." and move on.
