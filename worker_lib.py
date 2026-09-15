# -*- coding: utf-8 -*-
"""
worker_lib.py —— 各平台 RPA 脚本的公共「工人」运行时。

v2 更新：完整集成账号池调度！
  工人主循环现在是：
    借一个 AI 账号 → 连跑 BATCH_SIZE(默认10) 个问题 → 释放账号 → 重新借下一个 → ...
  每个账号有独立的浏览器 profile（按 account_id 命名目录），
  第一次使用时会让用户手动登录一次（cookie 持久化在 profile 里），
  之后自动从账号池轮换，再也不用手动开脚本登录了。

环境变量：
  RPA_SERVICE_URL      认领/心跳/账号池接口的地址（rpa-service，默认 http://localhost:8084）
  BACKEND_BASE_URL     回调/上传截图接口的地址（网关，默认 http://localhost:8080）
  CLAIM_IDLE_SLEEP     认领不到单元时的空转间隔秒数（默认 5）
  HEARTBEAT_INTERVAL   心跳续约间隔秒数（默认 60，需远小于服务端租约时长）
  BATCH_SIZE           每个账号连跑多少个问题后自动换号（默认 10）
  ACQUIRE_ACCOUNT_SLEEP 借不到账号时的等待间隔秒数（默认 15，因为要等账号冷却）
"""
import os
import socket
import threading
import time

import requests
from datetime import datetime

RPA_SERVICE_URL = os.getenv("RPA_SERVICE_URL", "http://localhost:8084")
BACKEND_BASE_URL = os.getenv("BACKEND_BASE_URL", "http://localhost:8080")
CLAIM_IDLE_SLEEP = int(os.getenv("CLAIM_IDLE_SLEEP", "5"))
HEARTBEAT_INTERVAL = int(os.getenv("HEARTBEAT_INTERVAL", "60"))

# ========== 账号池相关的新配置 ==========
BATCH_SIZE = int(os.getenv("BATCH_SIZE", "10"))          # 一个账号连跑多少题后换号
ACQUIRE_ACCOUNT_SLEEP = int(os.getenv("ACQUIRE_ACCOUNT_SLEEP", "15"))  # 借不到账号时等多久再试


def _log(msg):
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {msg}")


# 供各平台脚本 import 使用的日志入口（deepseek/doubao 等）
log = _log


def _post(url, payload, timeout=30):
    try:
        resp = requests.post(
            url,
            json=payload,
            headers={"Content-Type": "application/json"},
            timeout=timeout,
        )
        if resp.status_code == 200:
            return resp.json()
        _log(f"HTTP {resp.status_code}: {resp.text[:300]}")
        return None
    except Exception as e:
        _log(f"请求异常 {url}: {e}")
        return None


def default_worker_id():
    return socket.gethostname()


def upload_screenshot(local_path, task_id=None):
    """上传截图到后端，返回 URL；失败时返回 None。"""
    try:
        with open(local_path, "rb") as f:
            files = {"file": (os.path.basename(local_path), f, "image/png")}
            data = {}
            if task_id is not None:
                data["taskId"] = str(task_id)
            resp = requests.post(
                BACKEND_BASE_URL + "/api/rpa/upload",
                files=files,
                data=data,
                timeout=60,
            )
        if resp.status_code == 200:
            result = resp.json()
            if result.get("code") == 200 and isinstance(result.get("data"), dict):
                return result["data"].get("url") or (result["data"].get("data") if isinstance(result["data"].get("data"), str) else None)
            if isinstance(result.get("data"), str):
                return result["data"]
            if result.get("success") is True:
                return result.get("data")
        _log(f"上传失败: {resp.text[:300]}")
        return None
    except Exception as e:
        _log(f"上传异常: {e}")
        return None


# =====================================================================
#  账号池接口（新增！worker 不再硬编码浏览器 profile，每次启动先从账号池借）
# =====================================================================

def acquire_account(platform_code, worker_id):
    """
    从账号池借一个空闲账号。
    返回 dict：{id, platform, accountName, cookie, ...}；借不到返回 None。
    """
    obj = _post(
        RPA_SERVICE_URL + "/internal/rpa/worker/acquire-account",
        {"platform": platform_code, "workerId": worker_id},
        timeout=30,
    )
    if obj and obj.get("code") == 200:
        return obj.get("data")
    _log(f"借账号失败: {obj.get('message') if obj else '网络错误'}")
    return None


def release_account(account_id, worker_id):
    """释放账号（用完后主动归还）。"""
    _post(
        RPA_SERVICE_URL + "/internal/rpa/worker/release-account",
        {"accountId": account_id, "workerId": worker_id},
        timeout=15,
    )


def cooldown_account(account_id, worker_id, minutes=10):
    """标记账号冷却（遇到验证码/风控时主动换号）。"""
    _post(
        RPA_SERVICE_URL + "/internal/rpa/worker/cooldown-account",
        {"accountId": account_id, "workerId": worker_id, "minutes": minutes},
        timeout=15,
    )


# =====================================================================
#  任务认领 / 心跳 / 回调
# =====================================================================

def claim_work(platform_code, worker_id, account_id=None):
    """
    认领一个待执行单元。
    返回 dict(id/taskNo/aiPlatform/questionText)；无任务时返回 None。

    新增 account_id 参数：如果 worker 已经借到了账号，
    就把 accountId 传给后端，后端会记录到 task_result.account_id，
    回调时就知道该用哪个账号更新状态。
    """
    payload = {"platform": platform_code, "workerId": worker_id}
    if account_id is not None:
        payload["accountId"] = account_id
    obj = _post(
        RPA_SERVICE_URL + "/internal/rpa/worker/assign",
        payload,
        timeout=30,
    )
    if not obj or obj.get("code") != 200:
        return None
    data = obj.get("data")
    if isinstance(data, dict) and data.get("id") is not None:
        return data
    return None


def heartbeat(unit_id, worker_id):
    """心跳续约，返回是否续约成功。"""
    obj = _post(
        RPA_SERVICE_URL + "/internal/rpa/worker/heartbeat",
        {"unitId": unit_id, "workerId": worker_id},
        timeout=15,
    )
    return bool(obj and obj.get("code") == 200)


def abort_unit(unit_id, worker_id):
    """异常中止：把单元归还任务池，让别的工人重试。"""
    _post(
        RPA_SERVICE_URL + "/internal/rpa/worker/abort",
        {"unitId": unit_id, "workerId": worker_id},
        timeout=15,
    )


def callback(unit, status, answer_text=None, thinking_content=None,
             source_info=None, image_url=None, error_msg=None):
    """把单个单元的结果回调到后端（携带 taskResultId 精确定位对应记录）。"""
    payload = {
        "taskNo": unit.get("taskNo"),
        "taskResultId": unit.get("id"),
        "aiPlatform": unit.get("aiPlatform"),
        "questionText": unit.get("questionText"),
        "thinkingContent": thinking_content or "",
        "answerText": answer_text or "",
        "sourceInfo": source_info or "",
        "screenshotUrls": [image_url] if image_url else [],
        "status": status,
        "errorMsg": error_msg,
        "durationMs": 0,
    }
    obj = _post(BACKEND_BASE_URL + "/api/rpa/callback", payload, timeout=60)
    return bool(obj and obj.get("code") == 200)


# =====================================================================
#  主循环（重写：集成账号池轮换）
# =====================================================================

def run_worker_loop(platform_code, worker_context, handle_unit, worker_id=None):
    """
    工人主循环：借账号 → 连跑 BATCH_SIZE 个单元 → 释放 → 重新借。

    参数：
      platform_code  平台 code（必须等于后端 task_result.ai_platform 存储值，
                     如 deepseek/doubao/kimi/wenxin/qianwen/tencent）
      worker_context  一个 dict，包含当前 worker 的所有状态：
                     {
                       'get_page': lambda: page,          # 获取当前浏览器 page 对象
                       'open_profile': lambda(profile_dir):  # 打开指定 profile 目录的浏览器
                       'close_browser': lambda:              # 关闭当前浏览器
                       'profile_base_dir': str,             # profile 根目录
                     }
      handle_unit    回调签名 handle_unit(page, unit)，负责执行一个问题并调用 callback；
                     抛出异常时单元会被归还任务池。
      worker_id      工人标识，默认取机器名

    账号池策略：
      1. 启动时调 acquire_account 借一个账号
      2. 用这个账号连跑 BATCH_SIZE（默认10）个问题
      3. 用完后调 release_account 释放，重新借下一个
      4. 遇到验证码/风控 → 调 cooldown_account(分钟) 标记冷却，然后借新号
      5. 借不到账号时等待 ACQUIRE_ACCOUNT_SLEEP 秒后重试
    """
    worker_id = worker_id or default_worker_id()
    _log(f"Worker 启动: platform={platform_code}, workerId={worker_id}")
    _log(f"认领地址: {RPA_SERVICE_URL}   回调地址: {BACKEND_BASE_URL}")
    _log(f"账号池模式: BATCH_SIZE={BATCH_SIZE}（每 {BATCH_SIZE} 个问题自动换号）")

    current_account = None          # 当前借到的账号 dict
    questions_done_in_batch = 0     # 当前批次已完成的问题数
    current_profile_dir = None      # 当前账号用的浏览器 profile 目录

    def _try_acquire_account():
        """尝试借一个账号，同时打开对应的浏览器 profile。"""
        nonlocal current_account, current_profile_dir, questions_done_in_batch
        while True:
            account = acquire_account(platform_code, worker_id)
            if account is not None:
                current_account = account
                account_id = account["id"]
                questions_done_in_batch = 0
                # 构造这个账号专属的 profile 目录（按 account_id 隔离）
                current_profile_dir = os.path.join(
                    worker_context.get("profile_base_dir", "worker_profiles"),
                    f"{platform_code}_account_{account_id}"
                )
                os.makedirs(current_profile_dir, exist_ok=True)
                _log(f"成功借到账号: id={account_id}, name={account.get('accountName', '')}, profile={current_profile_dir}")
                # 让 worker 打开这个 profile 的浏览器
                if "open_profile" in worker_context:
                    try:
                        worker_context["open_profile"](current_profile_dir)
                        _log(f"浏览器已打开账号 {account_id} 的 profile")
                    except Exception as e:
                        _log(f"打开浏览器 profile 失败: {e}")
                return True
            _log(f"暂时没有可用账号，等待 {ACQUIRE_ACCOUNT_SLEEP} 秒后重试...")
            time.sleep(ACQUIRE_ACCOUNT_SLEEP)

    def _release_current_account(reason="正常用完"):
        """释放当前账号。"""
        nonlocal current_account, current_profile_dir
        if current_account is not None:
            try:
                release_account(current_account["id"], worker_id)
                _log(f"已释放账号: id={current_account['id']}, 原因={reason}")
            except Exception as e:
                _log(f"释放账号异常（不影响主流程）: {e}")
            current_account = None
            current_profile_dir = None

    # ============ 主循环 ============
    # _current_unit_id 用于 finally 块：如果退出时还有单元在跑，主动归还
    _current_unit_id = None

    try:
        while True:
            # 第一步：确保有账号用（刚启动或上一个用完了）
            if current_account is None:
                if not _try_acquire_account():
                    continue

            # 第二步：认领一个问题单元
            account_id = current_account["id"] if current_account else None
            unit = claim_work(platform_code, worker_id, account_id)
            if unit is None:
                time.sleep(CLAIM_IDLE_SLEEP)
                # 如果认领不到任务，但我们已经跑够了 BATCH_SIZE 个，就释放账号换一个
                if questions_done_in_batch >= BATCH_SIZE:
                    _log(f"已跑够 {BATCH_SIZE} 个问题，释放当前账号换号")
                    _release_current_account("批次完成")
                continue

            unit_id = unit.get("id")
            _current_unit_id = unit_id  # 记录当前在跑的单元，退出时归还用
            task_no = unit.get("taskNo")
            _log(f"认领单元: id={unit_id}, taskNo={task_no}, question={str(unit.get('questionText'))[:40]}, accountId={account_id}")

            # 心跳线程
            stop_event = threading.Event()
            failed = [0]

            def heartbeat_loop():
                while not stop_event.is_set():
                    stop_event.wait(HEARTBEAT_INTERVAL)
                    if stop_event.is_set():
                        break
                    if heartbeat(unit_id, worker_id):
                        failed[0] = 0
                    else:
                        failed[0] += 1
                        _log(f"心跳失败 {failed[0]} 次，请检查网络/后端状态")

            hb = threading.Thread(target=heartbeat_loop, daemon=True, name="worker-heartbeat")
            hb.start()

            page = worker_context["get_page"]() if "get_page" in worker_context else worker_context.get("page")

            try:
                handle_unit(page, unit)
                questions_done_in_batch += 1
                _log(f"批次进度: {questions_done_in_batch}/{BATCH_SIZE}")
            except Exception as e:
                _log(f"单元执行异常，归还任务池重试: unit_id={unit_id}, err={e}")
                abort_unit(unit_id, worker_id)
                # 执行失败时也递增计数，但如果连续失败可能会触发账号自动冷却
                questions_done_in_batch += 1

                # 如果遇到验证/风控类异常，主动把账号标记冷却，然后换号
                err_msg = str(e).lower()
                if any(kw in err_msg for kw in ("验证", "验证码", "captcha", "风控", "verified")):
                    _log(f"检测到风控/验证码，主动冷却账号 {account_id} 并换号")
                    if current_account is not None:
                        try:
                            cooldown_account(current_account["id"], worker_id, minutes=10)
                        except Exception:
                            pass
                    questions_done_in_batch = BATCH_SIZE  # 强制换号
            finally:
                stop_event.set()
                hb.join(timeout=2)
                _current_unit_id = None  # 单元处理完了，清掉记录

            # 第三步：检查是否需要换号
            if questions_done_in_batch >= BATCH_SIZE:
                _log(f"一个账号已完成 {BATCH_SIZE} 个问题，释放并重新借号")
                _release_current_account("批次完成")
                # 让 worker 关闭当前浏览器
                if "close_browser" in worker_context:
                    try:
                        worker_context["close_browser"]()
                    except Exception as e:
                        _log(f"关闭浏览器异常: {e}")
                # 等一下让上一个账号进入冷却，同时给下一个账号准备
                time.sleep(2)

            time.sleep(CLAIM_IDLE_SLEEP)

    except KeyboardInterrupt:
        _log("收到 Ctrl+C，正在优雅退出...")
    except Exception as e:
        _log(f"主循环异常退出: {e}")
    finally:
        # ===== 优雅清理：无论 Ctrl+C 还是主循环异常，都执行 =====

        # 1. 归还正在跑的单元（如果有）
        if _current_unit_id is not None:
            try:
                abort_unit(_current_unit_id, worker_id)
                _log(f"已归还正在跑的单元: {_current_unit_id}")
            except Exception as e:
                _log(f"归还单元异常: {e}")

        # 2. 释放账号（手动退出≠账号有问题，只清 worker_id，不标记冷却）
        _release_current_account("worker 手动退出")

        # 3. 关浏览器
        if "close_browser" in worker_context:
            try:
                worker_context["close_browser"]()
                _log("浏览器已关闭")
            except Exception as e:
                _log(f"关闭浏览器异常: {e}")

        _log("Worker 已优雅退出，所有资源已清理")
