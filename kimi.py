"""
kimi.py —— Kimi 平台的 RPA 自动化脚本，系统「工人」之一。

一、角色与整体流程（和 deepseek.py / doubao.py 完全一致）
--------------------------------------------------------
本脚本不是后端服务，而是一个跑在 Windows 机器上的 Python 进程：用 Playwright
驱动本机 Edge 浏览器登录 Kimi 网页版，然后不断从后端「任务池」认领「1 问题 ×
1 Kimi」的最小工作单元，让 AI 回答、截图、回调结果。核心是一个 run_worker_loop()
死循环：认领单元 -> handle_unit() 处理 -> 回调 -> 再认领下一个。

并发安全的保证全部在后端（租约 + 原子认领），本脚本不持有任何全局锁，所以多台
电脑跑同一个脚本也不会互相抢。

二、Kimi 平台特有的难点（也是本脚本最值得学习的设计）
------------------------------------------------------
1. 思考流程非一次性展开：Kimi 的「思考」是折叠的卡片，要先 detect 到思考区域再
   逐个点击展开（见 expand()），否则截图/提取会拿不到思考内容。
2. 回答「完成」判定难：Kimi 没有明显的「生成完成」标志，wait_answer() 用了一套
   「内容 + 停止按钮 + 操作栏 + 连续 N 秒不变」的启发式组合来判定生成结束，避免
   提前回调或永等。
3. 来源列表在侧边栏：extract_sources() 需要先点击「搜索网页」卡片，再在侧边栏
   （side-console-rail）里滚动拉取全部来源链接，并靠「滚动到底且连续几轮没新增」
   来判断已拉完。
4. 虚拟滚动 / 版式限制：shot() 同样用「克隆 DOM 到独立容器」的方式，保证长回答能
   截全，同时把「问题 + 回答 + 来源」拼成一整张干净的图。
"""

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

# ============================ 基础工具函数 ============================
# 下面这几个都是「原子小工具」，被上面的大流程反复调用。拆成小函数的目的是
# 减少重复代码、让每个函数只做一件事（单一职责），后面维护/调试时更好定位问题。

def log(msg):
    # 带时间戳的打印。flush=True 让日志立刻刷到控制台，不会被缓冲卡住，
    # 这样在 PowerShell 里实时看进度时不会「半天没输出」。
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def human_wait(a=1, b=3):
    # 随机等待 a~b 秒，模拟真人操作节奏，降低被 AI 平台反爬识别的概率。
    time.sleep(random.randint(a, b))


def first_visible(locator):
    # 从一个 Locator 集合里返回第一个「真正可见」的元素。
    # 设计原因：网页里经常有多个同名元素（比如好几个"登录"按钮），
    # 有的隐藏有的可见，只有可见的那个才是用户能点的。
    for i in range(locator.count()):
        item = locator.nth(i)
        if item.is_visible():
            return item
    return None


# ============================ 登录 / 就绪判断 ============================
# 思路：不主动强制登录，而是「检测是否已登录 -> 没有就点击登录入口并在浏览器里等
# 人工完成登录 -> 登录态持久化到本地 edge_kimi_profile 目录，下次免登录」。

def input_box(page):
    # Kimi 的输入框是一个 contenteditable 的富文本编辑器，不是普通 <textarea>。
    # 取 .last 的原因是页面上可能有多个编辑器节点，最后一个才是真正在用的。
    box = page.locator('.chat-input-editor[contenteditable="true"]').last
    box.wait_for(state="visible", timeout=30000)
    return box


def login_entry(page):
    # 用正则匹配「登录」或「登录/注册」这类入口文字。
    # 注意用 get_by_text + 正则，是应对入口文案可能微调（多一个词、多空格）的稳健写法。
    return first_visible(page.get_by_text(re.compile(r"^\s*(登录|登录/注册)\s*$")))


def ensure_login(page):
    # 登录态检测 + 人工兜底。若已登录（找不到"登录"入口）则直接返回；
    # 否则点击登录入口后，阻塞式等待用户在浏览器里手动扫码/输入完成登录。
    # 为什么等人工：登录通常涉及扫码/验证码，脚本自己无法可靠完成，交给人才最稳。
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
        # 登录成功的标志：登录入口消失，且能点到输入框。
        if login_entry(page) is None:
            try:
                input_box(page).click(trial=True, timeout=2000)
                log("账号登录成功，登录状态已保存")
                human_wait(1, 3)
                return
            except Exception:
                pass
        time.sleep(1)
    raise TimeoutError("等待 Kimi 登录超时")


def ready(page):
    # 阻塞到「输入框可点击」为止，作为页面彻底就绪的信号。
    # 用 click(trial=True) 是 Playwright 的「试探性点击」：只检查能否点到、不真正触发点击，
    # 用来判断元素是否已经可交互，又不产生副作用，非常合适做就绪探测。
    while True:
        try:
            box = input_box(page)
            box.click(trial=True, timeout=3000)
            return box
        except Exception:
            pass
    #    input("请完成 Kimi 登录/验证，确认输入框可用后按 Enter：")


def messages(page):
    # 拿到页面里所有 AI 回答节点的 id 列表（data-archer-id 是 Kimi 给每条回答的唯一标识）。
    # 后续靠「对比 old_ids 找出新出现的 id」来定位本次刚生成的回答。
    value = page.evaluate("""() => [...document.querySelectorAll(
      '.chat-content-item-assistant[data-archer-id]')].map(x => ({
        id:x.getAttribute('data-archer-id') || ''
      }))""")
    return value if isinstance(value, list) else []


def message_data(page, message_id):
    # 这是本脚本最核心的「内容提取器」：注入一段 JS，从某条回答的 DOM 里抽出
    # 回答正文、思考内容、思考标题、是否生成中、操作栏是否就绪等结构化信息。
    #
    # 为什么要把提取逻辑写成注入的 JS，而不是用 Playwright 的 Python API 逐个元素取？
    # 因为「回答正文」是 Markdown 渲染后的富文本，要干净地拿到内容、剔除按钮/脚本/
    # 样式等噪音、判断思考是否完成，这些在页面 JS 上下文里用 querySelector 一次做完
    # 又快又稳；用 Python 逐段 locator 会非常啰嗦且容易受版式变化影响。
    #
    # 返回值各字段含义：
    #   answer     : 剥离样式后的回答正文 HTML（多段用 \n\n 拼接）
    #   thinking   : 思考内容 HTML（多个思考卡片用 <hr> 分隔）
    #   thinkTitle : 思考卡片标题（用于判断思考是否完成）
    #   generating : 是否仍在生成（检测"停止生成"按钮是否存在）
    #   actionsReady: 底部操作栏（复制/点赞等）是否出现，作为回答已落定的辅助信号
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
    # 等待本次提问的回答生成完成。
    #
    # 核心思路：Kimi 没有显式的"生成完成"信号，所以用「多重信号 + 稳定判定」组合：
    #   1. 先找出新出现（不在 old_ids 里）的回答节点，锁定本次回答的 id；
    #   2. 对每条回答计算一个 signature（回答+思考+标题的拼接），连续 5 秒 signature
    #      都不变，说明内容已稳定（stable 计数）；
    #   3. 同时要求：回答有内容、没有"停止生成"按钮、操作栏已就绪——三者都满足才算完。
    #   4. 判定完成后，再等 1.5 秒二次复核，防止"刚好卡在稳定点"的误判。
    # 这个「稳定 N 秒 + 多条件」的写法是处理前端流式输出的通用套路。
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
    # 展开某条回答里所有折叠的「思考」卡片。
    # Kimi 的思考默认是收起状态（is-expanded 标记未加），不点开就拿不到思考正文。
    # 这里一次性把该回答下所有未展开的 thinking-container 逐一点开。
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
    # 提取回答引用的「搜索网页来源」。Kimi 的来源藏在侧边栏（.side-console-rail）。
    #
    # 流程：点击该回答里的"搜索网页"卡片打开侧边栏 -> 在侧边栏里不断向下滚动，边滚边
    # 抓 a.site[href] 链接（用 dict 按 url 去重）-> 当「滚动到底且连续 3 轮没新增链接」
    # 时停止。这个"滚到底 + 无新增"的终止条件是滚动加载类列表的通用判别法。
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
    # 给某个回答截图，采用「克隆 DOM 到独立容器再截图」的方案（和 doubao.py 同理）。
    #
    # 设计原因：Kimi 聊天页存在高度限制/虚拟滚动，直接整页截图有可能截不全长回答，
    # 也截不到右侧来源侧边栏。这里注入 JS，把「问题 + 回答（cloneNode 复制）+ 来源列表」
    # 拼进一个绝对定位、白底、强制展开所有折叠样式的容器 #kimi-shot 里，只对这个容器
    # 截图，得到既完整又干净的图。失败则退回整页截图兜底。
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
    # 发起一次提问并等待回答完成，返回结构化结果（含 id、answer、thinking 等）。
    #
    # 带 2 次重试：第 1 次若因 RuntimeError（比如中途弹验证码导致提取失败）失败，
    # 会调用 ready() 阻塞等人工处理，等待 20 秒后用新对话再试一次；第 2 次再失败就抛。
    # 这样设计是为了「偶发的验证码/页面抖动」不至于让整个单元直接失败，多一次机会。
    for attempt in range(2):
        try:
            page.goto(URL, wait_until="domcontentloaded", timeout=60000)
            time.sleep(random.uniform(2, 5))
            box = ready(page)
            old = {m["id"] for m in messages(page)}
            box.click(force=True)
            box.fill(question, force=True)
            time.sleep(random.uniform(2, 5))
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
    """处理单个任务单元（1 问题 x 1 Kimi = 1 条 task_result）。由 worker 循环调用。

    unit 是后端任务池派发来的最小工作项，含 taskNo / aiPlatform / questionText 等字段。

    流程：ask() 提问取结果 -> extract_sources() 拉来源 -> shot() 截图 -> 上传截图 ->
    worker_callback() 把结果回传给后端。

    异常处理设计（很重要）：
      无论成功失败，最终必须回调一次，把单元置为终态（SUCCESS / FAILED）。否则后端
      只能等租约超时才回收这个单元，白白多等。所以 try 里成功走 SUCCESS 回调，
      except 里兜底走 FAILED 回调——保证「每个认领到的单元一定有结局」。
    """
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

    # 模拟真人在开始处理前先"看一眼问题"的短暂停顿，避免操作节奏过于机械。
    human_wait(1, 3)

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
    finally:
        # 每个单元处理完后随机休息 40~90 秒，降低提问频率，避免触发风控。
        log("单元结束，随机休息中...")
        wait_time = random.randint(40, 90)
        log(f"等待 {wait_time} 秒后处理下一条...")
        time.sleep(wait_time)


def main():
    """Kimi Worker 进程入口（账号池模式）。

    改动点（相比旧版）：
      - 不再硬编码 PROFILE，而是账号池借号后按 account_id 建独立 profile 目录
      - run_worker_loop 从 (platform, page, handle_unit) 改为 (platform, worker_context, handle_unit)
      - worker_context 包含 open_profile / close_browser / get_page / profile_base_dir
      - 一个账号跑满 BATCH_SIZE（默认10）个问题后，自动关浏览器、释放账号、借下一个
    """
    log("启动 Kimi Worker（账号池模式）")
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
        log("Kimi 已打开")
        human_wait(2, 5)
        ready(page)
        log("已登录")
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
        run_worker_loop(PLATFORM_NAME, worker_context, handle_unit)
    except KeyboardInterrupt:
        log("收到中断信号，退出")
    finally:
        _close_browser()


if __name__ == "__main__":
    main()