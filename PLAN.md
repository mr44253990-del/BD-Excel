# BD Excel — সম্পূর্ণ বাস্তবায়ন প্ল্যান (ধাপে ধাপে)

## লক্ষ্য
মাইক্রোসফট এক্সেলের মতো অ্যান্ড্রয়েড স্প্রেডশীট অ্যাপ্লিকেশন "BD Excel" তৈরি। সব ফাংশন কাজ করবে, কোন ফাংশন বাদ পড়বে না, ইমপোর্ট/এক্সপোর্ট সঠিক ফরম্যাটে হবে, জুম সব জায়গায় working হবে। রিয়াল এক্সেল থেকে ফাইল দিলে ওপেন করা যাবে এবং এখান থেকে সঠিকভাবে এক্সপোর্ট হবে।

---

## পর্যায় ১ — প্রজেক্ট সেটআপ ও বিল্ড কনফিগারেশন (সম্পন্ন)
- গিট ব্রাঞ্চ: `feature/bd-excel-android`
- `settings.gradle`, `build.gradle`, `gradle.properties` — গ্র্যাডল কনফিগারেশন
- `app/build.gradle` — Android Gradle Plugin 8.2.0, Kotlin 1.9.22, compileSdk 34, minSdk 21, targetSdk 34
- `viewBinding`, `dataBinding` এনাবল করা
- ডিপেনডেন্সি: AppCompat, Material, ConstraintLayout, RecyclerView, Room, Lifecycle, Apache POI (XLSX), OpenCSV (CSV), Glide
- **টask: Gson ডিপেনডেন্সি যোগ করতে হবে** (`com.google.code.gson:gson:2.10.1`) — ঠিক করার তালিকায় রয়েছে

## পর্যায় ২ — অ্যান্ড্রয়েড ম্যানিফেস্ট ও পারমিশন (সম্পন্ন)
- `AndroidManifest.xml` — প্যাকেজ `com.bdexcel.app`
- পারমিশন: INTERNET, WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE, MANAGE_EXTERNAL_STORAGE, REQUEST_INSTALL_PACKAGES, VIBRATE
- FileProvider (`com.bdexcel.app.fileprovider`) — অন্যান্য অ্যাপ থেকে ফাইল ওপেনের জন্য
- Intent Filter: `.xlsx`, `.xls`, `.csv` ফাইল ভিউ/এডিট/ওপেন এবং CREATE_DOCUMENT

## পর্যায় ৩ — ডেটা লেয়ার (মডেল ক্লাস)
- `Cell.kt` — সেল ডেটা মডেল (row, column, value, formula, evaluatedValue, bold, italic, textColor, backgroundColor, fontSize, alignment, wrapText, columnWidth, rowHeight) — Parcelable
- `Sheet.kt` — শিট মডেল (id, name, cells Map, columnCount, rowCount, createdAt, updatedAt) — Parcelable
- `Workbook.kt` — ওয়ার্কবুক মডেল (id, name, sheets List, activeSheetIndex, createdAt, updatedAt)
- `CellCoordinate.kt` — সেল রেফারেন্স (A1, B2, ইত্যাদি) — `toAlpha()` / `fromAlpha()`
- `SheetSnapshot.kt` — শিটের স্ন্যাপশট (রিক্যালকুলেশনের জন্য)
- **স্ট্যাটাস:** সকল মডেল সঠিক, কোন বাগ নেই

## পর্যায় ৪ — ফরমুলা ইঞ্জিন (সম্পন্ন)
- `FormulaEngine.kt` — Excel-এর মতো ফরমুলা ইঞ্জিন
- সাপোর্ট করা ফরমুলা: `=SUM(range)`, `=AVERAGE(range)`, `=MIN(range)`, `=MAX(range)`, `=COUNT(range)`, `+`, `-`, `*`, `/`, `=IF(condition,true,false)`
- সেল রেফারেন্স (A1) এবং রেঞ্জ (A1:A10) উভয়ই সাপোর্ট
- `recalculateAll()` — পুরো শিটের সব ফরমুলা রিক্যালকুলেট
- **স্ট্যাটাস:** সঠিক, কোন বাগ নেই

## পর্যা ৫ — ইমপোর্ট/এক্সপোর্ট ম্যানেজার (বাগ ঠিক করার প্রয়োজন)
- `ImportExportManager.kt` — `.xlsx` (Apache POI) ও `.csv` (OpenCSV) ইমপোর্ট এবং এক্সপোর্ট
- **বাগ:** POI Cell/Font API ভুল ব্যবহার (`cell.bold`, `cell.fontSize`, `setColor(Int)`, `fillForegroundColorColor`) — কম্পাইল失败 হবে
- **সंशোধন:** POI সঠিক API ব্যবহার করে (`cell.cellStyle.getFont(workbook)`, `font.isBold`, `font.isItalic`, `font.fontHeightInPoints`, `XSSFColor` থেকে রঙ নেওয়া)
- রিয়াল এক্সেল ফাইল ওপেন → সেল ভ্যালু, রঙ, অ্যালাইনমেন্ট, बोल्ड/ইটালিক সঠিকভাবে আনা
- এক্সেল/সিএসভ ফরম্যাটে সঠিক এক্সপোর্ট
- লোকাল JSON ব্যাকআপ/রিস্টোর (`saveWorkbookToFile`, `loadWorkbookFromFile`)

## পর্যা ৬ — জসন সিরিলাইজার (বাগ ঠিক করার প্রয়োজন)
- `WorkbookSerializer.kt` — ওয়ার্কবুক(JSON) সিরিলাইজেশন/ডিসিরিলাইজেশন
- **বাগ:** কাস্টম JSON পার্সার ভ contém ভাগ (`parseObject` সঠিক इंडেক্স রিটার্ন করে না, নেস্টেড অবজেক্ট পার্স হবে না) — রিকভার করা ওয়ার্কবুক লোড করা যাবে না
- **সंशোধন:** Gson লাইব্রেরি ব্যবহার করে विश्वাসযোগ্য সিরিলাইজেশন (`CellDto`, `SheetDto`, `WorkbookDto` ডাটা ক্লাসের মাধ্যমে)
- অটোসেভ ফাইলে (`autosave.json`) ওয়ার্কবুক সেভ/লোড

## পর্যা ৭ — রিপোজিটরি (সম্পন্ন)
- `SheetRepository.kt` — ওয়ার্কবুক লোকাল স্টোরেজ (`context.filesDir/workbooks/`)
- `saveWorkbook()`, `loadWorkbook()`, `deleteWorkbook()`, `listWorkbooks()`
- `autosave()` (প্রতি ৩০ সেকেন্ড), `loadAutosave()`, `clearAutosave()`
- `exportWorkbookToExternal()` — ডাউনলোড ফোল্ডারে `.xlsx` / `.csv` এক্সপোর্ট

## পর্যায় ৮ — ইমেজ এক্সপোর্ট (শিট→ফটো) (ছোট বাগ ঠিক করার প্রয়োজন)
- `ImageExportManager.kt` — শিটকে পিএনজি ছবি হিসেবে গ্যালারিতে সেভ (`MediaStore`)
- `renderSheetToBitmap()` — ক্যানভাসে গ্রিড, সেল কন্টেন্ট, হেডার রেন্ডার
- **বাগ:** Int division (`width / sheet.columnCount`) — সেলের चौड़ाई শূন্য হতে পারে
- **সংশोधন:** Float division (`width.toFloat() / sheet.columnCount`)
- অ্যাসিনক্রোনাস এক্সিকিউটর (`ExecutorService`), কলব্যাকের মাধ্যমে URI রিটার্ন

## পর্যা ৯ — কালার প্যালেট ও থিম (সম্পন্ন)
- `ColorPalette.kt` —.primary, accent, surface, background, gridLine, selectedCell ইত্যাদি (লাইট + ডার্ক)
- `values/colors.xml`, `values-night/colors.xml` — লাইট ও ডার্ক মোড কালার
- `values/themes.xml`, `values-night/themes.xml` — Material Components থিম (DayNight)

## পর্যা ১০ — ইউআই/ইউএক্স (সম্পন্ন + ছোট পরিষ্কার)
- `activity_main.xml` — Toolbar, FormulaBar, SheetName, Zoom SeekBar, SheetView (কাস্টম গ্রিড), FloatingActionButton
- `SheetView.kt` — কাস্টম জুমযোগ্য গ্রিড ভিউ (pinch-zoom 0.5x–4x, প্যান)
  - সেল সিলেকশন হাইলাইট, কলাম হেডার (A,B,C…), রো নম্বর (1,2,3…)
  - সেল ব্যাকগ্রাউন্ড কালার, টেক্সট কালার, অ্যালাইনমেন্ট রেন্ডার
  - **বাগ:** অন্যযuž `clickListener` ভেরিয়েবল (ডেড কোড) — সরিয়ে ফেলা হবে
- `MainActivity.kt` — মূল অ্যাক্টিভিটি
  - সেল ক্লিক → ফর্মুলা বারে ভ্যালু, সেল দীর্ঘ ক্লিক → কনটেক্সট মেনু (এডিট/ফরমুলা/কালার/কপি/পেস্ট/ডিলিট)
  - নতুন ওয়ার্কবুক, ওপেন (ফাইল পিকার), সেভ, এক্সপোর্ট (xlsx/csv/ইমেজ), শিট যোগ/পরিষ্কার
  - অটোসেভ হ্যান্ডলার, Intent.ACTION_VIEW/EDIT/SEND থেকে ফাইল ওপেন
  - `startActivityForResult` ডেপ্রিকেটেড — আধুনিক Activity Result API-তে রূপান্তর করা যাবে (অতিরিক্ট)
- `menu_main.xml` — টুলবার মেনু (নতুন/খোলা/সেভ/এক্সপোর্ট/শিট যোগ/পরিষ্কার/সহায়তা)

## পর্যা ১১ — রিসোর্স: লোগো, আইকন, ড্রয়াবল (সম্পন্ন)
- `bd_map_logo.xml` — বাংলাদেশের মানচিত্রের মতো ভেক্টর লোগো (সবুজ মানচিত্র + লাল বৃত্ত)
- `ic_launcher_background.xml` — লঞ্চার ব্যাকগ্রাউন্ড
- `mipmap-*/ic_launcher.xml` ও `ic_launcher_round.xml` — সকল ডেনসিটিতে লঞ্চার আইকন
- `drawable/ic_add.xml`, `ic_new.xml`, `ic_open.xml`, `ic_save.xml`, `ic_formula.xml` — ভেক্টর আইকন
- `values/strings.xml`, `values/dimens.xml` — স্ট্রিং ও ডাইমেনশন
- `xml/file_paths.xml` — FileProvider পথ কনফিগারেশন

## পর্যা ১২ — সিআই/সিডি ও অটো APK বিল্ড (সম্পন্ন)
- `.github/workflows/android-build.yml` — গিটহাব অ্যাকশন
  - পুশ/পular রিকোয়েস্টে চলে (`feature/bd-excel-android`, `main`)
  - জেডকে ১৭, অ্যান্ড্রয়েড এসডিকে API 34 সেটআপ
  - `./gradlew assembleDebug` — ডিবাগ APK বিল্ড
  - `app-debug.apk` আর্টিফাক্ট হিসেবে আপলোড (৩০ দিন ধরে রাখা)
- `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`

## পর্যা ১৩ — ডকুমেন্টেশন (সম্পন্ন)
- `README.md` — অ্যাপের বিবরণ, ফিচার, স্ট্রাকচার, বিল্ড ವಿಧি, সিআই/সিডি
- `PLAN.md` — এই প্ল্যান ডকুমেন্ট

---

## বর্তমানে বাদ আছে / বাগ আছে (টask তালিকা)

| # | ত্রুটি / कमी | ফাইল |евидেন্স | সমাধান |
|---|-------------|------|--------|--------|
| ১ | POI Cell/Font API ভুল (`cell.bold`, `cell.fontSize`, `setColor(Int)`) | `ImportExportManager.kt` | কম্পাইল失败 হবে | সঠিক POI API (`getFont(workbook)`, `font.isBold`, `font.fontHeightInPoints`, `XSSFColor`) ব্যবহার |
| ২ | কাস্টম JSON পার্সার ভ bounds Error (`parseObject` সঠিক אינডেক্স রিটার্ন করে না) | `WorkbookSerializer.kt` | রিকভার করা ওয়ার্কবুক লোড হবে না | Gson লাইব্রেরি ব্যবহার করে সিরিলাইজেশন (`CellDto`, `SheetDto`, `WorkbookDto`) |
| ৩ | Int division — সেলের चौड़ाई শূন্য হতে পারে | `ImageExportManager.kt` | রেন্ডার করা শিট ভেঙে যাবে | Float division (`width.toFloat() / sheet.columnCount`) |
| ৪ | ডেড কোড (`clickListener` ভেরিয়েবল) | `SheetView.kt` | কম্পাইল আহম solitary-warning | অপ্রয়োজনীয় ভেরিয়েবল সরিয়ে ফেলা |
| ৫ | Gson ডিপেনডেন্সি নেই | `app/build.gradle` | Gson ইমপোর্টের জন্য প্রয়োজন | `implementation 'com.google.code.gson:gson:2.10.1'` যোগ |

---

## পরবর্তী পদক্ষেপ (এই সেশনে)

১. উপরের ৫টি বাগ ঠিক করা (কোড সংশোধন)
২. `build.gradle`-এ Gson ডিপেনডেন্সি যোগ
৩. সব ফাইল কমিট করে `feature/bd-excel-android` ব্রাঞ্চে পুশ করা
৪. GitHub Actions বিল্ড অপেক্ষা (`app-debug.apk` আর্টিফাক্ট)
৫. যদি Actions ফেল করে → এরর লগ পড়া → কোড সংশোধন → আবার পুশ → আবার বিল্ড
৬. বিল্ড সফল হলে APK আর্টিফাক্টের লিঙ্ক ব্যবহারকারীকে দেওয়া

---