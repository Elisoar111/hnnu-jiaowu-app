<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="校园助理图标"/>
</p>

<h1 align="center">校园助理</h1>

<p align="center">
  <strong>开源 · 免费 · 安全</strong><br/>
  Android 教务客户端，HNNU学子专用（正方教务 + 第二课堂 + 学工）<br/>
  支持 Android 与鸿蒙（HarmonyOS NEXT，经卓易通兼容层）<br/>
  选课、课表、成绩、二课、学工与考级报名，UI 是一整套液态玻璃
</p>

<p align="center">
  <sub>应用图标由 AI 生成；如认为该图标侵犯您的权益，请联系作者，核实后会及时删除或更换。</sub><br/>
  <sub>本应用基于原作者 <a href="https://github.com/znjhahaha/zhengfang-apk">znjhahaha / zhengfang-apk</a> 修改扩展而来，感谢原作者的慷慨开源 ❤</sub>
</p>

<p align="center">
  <a href="https://gitee.com/Elisoar/hnnu-jiaowu-app/releases"><img src="https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD-%E6%9C%80%E6%96%B0%E7%89%88%20APK-blueviolet?style=flat-square" alt="下载最新版 APK"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square" alt="License"/></a>
</p>

---

## 这是什么

一个给HNNU学子用的教务客户端。界面全套液态玻璃：折射、色散、高光都是实时算的，按下去会形变，松手弹回来，不是贴一层半透明白糊上去。老设备跑不动实时模糊会自动回退到透镜采样，不会直接卡死。

五个页面装下全部高频操作：**课表 · 选课 · 成绩 · 二课 · 我的**。冷启动欢迎回来，登录一次自动续期，通知直达课程详情。

---

## 界面预览

<table>
  <tr>
    <td align="center" width="33%">
      <img src="pic/schedule.jpg" width="100%"/><br/>
      <sub><b>课表</b>：周视图、日期标注、当前节高亮，横滑切周</sub>
    </td>
    <td align="center" width="33%">
      <img src="pic/course-workbench.jpg" width="100%"/><br/>
      <sub><b>选课工作台</b>：队列 / 定时 / 捡漏，圆钮一键执行</sub>
    </td>
    <td align="center" width="33%">
      <img src="pic/grades.jpg" width="100%"/><br/>
      <sub><b>成绩与考试</b>：按学期查、GPA 自动算、考试安排</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <img src="pic/second-class.jpg" width="100%"/><br/>
      <sub><b>第二课堂</b>：各模块完成度一目了然，榜单分层查看</sub>
    </td>
    <td align="center" width="33%">
      <img src="pic/schedule-settings.jpg" width="100%"/><br/>
      <sub><b>课表设置</b>：自定义课程、开学日期、节次时间与字号</sub>
    </td>
    <td align="center" width="33%">
      <img src="pic/mine.jpg" width="100%"/><br/>
      <sub><b>我的</b>：账号管理、主题背景、消息中心与手册</sub>
    </td>
  </tr>
</table>

---

## 功能

选课：

- 按关键词搜、按类别筛，余量和教师都显示
- 即时执行 — 有空位，拼手速

选课使用前台服务，定时启动使用 AlarmManager。通知、精确闹钟权限和系统后台限制会影响运行与触发时间；学校仍决定选课窗口、名额、学分和冲突规则。

课表成绩：

- 课表周视图，课程自动配色，可导出 `.ics` 到系统日历
- 课表字号四档可调（小 / 标准 / 大 / 特大），只影响课表，其它页面不动
- 时间冲突的课，在课程详情里手动指定本节要上的那门，另一门自动让位隐藏（按节记录、按账号保存）
- 成绩按学期查，GPA 自动算，另外还有总体成绩和考试安排

第二课堂：

- 学分、总积分、**综测附加分**与各模块完成情况集中展示，未达标一眼可见
- 综测附加分按学校口径由各模块积分加权算出（创新创业能力权重最高 0.30，单项封顶 100 分）
- 成绩榜单按班级 / 专业 / 院系 / 全校查询，同分共享名次
- 奖励申报在线提交：选项目、填档位、传材料、关联活动，全程应用内完成，不用开官网

学工系统：

- **日常请假**在线填表提交：类型、起止时间、是否离校、附件等，字段与官方页面一致
- **节假日去向登记**按批次查看与提交，外出地点为省 / 市 / 区县三级滚轮选择

考级项目报名：

- 四六级、普通话等等级考试的报名与退报在应用内完成，复用教务登录态
- 批次费用、名额、报名起止时间一目了然；已报记录含准考证号、成绩与证书编号
- 退报规则与教务一致：过了报名截止时间不支持退报，已缴费项目无法在线退报

个性化外观：

- 主题（浅色 / 暗色 / 跟随系统）与背景（预设 / 纯色 / 图片 + 模糊蒙版）随喜好搭配
- 字体三选一：系统字体（默认）、内置苹方（iOS 同款观感）、或从手机存储导入自己的 ttf / otf，全文当帧生效

杂项：

- 登录页一键测试主站与备用入口的连通性，连不上时直接告诉你卡在哪一环（DNS / 超时 / 证书）
- 应用内置用户手册（「我的」→ 用户手册），离线可读，主题跟随应用
- 应用内更新：启动时自动检查（6 小时节流），发现新版本弹出液态玻璃提示，点一下由应用自己下载安装包并唤起系统安装器 —— 不经过浏览器，也就不会出现"下载下来是个压缩包"；退到后台后每天还会自动查一次，发现新版本直接发系统通知
- 公告中心带未读红点，重要公告启动时以弹窗触达；反馈可发作者邮箱（「我的」→ 联系开发者），进不去 GitHub 的同学也可以加入 QQ 频道 **pd32446534**
- 最多使用 3 个学生账号

---

## 教务系统与限制

当前源码仅接入**淮南师范学院**教务系统（正方 jwglxt V9），主站 `jwgl.hnnu.edu.cn`（HTTPS），另配 IP 直连备用入口（HTTP，仅在应用内的网络安全白名单中放开明文）。

| 项目 | 说明 |
| --- | --- |
| 登录 | 账密直登 + RSA 密码加密；统一认证或定制页面转内置网页登录 |
| 执行限制 | 选课串行或最多 2 门并行；筛选与查询以学校返回内容为准 |

验证码、选课声明、结果待确认或登录失效时需要人工处理；应用不会绕过学校限制。

---


## 快速开始

### 直接下载（推荐）

去 [Releases 页面](https://gitee.com/Elisoar/hnnu-jiaowu-app/releases) 下载最新 APK，装上就能用。Android 7.0+，建议 12 以上，玻璃效果最全。

> **认准 `app-release.apk`**：同一个 Release 下的 `.zip` / `.tar.gz` 是源码包，不是安装包。
>
> 个别浏览器会把下载到的 APK 存成 `.zip` —— 服务端把 APK 的响应头声明成了 `application/zip`，浏览器据此给文件配了后缀，文件本身没坏。把后缀改回 `.apk` 即可安装。更省事的做法是直接用应用内的「检查更新 → 立即更新」，下载由应用自己完成，不经过浏览器。

> **从 1.2.1 及更早版本升级**：1.2.2 起安装包标识由 `com.tyust.course` 改为 `com.hnnujw.course`。安卓把包名当作应用身份，新旧包名互相独立、无法覆盖升级，需要手动安装一次新版；装好后重新登录教务账号、同步一次即可恢复课表。旧版可等新版确认可用后再卸载。

**鸿蒙（HarmonyOS NEXT）也能用**：通过「卓易通」兼容层安装本 APK 即可运行，安装方式与普通安卓应用一致。未做专门适配，遇到问题欢迎反馈。

**iOS 暂不支持**：本应用是安卓客户端，没有 iOS 版本；iPhone 用户请直接使用学校教务网页。后续有计划会在此更新。

### 从源码构建

```bash
git clone https://gitee.com/Elisoar/hnnu-jiaowu-app.git
cd hnnu-jiaowu-app
./gradlew assembleDebug
```

环境要求：Android Studio Hedgehog+、JDK 17+（Gradle 8.13 实测需 JDK 21 以下跑配置阶段）、`compileSdk 37` / `targetSdk 34` / `minSdk 24`。

---

## 新手教程

### 第一步：登录

选择或添加学校，填写教务账号密码。需要验证码时可输入或刷新图片；不确定站点通不通，先点一下「测试站点连通性」，主站和备用入口各探一次，结果直接上屏。

### 第二步：换背景（可选）

「我的」→ 背景。预设、颜色、图片三个来源，选图片之后能调模糊和蒙版。想换字体的话在「我的」→ 字体，内置苹方可选，也支持导入手机里的 ttf / otf 文件。

### 第三步：选课

| 模式 | 啥时候用 | 怎么操作 |
|------|----------|----------|
| 即时执行 | 现在就有空位 | 开始选课 |
| 定时任务 | 知道几点开选课 | 开始选择 |

### 第四步：看课表 / 查成绩

底栏第一个 Tab 是课表：周视图，左右横滑切周，右上角能导出 `.ics`，字号在课表设置里调。第三个 Tab 是成绩：按学期切，GPA 自动算好，考试安排也在里面。

---

## 技术栈

Kotlin + Jetpack Compose（Material 3）。玻璃渲染用 Kyant Backdrop 2.0，`blur + lens + vibrancy` 三层，按设备能力分档，低端机降级到透镜采样。动效走 Compose Animation，spring/tween 曲线全收在 `MotionTokens` 里。网络 OkHttp + Coroutines，HTML 用 Jsoup 解析。应用内更新由自带的下载器（OkHttp 流式下载 + FileProvider 唤起安装器）完成。安装包在本地构建后发布到 Gitee Release。

---

## 致谢

本应用基于原作者 [znjhahaha](https://github.com/znjhahaha) 的开源项目 [zhengfang-apk](https://github.com/znjhahaha/zhengfang-apk) 修改与扩展而来，感谢原作者的慷慨开源。

---

## 二次开发

项目基于 **GPLv3** 开源。


## 免责声明

- 项目开源免费，仅供学习交流
- 用出任何后果自己负责
- 选课归选课，别挂一晚上把学校教务打挂
- 应用图标由 AI 生成，如涉及侵权，联系作者核实后即删

## 许可证

[GPL-3.0](LICENSE)
