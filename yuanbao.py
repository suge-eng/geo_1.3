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
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def human_wait(a=1, b=3):
    time.sleep(random.randint(a, b))


def first_visible(locator):
    for i in range(locator.count()):
        item = locator.nth(i)
        if item.is_visible():
            return item
    return None


def input_box(page):
    box = page.locator('.ql-editor[contenteditable="true"]').last
    box.wait_for(state="visible", timeout=30000)
    return box


def login_entry(page):
    return first_visible(page.get_by_text(re.compile(r"^\s*(登录|登录/注册)\s*$")))


def ensure_login(page):
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
    page.goto(URL, wait_until="domcontentloaded", timeout=60000)
    ensure_login(page)
    return ready(page)


def enable_deep_thinking(page):
    button = page.locator('[dt-button-id="deep_think"][aria-label="深度思考"]').last
    button.wait_for(state="visible", timeout=20000)
    if "ThinkSelector_selected" in (button.get_attribute("class") or ""):
        log("深度思考已开启")
        return
    button.click(force=True)
    page.wait_for_timeout(500)
    if "ThinkSelector_selected" not in (button.get_attribute("class") or ""):
        raise RuntimeError("深度思考未能开启")
    log("已开启深度思考")


def ai_messages(page):
    return page.evaluate("""() => [...document.querySelectorAll(
      '.agent-chat__list__item--ai[data-conv-id]')].map(x => ({
        id:x.dataset.convId || '', done:x.dataset.convStatus === 'finished' &&
          x.dataset.convOutputting === 'false'
      }))""")


def message_data(page, conv_id):
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
    box = new_chat(page)
    enable_deep_thinking(page)
    old_ids = {x["id"] for x in ai_messages(page)}
    box.fill(question)
    time.sleep(random.uniform(1.0, 2.0))
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
    """处理单个任务单元（1 问题 x 1 Yuanbao = 1 条 task_result）。由 worker 循环调用。"""
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


def main():
    log("启动 Yuanbao Worker（认领模式，无全局锁）")
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

        page = browser.pages[0] if browser.pages else browser.new_page()

        page.goto(URL, wait_until="domcontentloaded")
        log("元宝已打开")

        try:
            ensure_login(page)
            ready(page)
        except TimeoutError:
            log("无法登录，退出")
            browser.close()
            return

        log("开始从任务池认领单元...")
        try:
            run_worker_loop("tencent", page, handle_unit)
        except KeyboardInterrupt:
            log("收到中断信号，退出")
        finally:
            browser.close()


if __name__ == "__main__":
    main()