# mockref

Machine reference for the Meiro Android HTML mock (`docs/android/mock/index.html`).
The Android app is later compared against what this tool writes.

## Run

```
cd tools/mockref
npm install          # playwright 1.63.0 (pinned; uses the already installed chromium build 1243)
node mockref.mjs     # all 28 screens x {light, dark}
node mockref.mjs --only home,np --no-png
```

Do not set `PLAYWRIGHT_BROWSERS_PATH`; the default cache (`~/.cache/ms-playwright`) is used and nothing is downloaded.

## What it writes (under `docs/android/mock/`)

- `ref/<key>-<theme>.png`: the phone screen only, device scale 1 (370 x 824 CSS px = dp the app is rendered at).
- `inventory/<key>.json`: every visible leaf / text / icon / control / surface element with path, tag, classes, role, aria-label, own text, icon name (from the mock's `I('name')` table, `logo:*` for brand logos), bbox relative to the phone screen (0.5 px), computed typography, colours (light and dark), radius. Pseudo elements with content (switch knobs, tab indicators) are listed as `::before` / `::after`. `offscreen: true` marks elements below/right of a scroll container's viewport; `clippedText` lists ellipsis / line-clamp / hidden-clip text.
- `audit.json`: self-audit of the mock (overflow, clipped-by-ancestor, outside-screen, centring, rows, font). Each finding names its heuristic; false positives are possible. The mock is never modified.
- `tokens.json`: CSS custom properties for both themes (value and rgb) and the `@font-face` declarations.

## Notes

- The mock's `@font-face` uses `../meiro/fonts/GoogleSans.ttf`, which only resolves when the mock sits at `OSS/mobile-mock/`. From `docs/android/mock/` it 404s, so the script injects an identical `@font-face` pointing at `fonts/GoogleSans.ttf` in this repo. The app must use that same file.
- Animations and transitions are disabled; the viewport is 1400 x 1000 so the phone gets its full 844 px height.
- Centring is measured against the container content box (padding box for absolutely positioned pseudo elements). Inline pseudo elements (e.g. the legend dots) have no measurable geometry and are not checked.
