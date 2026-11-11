# SmartTube الأصلي — نسخة القنوات المختارة

هذه ليست نسخة NewPipe ولا ExoPlayer المنفصل. عملية البناء تجلب **المصدر الأصلي** من https://github.com/yuliskov/SmartTube وتستبدل مصدر صفوف *الرئيسية* فقط. البحث ومشغّل SmartTube الأصلي كما هما.

## القنوات
حرّر الملف:
https://github.com/sloom059-svg/images-repo/blob/main/curated-tv/channels.json

مثال:
```json
{"version":1,"title":"قنواتي","showThumbnails":true,"channels":[{"name":"اسم القناة","channelId":"UCxxxxxxxxxxxxxxxxxxxxxx"}]}
```

تعتمد الرئيسية على قسم القناة الذي يقدمه SmartTube، وليس الخوارزمية العامة. الصور المصغرة الأصلية لا تُخفى. أي تعديل للملف يظهر عندما يعيد التطبيق تحميل الرئيسية. من الممكن ظهور فيديوهات أخرى في صفحة البحث أو أقسام أخرى أو في اقتراحات المشغّل، إذ هذه المرحلة تعدّل الرئيسية فقط. من المهم اختبار كل ذلك على شاومي ستيك.

## البناء
GitHub Actions → Build Curated SmartTube from Official Source → Run workflow. **لا يُعتمد الملف للتثبيت حتى ينجح البناء**. يتطلب المشروع متطلبات بناء قديمة/اعتماديات خارجية؛ Workflow تجريبي ويعتمد نسخة محددة من المصدر الأصلي. لا توجد مفاتيح أو أسرار داخله.

ترخيص SmartTube الأصلي MIT؛ احتفظ بإشعاراته وأسماء منشئيه.
