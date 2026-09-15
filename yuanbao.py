"""
yuanbao.py —— 腾讯元宝（Yuanbao / 混元）平台的 RPA 自动化脚本，系统「工人」之一。

一、角色与整体流程（与 deepseek.py / kimi.py / qianwen.py / doubao.py 一致）
--------------------------------------------------------------------------
用 Playwright 驱动本机 Edge 登录元宝网页版，从后端「任务池」认领「1 问题 ×
1 元宝」的最小单元，让 AI 深度思考回答、截图、回调。并发安全由后端（租约 + 原子
认领）保证，脚本无全局锁，可多机并行。

二、元宝平台特有的难点（本脚本最值得学习的设计）
------------------------------------------------
1. 深度思考是「对话级开关」：enable_deep_thinking() 通过 data-thinking-mode-switcher-
   trigger 定位模式切换按钮，按按钮文本是否含"深度思考"判断开关状态；若未开启则点
   击展开下拉菜单选择"深度思考"，开启后必须校验真的生效，否则后续取不到思考内容。
2. 回答完成判定靠 convStatus：每条 AI 消息的 DOM 上有 data-conv-status / data-conv-
   outputting 属性，finished + 不再 outputting 即完成（见 ai_messages 的 done 字段）。
3. 思考与回答分离：思考在 .hyc-component-deepsearch-cot 里，回答正文在 .hyc-content-md，
   message_data() 用这两个选择器分别抽取，并判断思考是否展开过。
4. 引用来源要二次校验：extract_sources() 先点引用按钮打开列表，等卡片加载稳定后再用
   注入 JS 从 #chatReferenceList 里抓 url+标题；若打开了引用却没读到内容会主动抛错，
   保证"宁失败不错报"。
5. 截图用「克隆 DOM + 左右分栏」：take_screenshot() 把问题+回答克隆到左侧、来源列表
   克隆到右侧，拼成一整张图，绕开长回答截断。
"""

import json
import os
import random
import re
import time
from datetime import datetime
from pathlib import Path

from playwright.sync_api import sync_playwright, TimeoutError

from worker_lib import (
    upload_screenshot as worker_upload,
    run_worker_loop,
    callback as worker_callback,
)

BASE_DIR = Path(__file__).resolve().parent
SCREENSHOT_DIR = BASE_DIR / "yuanbao_screenshots"
PROFILE = BASE_DIR / "edge_yuanbao_profile"

PLATFORM_NAME = "yuanbao"

URL = "https://yuanbao.tencent.com/chat/naQivTmsDa"

SCREENSHOT_DIR.mkdir(exist_ok=True)


def log(msg):
    # 带时间戳打印，flush=True 实时刷控制台。
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def human_wait(a=1, b=3):
    # 随机等待，模拟真人节奏、降低反爬识别。
    time.sleep(random.randint(a, b))


def first_visible(locator):
    # 返回集合里第一个可见元素。
    for i in range(locator.count()):
        item = locator.nth(i)
        if item.is_visible():
            return item
    return None


def input_box(page):
    # 元宝输入框是 .ql-editor 富文本编辑器（Quill），contenteditable=true。取 .last 兜底。
    box = page.locator('.ql-editor[contenteditable="true"]').last
    box.wait_for(state="visible", timeout=30000)
    return box


def login_entry(page):
    # 用正则匹配"登录/登录注册"入口文本，稳健应对文案微调。
    return first_visible(page.get_by_text(re.compile(r"^\s*(登录|登录/注册)\s*$")))


def ensure_login(page):
    # 登录态检测 + 人工兜底（和 kimi.py 完全同构）：已登录直接返回，否则点登录入口
    # 后阻塞等用户在浏览器里手动登录（扫码/验证码交给人才最稳）。
    page.wait_for_timeout(1000)
    login = login_entry(page)
    if login is None:
        return
    try:
        login.click(force=True)
    except Exception:
        pass
    log("请在打开的 Edge 窗口中完成元宝账号登录，脚本正在等待……")
    end = time.time() + 600
    while time.time() < end:
        if login_entry(page) is None:
            try:
                box = input_box(page)
                box.click(trial=True, timeout=2000)
                log("账号登录成功，登录状态已保存")
                return
            except Exception:
                pass
        time.sleep(1)
    raise TimeoutError("等待元宝登录超时")


def ready(page):
    # 就绪判断：反复试探输入框是否可点击（trial=True 不产生真实点击副作用），
    # 600 秒内没就绪就抛超时。
    end = time.time() + 600
    while time.time() < end:
        try:
            box = input_box(page)
            box.click(trial=True, timeout=3000)
            return box
        except Exception:
            log("等待元宝输入框……")
            time.sleep(1)
    raise TimeoutError("等待元宝输入框超时")


def new_chat(page):
    # 跳转对话 URL（代码里写死了一个会话入口）-> 确保登录 -> 返回就绪的输入框。
    page.goto(URL, wait_until="domcontentloaded", timeout=60000)
    ensure_login(page)
    return ready(page)


def enable_deep_thinking(page):
    # 元宝思考模式切换器：按钮显示当前模式（文本如"深度思考"），data-active="true"
    # 表示该模式已选中。若当前不是深度思考，点击切换器展开下拉菜单后选择"深度思考"。
    switcher = page.locator('[data-thinking-mode-switcher-trigger="true"]').last
    switcher.wait_for(state="visible", timeout=20000)

    if "深度思考" in (switcher.inner_text() or ""):
        log("深度思考已开启")
        return

    switcher.click(force=True)
    page.wait_for_timeout(500)

    option = page.locator('[data-thinking-mode-switcher-trigger="true"]', has_text="深度思考").last
    if option.count() == 0:
        option = page.get_by_text("深度思考", exact=True).last
    option.click(force=True)
    page.wait_for_timeout(600)

    switcher = page.locator('[data-thinking-mode-switcher-trigger="true"]').last
    if "深度思考" not in (switcher.inner_text() or ""):
        raise RuntimeError("深度思考未能开启")
    log("已开启深度思考")


def ai_messages(page):
    # 列出页面里所有 AI 回答节点的 id 与「是否完成」。
    # 完成判定直接读 DOM 上的 data-conv-status="finished" 且 data-conv-outputting="false"，
    # 这是元宝给每条会话提供的状态字段，比其它平台靠启发式判断更可靠。
    return page.evaluate("""() => [...document.querySelectorAll(
      '.agent-chat__list__item--ai[data-conv-id]')].map(x => ({
        id:x.dataset.convId || '', done:x.dataset.convStatus === 'finished' &&
          x.dataset.convOutputting === 'false'
      }))""")


def message_data(page, conv_id):
    # 从某条回答的 DOM 里提取思考、回答正文、完成状态（注入 JS，原因同 kimi/qianwen）。
    # 元宝的思考在 .hyc-component-deepsearch-cot，回答在 .hyc-content-md，二者天然分离，
    # 所以抽取比千问简单。同时返回 thinkingText（纯文本）供 wait_answer 做内容长度判断。
    return page.evaluate("""id => {
      const cleanText=s=>(s||'').replace(/\\r/g,'').replace(/\\n[ \\t]+/g,'\\n').trim();
      const cleanHtml = (el) => {
        if(!el) return '';
        const clone = el.cloneNode(true);
        clone.querySelectorAll('script, style, link, meta, noscript, iframe, button, form, input, textarea').forEach(n => n.remove());
        const all = clone.querySelectorAll('*');
        all.forEach(n => {
          for (let i = n.attributes.length - 1; i >= 0; i--) {
            const attr = n.attributes[i];
            if (attr.name === 'class' || attr.name === 'style' || attr.name === 'id' ||
                attr.name.indexOf('on') === 0 || attr.name.indexOf('data-') === 0 ||
                attr.name.indexOf('aria-') === 0 || attr.name === 'role' ||
                attr.name === 'tabindex' || attr.name === 'contenteditable') {
              n.removeAttribute(attr.name);
            }
          }
        });
        return clone.innerHTML.trim();
      };
      const htmlToText = html => {
        const tmp = document.createElement('div');
        tmp.innerHTML = html || '';
        return cleanText(tmp.innerText || tmp.textContent);
      };
      const root=[...document.querySelectorAll('.agent-chat__list__item--ai[data-conv-id]')]
        .find(x=>x.dataset.convId===id);
      if(!root)return null;
      const cot=root.querySelector('.hyc-component-deepsearch-cot');
      const thought=root.querySelector('.hyc-component-deepsearch-cot__think__content');
      let answer=cot ? [...cot.children].find(x=>x.matches('.hyc-content-md')) : null;
      if(!answer)answer=[...root.querySelectorAll('.hyc-content-md')]
        .filter(x=>!x.closest('.hyc-component-deepsearch-cot__think')).at(-1);
      const thinkingHtml = cleanHtml(thought);
      const thinkingText = htmlToText(thinkingHtml);
      return {id, answer:cleanHtml(answer) || cleanText(answer?.innerText||answer?.textContent),
        thinking:thinkingHtml,
        thinkingText:thinkingText,
        done:root.dataset.convStatus==='finished' && root.dataset.convOutputting==='false'};
    }""", conv_id)


def wait_answer(page, old_ids):
    # 等待回答生成完成。
    # 思路：找出新出现（不在 old_ids）的回答，取思考+回答拼接文本；完成条件是
    # done=true，或文本连续 30 秒不变（stable>=30）。720 秒兜底超时。
    end, last, stable = time.time() + 720, "", 0
    while time.time() < end:
        fresh = [x for x in ai_messages(page) if x["id"] and x["id"] not in old_ids]
        if fresh:
            data = message_data(page, fresh[-1]["id"])
            text = (data or {}).get("thinkingText", "") + (data or {}).get("answer", "")
            stable = stable + 1 if text and text == last else 0
            last = text
            if data and len(text) >= 2 and (fresh[-1]["done"] or stable >= 30):
                log("回答已完成")
                return data
        time.sleep(1)
    raise TimeoutError("等待元宝回答超时")


def expand_thinking(page, conv_id):
    # 展开回答里折叠的思考区。元宝思考默认收起，header 点击展开后 class 会带上
    # --expand 后缀；这里判断若还没展开就点一下 title/header 展开。
    expanded = page.evaluate("""id => {
      const root=[...document.querySelectorAll('.agent-chat__list__item--ai[data-conv-id]')]
        .find(x=>x.dataset.convId===id);
      const thought=root?.querySelector('.hyc-component-deepsearch-cot__think');
      if(!thought)return false;
      if(!thought.className.includes('hyc-component-deepsearch-cot__think--expand'))
        thought.querySelector('.hyc-component-deepsearch-cot__think__header')?.click();
      return true;
    }""", conv_id)
    if expanded:
        page.wait_for_timeout(700)


def extract_sources(page, conv_id):
    # 提取回答引用的"引用来源"网址。
    # 流程：先定位回答里的引用按钮（data-toolbar-type="citation"），若有则点开引用列表，
    # 等卡片数量稳定（连续 3 轮不变）后用注入 JS 从 #chatReferenceList 抓 url+标题；
    # 若确实打开了引用却一条都没读到，主动抛错（宁失败不错报），让上层知道取源出了问题。
    root = page.locator(f'.agent-chat__list__item--ai[data-conv-id="{conv_id}"]')
    citation = root.locator('[data-toolbar-type="citation"]')
    has_citation = citation.count() and citation.last.is_visible()
    if has_citation:
        citation.last.click(force=True)
        try:
            page.locator("#chatReferenceList").wait_for(state="visible", timeout=8000)
            cards = page.locator("#chatReferenceList .agent-dialogue-references__item")
            cards.first.wait_for(state="attached", timeout=8000)
            previous = stable = -1
            for _ in range(20):
                count = cards.count()
                stable = stable + 1 if count == previous else 0
                if count and stable >= 3:
                    break
                previous = count
                page.wait_for_timeout(300)
        except Exception:
            pass
    items = page.evaluate("""() => {
      const clean=s=>(s||'').replace(/\\s+/g,' ').trim(), seen=new Set(), out=[];
      for(const item of document.querySelectorAll('#chatReferenceList .agent-dialogue-references__item')){
        const card=item.querySelector('.hyc-common-markdown__ref_card[data-url]');
        const raw=(card?.dataset.url||'').trim();
        let url=''; try{url=new URL(raw,location.href).href}catch(_){};
        const title=clean(item.querySelector('.hyc-common-markdown__ref_card-title')?.innerText);
        if(/^https?:\\/\\//.test(url)&&title&&!seen.has(url)){seen.add(url);out.push({title,url})}
      }
      return out;
    }""")
    items = items if isinstance(items, list) else []
    if has_citation and not items:
        raise RuntimeError("已打开引用来源，但没有读取到标题和网址")
    return items


def ask(page, question):
    # 发起一次提问并等待回答，返回结构化结果（含 id/thinking/answer/sources）。
    # 流程：新建对话 -> 开启深度思考 -> 记录发送前已有回答 id -> 填问题 -> 点发送
    # -> 等完成 -> 展开思考 -> 重新取数据 -> 校验思考确实有内容 -> 提取来源。
    # 特别地：开启深度思考后若没拿到思考内容会直接抛错，保证"要思考就一定拿到思考"。
    box = new_chat(page)
    enable_deep_thinking(page)
    old_ids = {x["id"] for x in ai_messages(page)}
    box.click(force=True)
    box.fill(question, force=True)
    time.sleep(random.uniform(2, 5))
    send = page.locator("#yuanbao-send-btn")
    send.wait_for(state="visible", timeout=10000)
    page.wait_for_function("() => !document.querySelector('#yuanbao-send-btn')?.className.includes('disabled')",
                           timeout=10000)
    send.click(force=True)
    log("问题已发送")
    result = wait_answer(page, old_ids)
    expand_thinking(page, result["id"])
    result = message_data(page, result["id"])
    if not result:
        raise RuntimeError("回答节点已消失，无法提取")
    if not (result.get("thinkingText") or "").strip():
        raise RuntimeError("深度思考已开启，但没有提取到思考内容")
    result["sources"] = extract_sources(page, result["id"])
    return result


def sources_text(items):
    result, seen = [], set()
    for item in items or []:
        title, url = str(item.get("title", "")).strip(), str(item.get("url", "")).strip()
        if title and url and url not in seen:
            seen.add(url)
            result.append(f"{len(result) + 1}. {title}\n网址：{url}")
    return "\n\n".join(result)


def safe_name(s):
    return "".join("_" if c in '<>:"/\\|?*' else c for c in " ".join(str(s).split()))[:80]


def take_screenshot(page, conv_id, question, save_path):
    # 截图，采用「克隆 DOM + 左右分栏」方案：左侧克隆问题+回答，右侧克隆引用来源列表，
    # 拼进 #yuanbao-shot 容器再截图，得到既完整又干净的图；失败退回整页截图兜底。
    try:
        page.evaluate("""arg => {
          document.querySelector('#yuanbao-shot')?.remove();
          const root=[...document.querySelectorAll('.agent-chat__list__item--ai[data-conv-id]')]
            .find(x=>x.dataset.convId===arg.id); if(!root)return;
          const box=document.createElement('div'); box.id='yuanbao-shot';
          box.innerHTML='<style>'+`
            #yuanbao-shot{position:absolute;left:0;top:0;z-index:2147483647;width:1320px;
              padding:28px;background:#fff;color:#171717;font:15px/1.65 Arial,"Microsoft YaHei",sans-serif;box-sizing:border-box}
            #yuanbao-shot .yb-main{width:860px;min-width:0} #yuanbao-shot .yb-source{width:350px;margin-left:30px;border-left:1px solid #ddd;padding-left:22px}
            #yuanbao-shot,#yuanbao-shot *{max-height:none!important;overflow:visible!important}
            #yuanbao-shot .yb-main *,#yuanbao-shot .yb-source *{height:auto!important;transform:none!important;
              -webkit-line-clamp:unset!important;white-space:normal!important}
            #yuanbao-shot [class*="sticky"],#yuanbao-shot [class*="fixed"]{position:relative!important;inset:auto!important}
            #yuanbao-shot .hyc-component-deepsearch-cot__think,
            #yuanbao-shot .hyc-component-deepsearch-cot__think__content{display:block!important;height:auto!important;opacity:1!important}
            #yuanbao-shot [data-toolbar-type],#yuanbao-shot .agent-chat__action-bar{display:none!important}
            #yuanbao-shot .t-drawer__close{display:none!important}
            #yuanbao-shot .yb-question{margin:0 0 22px auto;max-width:70%;width:max-content;padding:10px 16px;border-radius:14px;background:#eaf3ff;font-size:16px}
            #yuanbao-shot .agent-chat__list__item{width:100%!important;margin:0!important;position:relative!important}
            #yuanbao-shot .agent-dialogue-references__list{padding-left:0!important}
          `+'</style>';
          const wrap=document.createElement('div');wrap.style.cssText='display:flex;align-items:flex-start;width:100%';
          const main=document.createElement('div');main.className='yb-main';
          const q=document.createElement('div');q.className='yb-question';q.textContent=arg.question;
          main.append(q,root.cloneNode(true)); wrap.appendChild(main);
          const side=document.createElement('div');side.className='yb-source';
          const refs=document.querySelector('#chatReferenceList');
          if(refs)side.append(refs.cloneNode(true));else side.textContent='无搜索网页';
          wrap.appendChild(side);box.appendChild(wrap);document.body.appendChild(box);
        }""", {"id": conv_id, "question": question})
        page.wait_for_timeout(600)
        page.locator("#yuanbao-shot").screenshot(path=save_path, animations="disabled")
    except Exception as exc:
        log(f"合成截图失败，改用整页截图：{exc}")
        page.screenshot(path=save_path, full_page=True)
    finally:
        page.evaluate("() => document.querySelector('#yuanbao-shot')?.remove()")
    log(f"截图：{save_path}")
    return save_path


def handle_unit(page, unit):
    """处理单个任务单元（1 问题 x 1 Yuanbao = 1 条 task_result）。由 worker 循环调用。

    流程：ask() 提问取结果 -> take_screenshot() 截图 -> 上传截图 -> worker_callback()
    回调结果。

    异常处理设计（重要）：无论成功失败都必须回调一次置终态（SUCCESS/FAILED），否则后端
    只能等租约超时才回收单元。try 走 SUCCESS，except 走 FAILED。
    """
    task_no = str(unit.get("taskNo"))
    agent_name = unit.get("aiPlatform") or "tencent"
    question = str(unit.get("questionText") or "")

    output_dir = os.path.join(str(SCREENSHOT_DIR), task_no)
    os.makedirs(str(output_dir), exist_ok=True)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    screenshot_file = os.path.join(str(output_dir), f"{task_no}_{agent_name}_{timestamp}.png")

    thinking = ""
    answer = ""
    sources = []
    source_pairs = []
    image_url = None

    # 模拟真人在开始处理前先"看一眼问题"的短暂停顿，避免操作节奏过于机械。
    human_wait(1, 3)

    try:
        result = ask(page, question)

        conv_id = result.get("id", "")
        thinking = result.get("thinking", "")
        answer = result.get("answer", "")
        sources = result.get("sources", [])

        take_screenshot(page, conv_id, question, screenshot_file)

        source_pairs = [[s.get("title", ""), s.get("url", "")] for s in sources]
        source_info = json.dumps(source_pairs, ensure_ascii=False) if source_pairs else ""

        image_url = worker_upload(screenshot_file, task_no)
        if not image_url:
            log("截图上传失败，使用本地路径回调")
            image_url = screenshot_file

        ok = worker_callback(
            unit,
            status="SUCCESS",
            answer_text=answer,
            thinking_content=thinking,
            source_info=source_info,
            image_url=image_url,
            error_msg=None,
        )
        log(f"回调结果: {'成功' if ok else '失败'}")

    except Exception as e:
        log(f"单元处理异常: {e}")
        source_info = json.dumps(source_pairs, ensure_ascii=False) if source_pairs else ""
        worker_callback(
            unit,
            status="FAILED",
            answer_text=answer,
            thinking_content=thinking,
            source_info=source_info,
            image_url=image_url,
            error_msg=str(e),
        )
    finally:
        # 每个单元处理完后随机休息 40~90 秒，降低提问频率，避免触发风控。
        log("单元结束，随机休息中...")
        wait_time = random.randint(40, 90)
        log(f"等待 {wait_time} 秒后处理下一条...")
        time.sleep(wait_time)


def main():
    """Yuanbao Worker 进程入口（账号池模式）。

    改动点（相比旧版）：
      - 不再硬编码 PROFILE，而是账号池借号后按 account_id 建独立 profile 目录
      - run_worker_loop 从 (platform, page, handle_unit) 改为 (platform, worker_context, handle_unit)
      - 注意：run_worker_loop 传的是 "tencent"（后端 task_result.ai_platform 存的是 tencent）
        而不是 PLATFORM_NAME="yuanbao"
      - 一个账号跑满 BATCH_SIZE（默认10）个问题后，自动关浏览器、释放账号、借下一个
    """
    log("启动 Yuanbao Worker（账号池模式）")
    log("注意：第一次使用某个账号时，需要在浏览器里手动登录一次，之后 cookie 会自动复用")

    playwright_instance = None
    browser = None
    page = None

    PROFILE_BASE = str(BASE_DIR / "edge_profiles")  # 所有账号的 profile 放这
    os.makedirs(PROFILE_BASE, exist_ok=True)

    def _open_browser(profile_dir):
        nonlocal playwright_instance, browser, page
        if browser is not None:
            try: browser.close()
            except Exception: pass
        if playwright_instance is not None:
            try: playwright_instance.stop()
            except Exception: pass

        log(f"打开浏览器，profile 目录: {profile_dir}")
        playwright_instance = sync_playwright().start()
        browser = playwright_instance.chromium.launch_persistent_context(
            user_data_dir=profile_dir,
            channel="msedge",
            headless=False,
            no_viewport=True,
            args=[
                "--start-maximized",
                "--disable-blink-features=AutomationControlled",
                "--disable-infobars",
                "--disable-extensions",
                "--disable-features=IsolateOrigins,site-per-process"
            ]
        )
        browser.add_init_script(
            """
            () => {
                Object.defineProperty(navigator, 'webdriver', { get: () => undefined, configurable: true });
                window.chrome = window.chrome || {};
                window.chrome.runtime = window.chrome.runtime || { OnInstalledReason: {}, OnRestartRequiredReason: {}, PlatformArch: {}, PlatformNaclArch: {}, PlatformOs: {}, RequestUpdateCheckStatus: {} };
                Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en-US', 'en'], configurable: true });
                Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5], configurable: true });
                const originalQuery = window.navigator.permissions.query;
                window.navigator.permissions.query = (parameters) => (parameters.name === 'notifications' ? Promise.resolve({ state: Notification.permission }) : originalQuery(parameters));
                const originalToString = Function.prototype.toString;
                Function.prototype.toString = function() { if (this === window.navigator.permissions.query) return 'function query() { [native code] }'; return originalToString.call(this); };
            }
            """
        )
        page = browser.pages[0] if browser.pages else browser.new_page()
        page.goto(URL, wait_until="domcontentloaded")
        log("元宝已打开")
        human_wait(2, 5)
        try:
            ensure_login(page)
            ready(page)
            log("已登录")
        except TimeoutError:
            log("登录超时，等待用户手动处理...")
        human_wait(1, 3)

    def _close_browser():
        nonlocal playwright_instance, browser, page
        if browser is not None:
            try: browser.close()
            except Exception as e: log(f"关闭浏览器异常: {e}")
            browser = None
        if playwright_instance is not None:
            try: playwright_instance.stop()
            except Exception as e: log(f"停止 playwright 异常: {e}")
            playwright_instance = None
        page = None

    def _get_page():
        return page

    worker_context = {
        "get_page": _get_page,
        "open_profile": _open_browser,
        "close_browser": _close_browser,
        "profile_base_dir": PROFILE_BASE,
    }

    log("开始进入账号池循环...")
    try:
        run_worker_loop("tencent", worker_context, handle_unit)  # 注意：后端平台名是 tencent
    except KeyboardInterrupt:
        log("收到中断信号，退出")
    finally:
        _close_browser()


if __name__ == "__main__":
    main()