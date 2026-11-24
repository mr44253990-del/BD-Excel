# BD Excel

মাইক্রোসফট এক্সেলের মতো অ্যান্ড্রয়েড স্প্রেডশীট অ্যাপ্লিকেশন।

## অ্যাপের নাম
**BD Excel** — লোগো বাংলাদেশের মানচিত্রের মতো (সবুজ মানচিত্র + লাল বৃত্ত, জাতীয় পতাকার থিমে)。

## মূল ফিচার
- গ্রিড শিট (রো/কಾಲাম) এক্সেলের মতো
- সেল এডিট, ভ্যালু ও ফরমুলা (=SUM, =AVERAGE, =MIN, =MAX, =COUNT, +, -, *, /, IF)
- সেল ব্যাকগ্রাউন্ড কালার, টেক্সট কালার, बोल্ড/ইটালিক, অ্যালাইনমেন্ট, ফন্ট সাইজ
- pinch-zoom এবং প্যান — যেকোন জায়গায় জুম করা যাবে
- রিয়াল এক্সেল (.xlsx/.xls) ও .csv ফাইল ওপেন/ইমপোর্ট
- সঠিক ফরম্যাটে এক্সপোর্ট (.xlsx, .csv)
- শিটকে পিএনজি ছবি হিসেবে গ্যালারিতে সেভ (Export photo)
- লোকাল 오토সেভ ও ওয়ার্কবুক লিস্ট
- একাধিক শিট (ট্যাব) সাপোর্ট
- বাংলাদেশ মানচিত্রের লোগো ও স্মার্ট ডিজাইন
- ডার্ক মোড সাপোর্ট
- অ্যান্ড্রয়েড API 21 থেকে API 34 পর্যন্ত সকল ভার্সনে কমফোর্টেবল

## প্রজেক্ট স্ট্রাকচার
```
BD Excel/
  app/
    src/main/
      AndroidManifest.xml
      java/com/bdexcel/app/
        MainActivity.kt        # মূল স্ক্রিন, ইমপোর্ট/এক্সপোর্ট, অটোসেভ
        SheetView.kt           # জুমযোগ্য গ্রিড শিট ভিউ
        Cell.kt / Sheet.kt / Workbook.kt   # ডেটা মডেল
        FormulaEngine.kt       # এক্সেল ফরমুলা ইঞ্জিন
        ImportExportManager.kt # xlsx/csv ইমপোর্ট-এক্সপোর্ট
        ImageExportManager.kt  # শিট→পিএনজি ফটো সেভ
        ColorPalette.kt        # কালার প্যালেট
        SheetRepository.kt     # লোকাল জসন স্টোরেজ
        WorkbookSerializer.kt  # জসন সিরিলাইজার
      res/
        layout/activity_main.xml
        drawable/bd_map_logo.xml   # বাংলাদেশ মানচিত্র লোগো
        drawable/ic_*.xml
        mipmap-*/ic_launcher.xml   # লঞ্চার আইকন
        values/colors.xml, themes.xml, strings.xml, dimens.xml
        values-night/...           # ডার্ক মোড
        xml/file_paths.xml         # FileProvider পথ
    build.gradle
  build.gradle
  settings.gradle
  gradle.properties
  .github/workflows/android-build.yml   # অটো APK বিল্ড ওয়ার্কফ্লো
```

## বিল্ড করার ವಿಧি (লোকাল)
প্রয়োজন: জেডকে ১৭ + অ্যান্ড্রয়েড এসডিকে (API 34)

```bash
chmod +x gradlew
./gradlew assembleDebug
```

ডিবাগ এপিকে পাবেন এখানে:
```
app/build/outputs/apk/debug/app-debug.apk
```

## সিআই/সিডি (গিটহাব অ্যাকশন)
`.github/workflows/android-build.yml` ফাইলটি পুশ বা পুল রিকোয়েস্টে অটোমেটিকালি চলে এবং ডিবাগ এপিকে বিল্ড করে আর্টিফাক্ট হিসেবে আপলোড করে। আর্টিফাক্ট থেকে `app-debug.apk` ডাউনলোড করে ইনস্টল করা যাবে।

## লাইসেন্স
MIT
