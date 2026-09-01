import json
import os
import random
import re
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
SCREENSHOT_DIR = BASE_DIR / "kimi_screenshots"
PROFILE = BASE_DIR / "edge_kimi_profile"

PLATFORM_NAME = "kimi"

URL = "https://www.kimi.com/?chat_enter_method=new_chat"

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
    box = page.locator('.chat-input-editor[contenteditable="true"]').last
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
    log("请在打开的 Edge 窗口中完成 Kimi 登录，脚本正在等待……")
    end = time.time() + 600
    while time.time() < end:
        if login_entry(page) is None:
            try:
                input_box(page).click(trial=True, timeout=2000)
                log("账号登录成功，登录状态已保存")
                return
            except Exception:
                pass
        time.sleep(1)
    raise TimeoutError("等待 Kimi 登录超时")


def ready(page):
    while True:
        try:
            box = input_box(page)
            box.click(trial=True, timeout=3000)
            return box
        except Exception:
            pass
    #    input("请完成 Kimi 登录/验证，确认输入框可用后按 Enter：")


def messages(page):
    value = page.evaluate("""() => [...document.querySelectorAll(
      '.chat-content-item-assistant[data-archer-id]')].map(x => ({
        id:x.getAttribute('data-archer-id') || ''
      }))""")
    return value if isinstance(value, list) else []


def message_data(page, message_id):
    return page.evaluate(r"""id => {
      const clean=s=>(s||'').replace(/\r/g,'').replace(/\n[ \t]+/g,'\n').trim();
      const visible=e=>{
        if(!e)return false;
        const s=getComputedStyle(e), r=e.getBoundingClientRect();
        return s.display!=='none' && s.visibility!=='hidden' &&
          s.opacity!=='0' && r.width>0 && r.height>0;
      };
      const cleanHtml = (el) => {
        if (!el) return '';
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
      const root=[...document.querySelectorAll(
        '.chat-content-item-assistant[data-archer-id]')]
        .find(x=>x.getAttribute('data-archer-id')===id);
      if(!root)return null;
      const markdownEls=[...root.querySelectorAll('.markdown-container')]
        .filter(x=>!x.closest('.thinking-container'))
        .filter(x=>!x.parentElement?.closest('.markdown-container'));
      const htmlParts=markdownEls.map(x=>cleanHtml(x)).filter(Boolean);
      const textParts=markdownEls.map(x=>clean(x.innerText||x.textContent)).filter(Boolean);
      const thinkBoxes=[...root.querySelectorAll('.thinking-container')];
      const thoughts=thinkBoxes.map(box=>{
        const el=box.querySelector('.toolcall-content');
        const h=el?cleanHtml(el):'';
        const text=clean(el?.innerText||el?.textContent);
        if(h&&h.length>10)return h;
        if(text&&text.length>10)return'<div>'+text.replace(/\n/g,'<br>')+'</div>';
        return'';
      }).filter(Boolean);
      const thinkTitles=thinkBoxes.map(box=>clean(box.querySelector(
        '.toolcall-title-name-text,.toolcall-title-name')?.innerText)).filter(Boolean);
      const thinkTitle=thinkTitles.join(' / ');
      const thinkingDone=!thinkBoxes.length ||
        thinkTitles.some(x=>/思考.*(?:已完成|完成|结束)|已完成.*思考/.test(x));
      const candidates=[...document.querySelectorAll(
        '.stop-button-container,[class*="stop-button"],button,[role="button"],svg[name="Stop"]')];
      const generating=candidates.some(e=>{
        const hit=e.closest('button,[role="button"],.stop-button-container,[class*="stop-button"]')||e;
        if(!visible(hit))return false;
        const label=[e.getAttribute('aria-label'),e.getAttribute('title'),
          e.getAttribute('name'),e.textContent,String(e.className)].filter(Boolean).join(' ');
        return /停止生成|停止|stop/i.test(label);
      });
      const actionsContent=root.querySelector(
        '.segment-assistant-actions-content');
      const actionsReady=visible(actionsContent) &&
        actionsContent.querySelectorAll(
          '.icon-button,button,[role="button"],a').length>0;
      return {id,answer:htmlParts.join('\n\n') || textParts.join('\n\n'),
        thinking:thoughts.join('<hr>'),
        thinkTitle,thinkingDone,generating,
        actionsReady};
    }""", message_id)


def wait_answer(page, old_ids):
    end, last, stable, last_log = time.time() + 600, "", 0, 0
    data, last_error = {}, ""
    while time.time() < end:
        try:
            fresh = [x for x in messages(page)
                     if x["id"] and x["id"] not in old_ids]
            if fresh:
                message_id = fresh[-1]["id"]
                data = message_data(page, message_id) or {}
                answer = data.get("answer", "")
                signature = "\n".join((answer, data.get("thinking", ""),
                                       data.get("thinkTitle", "")))
                stable = stable + 1 if signature and signature == last else 0
                last = signature
                ready_flag = (len(answer) >= 2 and not data.get("generating") and
                         data.get("actionsReady") and stable >= 5)
                if ready_flag:
                    page.wait_for_timeout(1500)
                    check = message_data(page, message_id) or {}
                    check_signature = "\n".join((check.get("answer", ""),
                        check.get("thinking", ""), check.get("thinkTitle", "")))
                    if (check_signature == signature and
                            not check.get("generating") and
                            check.get("actionsReady")):
                        log("思考和回答均已完成")
                        return check
                    last, stable = check_signature, 0
            last_error = ""
        except Exception as exc:
            last_error = str(exc)
            stable = 0
        if time.time() - last_log >= 10:
            log(f"等待完成：回答 {len(data.get('answer', ''))} 字，"
                f"思考 {len(data.get('thinking', ''))} 字，"
                f"状态 {data.get('thinkTitle') or '生成中'}"
                + (f"，DOM重试：{last_error}" if last_error else ""))
            last_log = time.time()
        time.sleep(1)
    raise TimeoutError("等待 Kimi 回答超时" +
                       (f"；最后错误：{last_error}" if last_error else ""))


def expand(page, message_id):
    clicked = page.evaluate("""id => {
      const root=[...document.querySelectorAll(
        '.chat-content-item-assistant[data-archer-id]')]
        .find(x=>x.getAttribute('data-archer-id')===id);
      let count=0;
      for(const box of root?.querySelectorAll('.thinking-container')||[]){
        if(box.classList.contains('is-expanded'))continue;
        box.querySelector('.toolcall-title-container')?.click(); count++;
      }
      return count;
    }""", message_id)
    if clicked:
        log("已展开思考区域")
        page.wait_for_timeout(600)


def extract_sources(page, message_id):
    opened = page.evaluate("""id => {
      const root=[...document.querySelectorAll(
        '.chat-content-item-assistant[data-archer-id]')]
        .find(x=>x.getAttribute('data-archer-id')===id);
      const card=root?.querySelector('.toolcall-web_search');
      if(!card)return false;
      (card.querySelector('.toolcall-title-container')||card).click();
      return true;
    }""", message_id)
    if not opened:
        return []
    try:
        page.locator(
            '.side-console-rail.open .search-site-tool-content a.site[href]'
        ).first.wait_for(state="attached", timeout=10000)
    except Exception:
        return []

    found, unchanged = {}, 0
    for _ in range(100):
        state = page.evaluate(r"""() => {
          const clean=s=>(s||'').replace(/\r/g,'').trim();
          const rail=document.querySelector('.side-console-rail.open');
          const scroll=rail?.querySelector('.tool-content.webSearch')||
            rail?.querySelector('.content');
          const links=[...rail?.querySelectorAll(
            '.search-site-tool-content a.site[href]')||[]].map(a=>{
              const lines=clean(a.innerText||a.textContent).split(/\n+/)
                .map(x=>x.trim()).filter(Boolean);
              const named=[...a.querySelectorAll('*')].find(e=>{
                const c=typeof e.className==='string'?e.className:'';
                const t=clean(e.innerText||e.textContent);
                return /(^|[-_])(title|name)([-_]|$)/i.test(c)&&t.length>3&&t.length<300;
              });
              const title=clean(named?.innerText)||lines.find(x=>
                !/\b[\w.-]+\.(com|cn|org|gov|net|edu|ai)\b/i.test(x)&&
                !/^\d{4}[\/-]\d{1,2}[\/-]\d{1,2}$/.test(x))||lines[0]||a.href;
              return {title,url:a.href};
            });
          if(!scroll)return {links,bottom:true,top:0,height:0};
          const before=scroll.scrollTop;
          scroll.scrollTop=Math.min(scroll.scrollHeight,
            before+Math.max(400,scroll.clientHeight*.8));
          return {links,bottom:scroll.scrollTop+scroll.clientHeight>=scroll.scrollHeight-5,
            top:scroll.scrollTop,height:scroll.scrollHeight};
        }""")
        before = len(found)
        for item in state.get("links", []):
            if item.get("url"):
                found[item["url"]] = item.get("title") or item["url"]
        unchanged = unchanged + 1 if len(found) == before else 0
        if state.get("bottom") and unchanged >= 3:
            break
        page.wait_for_timeout(300)
    result = [{"title": title, "url": url} for url, title in found.items()]
    log(f"提取到 {len(result)} 条搜索网页")
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


def shot(page, message_id, save_path, question="", sources=None):
    sources = sources or []
    try:
        page.evaluate("""arg => {
          document.querySelector('#kimi-shot')?.remove();
          const root=[...document.querySelectorAll(
            '.chat-content-item-assistant[data-archer-id]')]
            .find(x=>x.getAttribute('data-archer-id')===arg.id);
          if(!root)return;
          const box=document.createElement('div');box.id='kimi-shot';
          box.innerHTML='<style>'+`
            #kimi-shot{position:absolute;left:0;top:0;z-index:2147483647;width:1320px;
              padding:32px 44px;background:#fff;color:#171717;box-sizing:border-box;
              font:15px/1.65 Arial,"Microsoft YaHei",sans-serif}
            #kimi-shot .kimi-layout{display:grid;grid-template-columns:minmax(0,1fr) 380px;gap:30px}
            #kimi-shot .kimi-main{min-width:0}
            #kimi-shot .kimi-side{border-left:1px solid #ddd;padding-left:22px}
            #kimi-shot .kimi-side h2{font-size:18px;margin:0 0 16px}
            #kimi-shot .kimi-source{padding:0 0 14px;margin:0 0 14px;border-bottom:1px solid #eee}
            #kimi-shot .kimi-source-title{font-weight:600;overflow-wrap:anywhere}
            #kimi-shot .kimi-source-url{font-size:12px;color:#777;overflow-wrap:anywhere}
            #kimi-shot,#kimi-shot *{max-height:none!important;overflow:visible!important}
            #kimi-shot .chat-content-item{width:100%!important;max-width:none!important;
              height:auto!important;margin:0!important;position:relative!important;transform:none!important}
            #kimi-shot .thinking-container,#kimi-shot .toolcall-content,
            #kimi-shot .resize-container,#kimi-shot .slot-container{display:block!important;
              height:auto!important;opacity:1!important;transform:none!important}
            #kimi-shot .segment-assistant-actions,#kimi-shot .table-actions{display:none!important}
            #kimi-shot .kimi-question{margin:0 0 24px auto;max-width:70%;width:max-content;
              padding:11px 17px;border-radius:14px;background:#edf3ff;font-size:16px}
          `+'</style>';
          const q=document.createElement('div');q.className='kimi-question';q.textContent=arg.question;
          const layout=document.createElement('div');layout.className='kimi-layout';
          const main=document.createElement('div');main.className='kimi-main';
          main.append(q,root.cloneNode(true));layout.appendChild(main);
          const side=document.createElement('div');side.className='kimi-side';
          const h=document.createElement('h2');h.textContent=`搜索网页（${arg.sources.length}）`;side.appendChild(h);
          arg.sources.forEach((s,i)=>{
            const item=document.createElement('div');item.className='kimi-source';
            const title=document.createElement('div');title.className='kimi-source-title';
            title.textContent=`${i+1}. ${s.title}`;
            const url=document.createElement('div');url.className='kimi-source-url';url.textContent=s.url;
            item.append(title,url);side.appendChild(item);
          });
          layout.appendChild(side);box.appendChild(layout);document.body.appendChild(box);
        }""", {"id": message_id, "question": question, "sources": sources})
        page.wait_for_timeout(500)
        shot_loc = page.locator("#kimi-shot")
        shot_loc.wait_for(state="visible", timeout=5000)
        shot_loc.screenshot(path=str(save_path), animations="disabled")
    except Exception as e:
        log(f"克隆截图失败，退回整页截图：{e}")
        page.screenshot(path=str(save_path), full_page=True)
    finally:
        try:
            page.evaluate("() => document.querySelector('#kimi-shot')?.remove()")
        except Exception:
            pass
    log(f"截图：{save_path}")


def ask(page, question):
    for attempt in range(2):
        try:
            page.goto(URL, wait_until="domcontentloaded", timeout=60000)
            time.sleep(random.uniform(2, 5))
            box = ready(page)
            old = {m["id"] for m in messages(page)}
            box.fill(question)
            time.sleep(random.uniform(1, 2))
            send = page.locator(".send-button-container").last
            send.wait_for(state="visible", timeout=10000)
            page.wait_for_function(
                "() => !document.querySelector('.send-button-container')?.classList.contains('disabled')",
                timeout=10000)
            send.click(force=True)
            log("问题已发送")
            result = wait_answer(page, old)
            if not result:
                raise RuntimeError("没有提取到回答")
            expand(page, result["id"])
            page.wait_for_timeout(800)
            return message_data(page, result["id"]) or result
        except RuntimeError as e:
            if attempt == 1:
                raise
            log(f"{e}，请完成验证后重试当前问题")
            ready(page)
            time.sleep(20)
    raise RuntimeError("回答失败")


def handle_unit(page, unit):
    """处理单个任务单元（1 问题 x 1 Kimi = 1 条 task_result）。由 worker 循环调用。"""
    task_no = str(unit.get("taskNo"))
    agent_name = unit.get("aiPlatform") or "kimi"
    question = str(unit.get("questionText") or "")

    log(f"处理问题: {question[:50]}...")
    output_dir = os.path.join(str(SCREENSHOT_DIR), task_no)
    os.makedirs(output_dir, exist_ok=True)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    screenshot_file = os.path.join(output_dir, f"{task_no}_{timestamp}.png")

    thinking = ""
    answer = ""
    sources = []
    image_url = None

    try:
        result = ask(page, question)
        answer = result["answer"].strip()
        thinking = result["thinking"].strip()
        sources = extract_sources(page, result["id"])
        source_pairs = [[s.get("title", ""), s.get("url", "")] for s in sources]
        source_info = json.dumps(source_pairs, ensure_ascii=False) if source_pairs else ""

        shot(page, result["id"], screenshot_file, question, sources)

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
        log(f"问题处理完成")

    except Exception as e:
        log(f"问题处理失败: {e}")
        source_info = json.dumps(sources, ensure_ascii=False) if sources else ""
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
    log("启动 Kimi Worker（认领模式，无全局锁）")
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

        page.goto(URL, wait_until="domcontentloaded")
        log("Kimi 已打开")

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