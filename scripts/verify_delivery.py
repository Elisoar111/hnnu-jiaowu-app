#!/usr/bin/env python3
"""Verify that a release really landed on Gitee and that the app can actually see it.

交付校验只回答一个问题：**装了这一版的用户点「检查更新」，能不能拿到这次发的包。**

应用侧的契约在 `app/src/main/java/com/hnnujw/course/network/AppUpdateChecker.kt`：

    GET https://gitee.com/api/v5/repos/<owner>/<repo>/releases/latest
      -> 取 tag_name 与当前安装版本比较
      -> 在 assets 里挑**第一个以 .apk 结尾**的资产当下载地址

所以真正该校验的就是这个接口。这里原先校验的是仓库根的 `version.json` —— 那个文件
**从来没有被提交进任何分支**，也**没有任何一版应用读过它**（v1.2.0 / v1.2.1 的源码里
都搜不到 "version.json"），于是成了一道永远不可能通过的闸：CI 的 Verify Gitee Delivery
只要跑到就红，而它本来想防的问题（"发了版但用户更新不到"）恰恰没被防住。

同一个坑当天踩过第二次：公告那时也是"校验了一个应用不读的通道"。结论一样 ——
**校验必须打在使用方真正读的地方**（见 docs/design/2026-09-20-announcement-version-gating.md）。
"""
import argparse
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

# Gitee 仓库可由环境变量 GITEE_REPO 覆盖（形如 owner/repo），与
# .github/workflows/release.yml 的 sync-to-gitee job 用同一个仓库变量；
# 未设置时回退到本项目仓库。
_GITEE_REPO = os.environ.get("GITEE_REPO") or "Elisoar/hnnu-jiaowu-app"
API_ROOT = f"https://gitee.com/api/v5/repos/{_GITEE_REPO}"


class Gitee:
    """只读客户端。

    校验过程不需要任何写权限。令牌是可选的，带上只是为了避开匿名请求的限流；
    不带也必须能跑 —— 否则"用户没登录时能不能更新"这件事就永远验不了。
    """

    def __init__(self, token=""):
        self.token = token or ""

    def request(self, method, path, allow_missing=False):
        if method != "GET":
            raise AssertionError("交付校验是只读的，不该发写请求")
        if self.token:
            separator = "&" if "?" in path else "?"
            path += separator + urlencode({"access_token": self.token})
        request = Request(
            API_ROOT + "/" + path, method="GET",
            headers={"Accept": "application/json",
                     "User-Agent": "academic-assistant-release",
                     "Cache-Control": "no-cache"},
        )
        try:
            with urlopen(request, timeout=30) as response:
                return json.load(response)
        except HTTPError as error:
            if allow_missing and error.code == 404:
                return None
            # Never print the authenticated URL, response body or token.
            raise RuntimeError(f"Gitee returned HTTP {error.code}") from None
        except URLError:
            raise RuntimeError("Gitee request failed") from None


def verify_delivery(client, tag):
    """返回应用会拿到的下载地址；任何一项不满足就抛错。"""
    latest = client.request("GET", "releases/latest", allow_missing=True)
    if not isinstance(latest, dict):
        raise RuntimeError(
            "Gitee 上还没有任何已发布的 Release —— 应用点「检查更新」会直接提示"
            "「Gitee 仓库还没有发布任何版本」")

    latest_tag = str(latest.get("tag_name") or "")
    if latest_tag != tag:
        raise RuntimeError(
            f"releases/latest 指向 {latest_tag or '<空>'}，不是本次发布的 {tag}："
            f"用户点「检查更新」拿到的还是旧版本")

    assets = latest.get("assets")
    assets = assets if isinstance(assets, list) else []
    apks = [asset for asset in assets
            if isinstance(asset, dict)
            and str(asset.get("name", "")).lower().endswith(".apk")]
    if not apks:
        names = "、".join(str(asset.get("name")) for asset in assets if isinstance(asset, dict))
        raise RuntimeError(
            f"{tag} 下没有 .apk 资产（现有：{names or '无'}）："
            f"应用在 assets 里挑不到安装包，只会退回 Release 网页让用户自己找")

    url = str(apks[0].get("browser_download_url") or "")
    if not url:
        raise RuntimeError(f"{tag} 的 .apk 资产没有 browser_download_url，应用下不到")
    return url


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    if re.fullmatch(r"v\d+\.\d+\.\d+", args.tag) is None:
        parser.error("tag must have the form vX.Y.Z")
    url = verify_delivery(Gitee(os.environ.get("GITEE_TOKEN", "")), args.tag)
    print(f"Verified Gitee delivery for {args.tag}: {url}")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        raise SystemExit(f"ERROR: {error}")
