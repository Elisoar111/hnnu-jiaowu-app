<h1 align="center">教务助手</h1>

<p align="center">
  <strong>开源 · 免费 · 安全</strong><br/>
  Android 教务客户端，淮南师范学院专用（正方教务 + 第二课堂）<br/>
  选课、抢课、课表、成绩，UI 是一整套液态玻璃
</p>

<p align="center">
  <a href="https://github.com/Elisoar111/hnnu-jiaowu-app/releases/latest"><img src="https://img.shields.io/github/v/release/Elisoar111/hnnu-jiaowu-app?style=flat-square&color=blueviolet&label=最新版本" alt="Release"/></a>
  <a href="https://github.com/Elisoar111/hnnu-jiaowu-app/actions"><img src="https://img.shields.io/github/actions/workflow/status/Elisoar111/hnnu-jiaowu-app/release.yml?style=flat-square&label=CI/CD" alt="CI"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square" alt="License"/></a>
  <a href="https://github.com/Elisoar111/hnnu-jiaowu-app/stargazers"><img src="https://img.shields.io/github/stars/Elisoar111/hnnu-jiaowu-app?style=flat-square" alt="Stars"/></a>
</p>

---

## 这是什么

一个给淮南师范学院学生用的教务客户端。界面全套液态玻璃：折射、色散、高光都是实时算的，按下去会形变，松手弹回来，不是贴一层半透明白糊上去。老设备跑不动实时模糊会自动回退到透镜采样，不会直接卡死。

- **背景可以自己换**：预设渐变、纯色、或者直接用相册里的图，图片背景能调模糊和蒙版
- **配色跟着背景走**：浅色底自动配深字，深色底配浅字，不用手动切
- **顶栏随滚动收起**：往下翻的时候一屏能多看一节多的内容
- **圆形抢课入口**：玻璃圆钮轻点执行，长按展开带模糊背景的扇形菜单

---

## 功能

选课抢课：

- 按关键词搜、按类别筛，余量和教师都显示
- 即时执行 — 有空位，拼手速
- 定时任务 — 设好开抢时间，到点自动发包
- 捡漏 — 盯着满员的课，有人退立刻顶上

抢课使用前台服务，定时启动使用 AlarmManager。通知、精确闹钟权限和系统后台限制会影响运行与触发时间；学校仍决定选课窗口、名额、学分和冲突规则。

课表成绩：

- 课表周视图，课程自动配色，可导出 `.ics` 到系统日历
- 课表字号四档可调（小 / 标准 / 大 / 特大），只影响课表，其它页面不动
- 成绩按学期查，GPA 自动算，另外还有总体成绩和考试安排

第二课堂：

- 成绩榜单按班级 / 专业 / 院系 / 全校查询，同分共享名次

杂项：

- 登录页一键测试主站与备用入口的连通性，连不上时直接告诉你卡在哪一环（DNS / 超时 / 证书）
- 公告带时间线，反馈直接发作者邮箱
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

## 密码和会话怎么存

支持账号密码登录，学校要求图片验证码时由用户填写；定制登录保留网页登录入口。

- 密码走 Android KeyStore 的 AES/GCM 加密，仅保存在本机，并在登录时提交到所选学校的教务或认证地址。加密或读取失败时按「未保存凭据」处理，不会明文写盘
- HTTPS 全程严格校验系统信任链，**没有**「信任所有证书」的开关；明文 HTTP 只对确知的备用入口放开
- 会话 Cookie 与密码密文**不参与云备份和设备迁移**，换设备需要重新登录
- 运行日志不记录密码与 Cookie 值（应用有导出日志功能，日志里出现会话凭证等于把账号交出去）

不信可以自己翻 `CredentialStore.kt`、`SessionRenewer.kt` 和 `res/xml/network_security_config.xml`。

---

## 快速开始

### 直接下载（推荐）

去 [Releases 页面](https://github.com/Elisoar111/hnnu-jiaowu-app/releases/latest) 下载最新 APK，装上就能用。Android 7.0+，建议 12 以上，玻璃效果最全。

### 从源码构建

```bash
git clone https://github.com/Elisoar111/hnnu-jiaowu-app.git
cd hnnu-jiaowu-app
./gradlew assembleDebug
```

环境要求：Android Studio Hedgehog+、JDK 17、`compileSdk 37` / `targetSdk 34` / `minSdk 24`。

---

## 新手教程

### 第一步：登录

选择或添加学校，填写教务账号密码。需要验证码时可输入或刷新图片；不确定站点通不通，先点一下「测试站点连通性」，主站和备用入口各探一次，结果直接上屏。

### 第二步：换背景（可选）

设置 → 背景。预设、颜色、图片三个来源，选图片之后能调模糊和蒙版。

### 第三步：抢课

| 模式 | 啥时候用 | 怎么操作 |
|------|----------|----------|
| 即时执行 | 现在就有空位 | 课程详情 → 立即抢课 |
| 定时任务 | 知道几点开抢 | 先把课加进队列 → 设启动时间 → 到点自动跑 |
| 捡漏 | 想抢已经满了的热门课 | 选好课 → 开捡漏 → 有人退自动顶 |

定时任务得先往队列里加课，队列空着创建不了，会提示你。

### 第四步：看课表 / 查成绩

课表在底栏第二个 Tab，周视图，右上角能导出 `.ics`，字号在课表设置里调。成绩在第四个，按学期切，GPA 自动算好。

---

## 技术栈

Kotlin + Jetpack Compose（Material 3）。玻璃渲染用 Kyant Backdrop 2.0，`blur + lens + vibrancy` 三层，按设备能力分档，低端机降级到透镜采样。动效走 Compose Animation，spring/tween 曲线全收在 `MotionTokens` 里。网络 OkHttp + Coroutines，HTML 用 Jsoup 解析。打包发布走 GitHub Actions。

---

## 二次开发

项目基于 **GPLv3** 开源，二开前先把协议看清楚。

> **不接受任何形式的私自打包和分发。** 唯一的二开渠道是向本仓库提 PR，CI/CD 会自动构建并发布——这是为了避免外面满天飞的山寨包。

提 PR 流程：

1. Fork
2. 改完跑一遍 `./gradlew assembleDebug` 确认能编
3. 提交：`git commit -m 'feat: xxx'`
4. 推上去开 PR，CI 会自己跑

UI 类改动记得附真机截图，审起来省事。

更新日志写在 `release-notes/vX.Y.Z.md`，CI 从那儿读，扇到 GitHub Release 和 App 内的更新提示，别到处各写一份。

---

## 免责声明

- 项目开源免费，仅供学习交流
- 用出任何后果自己负责
- 抢课归抢课，别挂一晚上把学校教务打挂

## 许可证

[GPL-3.0](LICENSE)
