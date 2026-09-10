# -*- coding: utf-8 -*-
"""
worker_lib.py —— 各平台 RPA 脚本的公共「工人」运行时。

替代原先「RabbitMQ 单队列 + SQLite 全局锁」的串行消费模式：
  工人 -> 认领一个单元(1问题x1AI) -> 执行 -> 回调 -> 再认领下一个

服务端由 geo-rpa-service 的 RpaWorkerDispatcherService 提供原子认领（
SELECT ... FOR UPDATE SKIP LOCKED 保证并发不重复 + 租约超时回收），
因此多台电脑、多个用户提交的任务能公平并行，不再饥饿、不再需要任何锁。

环境变量：
  RPA_SERVICE_URL  认领/心跳/中止接口的地址（rpa-service，默认 http://localhost:8084）
                   多机部署时改成后端服务器地址，如 http://192.168.x.x:8084
  BACKEND_BASE_URL 回调/上传截图接口的地址（网关，默认 http://localhost:8080）
  CLAIM_IDLE_SLEEP 认领不到单元时的空转间隔秒数（默认 5）
  HEARTBEAT_INTERVAL  心跳续约间隔秒数（默认 60，需远小于服务端租约时长）
"""
import os
import socket
import threading
import time

import requests
from datetime import datetime

RPA_SERVICE_URL = os.getenv("RPA_SERVICE_URL", "http://172.28.30.78:8084")
BACKEND_BASE_URL = os.getenv("BACKEND_BASE_URL", "http://172.28.30.78:8080")
CLAIM_IDLE_SLEEP = int(os.getenv("CLAIM_IDLE_SLEEP", "5"))
HEARTBEAT_INTERVAL = int(os.getenv("HEARTBEAT_INTERVAL", "60"))


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


def claim_work(platform_code, worker_id):
    """认领一个待执行单元，返回 dict(id/taskNo/aiPlatform/questionText)；无任务时返回 None。"""
    obj = _post(
        RPA_SERVICE_URL + "/internal/rpa/worker/assign",
        {"platform": platform_code, "workerId": worker_id},
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


def run_worker_loop(platform_code, page, handle_unit, worker_id=None):
    """
    工人主循环：认领 -> 执行一个单元 -> 回调 -> 再认领。

    参数：
      platform_code  平台 code（必须等于后端 task_result.ai_platform 存储值，
                     如 deepseek/doubao/kimi/wenxin/qianwen/tencent）
      page           playwright 已登录的 page 对象
      handle_unit    回调签名 handle_unit(page, unit)，负责执行一个问题并调用本模块的
                     callback(...) 回传结果；抛出异常时单元会被归还任务池。
      worker_id      工人标识，默认取机器名（多台电脑各自不同，便于观察认领情况）
    """
    worker_id = worker_id or default_worker_id()
    _log(f"Worker 启动: platform={platform_code}, workerId={worker_id}")
    _log(f"认领地址: {RPA_SERVICE_URL}   回调地址: {BACKEND_BASE_URL}")

    while True:
        unit = claim_work(platform_code, worker_id)
        if unit is None:
            time.sleep(CLAIM_IDLE_SLEEP)
            continue

        unit_id = unit.get("id")
        task_no = unit.get("taskNo")
        _log(f"认领单元: id={unit_id}, taskNo={task_no}, platform={platform_code}, question={str(unit.get('questionText'))[:40]}")

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

        try:
            handle_unit(page, unit)
        except Exception as e:
            _log(f"单元执行异常，归还任务池重试: unit_id={unit_id}, err={e}")
            abort_unit(unit_id, worker_id)
        finally:
            stop_event.set()
            hb.join(timeout=2)

        time.sleep(CLAIM_IDLE_SLEEP)