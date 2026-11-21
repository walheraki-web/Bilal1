# Bilal

تطبيق أندرويد (TV / Android TV) يشغّل محطة صوتية من الإنترنت فوق قناة تلفزيونية
حيّة، **يزامن صوت المحطة مع صورة القناة تلقائيًا**، ويبقي المزامنة قائمة بعد
انقطاع النت أو تقطّع الصوت، ويعرض **ترجمة عربية حيّة لأصوات الأفلام**.

هذا مشروع مصدري كامل من الصفر (الحزمة `com.bilal.tv`). يُبنى بنقرة واحدة في
Android Studio.

---

## لماذا هذا التطبيق

عند مزامنة صوت محطة إنترنت مع صورة قناة، المشكلة الأهم أن المزامنة تُحسب مرة
واحدة، فإذا انقطع النت أو تقطّع الصوت تخرج المحطة عن الصورة ولا تعود. Bilal
صُمّم حول هذه النقطة:

- **إعادة اتصال بلا حد** مع تمهّل متصاعد يتوقف عند 30 ثانية، لا يستسلم بعد
  عدد محاولات.
- **إعادة محاولة فورية عند عودة الشبكة** عبر `ConnectivityManager`.
- **معالجة "تجاوز نافذة البث المباشر"** (يحدث لبث HLS بعد انقطاع طويل):
  قفز إلى البث المباشر بدل التوقف.
- **إعادة مزامنة تلقائية** بعد أي انقطاع، بمجرد استقرار البث (قابلة للإيقاف).
- قياس المزامنة يعتمد على **زمن الخروج الفعلي للصوت** من مقبس التشغيل
  (`TappingAudioSink`)، لا على موضع المشغّل، فيصحّ لبث HLS المباشر وللتوقفات.

## كيف تعمل المزامنة

1. يلتقط Bilal صوت القناة (`capture/`) ويحوّله إلى خط زمني من ملامح الصوت
   (`sync/FeatureTrack`): تغيّر طاقة 24 نطاقًا كل 20 ms، وهي ثابتة أمام فروق
   الصوت واﻹيكواﻹيزر بين البث والقناة.
2. يلتقط صوت المحطة من مقبس التشغيل نفسه بخط زمني مماثل.
3. `sync/Correlator` يطابق الخطين بالارتباط المتبادل عبر FFT، ويعطي الإزاحة
   مع درجة ثقة ومنافِس؛ لا تُعتمد النتيجة إلا إذا كانت واثقة واتفقت نافذتان
   متتاليتان (`SyncPolicy`).
4. يحوّل الإزاحة إلى خطة: يدفع **تأخير الصورة** في الريسيفر على خطوات لا تتجاوز
   8 ثوانٍ (ما يتحمّله)، ويضبط **تأخير صوت المحطة** للباقي الدقيق.

محرك المزامنة بالكامل كود Kotlin صافٍ (لا أنواع أندرويد)، ومختبَر على JVM
بأصوات مُركّبة بإزاحة معروفة: `app/src/test/.../CorrelatorTest.kt`.

## ترجمة الفيلم (ملفات SRT)

`subs/Srt` + `subs/SrtController`: تحمّل ملف ترجمة **SRT** (من رابط أو مسار
على الجهاز)، وتعرض الأسطر في شريط سفلي (`subs/SubtitleOverlay`) بلا إنترنت
بعد التحميل، وبلا Google Play. بما أن Bilal لا يشغّل الفيديو (الريسيفر يشغّله)،
تُضبط المزامنة يدويًا: شغّل الفيلم، اضغط «ابدأ الترجمة» عند بدايته، واضبط
الإزاحة (±) حتى تتطابق. الإزاحة تُحفظ. يدعم SRT و WebVTT، وبفاصل «,» أو «.».


## مصدر صوت القناة

واجهة واحدة `capture/TvAudioSource` بمصدرين:

- **ميكروفون** (`MicTvAudioSource`) — يعمل فورًا، يحتاج ميكروفونًا ومكانًا هادئًا.
  هو الافتراضي.
- **التقاط داخلي** (`InternalCaptureSource` + `CaptureMain`) — يلتقط خرج الصوت
  نفسه بدقة ودون ميكروفون. المُلتقِط `CaptureMain` يعمل بصلاحية مستخدم الـ shell
  عبر `app_process`، ويبثّ الصوت إلى التطبيق عبر `LocalSocket`. تشغيله يحتاج أمرًا
  واحدًا على الريسيفر:

  ```
  CLASSPATH=<path-to-apk> app_process /system/bin com.bilal.tv.capture.CaptureMain
  ```

  هذا الأمر يُنفَّذ عبر منفذ الـ ADB الخاص بالريسيفر. وفّر منفّذًا يطبّق
  `InternalCaptureSource.HelperLauncher.launch(classpath)` (نقلٌ عبر ADB على
  ‎127.0.0.1) ثم عيّن المصدر في الخدمة:

  ```kotlin
  service.tvAudioSource = InternalCaptureSource(context) { classpath -> myAdb.runAsShell("CLASSPATH='$classpath' app_process /system/bin com.bilal.tv.capture.CaptureMain") }
  ```

  ناقل ADB غير مضمّن هنا؛ البنية جاهزة لاستقباله.

## التحكم بالريسيفر

`receiver/ReceiverControl`: يضبط تأخير الصورة عبر أمر UDP محلي، ويكتم صوت القناة
عبر `hal_param_tv_mute` أو DroidLogic. كلاهما يعتمد على المنصّة؛ عدّلهما لصندوقك
إن لزم (نقطة واحدة لكلٍّ).

---

## البناء

المتطلبات: Android Studio (Koala أو أحدث) أو Android SDK مع JDK 17.

```
# من Android Studio: افتح المجلد، ثم Run ▸ app
# أو من الطرفية:
cp local.properties.sample local.properties   # ثم عدّل sdk.dir
./gradlew :app:assembleRelease
# الناتج: app/build/outputs/apk/release/app-release.apk
```

تشغيل اختبارات محرك المزامنة:

```
./gradlew :app:testReleaseUnitTest
```

> أول بناء ينزّل Gradle وAndroid Gradle Plugin وmedia3 وML Kit وVosk من
> Google وMaven Central. تمّ التحقق من أنواع كل الكود المصدري هنا، لكن لم يُبنَ
> APK في بيئة الإعداد لأنها تمنع تنزيل حزم Google؛ البناء يتم على جهازك.

## الصلاحيات

- `SYSTEM_ALERT_WINDOW` لشريط الترجمة فوق الصورة (يطلبه التطبيق عند أول تشغيل).
- `POST_NOTIFICATIONS` لإشعار الخدمة.
- الالتقاط الداخلي يتطلب تفعيل ADB على الريسيفر لمرة واحدة.

## البنية

```
sync/      FeatureTrack, Correlator, SyncPolicy, SyncCoordinator   ← محرك المزامنة (مختبَر)
audio/     StationPlayer (تشغيل + إعادة اتصال), DelayAudioProcessor, TappingAudioSink
capture/   TvAudioSource, MicTvAudioSource, InternalCaptureSource, CaptureMain
receiver/  ReceiverControl (تأخير الصورة + كتم القناة)
subs/      SpeechModels, SubtitleEngine (Vosk+ML Kit), SubtitleOverlay
ui/        MainActivity
BilalService  ← يربط كل شيء
```

## الترخيص

كود هذا المشروع لك. يعتمد على مكتبات مفتوحة: AndroidX media3 (Apache-2.0)،
Google ML Kit، وVosk (Apache-2.0) ونماذجها.
