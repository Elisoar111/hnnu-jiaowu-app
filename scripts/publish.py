#!/usr/bin/env python3
"""一键发布：本地准备 → 构建 → 验包 → 推两端 → 建 Release 传 APK → 线上校验。

为什么要有这个程序
------------------
一次发版原先要在五六个地方来回切：先跑 scripts/release.sh 写版本号/归档 CHANGELOG/
打 tag，再手动 gradlew 构建，再手动 git push 两个远端，再用 curl 打 Gitee Open API
建 Release、传附件，最后还要自己去仓库页面核一遍线上到底有没有。每一步都有
"顺序错了才暴露"的坑：

  - tag 没推就先建 Release  → 下载链接指向一个不存在的 tag，装了新版的人点更新 404；
  - APK 没传上去            → Release 在、附件不在，应用内「检查更新」挑不到 .apk
                              资产，只能退回 Release 网页让用户自己找；
  - 只发 Gitee 忘了 GitHub（或反过来）。

这个程序把顺序固化下来，一条命令跑完，并且**每一步都当场校验**：

    本地准备 → 构建 → 验包（包名/版本号/内置资产/签名）→ 推 Gitee + GitHub
    → 建两端 Release 并上传 app-release.apk → 线上校验 releases/latest 真的指到这一版

最后那步是重点：应用内「检查更新」读的就是 Gitee 的
`/api/v5/repos/<owner>/<repo>/releases/latest`，在这里挑第一个 `.apk` 资产。
所以线上校验必须打这个接口，而不是去仓库里找某个版本文件 —— 仓库根的
`version.json` 从来没被提交过、也没有任何一版应用读过它（见 scripts/verify_delivery.py）。

用法
----
    python scripts/publish.py 1.2.3        # 全自动：本地准备 → 构建 → 推送 → 发布 → 校验
    python scripts/publish.py              # 用当前 app/version.properties 的版本重发一轮
    python scripts/publish.py --local-only # 只构建 + 验包 + 落地 dist，不推送不发布
    python scripts/publish.py --dry-run    # 只打印将要做什么，不碰任何远端
    python scripts/publish.py --skip-build --reupload
    python scripts/publish.py --only gitee

凭据
----
Gitee 的写操作（push / 建 Release / 传附件）需要私人令牌，按顺序查找：

    1. 环境变量 GITEE_TOKEN
    2. local.properties 里的 GITEE_TOKEN=
       （与 RELEASE_STORE_PASSWORD 那批签名密钥放同一个地方，该文件已被 .gitignore 忽略）

Gitee 的 HTTPS push 用户名是**账号登录名**（不是令牌，写令牌会被拒 403），本程序
自动向 API 问一次；想省掉这次请求就配 GITEE_USER=你的登录名（环境变量或同上文件）。

GitHub 走 `gh` 的凭据助手（`gh auth login` 一次即可），令牌不落在本仓库里。
本程序不打印令牌，也不把它写进 git config：Gitee 的 push 用临时
`credential.helper=store --file=<临时文件>`，命令结束随临时目录一起删除。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
VERSION_PROPERTIES = ROOT / "app" / "version.properties"
RELEASE_APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "release"
DIST_DIR = ROOT / "dist"
LOCAL_PROPERTIES = ROOT / "local.properties"
RELEASE_NOTES_SH = ROOT / "scripts" / "release_notes.sh"

# 仓库根 version.json 不是本程序的产物：它从未提交、也没有任何一版应用读过。
# 应用内更新的真实契约是下面这两个地址。
GITEE_REPO = os.environ.get("GITEE_REPO") or "Elisoar/hnnu-jiaowu-app"
GITHUB_REPO = "Elisoar111/hnnu-jiaowu-app"
GITEE_API = f"https://gitee.com/api/v5/repos/{GITEE_REPO}"

EXPECTED_PACKAGE = "com.hnnujw.course"

# 上传到两端 Release 时统一用这个名字：应用挑资产是按后缀 .apk 找第一个，
# Gitee 还会自动挂上 <tag>.zip / <tag>.tar.gz 两个**源码包**，所以自己的安装包
# 必须是一个明确的 .apk 名字，别让用户和程序都去猜。
ASSET_NAME = "app-release.apk"

# 至少要打进包里的内置资产。公告跟代码同一次构建、同一个版本号，是 v1.2.2 起
# 定下的规矩（不再有线上拉取通道）；漏掉它等于发出去一个"没有任何公告"的版本。
# 用户手册同理：由 scripts/build_manual.py 从 用户手册.md 生成的 users_manual.json，
# 漏掉它「我的 → 用户手册」就成了空页。
REQUIRED_ASSETS = ("assets/announcement.json", "assets/users_manual.json")

GRADLE_TASKS = (
    ":app:assembleDebug",
    ":app:testDebugUnitTest",
    ":app:assembleRelease",
)

TOKEN = ""
GITEE_LOGIN = ""
DRY = False


# ── 输出 ────────────────────────────────────────────────────────────────────

def say(message=""):
    print(message, flush=True)


def step(number, title):
    say()
    say(f"== [{number}] {title} ==")


def ok(message):
    say(f"   OK   {message}")


def warn(message):
    say(f"   WARN {message}")


def info(message):
    say(f"        {message}")


def die(message):
    raise SystemExit(f"ERROR: {message}")


# ── 基础工具 ────────────────────────────────────────────────────────────────

def redact(text):
    """把令牌从任何将要打印的文本里抹掉。"""
    if TOKEN:
        text = text.replace(TOKEN, "***")
    return text


def run(cmd, *, cwd=None, env=None, capture=False, check=True, quiet=False, mutating=True):
    """跑一条命令。mutating=False 表示只读（git status / gh release view 之类），
    这类命令在 --dry-run 下也要真的执行 —— 否则 dry-run 会连"当前是什么状态"都判断不了。"""
    if DRY and mutating:
        say(f"   [dry-run] {redact(' '.join(str(part) for part in cmd))}")
        return ""
    merged_env = dict(os.environ)
    if env:
        merged_env.update(env)
    # 非交互：凭据缺失时直接失败，别挂在提示框上（那看起来像卡死）。
    merged_env.setdefault("GIT_TERMINAL_PROMPT", "0")
    result = subprocess.run(
        [str(part) for part in cmd],
        cwd=str(cwd or ROOT),
        env=merged_env,
        capture_output=capture or quiet,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip().splitlines()
        tail = redact(" | ".join(detail[-4:])) if detail else ""
        die(f"命令失败（exit {result.returncode}）：{redact(' '.join(str(p) for p in cmd))}"
            + (f"\n       {tail}" if tail else ""))
    return (result.stdout or "") + (result.stderr or "")


def run_output(cmd, **kwargs):
    return run(cmd, capture=True, **kwargs).strip()


def git(*args, **kwargs):
    return run(["git", *args], **kwargs)


def git_read(*args):
    """只读的 git 查询：必须捕获输出。

    这里踩过一次：`git rev-parse` 不捕获时 result.stdout 是 None，而 run() 返回
    `(stdout or "") + (stderr or "")` = 空串，于是"tag 存不存在"永远判成不存在 ——
    命令成功、判断相反，最坏的一类 bug。
    """
    return run(["git", *args], capture=True, check=False, mutating=False)


def gh_read(*args):
    """只读的 gh 查询：只认 stdout，而且要求退出码为 0，失败返回 None。

    同样踩过一次：把 gh 的 stderr 一起当结果收，Release 不存在时它那句
    "release not found" 被 split() 成了三个"资产名"，于是"Release 已存在"
    被判成真 —— 该建的没建。宁可返回 None 让调用方显式判断。
    """
    result = subprocess.run(
        ["gh", *args], cwd=str(ROOT), capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    )
    return result.stdout if result.returncode == 0 else None


def find_bash():
    """`scripts/release_notes.sh` 是 bash，publish.py 要借它抽 Release 正文。

    与其在 Python 里再写一遍同样的 awk/sed（两份实现迟早会漂，而这段文案会原样
    进应用内的更新弹窗），不如直接调那一份。所以这里要能找到一个 bash。
    """
    found = shutil.which("bash")
    if found:
        return found
    for candidate in (
        pathlib.Path(r"C:\Program Files\Git\bin\bash.exe"),
        pathlib.Path(r"C:\Program Files (x86)\Git\bin\bash.exe"),
        pathlib.Path(os.environ.get("LOCALAPPDATA", "")) / "Programs/Git/bin/bash.exe",
    ):
        if candidate.is_file():
            return str(candidate)
    return None


def properties_unescape(value):
    """还原 .properties 的转义。

    local.properties 里的 Windows 路径长这样：`sdk.dir=D\\:\\\\android-sdk`
    （冒号和反斜杠都被转义）。不还原就得到一个 `D/:/android-sdk` 这种既不合法
    也不存在的路径，报出来的错还是"找不到 build-tools"，离真正的原因很远。
    """
    out = []
    index = 0
    while index < len(value):
        char = value[index]
        if char == "\\" and index + 1 < len(value):
            nxt = value[index + 1]
            if nxt == "u" and index + 5 < len(value):
                try:
                    out.append(chr(int(value[index + 2:index + 6], 16)))
                    index += 6
                    continue
                except ValueError:
                    pass
            out.append({"n": "\n", "t": "\t", "r": "\r", "f": "\f"}.get(nxt, nxt))
            index += 2
            continue
        out.append(char)
        index += 1
    return "".join(out)


def local_properties():
    values = {}
    if LOCAL_PROPERTIES.is_file():
        for line in LOCAL_PROPERTIES.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = properties_unescape(value.strip())
    return values


def read_version():
    """版本号的权威在 tag；这里读的是 release.sh 与 tag 同步好的那份文件。"""
    name = code = None
    if VERSION_PROPERTIES.is_file():
        for line in VERSION_PROPERTIES.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("VERSION_NAME="):
                name = line.split("=", 1)[1].strip()
            elif line.startswith("VERSION_CODE="):
                code = line.split("=", 1)[1].strip()
    if not name or not code or not code.isdigit():
        die(f"读不出 {VERSION_PROPERTIES.relative_to(ROOT)} 里的 VERSION_NAME / VERSION_CODE")
    return name, int(code)


def version_code_of(version):
    """versionCode = major*10000 + minor*100 + patch。

    与 scripts/release.sh、.github/workflows/release.yml 的 Resolve Version From Tag
    必须一致（本程序是第三处）：三处算出不同的 code，应用内「检查更新」就会给出一个
    永远装不上的版本。旧方案"取 patch 位"只对 1.0.x 成立，1.2.2 按它算是 2 < 线上 1.2.1
    的 91，用户端永远升不上去。
    """
    major, minor, patch = (int(part) for part in version.split("."))
    return major * 10000 + minor * 100 + patch


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def release_notes_for(tag):
    """Release 正文 = release-notes/<tag>.md 的 '## notes' 区块。

    走 release_notes.sh —— bash 侧唯一那份实现，和 scripts/release.sh 用的是同一套规则。
    取回来顺手把 CRLF 收敛成 LF：这段文本会被应用当纯文本逐行显示。
    """
    notes_file = ROOT / "release-notes" / f"{tag}.md"
    if not notes_file.is_file():
        die(f"缺少 {notes_file.relative_to(ROOT)}：Release 正文与提交信息都以它为唯一数据源")
    bash = find_bash()
    if not bash:
        die("找不到 bash，无法调用 scripts/release_notes.sh 抽 Release 正文")

    # 抽取与合法性校验都借 release_notes.sh —— 它同时是 scripts/release.sh 的实现，
    # 两边不会漂。在 Python 里重写一遍同样的 awk/sed/grep，就是等着某天不一致。
    # 路径走 as_posix()：Windows 的反斜杠在 bash 里是转义符，直接传会把路径吃掉。
    def in_bash(snippet, path):
        return subprocess.run(
            [bash, "-c", f'source "$1"; {snippet}', "_",
             RELEASE_NOTES_SH.as_posix(), path.as_posix()],
            cwd=str(ROOT), capture_output=True, text=True, encoding="utf-8", errors="replace",
        )

    extracted = in_bash('extract_notes "$2"', notes_file)
    if extracted.returncode != 0:
        die(f"抽取 {notes_file.name} 的 '## notes' 失败：{redact(extracted.stderr.strip())}")
    # 这段文本会被应用当纯文本逐行显示，顺手把 CRLF 收敛掉。
    notes = extracted.stdout.replace("\r\n", "\n").replace("\r", "\n").strip("\n")
    if not notes.strip():
        die(f"{notes_file.name} 的 '## notes' 区块是空的")

    violations = in_bash('extract_notes "$2" | notes_violations', notes_file).stdout.strip()
    if violations:
        die("'## notes' 里有 Markdown 标记（# * - 或反引号），会原样显示在应用内的更新弹窗里：\n       "
            + "\n       ".join(violations.splitlines()))
    return notes


# ── 步骤 1：本地准备 ────────────────────────────────────────────────────────

def step_local_prep(requested_version, allow_dirty=False):
    """没有显式给版本时，要求仓库里那一版已经准备完毕、tag 也已经打好。

    给了版本号就交给 scripts/release.sh —— 写 version.properties、归档 CHANGELOG、
    提交、打 tag 这套逻辑只该有一份实现（它还负责"工作区必须干净""版本必须递增"
    这两道闸），本程序不重复实现。
    """
    step(1, "本地准备")

    if requested_version:
        version = requested_version
        tag = f"v{version}"
        if git_read("rev-parse", "-q", "--verify", f"refs/tags/{tag}").strip():
            info(f"{tag} 已存在，跳过本地准备（重发/补发场景）")
        else:
            info(f"调用 scripts/release.sh {version}（写版本号 / 归档 CHANGELOG / 提交 / 打 tag）")
            bash = find_bash()
            if not bash:
                die("找不到 bash，无法调用 scripts/release.sh")
            run([bash, "scripts/release.sh", version])
            ok(f"本地准备完成，已打好 {tag}")
        return version, tag

    version, _ = read_version()
    tag = f"v{version}"
    if not git_read("rev-parse", "-q", "--verify", f"refs/tags/{tag}").strip():
        die(f"本地没有 tag {tag}。要么先跑 `bash scripts/release.sh {version}` 备好这一版，"
            f"要么直接 `python scripts/publish.py {version}` 让本程序代劳。")

    dirty = git_read("status", "--porcelain")
    if dirty.strip():
        warn("工作区有未提交的改动，它们不会出现在已推出去的 tag 里：")
        for line in dirty.strip().splitlines()[:10]:
            info(line)
        if not allow_dirty:
            die("先提交或 stash；确认要无视就用 --allow-dirty")
        warn("--allow-dirty：继续")

    ok(f"版本 {version}（{tag}）")
    return version, tag


# ── 步骤 2：构建 ────────────────────────────────────────────────────────────

def gradle_command(tasks):
    if os.name == "nt":
        # .bat 不能被 CreateProcess 直接执行，必须过一层 cmd。
        return ["cmd", "/c", "gradlew.bat", *tasks]
    return ["./gradlew", *tasks]


def build_env():
    env = {}
    java_home = os.environ.get("JAVA_HOME", "")
    if not java_home or not pathlib.Path(java_home).is_dir():
        for candidate in (
            pathlib.Path(r"C:\Users\44516\.jdks\jdk-21.0.12.1+1"),
            pathlib.Path(r"C:\Program Files\Eclipse Adoptium"),
        ):
            if candidate.is_dir() and (candidate / "bin").is_dir():
                java_home = str(candidate)
                break
    if java_home and pathlib.Path(java_home).is_dir():
        env["JAVA_HOME"] = java_home
        info(f"JAVA_HOME = {java_home}")
    else:
        warn("没找到 JDK 21，沿用环境里的 JAVA_HOME。Gradle 8.13 在 JDK 25 上会报 "
             "Unsupported class file major version 69")
    env["PATH"] = "/usr/bin:/bin:" + os.environ.get("PATH", "")
    return env


def step_build(skip):
    step(2, "构建")
    if skip:
        info("--skip-build，复用 app/build/outputs/apk/release 下的现有产物")
        return
    # 先删旧产物：留着上一版会让人分不清 dist 里那份到底是不是这次构建的。
    if RELEASE_APK_DIR.exists() and not DRY:
        shutil.rmtree(RELEASE_APK_DIR)
        info(f"已删除旧产物 {RELEASE_APK_DIR.relative_to(ROOT)}")
    env = build_env()
    say("        正在构建（Debug + 单测 + Release，通常 6–8 分钟）…")
    run(gradle_command(GRADLE_TASKS), env=env)


# ── 步骤 3：验包 ────────────────────────────────────────────────────────────

def _build_tools_key(directory):
    # 只按数字段排序：直接比字符串会得到 "9.0.0" > "37.0.0"，而把非数字段映射成 0
    # 又会让 int 与 str 混比。取全部数字段做元组最省事。
    numbers = re.findall(r"\d+", directory.name)
    return tuple(int(part) for part in numbers) or (0,)


def build_tools_dir():
    sdk = local_properties().get("sdk.dir") or os.environ.get("ANDROID_HOME") or os.environ.get(
        "ANDROID_SDK_ROOT")
    if not sdk:
        die("找不到 Android SDK：在 local.properties 里写 sdk.dir，或设置 ANDROID_HOME")
    tools_root = pathlib.Path(sdk)
    candidates = [d for d in tools_root.glob("build-tools/*")
                  if (d / "aapt2.exe").is_file() or (d / "aapt2").is_file()]
    if not candidates:
        die(f"{tools_root} 下没有可用的 build-tools（需要 aapt2）")
    return max(candidates, key=_build_tools_key)


def exe_in(tools, name):
    for suffix in (".exe", ".bat", ""):
        candidate = tools / f"{name}{suffix}"
        if candidate.is_file():
            return str(candidate)
    die(f"{tools} 下找不到 {name}")


def step_verify_apk(version, code):
    step(3, "验包")
    apks = sorted(RELEASE_APK_DIR.glob("*.apk")) if RELEASE_APK_DIR.is_dir() else []
    if not apks:
        die(f"{RELEASE_APK_DIR.relative_to(ROOT)} 下没有 APK，构建阶段可能没跑完")
    apk = apks[0]
    if len(apks) > 1:
        info(f"目录下有 {len(apks)} 个 APK，取 {apk.name}")

    tools = build_tools_dir()

    # 包名 + 版本号：aapt2 dump badging 的输出走 stderr，别只看 stdout。
    badging = subprocess.run(
        [exe_in(tools, "aapt2"), "dump", "badging", str(apk)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    text = (badging.stdout or "") + (badging.stderr or "")
    if f"name='{EXPECTED_PACKAGE}'" not in text:
        die(f"包名不对：期望 {EXPECTED_PACKAGE}，实际 {text.splitlines()[0] if text else '<无输出>'}")
    if f"versionCode='{code}'" not in text:
        found = re.search(r"versionCode='(\d+)'", text)
        die(f"versionCode 不对：期望 {code}，实际 {found.group(1) if found else '<未解析到>'}"
            f"（多半是 app/version.properties 与 tag 不一致）")
    if f"versionName='{version}'" not in text:
        found = re.search(r"versionName='([^']*)'", text)
        die(f"versionName 不对：期望 {version}，实际 {found.group(1) if found else '<未解析到>'}")
    ok(f"包名 {EXPECTED_PACKAGE}，versionCode {code} / versionName {version}")

    # 签名：没签名的包用户装不上，而且这个问题只在下载完之后才暴露。
    signed = subprocess.run(
        [exe_in(tools, "apksigner"), "verify", "--print-certs", str(apk)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if signed.returncode != 0:
        die("APK 签名校验未通过：" + redact((signed.stdout + signed.stderr).strip()[:300]))
    cert = re.search(r"SHA-256 digest: ([0-9a-f]+)", signed.stdout + signed.stderr)
    ok(f"已签名（证书 {cert.group(1)[:16] + '…' if cert else '未知'}）")

    # 内置资产：公告跟代码同一次构建，"这一版必须带公告"由单测
    # （AnnouncementLogicTest）守；这里只确认它真的被打进了包。
    with zipfile.ZipFile(apk) as zf:
        names = set(zf.namelist())
    for required in REQUIRED_ASSETS:
        if required not in names:
            die(f"包内缺少内置资产 {required}（漏打包 = 发出去一个功能残缺的版本）")
    ok("内置资产齐全：" + "、".join(REQUIRED_ASSETS))
    return apk


# ── 步骤 4：落地 dist ───────────────────────────────────────────────────────

def step_stage(apk, tag):
    step(4, "落地 dist")
    target = DIST_DIR / f"hnnu-jiaowu-app-{tag}.apk"
    if DRY:
        # dry-run 不落盘：报的是将要复制的那个源文件，别去 stat 上一轮残留的同名文件，
        # 否则会拿旧包的体积/哈希冒充这一版。
        info(f"[dry-run] 将复制 {apk.relative_to(ROOT)} → {target.relative_to(ROOT)}")
        ok(f"{apk.stat().st_size:,} 字节（源包）")
        return target
    DIST_DIR.mkdir(exist_ok=True)
    shutil.copyfile(apk, target)
    ok(f"{target.relative_to(ROOT)}  {target.stat().st_size:,} 字节")
    info(f"SHA256 {sha256_of(target)}")
    return target


# ── 步骤 5：推送 ────────────────────────────────────────────────────────────

def step_push(tag, platforms):
    step(5, "推送代码与 tag")
    if "gitee" in platforms:
        _push_gitee(tag)
    if "github" in platforms:
        _push_github(tag)


def gitee_login():
    """Gitee 的 **HTTPS push 用户名必须是账号登录名（Elisoar），不是令牌**。

    这条以前记反了：`https://<token>:<token>@gitee.com` 会被服务端直接拒掉，报
    `remote: The token username invalid` + 403，看起来像"令牌失效"。而同一个令牌走
    Open API 的 `?access_token=…` 一切正常 —— 所以"令牌有效"和"push 能认证"是两件事，
    别拿 API 通不通去推断 push。

    登录名直接问 API 拿，省得再让用户多配一项；要跳过这一步就写 GITEE_USER=。
    """
    global GITEE_LOGIN
    if GITEE_LOGIN:
        return GITEE_LOGIN
    if not TOKEN:
        die("Gitee 写操作需要私人令牌：设 GITEE_TOKEN 环境变量，或在 local.properties 里写 "
            "GITEE_TOKEN=（该文件已被 .gitignore 忽略）")
    query = urllib.parse.urlencode({"access_token": TOKEN})
    request = urllib.request.Request(
        f"https://gitee.com/api/v5/user?{query}",
        headers={"Accept": "application/json", "User-Agent": "hnnu-jiaowu-publish"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except Exception as error:  # noqa: BLE001 - 统一转成一句能照着做的话
        die(f"问不到 Gitee 登录名（{redact(str(error))}）。"
            "在 local.properties 里写 GITEE_USER=你的登录名 即可跳过这一步。")
    login = str(payload.get("login") or "").strip()
    if not login:
        die("Gitee 没返回 login 字段，请在 local.properties 里写 GITEE_USER=你的登录名。")
    GITEE_LOGIN = login
    return login


def _push_gitee(tag):
    """Gitee 的 push 用一个**临时**凭据文件。

    令牌写进 remote URL 或全局 git config 都会留在磁盘上（reflog、.git/config、
    Windows 凭据管理器），而这个仓库是公开的，一旦误提交就是事故。临时文件与
    临时目录一起销毁，令牌不落任何持久位置。

    三个必须照做的细节（都是实测出来的；错了不会报错、只会静默失败 —— 症状统一是
    `could not read Username for 'https://gitee.com'`，看着像"令牌不对"，其实不是）：

    1. 写凭据文件必须传 `newline="\\n"`。Windows 上 `write_text` 默认把 `\\n` 翻成
       `\\r\\n`，git-credential-store 按行切分后 host 变成 `gitee.com\\r`，匹配不上
       就**静默返回空凭据**。这是本机 2026-09-20 踩到的真正原因（曾误判成路径反斜杠）。
    2. `--file=` 的路径必须是 git.exe 认得的 **Windows 绝对路径**。正斜杠写法
       `C:/Users/.../credentials` 可用；`/tmp/...` 这类 MSYS 路径 git.exe 解析不了。
    3. 先 `credential.helper=` 清空 helper 列表再加 store：默认 helper
       （本机是 `helper-selector`）会在缺凭据时弹交互框，把无人值守脚本挂住。
    """
    if not TOKEN:
        die("Gitee 写操作需要私人令牌：设 GITEE_TOKEN 环境变量，或在 local.properties 里写 "
            "GITEE_TOKEN=（该文件已被 .gitignore 忽略）")
    login = gitee_login()
    with tempfile.TemporaryDirectory(prefix="hnnu-publish-") as tmp:
        cred = pathlib.Path(tmp) / "credentials"
        if not DRY:
            cred.write_text(f"https://{login}:{TOKEN}@gitee.com\n", encoding="utf-8",
                            newline="\n")
        helper = f"credential.helper=store --file={cred.as_posix()}"
        base = ["git", "-c", "credential.helper=", "-c", helper]
        if not DRY:
            probe = subprocess.run(
                base + ["credential", "fill"], cwd=str(ROOT), capture_output=True,
                text=True, encoding="utf-8", errors="replace",
                input="protocol=https\nhost=gitee.com\n\n",
            )
            if "password=" not in (probe.stdout or ""):
                die("临时凭据文件写好了，但 git-credential-store 匹配不到它，push 一定会报 "
                    "could not read Username。两个已知原因：行尾被写成 CRLF"
                    "（write_text 必须传 newline=\"\\n\"）、或 --file 不是 git.exe 能解析的 "
                    "Windows 绝对路径（如 MSYS 的 /tmp/...）。")
        run(base + ["push", "gitee", "main"])
        run(base + ["push", "gitee", f"refs/tags/{tag}"])
    ok(f"Gitee：main 与 {tag} 已推送")


def _push_github(tag):
    """GitHub 走 gh 的凭据助手（`gh auth setup-git` 之后 git 会自动取到令牌）。"""
    run(["git", "push", "origin", "main"])
    run(["git", "push", "origin", f"refs/tags/{tag}"])
    ok(f"GitHub：main 与 {tag} 已推送")


# ── 步骤 6：建 Release + 传 APK ─────────────────────────────────────────────

def gitee_request(method, path, payload=None, allow_missing=False):
    """Gitee Open API 的令牌位置不统一：GET/DELETE 认 query，POST/PUT 认 JSON body。
    搞错位置的表现是 401 / "Access token does not exist"，很难从字面看出来是这个问题。"""
    data = None
    if method in ("GET", "DELETE"):
        separator = "&" if "?" in path else "?"
        path += separator + urllib.parse.urlencode({"access_token": TOKEN})
    else:
        data = json.dumps(dict(payload or {}, access_token=TOKEN)).encode("utf-8")
    request = urllib.request.Request(
        f"{GITEE_API}/{path}", data=data, method=method,
        headers={"Accept": "application/json", "Content-Type": "application/json",
                 "User-Agent": "hnnu-jiaowu-publish"},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read().decode("utf-8")
            return json.loads(body) if body.strip() else {}
    except urllib.error.HTTPError as error:
        if allow_missing and error.code == 404:
            return None
        # 绝不把带令牌的 URL 或响应体打出来。
        raise RuntimeError(f"Gitee 返回 HTTP {error.code}") from None
    except urllib.error.URLError:
        raise RuntimeError("Gitee 请求失败（网络）") from None


def multipart(fields, files):
    """手搓 multipart：Gitee 的 attach_files 只收 multipart/form-data，
    而本机没有 jq/curl 这类现成工具可依赖（Windows 上尤其不可靠）。"""
    boundary = "----hnnuPublish" + base64.urlsafe_b64encode(os.urandom(12)).decode().rstrip("=")
    body = bytearray()
    for key, value in fields.items():
        body += f"--{boundary}\r\n".encode()
        body += f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode()
        body += f"{value}\r\n".encode()
    for key, (filename, content, ctype) in files.items():
        body += f"--{boundary}\r\n".encode()
        body += f'Content-Disposition: form-data; name="{key}"; filename="{filename}"\r\n'.encode()
        body += f"Content-Type: {ctype}\r\n\r\n".encode()
        body += content + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    return boundary, bytes(body)


def release_gitee(tag, notes, apk, reupload):
    if not TOKEN:
        die("Gitee 发布需要私人令牌（GITEE_TOKEN 环境变量或 local.properties）")
    releases = gitee_request("GET", "releases?per_page=100")
    release = next((r for r in releases if r.get("tag_name") == tag), None) \
        if isinstance(releases, list) else None
    if release is None:
        if DRY:
            say(f"   [dry-run] 在 Gitee 创建 Release {tag}")
            return
        release = gitee_request("POST", "releases", {
            "tag_name": tag, "name": f"Release {tag}",
            "target_commitish": "main", "body": notes, "prerelease": False,
        })
        if not release.get("id"):
            die(f"Gitee 建 Release 失败：{json.dumps(release)[:300]}")
        ok(f"Gitee：已创建 Release {tag}（id {release['id']}）")
    else:
        info(f"Gitee：Release {tag} 已存在（id {release['id']}）")

    release_id = release["id"]
    assets = gitee_request("GET", f"releases/{release_id}/attach_files")
    existing = next((a for a in assets if a.get("name") == ASSET_NAME), None) \
        if isinstance(assets, list) else None
    if existing and not reupload:
        info(f"Gitee：资产 {ASSET_NAME} 已存在，跳过上传（要覆盖用 --reupload）")
        return
    if DRY:
        say(f"   [dry-run] 上传 {apk.name} 到 Gitee Release，资产名 {ASSET_NAME}")
        return
    if existing and reupload:
        gitee_request("DELETE", f"releases/{release_id}/attach_files/{existing['id']}")
        info("Gitee：已删除同名旧资产")
    boundary, body = multipart(
        {"access_token": TOKEN},
        {"file": (ASSET_NAME, apk.read_bytes(), "application/vnd.android.package-archive")},
    )
    request = urllib.request.Request(
        f"{GITEE_API}/releases/{release_id}/attach_files", data=body, method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}",
                 "User-Agent": "hnnu-jiaowu-publish"},
    )
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            uploaded = json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Gitee 上传附件失败：HTTP {error.code}") from None
    url = uploaded.get("browser_download_url")
    if not url:
        die(f"Gitee 上传后没拿到下载地址：{json.dumps(uploaded)[:300]}")
    ok(f"Gitee：已上传 {ASSET_NAME}")


def release_github(tag, notes, apk, reupload):
    listing = gh_read("release", "view", tag, "--json", "assets", "--jq", ".assets[].name")
    existing = [] if listing is None else listing.split()
    if listing is None:
        if DRY:
            say(f"   [dry-run] 在 GitHub 创建 Release {tag} 并上传 {ASSET_NAME}")
            return
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as fh:
            fh.write(notes)
            notes_path = fh.name
        try:
            run(["gh", "release", "create", tag, "--title", f"Release {tag}",
                 "--notes-file", notes_path])
        finally:
            os.unlink(notes_path)
        ok(f"GitHub：已创建 Release {tag}")
    else:
        info(f"GitHub：Release {tag} 已存在（资产 {', '.join(existing) or '无'}）")
        if ASSET_NAME in existing and not reupload:
            info(f"GitHub：资产 {ASSET_NAME} 已存在，跳过上传（要覆盖用 --reupload）")
            return
    if DRY:
        say(f"   [dry-run] 上传 {apk.name} 到 GitHub Release，资产名 {ASSET_NAME}")
        return
    with tempfile.TemporaryDirectory(prefix="hnnu-publish-") as tmp:
        staged = pathlib.Path(tmp) / ASSET_NAME
        shutil.copyfile(apk, staged)
        run(["gh", "release", "upload", tag, str(staged), "--clobber"])
    ok(f"GitHub：已上传 {ASSET_NAME}")


def step_release(tag, notes, apk, platforms, reupload):
    step(6, "建 Release 并上传安装包")
    info(f"Release 正文来自 release-notes/{tag}.md 的 notes 区块（{len(notes.splitlines())} 行）")
    if "gitee" in platforms:
        release_gitee(tag, notes, apk, reupload)
    if "github" in platforms:
        release_github(tag, notes, apk, reupload)


# ── 步骤 7：线上校验 ────────────────────────────────────────────────────────

def step_verify_live(tag, platforms):
    """校验应用内「检查更新」真正读的那个接口，而不是仓库里的某个文件。

    AppUpdateChecker 打的是 /releases/latest，然后按扩展名挑第一个 .apk 资产。
    所以这里必须确认：latest 就是本版、且带一个 .apk。
    """
    step(7, "线上校验")
    if DRY:
        say("   [dry-run] 跳过线上校验")
        return
    problems = []

    if "gitee" in platforms:
        try:
            with urllib.request.urlopen(f"{GITEE_API}/releases/latest", timeout=30) as response:
                latest = json.loads(response.read().decode("utf-8"))
        except Exception as error:  # noqa: BLE001 - 网络问题都归为"校验不通过"
            problems.append(f"Gitee releases/latest 取不到：{redact(str(error))}")
        else:
            latest_tag = latest.get("tag_name", "")
            apks = [a for a in (latest.get("assets") or [])
                    if str(a.get("name", "")).lower().endswith(".apk")]
            if latest_tag != tag:
                problems.append(f"Gitee releases/latest 是 {latest_tag or '<空>'}，期望 {tag}")
            elif not apks:
                problems.append(f"Gitee {tag} 下没有 .apk 资产，应用内更新挑不到安装包")
            else:
                ok(f"Gitee releases/latest = {latest_tag}，安装包 {apks[0].get('name')}")

    if "github" in platforms:
        listing = gh_read("release", "view", tag, "--json", "assets", "--jq", ".assets[].name")
        names = [] if listing is None else listing.split()
        if ASSET_NAME in names:
            ok(f"GitHub {tag} 带资产 {ASSET_NAME}")
        else:
            problems.append(f"GitHub {tag} 缺资产 {ASSET_NAME}"
                            f"（现有：{'、'.join(names) or 'Release 不存在或没有资产'}）")

    if problems:
        raise SystemExit("ERROR: 线上校验未通过：\n       " + "\n       ".join(problems))


# ── 入口 ────────────────────────────────────────────────────────────────────

def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="publish.py", description="一键发布：构建、校验、推两端、建 Release 传 APK。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("version", nargs="?",
                        help="要发布的版本号（如 1.2.3）。省略则用 app/version.properties 里的版本")
    parser.add_argument("--dry-run", action="store_true",
                        help="只打印将要做什么，不动任何远端")
    parser.add_argument("--skip-build", action="store_true", help="复用已有 Release APK")
    parser.add_argument("--only", choices=("gitee", "github", "both"), default="both",
                        help="只发布到某一个平台，默认两端都发")
    parser.add_argument("--reupload", action="store_true",
                        help="资产已存在时删除旧的重传（默认跳过）")
    parser.add_argument("--local-only", action="store_true",
                        help="只做到构建 + 验包 + 落地 dist，不推送、不发布"
                             "（对应「编译一个包，先不发布」）")
    parser.add_argument("--allow-dirty", action="store_true",
                        help="工作区有未提交改动时也继续（不推荐）")
    args = parser.parse_args(argv)
    if args.version and re.fullmatch(r"\d+\.\d+\.\d+", args.version) is None:
        parser.error("版本号形如 X.Y.Z（不带 v 前缀）")
    return args


def main(argv=None):
    global TOKEN, GITEE_LOGIN, DRY
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:  # noqa: BLE001 - 老终端不支持就算了
            pass

    args = parse_args(sys.argv[1:] if argv is None else argv)
    DRY = args.dry_run

    TOKEN = os.environ.get("GITEE_TOKEN", "").strip() or local_properties().get("GITEE_TOKEN", "")
    GITEE_LOGIN = (os.environ.get("GITEE_USER", "").strip()
                   or local_properties().get("GITEE_USER", ""))
    platforms = {"gitee": {"gitee"}, "github": {"github"}, "both": {"gitee", "github"}}[args.only]

    say("=== 发布教务助理 ===")
    if DRY:
        say("(dry-run：只打印动作，不改任何远端)")
    say(f"    Gitee  {GITEE_REPO}  {'有令牌' if TOKEN else '缺令牌'}")
    say(f"    GitHub {GITHUB_REPO}")

    version, tag = step_local_prep(args.version, args.allow_dirty or args.local_only)

    file_version, file_code = read_version()
    if file_version != version:
        die(f"版本不一致：目标是 {version}，app/version.properties 写的是 {file_version}")
    if file_code != version_code_of(version):
        die(f"versionCode 不自洽：文件里 {file_code}，按公式 {version} → {version_code_of(version)}")
    info(f"versionCode = {file_code}")

    step_build(args.skip_build)
    apk = step_verify_apk(version, file_code)
    staged = step_stage(apk, tag)

    if args.local_only:
        say()
        say(f"=== 本地完成（--local-only，未推送任何平台）：{tag} ===")
        say(f"    dist      {staged.relative_to(ROOT)}")
        say(f"    确认无误后跑 `python scripts/publish.py` 推送并发布")
        return

    notes = release_notes_for(tag)
    step_push(tag, platforms)
    step_release(tag, notes, staged, platforms, args.reupload)
    step_verify_live(tag, platforms)

    say()
    say(f"=== 完成：{tag} 已发布 ===")
    say(f"    dist      {staged.relative_to(ROOT)}")
    say(f"    Gitee     https://gitee.com/{GITEE_REPO}/releases/tag/{tag}")
    say(f"    GitHub    https://github.com/{GITHUB_REPO}/releases/tag/{tag}")


if __name__ == "__main__":
    main()
