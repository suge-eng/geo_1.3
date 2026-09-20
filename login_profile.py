# -*- coding: utf-8 -*-
"""
login_profile.py —— 一键登录新账号的浏览器 profile

用法（PowerShell）：
    # 登录单个账号（最简单，常用）
    python login_profile.py doubao 23

    # 批量登录多个账号（空格隔开）
    python login_profile.py doubao 3 4 5 6 7

    # 登录其他平台
    python login_profile.py deepseek 1

功能：
    Playwright 以持久化模式打开 profile 目录 → 你手动在浏览器里扫码/登录 →
    回到终端按 Enter → 脚本自动关浏览器（cookie 已持久化到 profile 里）。

    同时会把 cookie 以 JSON 明文导出（cookies.json）并上传到后端账号池，
    这样其他机器的 Worker 借到同一个账号时，后端会直接下发 cookie，免手动登录。

原理：
    Playwright launch_persistent_context 会把 cookie / localStorage / sessionStorage
    全部存在指定的 user_data_dir（就是 profile 目录）。关浏览器不删目录，
    下次再用同一个目录启动就是已登录状态。
"""
import json
import os
import sys
import time
from pathlib import Path

import requests
from playwright.sync_api import sync_playwright

BASE_DIR = Path(__file__).resolve().parent

RPA_SERVICE_URL = os.getenv("RPA_SERVICE_URL", "http://localhost:8084")
WORKER_ID = os.getenv("WORKER_ID", "")

STATE_FILE_NAME = "state.json"

# 各平台的配置：profile 目录名 + 登录首页 URL
PLATFORMS = {
    "doubao": {
        "profile_base": BASE_DIR / "edge_doubao_profiles",
        "url": "https://www.doubao.com/chat?channel=xiazai",
    },
    "deepseek": {
        "profile_base": BASE_DIR / "edge_deepseek_profiles",
        "url": "https://chat.deepseek.com",
    },
    "kimi": {
        "profile_base": BASE_DIR / "edge_kimi_profiles",
        "url": "https://kimi.moonshot.cn",
    },
    "wenxin": {
        "profile_base": BASE_DIR / "edge_wenxin_profiles",
        "url": "https://yiyan.baidu.com",
    },
    "qianwen": {
        "profile_base": BASE_DIR / "edge_qianwen_profiles",
        "url": "https://tongyi.aliyun.com/qianwen",
    },
    "yuanbao": {
        "profile_base": BASE_DIR / "edge_yuanbao_profiles",
        "url": "https://yuanbao.tencent.com",
    },
}


def launch_browser(profile_dir, url):
    """
    用持久化模式打开浏览器。cookie 自动存到 profile_dir。
    这和 Worker 脚本里的 _open_browser 写法完全一致，保证 profile 格式兼容。
    """
    playwright = sync_playwright().start()
    browser = playwright.chromium.launch_persistent_context(
        user_data_dir=str(profile_dir),
        channel="msedge",
        headless=False,
        no_viewport=True,
        args=[
            "--start-maximized",
            "--disable-blink-features=AutomationControlled",
            "--disable-infobars",
            "--disable-extensions",
            "--disable-features=IsolateOrigins,site-per-process",
        ],
    )

    # 反检测：把 webdriver 标记抹掉，防止 AI 平台识别出是自动化浏览器
    browser.add_init_script(
        """
        () => {
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined, configurable: true });
            window.chrome = window.chrome || {};
            window.chrome.runtime = window.chrome.runtime || {};
            Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en-US', 'en'], configurable: true });
            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5], configurable: true });
        }
        """
    )

    page = browser.pages[0] if browser.pages else browser.new_page()
    page.goto(url, wait_until="domcontentloaded")
    return playwright, browser, page


def login_one(platform, account_id):
    """
    登录单个账号的流程：
      1. 拼 profile 路径（命名规则必须和 Worker 里保持一致）
      2. 打开浏览器 → 你手动登录 → 按 Enter → 关浏览器
      3. cookie 自动持久化到 profile 目录
      4. 同时导出 cookies.json 并上传到后端账号池（跨机器免登录的关键）
    """
    cfg = PLATFORMS[platform]
    profile_dir = cfg["profile_base"] / f"{platform}_account_{account_id}"
    profile_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n{'='*50}")
    print(f"平台: {platform}    账号 ID: {account_id}")
    print(f"Profile 目录: {profile_dir}")
    print(f"{'='*50}")

    playwright, browser, page = launch_browser(profile_dir, cfg["url"])

    try:
        print(f">>> 浏览器已打开，请到浏览器里手动登录账号 {account_id}")
        print(f">>> 登录完成后，回到终端按 Enter 继续下一个...")
        input()

        print(f"等待 3 秒让 cookie 落盘...")
        time.sleep(3)

        try:
            page.reload(wait_until="domcontentloaded", timeout=30000)
            time.sleep(2)
            print(f"✓ 账号 {account_id} 登录完成，cookie 已保存到 {profile_dir}")
        except Exception as e:
            print(f"刷新页面时遇到小问题（不影响 cookie 保存）: {e}")

        # ===== 导出完整便携式 storage_state（cookie + localStorage + sessionStorage）=====
        # launch_persistent_context 返回的就是 BrowserContext 本身
        # 用 storage_state() 一次性拿全，避免"只有 cookie 没有 localStorage"导致登录失效
        try:
            state = browser.storage_state()
            cookie_file = profile_dir / STATE_FILE_NAME
            with open(cookie_file, "w", encoding="utf-8") as f:
                json.dump(state, f, ensure_ascii=False, indent=2)
            _cn = len(state.get("cookies", []))
            _ls = sum(len(o.get("localStorage", [])) for o in state.get("origins", []))
            print(f"  ✓ 已导出 state: {_cn} cookies + {_ls} localStorage → {cookie_file}")
        except Exception as e:
            print(f"  ⚠ 导出 state.json 失败（不影响本地登录）: {e}")

    finally:
        try:
            browser.close()
        except Exception:
            pass
        try:
            playwright.stop()
        except Exception:
            pass

    # ===== 上传完整 storage_state 到后端账号池 =====
    state_file = profile_dir / STATE_FILE_NAME
    if state_file.exists():
        try:
            with open(state_file, "r", encoding="utf-8") as f:
                state_json_str = f.read()
            payload = {
                "accountId": account_id,
                "workerId": WORKER_ID or "login_profile_manual",
                "cookie": state_json_str,
            }
            resp = requests.post(
                RPA_SERVICE_URL + "/internal/rpa/worker/upload-cookie",
                json=payload,
                timeout=15,
            )
            if resp.status_code == 200:
                obj = resp.json()
                if obj.get("code") == 200:
                    print(f"  ✓ state 已上传到后端账号池（其他 Worker 借到此号时免登录！）")
                else:
                    print(f"  ⚠ 后端返回: {obj.get('message', obj)}")
            else:
                print(f"  ⚠ 上传失败 HTTP {resp.status_code}（后端可能没起，不影响本地）")
        except Exception as e:
            print(f"  ⚠ 上传 cookies 异常（不影响本地登录）: {e}")


def main():
    if len(sys.argv) < 3:
        print("用法: python login_profile.py <平台> <账号ID1> [账号ID2] [账号ID3] ...")
        print()
        print("支持的平台: " + ", ".join(PLATFORMS.keys()))
        print()
        print("示例:")
        print("  python login_profile.py doubao 23          # 登录豆包账号 23")
        print("  python login_profile.py doubao 3 4 5       # 批量登录豆包账号 3/4/5")
        print("  python login_profile.py deepseek 1         # 登录 deepseek 账号 1")
        sys.exit(1)

    platform = sys.argv[1].lower()
    if platform not in PLATFORMS:
        print(f"❌ 不支持的平台: {platform}")
        print(f"支持的平台: {', '.join(PLATFORMS.keys())}")
        sys.exit(1)

    # 账号 ID 列表：从命令行参数里取，全部转成 int
    try:
        account_ids = [int(x) for x in sys.argv[2:]]
    except ValueError:
        print("❌ 账号 ID 必须是数字")
        sys.exit(1)

    print(f"准备登录 {len(account_ids)} 个 {platform} 账号: {account_ids}")
    print("提示：每个账号浏览器打开后，你手动完成登录，回终端按 Enter 就自动切到下一个")

    success, fail = [], []
    for aid in account_ids:
        try:
            login_one(platform, aid)
            success.append(aid)
        except KeyboardInterrupt:
            print(f"\n⏹ 被用户中断，已登录 {success}，跳过 {account_ids[len(success):]}")
            break
        except Exception as e:
            print(f"❌ 账号 {aid} 登录失败: {e}")
            fail.append(aid)

    print(f"\n{'='*50}")
    print(f"全部完成！成功 {len(success)} 个: {success}")
    if fail:
        print(f"失败 {len(fail)} 个: {fail}")
    print(f"{'='*50}")


if __name__ == "__main__":
    main()