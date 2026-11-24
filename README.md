# 🇧🇩 BD Excel

A Microsoft Excel–style spreadsheet app for Android, built fully offline — no third-party runtime dependencies.

## Features

| Area | What works |
|---|---|
| Grid | Smooth pan, **pinch zoom** (0.25×–4×), fling physics, sticky row/column headers |
| Editing | Tap to select, tap again / double-tap to edit, formula bar editor |
| Formulas | `=SUM(A1:B5)`, `AVERAGE`, `MIN`, `MAX`, `COUNT`, `COUNTA`, `IF`, `IFERROR`, `ROUND`, `ABS`, `SQRT`, `POWER`, `MOD`, `INT`, `PRODUCT`, `CONCAT`, `LEN`, `LEFT`, `RIGHT`, `MID`, `UPPER`, `LOWER`, `TRIM`, `AND`, `OR`, `NOT`, `MEDIAN`, `STDEV`, `TODAY`, `NOW`, `PI`, `RAND` — plus arithmetic `+ − × ÷ ^ %`, comparisons, and `&` concatenation, with circular-reference detection |
| Formatting | Bold, italic, text color, fill color, alignment (auto/left/center/right) |
| Structure | Insert / delete rows & columns, multiple sheets (add, rename, delete, clear), undo/redo (30 steps) |
| Files | Open real Excel (`.xlsx`) files, export back to `.xlsx`; CSV import/export; project `.json` save/load; everything via Android Storage Access Framework — opens files shared from Gmail/Drive/Files |
| Privacy | 100% offline, autosaves locally to `project.json` |

## Project layout

```
.github/workflows/android-apk.yml   CI: builds APK automatically & uploads it as an artifact
app/src/main/java/com/bdexcel/app/
  MainActivity.kt                   toolbar, formula bar, formatting bar, sheet tabs, SAF import/export
  model/Model.kt                     Cell / Sheet / Workbook + JSON persistence
  engine/Calculator.kt               full formula parser & evaluator
  io/Codecs.kt                       CSV codec + zero-dependency XLSX reader/writer
  view/SpreadsheetView.kt            zoomable, flingable grid with selection
```

## Getting the APK

Every push runs the **Build APK** GitHub Action. Go to *Actions → Build APK → (latest run) → Artifacts* and download `BD-Excel-debug-APK`. Install it on any Android 7.0+ device.

## Build locally

```bash
gradle assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```
