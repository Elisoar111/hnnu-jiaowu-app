# ===========================================================================
# 正方教务助手 —— R8 / ProGuard 规则
# ===========================================================================
#
# 为什么会有这个文件
# ------------------
# 1.2.6 之前本文件**根本不存在**：app/build.gradle 的 release 块引用了它，但它同时被
# .gitignore 忽略、也从未进过库。那时的表现只是构建日志里孤零零一行
#
#     Supplied proguard configuration does not exist: ...\app\proguard-rules.pro
#
# 属于「软失败」——R8 照常跑完、包照样出，等于一直用**空规则**混淆，所以线上并没有
# 因为缺文件炸过。真正的问题是另外两条：
#
#   1) 规则需要落地时没有落点：改了也无从 review，换台机器/换个人拿到的产物不可复现；
#   2) 这个「软失败」是 AGP 8.13.2 的行为，不是承诺。AGP / R8 升级后同一情况完全
#      可能变成硬错，那时 CI 才会突然红，而线索只有这一行日志。
#
# 所以补上本文件，并把 proguard-rules.pro 从 .gitignore 移出（见同一次改动）。
#
#
# 本文件的原则
# ------------
#   · 只写「有依据」的规则：每一条都对应一个**已核实**的失败模式，或者是**不希望
#     被 AGP 默认值变化放开**的契约。不写"给所有模型类 -keep"这类 cargo cult ——
#     那只会把死代码塞回包里，还会掩盖真正的入口。
#   · **保持与 v1.2.6 线上产物等价**：本次刻意不新开 SourceFile / LineNumberTable
#     等调试属性（见文末「故意没写的东西」），避免"改了构建配置"顺带改变线上行为。
#   · 生效的指令一律用 ASCII，注释用中文：即使某个工具链按非 UTF-8 读这个文件，
#     最坏情况也只是注释显示成乱码，规则本身不会变。
#
#
# 已核实：以下这些**不需要**写规则
# --------------------------------
# 依据是 AGP 8.13.2 + 其自带 R8 在**空规则**下产出的三份实据：
#   app/build/outputs/mapping/release/{aapt_rules.txt, configuration.txt, mapping.txt}
# （aapt_rules / configuration 是 R8 实际吃的合并后规则，mapping 是实际的改名结果）
#
#   · 清单组件：aapt 会为 AndroidManifest 里每个 <activity>/<service>/<receiver>/
#     <provider> 自动生成 `-keep class X { <init>(); }`，已覆盖 CourseApplication、
#     各 Activity、MessageCheckReceiver / UpdateCheckReceiver / GrabAlarmReceiver /
#     CourseReminderReceiver / ExamReminderReceiver、GrabService、AppearanceThemeProvider
#     以及 7 个桌面卡片组件。**不要再手写一遍**：写了会让人误以为清单是"手动维护"的，
#     下次加组件就可能漏。
#   · 自定义 View：全仓没有 (Context, AttributeSet) 构造器的类，res/layout 里也没有
#     引用自己的类 → 不存在 InflateException 风险。
#   · WebView JS 桥：没有 @JavascriptInterface。
#   · ServiceLoader / 动态加载：没有 ServiceLoader，也没有 DexClassLoader / PathClassLoader。
#   · Parcelable / Serializable：除框架自带的 android.util.SizeF 外没有自定义实现，
#     也没有启用 kotlin-parcelize。
#   · 反射：全仓只有 javax.xml.parsers.DocumentBuilderFactory.newInstance()（框架 API），
#     没有 Class.forName / getConstructor / newInstance 之类按字符串找类。
#   · 序列化库：没有 Gson / Moshi / kotlinx.serialization，业务 JSON 全部手写 org.json 映射，
#     不存在"字段名反射配对"的隐患。
#   · 本次构建 R8 **零警告**，所以这文件里一条 -dontwarn 都没有。
#
# 反过来说：**日志里看不到 Log.d 不代表 R8 删了它**。空规则那一版实测过，活代码里的
# Log.d 消息字面量全部保留在 dex 里；消失的那几条全部来自 CourseListRoute（当时已经
# 失去入口、被 R8 整块判为死代码）。也就是说——release 下某行日志不见了，先怀疑那段
# 代码被判成不可达，而不是怀疑混淆把日志优化掉了。
# ===========================================================================


# ---------------------------------------------------------------------------
# 1) 桌面卡片组件：类名 + 无参构造器
# ---------------------------------------------------------------------------
# 系统是按 **AndroidManifest 里写的类名**反射实例化 AppWidgetProvider 的。这个类名一旦
# 被 R8 改掉，或者构造器变成带参数的，症状是「用户加组件时直接失败 / 桌面出现空白块」，
# 而且往往连崩溃日志都没有 —— 属于最难查的那类故障。
#
# 这里逐个列出，**不写 `*CardWidget` 通配符**：通配符会顺带匹配到 CardWidget 这个枚举
# 本身（它并不需要保住类名，保了只是白白放弃一次混淆），会让规则的实际作用范围和注释
# 写的不一致。新增卡片时请在本清单补一行；漏补也不会坏 —— 组件只要注册进清单就会被
# aapt 规则保住，这里是双保险，不是唯一防线。
#
# aapt_rules.txt 目前已经生成了等价规则；这里再显式写一遍，是为了不让这条命门
# 依赖 AGP 的自动行为（它会随版本变，而那套自动规则本来就属于"实现细节"）。
# ---------------------------------------------------------------------------
-keep class com.hnnujw.course.widgetboard.NextCourseCardWidget { <init>(); }
-keep class com.hnnujw.course.widgetboard.TodayScheduleCardWidget { <init>(); }
-keep class com.hnnujw.course.widgetboard.TodayTimelineCardWidget { <init>(); }
-keep class com.hnnujw.course.widgetboard.ExamCountdownCardWidget { <init>(); }
-keep class com.hnnujw.course.widgetboard.GradesOverviewCardWidget { <init>(); }
-keep class com.hnnujw.course.widgetboard.SecondClassOverviewCardWidget { <init>(); }
-keep class com.hnnujw.course.widgetboard.MessageUnreadCardWidget { <init>(); }
-keep class com.hnnujw.course.widgetboard.CardWidgetRefreshReceiver { <init>(); }


# ---------------------------------------------------------------------------
# 2) 按名字持久化的枚举：必须保住 values() / valueOf()，以及枚举常量的 name 字面量
# ---------------------------------------------------------------------------
# 这四个枚举都存在**同一套字符串契约**：写进 SharedPreferences 的是常量名（有的来自
# `.name`，有的干脆是代码里硬编码的字面量），读回来靠 `Enum.valueOf(那个字符串)`。
# 一旦 R8 开始改写枚举常量的 name 字面量，症状全是**静默的**：
#
#   · WallpaperPreset / WallpaperMode / AppFontOption（AppearanceSettingsManager.kt）
#     用户选好的壁纸、配色、字体在重启后悄悄回到默认值 —— 读侧包着 runCatching，
#     连一条报错都不会有。
#
# AGP 的默认配置里已经有同样的 values()/valueOf() 规则，而这**正是** R8 不敢动
# name 字面量的原因 —— 两者是一件事，不能拆开看。把它显式写出来，是为了把这个
# 前提钉在仓库里：它同时就是"用户设置不会莫名复位"的保证，以后谁想清理"冗余规则"
# 时应该看到这段说明，而不是把它当噪音删掉。
# ---------------------------------------------------------------------------
-keepclassmembers enum com.hnnujw.course.manager.WallpaperPreset {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.hnnujw.course.manager.WallpaperMode {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.hnnujw.course.manager.AppFontOption {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# ---------------------------------------------------------------------------
# 故意没写的东西（以及为什么）
# ---------------------------------------------------------------------------
# · -dontwarn ...
#     R8 当前零警告，没有要抑制的对象。将来引入可选依赖（比如只在部分设备上存在的
#     厂商 SDK）而出现 missing class 警告时，再针对那**一个**包名写 -dontwarn ——
#     不要图省事写 `-dontwarn **`，那会把真正该修的缺失依赖一起盖住。
#
# · -keepattributes SourceFile,LineNumberTable（+ -renamesourcefileattribute SourceFile）
#     加上它崩溃栈才有行号，排线上问题会轻松很多；代价是包体略增，而且**会改变本次
#     产物的字节**（行号表进了 dex）。为了不把"补规则文件"和"改产物形态"混在同一次
#     发版里，这里先不加。想开就单独走一个版本，并在更新说明里写明"崩溃日志开始带行号"。
#
# · -keep class com.hnnujw.course.model.** { *; } 之类的"保险"
#     业务模型全部手工 org.json 映射，没有反射读字段，保留它们只会把死字段留在包里。
