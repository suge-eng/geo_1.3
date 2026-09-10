"""
wenxin.py —— 文心一言（WenXin / 百度）平台的 RPA 自动化脚本，系统「工人」之一。

一、角色与整体流程（与 deepseek.py / kimi.py / qianwen.py / doubao.py 一致）
--------------------------------------------------------------------------
用 Playwright 驱动本机 Edge 登录文心一言网页版，从后端「任务池」认领「1 问题 ×
1 文心」的最小单元，让 AI 回答、截图、回调。并发安全全部由后端（租约 + 原子认领）
保证，脚本自身无全局锁，可多机并行。

二、文心平台特有的难点（本脚本最值得学习的设计）
------------------------------------------------
1. 验证码检测：文心偶尔弹「安全验证」，verify() 通过抓页面正文关键词（captcha / 安全
   验证 / 请完成验证 / 拖拽到这里）来判断是否命中；一旦命中，ready() 就停下来等人工
   处理（对应后端"卡住发邮件通知"那一套）。
2. 深度思考开关：answer 前需要开启「深度思考」（enable_deep_thinking()），是否已开启
   靠按钮 class 里有没有 ci-model-button-active 来判定。
3. 内容提取统一用一段脚本：把「提取回答 + 思考 + 来源 + 是否完成」都写进模块级常量
   EXTRACT（一段注入 JS），latest() 只需调用它，避免相同 JS 在多处重复；这也是「把
   稳定不变的公共逻辑抽成常量」的写法。
4. 截图用「克隆 DOM」：full_screenshot() 把问答内容克隆到 #wenxin-shot 独立容器再截图，
   绕开长回答被截断的问题。
"""

import json
import os
import random
import time
from datetime import datetime
from pathlib import Path

from playwright.sync_api import sync_playwright

from worker_lib import (
    upload_screenshot as worker_upload,
    run_worker_loop,
    callback as worker_callback,
)

BASE_DIR = Path(__file__).resolve().parent
SCREENSHOT_DIR = BASE_DIR / "wenxin_screenshots"
PROFILE = BASE_DIR / "edge_wenxin_profile"

PLATFORM_NAME = "wenxin"

WENXIN_URL = "https://wenxin.baidu.com/"

SCREENSHOT_DIR.mkdir(exist_ok=True)


# EXTRACT 是一段「注入页面执行的 JS」，负责从最后一条回答里一次性提取：
#   answer   —— 回答正文 HTML
#   thinking —— 思考内容 HTML
#   sources  —— 来源链接列表（含标题 url）
#   complete —— 回答是否完成（靠 .answer-menu[data-status="COMPLETE"] 判定）
#   index    —— 这是第几条回答（用来和发送前数量做比较，判断是否生成了新回答）
# 把它定义成模块级常量、供 latest() 反复调用，是「公共稳定逻辑抽离复用」的做法：
# 既避免在多个函数里复制同一大段 JS，也让这块最易随网页改版而变动的逻辑集中在一处维护。
EXTRACT = r"""() => {
  const cleanText=s=>(s||'').replace(/\r/g,'').replace(/\n[ \t]+/g,'\n').trim();
  const cleanHtml = (el) => {
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
  const roots=[...document.querySelectorAll('.answer-container')]
    .filter(x=>x.querySelector('.ai-entry'));
  const root=roots.at(-1); if(!root)return null;
  const blocks=[...root.querySelectorAll('.ai-entry-block.ai-markdown')];
  let answer='';
  if(blocks.length>0){
    const wrapper=document.createElement('div');
    blocks.forEach(b=>wrapper.appendChild(b.cloneNode(true)));
    answer=cleanHtml(wrapper);
    if(!answer||answer.length<50){
      answer=blocks.map(x=>cleanText(x.innerText)).filter(Boolean).join('\n\n');
    }
  }
  const thought=root.querySelector('.ai-entry-block.ai-thinking-steps');
  let thinking='';
  if(thought){
    const h=cleanHtml(thought);
    const text=cleanText(thought.innerText||thought.textContent);
    if(h&&h.length>10){
      thinking=h;
    }else if(text&&text.length>10){
      thinking='<div>'+text.replace(/\n/g,'<br>')+'</div>';
    }
  }
  const sources=[],seen=new Set();
  for(const li of root.querySelectorAll('li[data-long-press-ext-info]')){
    try{
      const d=JSON.parse(li.getAttribute('data-long-press-ext-info')||'{}');
      const url=(d.link||'').trim(),title=(d.linkTitle||li.innerText||'').trim();
      if(/^https?:\/\//.test(url)&&!seen.has(url)){seen.add(url);sources.push({title,url})}
    }catch(_){}
  }
  for(const a of root.querySelectorAll('a[href]')){
    const url=(a.href||'').trim(),title=cleanText(a.innerText||a.textContent);
    if(/^https?:\/\//.test(url)&&title&&!seen.has(url)&&!url.includes('wenxin.baidu.com')){
      seen.add(url);sources.push({title,url});
    }
  }
  const complete=!!root.parentElement?.querySelector('.answer-menu[data-status="COMPLETE"]');
  return {answer,thinking,sources,complete,index:roots.length-1};
}"""


def log(msg):
    # 带时间戳打印，flush=True 实时刷控制台。
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def human_wait(a=1, b=3):
    # 随机等待，模拟真人节奏、降低反爬识别。
    time.sleep(random.randint(a, b))


def verify(page):
    # 检测页面是否出现验证码/安全验证。做法是抓整个 body 的文本转小写，看是否命中
    # captcha、安全验证、请完成验证、拖拽到这里 等关键词。这是"验证码识别"的简单版：
    # 不识别验证码内容本身，只判断"现在是不是卡在验证页"，命中就把控制权交回给人。
    try:
        text = page.locator("body").inner_text(timeout=2000).lower()
        return any(x in text for x in ("captcha", "安全验证", "请完成验证", "拖拽到这里"))
    except Exception:
        return False


def input_box(page):
    # 文心的输入框是固定 id 的 #chat-textarea，直接等它可见。
    box = page.locator("#chat-textarea")
    box.wait_for(state="visible", timeout=30000)
    return box


def ready(page):
    # 就绪判断 + 验证码人工兜底。
    # 循环里：只要没验证码且能点到输入框就返回；否则用 input() 阻塞等人工处理。
    # 这体现"遇到 AI 平台验证码时不傻等、把决定权交回给人"的设计（后端配套有卡住邮件通知）。
    while True:
        if not verify(page):
            try:
                box = input_box(page)
                box.click(trial=True, timeout=3000)
                return box
            except Exception:
                pass
        input("请完成文心一言登录/验证，确认输入框可用后按 Enter：")


def first_visible(locator):
    # 返回集合里第一个可见元素。
    for i in range(locator.count()):
        if locator.nth(i).is_visible():
            return locator.nth(i)
    return None


def logged_in(page):
    # 判断是否已登录：既没有"请登录/登录同步历史对话"这类未登录提示，也找不到"登录"按钮，
    # 就认为已登录。用"排除法"判断登录态，比正向找登录成功标志更稳。
    for text in ("请登录", "登录同步历史对话"):
        if first_visible(page.get_by_text(text, exact=True)):
            return False
    return first_visible(page.get_by_text("登录", exact=True)) is None


def ensure_login(page):
    # 登录态检测 + 人工兜底。已登录直接返回；否则点登录入口后阻塞等人工登录完成。
    page.wait_for_timeout(1200)
    if logged_in(page):
        log("账号已登录，复用保存的登录状态")
        return
    entry = first_visible(page.get_by_text("请登录", exact=True))
    if entry is None:
        entry = first_visible(page.get_by_text("登录", exact=True))
    if entry is not None:
        entry.click(force=True)
    log("请在打开的 Edge 中完成文心一言登录，脚本正在等待……")
    end = time.time() + 600
    while time.time() < end:
        if logged_in(page):
            page.wait_for_timeout(1500)
            log("账号登录成功，登录状态已保存")
            return
        time.sleep(1)
    raise TimeoutError("等待账号登录超时")


def enable_deep_thinking(page):
    # 开启「深度思考」。判断依据是按钮 class 里含 ci-model-button-active、或 data-ci-show-ext
    # 里的 "is_open":"1"；没开启就点一下，点完再校验是否真的变成 active，防止"点了没生效"。
    button = page.locator(".ci-model-button:has(.deep-search-text)").last
    button.wait_for(state="visible", timeout=15000)
    cls = button.get_attribute("class") or ""
    state = button.get_attribute("data-ci-show-ext") or ""
    if "ci-model-button-active" in cls or '"is_open":"1"' in state:
        log("深度思考已开启")
        return
    button.click(force=True)
    page.wait_for_timeout(600)
    if "ci-model-button-active" not in (button.get_attribute("class") or ""):
        raise RuntimeError("深度思考未成功开启")
    log("已开启深度思考")


def new_chat(page):
    # 构建一个"干净可用"的对话环境：跳转首页 -> 确保登录 -> 等输入框就绪 ->
    # 若有"开启新对话"按钮就点一下清空上下文 -> 再等输入框，返回输入框。
    page.goto(WENXIN_URL, wait_until="domcontentloaded", timeout=60000)
    ensure_login(page)
    box = ready(page)
    button = page.get_by_text("开启新对话", exact=True)
    if button.count() and button.first.is_visible():
        button.first.click()
        page.wait_for_timeout(800)
        box = ready(page)
    return box


def latest(page):
    # 执行 EXTRACT 脚本，得到最后一条回答的结构化结果（dict）或 None。
    # 抽取逻辑集中在 EXTRACT，这里只负责调用和类型安全兜底。
    value = page.evaluate(EXTRACT)
    return value if isinstance(value, dict) else None


def wait_answer(page, old_count):
    # 等待回答生成完成。
    #
    # 思路：先看有没有验证页（有就直接抛给上层走重试）；再取最新回答，只有当它出现在
    # 发送之后（index+1 > old_count，old_count 是发送前已有回答数）才继续处理。
    # 完成条件：EXTRACT 里的 complete 标记为真 且 文本够长；或者文本连续 10 秒不变（stable
    # 计数）也算稳定完成——双保险，避免某些情况 complete 标记不出现而永久等待。
    end, last, stable = time.time() + 360, "", 0
    while time.time() < end:
        if verify(page):
            raise RuntimeError("出现验证页面")
        result = latest(page)
        if result and result["index"] + 1 > old_count:
            text = result["answer"].strip()
            if text == last and text:
                stable += 1
            else:
                last, stable = text, 0
            if result["complete"] and len(text) >= 10:
                log("回答已完成")
                return result
            if stable >= 10 and len(text) >= 10:
                log("回答内容已稳定")
                return result
        time.sleep(1)
    raise TimeoutError("等待文心一言回答超时")


def ask(page, question):
    # 发起一次提问并等待回答，带 2 次重试（和 kimi.py 的 ask 同理）。
    # 说明：文心的深度思考默认是持久化的（一旦开启后续都生效），所以这里 enable_deep_
    # thinking 被注释掉了，只保留开启入口供必要时启用。这样既保留能力又不重复开关。
    for attempt in range(2):
        box = new_chat(page)
       # enable_deep_thinking(page)
        old_count = page.evaluate(
            "() => [...document.querySelectorAll('.answer-container')]"
            ".filter(x => x.querySelector('.ai-entry')).length")
        box.click(force=True)
        box.fill(question, force=True)
        time.sleep(random.uniform(2, 5))
        send = page.locator("#ci-submit-button-ai")
        send.wait_for(state="visible", timeout=10000)
        send.click(force=True)
        log("问题已发送")
        try:
            return wait_answer(page, old_count)
        except RuntimeError as e:
            if attempt == 1:
                raise
            log(f"{e}，请完成验证后重试当前问题")
            ready(page)
            time.sleep(20)
    raise RuntimeError("回答失败")


def full_screenshot(page, path, question):
    # 长截图，用「克隆 DOM」方案（和 kimi/doubao 同理）：把问题 + 回答克隆进独立的
    # #wenxin-shot 容器（白底、强制展开折叠/吸顶样式），只对这个容器截图，得到干净完整
    # 的长图；失败则退回整页截图兜底。
    log(f"长截图:{path}")
    try:
        page.evaluate(
            """question => {
              document.querySelector('#wenxin-shot')?.remove();
              const roots=[...document.querySelectorAll('.answer-container')]
                .filter(x=>x.querySelector('.ai-entry'));
              const answer=roots.at(-1); if(!answer)return;
              const box=document.createElement('div'); box.id='wenxin-shot';
              box.style.cssText='position:absolute;left:0;top:0;z-index:2147483647;'+
                'width:900px;padding:30px 42px;background:#fff;color:#111;box-sizing:border-box;';
              const style=document.createElement('style');
              style.textContent=`
                #wenxin-shot,#wenxin-shot *{max-height:none!important;overflow:visible!important}
                #wenxin-shot .answer-container{width:100%!important;margin:0!important}
                #wenxin-shot .ai-thinking-steps main{max-height:none!important;height:auto!important}
                #wenxin-shot .answer-menu,#wenxin-shot [class*="selection-bar"]{display:none!important}
                #wenxin-shot [class*="sticky"],#wenxin-shot [class*="fixed"]{position:relative!important;inset:auto!important}
                #wenxin-shot-question{margin:0 0 24px auto;padding:12px 18px;max-width:70%;width:max-content;
                  border-radius:14px;background:#eaf3ff;font-size:17px;line-height:1.6}
              `;
              const q=document.createElement('div');q.id='wenxin-shot-question';q.textContent=question;
              box.append(style,q,answer.cloneNode(true));document.body.appendChild(box);
            }""",
            question,
        )
        page.wait_for_timeout(700)
        shot = page.locator("#wenxin-shot")
        shot.wait_for(state="visible", timeout=5000)
        shot.screenshot(path=str(path), animations="disabled")
    except Exception as e:
        log(f"独立截图失败，改用整页截图：{e}")
        page.screenshot(path=str(path), full_page=True)
    finally:
        page.evaluate("() => document.querySelector('#wenxin-shot')?.remove()")
    log(f"截图完成：{path}")
    return True


def handle_unit(page, unit):
    """处理单个任务单元（1 问题 x 1 WenXin = 1 条 task_result）。由 worker 循环调用。

    流程：ask() 提问取结果 -> 组装 answer/thinking/sources -> full_screenshot() 截图
    -> 上传截图 -> worker_callback() 回调结果。

    异常处理设计（重要）：无论成功失败都必须在 finally 语义下回调一次置终态，否则后端
    只能等租约超时才回收单元。try 走 SUCCESS，except 走 FAILED。
    """
    task_no = str(unit.get("taskNo"))
    agent_name = unit.get("aiPlatform") or "wenxin"
    question = str(unit.get("questionText") or "")

    output_dir = os.path.join(str(SCREENSHOT_DIR), task_no)
    os.makedirs(output_dir, exist_ok=True)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    screenshot_file = os.path.join(output_dir, f"{task_no}_{agent_name}_{timestamp}.png")

    thinking = ""
    answer = ""
    sources = []
    image_url = None

    # 模拟真人在开始处理前先"看一眼问题"的短暂停顿，避免操作节奏过于机械。
    human_wait(1, 3)

    try:
        result = ask(page, question)

        answer = result["answer"].strip()
        thinking = result["thinking"].strip()
        sources = result.get("sources") or []

        source_pairs = [[s.get("title", ""), s.get("url", "")] for s in sources]
        source_info = json.dumps(source_pairs, ensure_ascii=False) if source_pairs else ""

        success = full_screenshot(page, screenshot_file, question)
        if not success or not os.path.exists(screenshot_file):
            raise Exception("截图失败")

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
        source_info = json.dumps([[s.get("title", ""), s.get("url", "")] for s in sources], ensure_ascii=False) if sources else ""
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
    # 入口：启动浏览器 -> 登录 -> 进入认领循环。细节同其它平台脚本：
    # launch_persistent_context 持久化登录态；--disable-blink-features=AutomationControlled
    # 隐藏自动化特征；run_worker_loop 是 worker_lib 提供的认领主循环。
    log("启动 WenXin Worker（认领模式，无全局锁）")
    log("打开浏览器...")
    with sync_playwright() as p:
        browser = p.chromium.launch_persistent_context(
            user_data_dir=str(PROFILE),
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

        # 注入反检测脚本：覆盖常见自动化探测点。
        browser.add_init_script(
            """
            () => {
                Object.defineProperty(navigator, 'webdriver', {
                    get: () => undefined,
                    configurable: true
                });
                window.chrome = window.chrome || {};
                window.chrome.runtime = window.chrome.runtime || {
                    OnInstalledReason: {},
                    OnRestartRequiredReason: {},
                    PlatformArch: {},
                    PlatformNaclArch: {},
                    PlatformOs: {},
                    RequestUpdateCheckStatus: {}
                };
                Object.defineProperty(navigator, 'languages', {
                    get: () => ['zh-CN', 'zh', 'en-US', 'en'],
                    configurable: true
                });
                Object.defineProperty(navigator, 'plugins', {
                    get: () => [1, 2, 3, 4, 5],
                    configurable: true
                });
                const originalQuery = window.navigator.permissions.query;
                window.navigator.permissions.query = (parameters) => (
                    parameters.name === 'notifications'
                        ? Promise.resolve({ state: Notification.permission })
                        : originalQuery(parameters)
                );
                const originalToString = Function.prototype.toString;
                Function.prototype.toString = function() {
                    if (this === window.navigator.permissions.query) {
                        return 'function query() { [native code] }';
                    }
                    return originalToString.call(this);
                };
            }
            """
        )

        if browser.pages:
            page = browser.pages[0]
        else:
            page = browser.new_page()

        page.goto(WENXIN_URL, wait_until="domcontentloaded", timeout=60000)
        log("文心一言 已打开")
        human_wait(2, 5)

        ensure_login(page)
        ready(page)
        log("已登录")
        human_wait(1, 3)

        # 开始从任务池认领单元，循环处理。
        log("开始从任务池认领单元...")
        try:
            run_worker_loop(PLATFORM_NAME, page, handle_unit)
        except KeyboardInterrupt:
            log("收到中断信号，退出")
        finally:
            browser.close()


if __name__ == "__main__":
    main()