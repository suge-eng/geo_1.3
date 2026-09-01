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
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def human_wait(a=1, b=3):
    time.sleep(random.randint(a, b))


def verify(page):
    try:
        text = page.locator("body").inner_text(timeout=2000).lower()
        return any(x in text for x in ("captcha", "安全验证", "请完成验证", "拖拽到这里"))
    except Exception:
        return False


def input_box(page):
    box = page.locator("#chat-textarea")
    box.wait_for(state="visible", timeout=30000)
    return box


def ready(page):
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
    for i in range(locator.count()):
        if locator.nth(i).is_visible():
            return locator.nth(i)
    return None


def logged_in(page):
    for text in ("请登录", "登录同步历史对话"):
        if first_visible(page.get_by_text(text, exact=True)):
            return False
    return first_visible(page.get_by_text("登录", exact=True)) is None


def ensure_login(page):
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
    value = page.evaluate(EXTRACT)
    return value if isinstance(value, dict) else None


def wait_answer(page, old_count):
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
    for attempt in range(2):
        box = new_chat(page)
       # enable_deep_thinking(page)
        old_count = page.evaluate(
            "() => [...document.querySelectorAll('.answer-container')]"
            ".filter(x => x.querySelector('.ai-entry')).length")
        box.fill(question)
        time.sleep(random.uniform(1.5, 3.5))
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
    """处理单个任务单元（1 问题 x 1 WenXin = 1 条 task_result）。由 worker 循环调用。"""
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


def main():
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
                "--no-sandbox",
                "--disable-setuid-sandbox"
            ]
        )

        if browser.pages:
            page = browser.pages[0]
        else:
            page = browser.new_page()

        page.goto(WENXIN_URL, wait_until="domcontentloaded", timeout=60000)
        log("文心一言 已打开")

        ensure_login(page)
        ready(page)
        log("已登录")

        log("开始从任务池认领单元...")
        try:
            run_worker_loop(PLATFORM_NAME, page, handle_unit)
        except KeyboardInterrupt:
            log("收到中断信号，退出")
        finally:
            browser.close()


if __name__ == "__main__":
    main()