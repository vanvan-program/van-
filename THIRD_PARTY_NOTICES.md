# Third-party notices

van课表 authored code is GPL-3.0-only. No GPL/AGPL competitor source has been copied.

Runtime dependencies: Kotlin, kotlinx.coroutines, kotlinx.serialization, AndroidX Room, DataStore, Core, Activity, Lifecycle, WebKit, Compose and Material Components are distributed under Apache-2.0. Full Apache-2.0 text is included in `app/src/main/assets/licenses/Apache-2.0.txt`; original dependency artifacts retain upstream notices.

Tesseract4Android 4.9.0 (Copyright 2019 Adaptech s.r.o., Robert Pösel; JNI wrapper also credits Google) is Apache-2.0: https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0 . Its native components are Tesseract OCR 5.5.1 (Apache-2.0), Leptonica 1.85.0 (BSD-style), libjpeg v9f (IJG), and libpng 1.6.48 (libpng license). Original library artifacts remain unmodified. Native notices are included under `app/src/main/assets/licenses/tesseract-native-notices.txt`.

Chinese simplified and English OCR model files are unmodified `tessdata_fast` release 4.1.0 (Apache-2.0): https://github.com/tesseract-ocr/tessdata_fast/tree/4.1.0 . Version and SHA-256 are recorded in `app/src/main/assets/tessdata/README.md`. The models are packaged locally; no recognition server, analytics or runtime model download is used.

JExcelAPI / JXL 2.6.12, Copyright 2002 Andrew Khan and contributors, is LGPL-2.1: https://jexcelapi.sourceforge.net/ . Full license is included in `app/src/main/assets/licenses/LGPL-2.1.txt`. It is an unmodified Gradle library dependency; upstream source is available as `net.sourceforge.jexcelapi:jxl:2.6.12:sources` from Maven Central. Rebuilding this project's source with a modified or replacement JXL dependency allows relinking. XLSX reading is implemented independently in this project; formulas and external links are not executed.

Build and test tooling is not included in the runtime APK: Gradle, Android Gradle Plugin, KSP, JUnit and AndroidX Test. Their original distributions retain their own licenses. SDK and emulator are local development tools under their respective SDK terms.

School adapters in this project are original implementations based on documented interface paths and page conventions. The four schools require real authenticated fixtures before claiming compatibility.

Material icons supplied by the Compose dependency are Apache-2.0; the van app icon is an original vector.

Timetable paging references the page-offset animation approach demonstrated by
ryanw-mobile/compose-pager-demo (Apache-2.0): https://github.com/ryanw-mobile/compose-pager-demo .
The timetable-specific implementation uses the existing AndroidX Compose Foundation
HorizontalPager, with independently written date/week state handling, subtle layer
transforms, and an enlarged-grid gesture guard. No additional runtime library is bundled.
