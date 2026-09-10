"""
doubao.py —— 豆包（Doubao）平台的 RPA 自动化脚本，系统「工人」之一。

角色与流程和 deepseek.py 完全一致：用 Playwright 驱动 Edge 登录豆包网页版，
然后不断从后端任务池认领「1 问题 × 1 豆包」的最小单元，让 AI 回答、截图、回调。
区别只在于「豆包这个网页具体怎么操作、怎么取内容」。

豆包相比 DeepSeek 多出三个难点（也正是本脚本最有价值的设计）：

1. 反爬 / 验证码页：豆包经常弹安全验证。verify() 负责检测验证码；ready() 检测到后
   会调用 input() 暂停，等人工在浏览器里完成验证、按 Enter 再继续——
   这就是「卡住时不傻等、把决定权交回给人」的解法（对应后端的卡住邮件通知）。

2. 虚拟滚动列表：豆包的聊天记录是虚拟滚动渲染，直接 full_page 截图截不到完整内容，
   所以 shot() 采用「把问题 + 回答的 DOM 克隆到一个新容器里再截图」的办法绕开。

3. 思考内容识别：豆包把思考过程、搜索结果、正文揉在同一个消息块里，MESSAGES 里
   用一大段注入 JS 做启发式分离，分别归类为 answer / thinking / sources。
"""
import json
import os
import random
import time
from datetime import datetime
from pathlib import Path

from playwright.sync_api import sync_playwright

from worker_lib import (
    log,
    upload_screenshot as worker_upload,
    run_worker_loop,
    callback as worker_callback,
)

BASE_DIR = Path(__file__).resolve().parent
SCREENSHOT_DIR = BASE_DIR / "doubao_screenshots"
PROFILE = BASE_DIR / "edge_doubao_profile"

PLATFORM_NAME = "doubao"

URL = "https://www.doubao.com/chat?channel=xiazai"
PLACEHOLDER = "发消息或按住空格说话..."

SCREENSHOT_DIR.mkdir(exist_ok=True)

# 一段注入到页面的 JS，作用是把「一个 AI 回答消息块」拆解成结构化的三部分：
#   answer(正文) / thinking(思考过程 + 搜索引用) / sources(引用的网页来源)。
# 之所以写这么长，是因为豆包的 DOM 没有稳定 class 可依，只能靠启发式规则：
#   先定位正文容器(md-box-root 等)，再在正文"之前"的兄弟节点里找思考块，去重、
#   剔除"搜索 N 个关键词"这类摘要行，最后从 tool-call 链接里摘出来源 URL。
# 每个步骤都带"找不到就跳过/置空"的兜底，网页改版后顶多是某部分为空，不会崩。
MESSAGES = r"""() => [...document.querySelectorAll('[data-message-id]')].map(m => {
  const bodySelectors=['.md-box-root','[class*="md-box-root"]','[class*="prose"]','[class*="markdown"]','[class*="answer"]','.ProseMirror','[data-message-body]'];
  let body=null; for(const s of bodySelectors){body=m.querySelector(s);if(body&&(body.innerText||'').trim())break}
  const cls=m.className||'';
  const isUser=!!m.querySelector('[class*="send-msg-bubble"],[class*="user-bubble"]')||cls.includes('justify-end')||cls.includes('items-end')||!!m.querySelector('[class*="bg-"][class*="primary"]');
  if(!body||isUser)return null;
  const clean=s=>(s||'').replace(/\r/g,'').trim();
  const stripAttrs=html=>(html||'').replace(/\s+(?:data-[\w-]+|style|class|id|role|tabindex|translate|contenteditable)="[^"]*"/g,' ').replace(/>\s+</g,'><').trim();
  const answerText=clean(body.innerText);

  const isBeforeBody=(el)=>el!==body&&!body.contains(el)&&!el.contains(body)&&(body.compareDocumentPosition(el)&Node.DOCUMENT_POSITION_FOLLOWING)===0;
  const simSame=(a,b)=>{const A=a.replace(/\s+/g,''),B=b.replace(/\s+/g,'');return A===B||(B.length>20&&A.includes(B))||(A.length>20&&B.includes(A))};

  const thinkingSelectors=[
    '[data-plugin-identifier*="block_type:10025"]',
    '[data-plugin-identifier*="search_query_result_block"]',
    'div.mb-8.text-sm.text-dbx-neutral-400',
    '[class*="text-sm"][class*="neutral"]',
    '[class*="thought"]',
    '[class*="thinking"]',
    'div[data-render-engine="node"]'
  ];
  let thinking='',thinkingText='';
  const thinkingNodes=[];
  for(const s of thinkingSelectors){
    for(const el of m.querySelectorAll(s)){
      if(el===body||body.contains(el)||el.contains(body))continue;
      if(!isBeforeBody(el))continue;
      if(el.querySelector('.md-box-root,[class*="md-box-root"]'))continue;
      if(thinkingNodes.some(n=>n.contains(el)||el.contains(n)))continue;
      const t=clean(el.innerText);
      if(simSame(t,answerText))continue;
      thinkingNodes.push(el);
    }
  }
  if(thinkingNodes.length){
    const sorted=thinkingNodes.sort((a,b)=>((a.compareDocumentPosition(b)&2)?1:-1));
    const htmls=[],txts=[];
    for(const el of sorted){
      const t=clean(el.innerText);
      if(!t||/^搜索\s*\d+\s*个关键词.*参考\s*\d+\s*篇资料$/.test(t)||/^思考(?:已完成|过程)?$/.test(t))continue;
      if(txts.some(x=>simSame(x,t)))continue;
      if(simSame(t,answerText))continue;
      txts.push(t);
      htmls.push(stripAttrs(el.outerHTML||el.innerHTML||''));
    }
    thinking=htmls.join('\n\n');
    thinkingText=txts.join('\n\n');
  }

  let parts=[], n=body;
  while(n&&n!==m){const p=n.parentElement;if(!p||p===m.parentElement)break;const a=[...p.children],i=a.findIndex(x=>x===body||x.contains(body));
    if(i>0&&(p.className||'').includes('flex-col')){parts=a.slice(0,i).filter(x=>clean(x.innerText));break} n=p}

  if(parts.length&&!thinking){
    const filt=parts.filter(x=>!x.querySelector('a[data-thinking-box-tool-call="true"],a[data-tool-call-item-id*="-result-"]')&&isBeforeBody(x)&&!x.contains(body)&&!body.contains(x));
    const txts=filt.map(x=>clean(x.innerText)).filter(t=>t&&!/^搜索\s*\d+\s*个关键词.*参考\s*\d+\s*篇资料$/.test(t)&&!/^思考(?:已完成|过程)?$/.test(t)&&!simSame(t,answerText));
    const htmls=[];
    for(const x of filt){const t=clean(x.innerText);if(txts.includes(t))htmls.push(stripAttrs(x.outerHTML||x.innerHTML||''))}
    if(txts.length){thinking=htmls.join('\n\n');thinkingText=txts.join('\n\n')}
  }

  const sources=[],seen=new Set(),refs=[...m.querySelectorAll('a[href]')].filter(a=>a.dataset.thinkingBoxToolCall==='true'||(a.dataset.toolCallItemId||'').includes('-result-'));
  for(const a of refs){const raw=(a.href||'').trim(),title=clean(a.innerText||a.textContent);if(!raw||!title)continue;let u;try{u=new URL(raw,location.href).href}catch(_){continue}
    if(!/^https?:/.test(u)||u.includes('doubao.com/chat')||seen.has(u))continue;seen.add(u);sources.push({title:title.replace(/^\s*\d+\.\s*/,''),url:u})}

  const sourcesCovered=new Set();
  for(const tn of thinkingNodes){for(const a of tn.querySelectorAll('a[href]')){const raw=(a.href||'').trim();if(raw)sourcesCovered.add(raw)}}
  const refsMissing=[...refs].filter(a=>{
    if(!isBeforeBody(a))return false;
    const u=(a.href||'').trim();
    return u&&!sourcesCovered.has(u);
  });
  const blueNodes=refsMissing.map(a=>clean(a.innerText||a.textContent)).filter(Boolean);
  if(blueNodes.length){
    const uniqBlue=[];
    for(const t of blueNodes)if(!uniqBlue.some(x=>simSame(x,t))&&!simSame(t,answerText))uniqBlue.push(t);
    if(uniqBlue.length&&!thinkingText.split(/\s+/).join('').includes(uniqBlue.join('').split(/\s+/).join(''))){
      const blueHtml='<div class="thinking-refs"><ul>'+uniqBlue.map(t=>`<li>${t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}</li>`).join('')+'</ul></div>';
      thinking=[thinking,blueHtml].filter(Boolean).join('\n\n');
      thinkingText=[thinkingText,uniqBlue.join('\n')].filter(Boolean).join('\n\n');
    }
  }

  if(!thinking){let p=body.parentElement;while(p&&p!==m){const before=[...p.children].slice(0,[...p.children].findIndex(x=>x===body||x.contains(body)));
    const filt=before.filter(x=>!x.querySelector('a[data-thinking-box-tool-call="true"],a[data-tool-call-item-id*="-result-"]')&&!x.contains(body));
    const txts=filt.map(x=>clean(x.innerText)).filter(t=>t&&t!==answerText&&!simSame(t,answerText)&&!/^搜索\s*\d+\s*个关键词.*参考\s*\d+\s*篇资料$/.test(t));
    const htmls=[];
    for(const x of filt){const t=clean(x.innerText);if(txts.includes(t))htmls.push(stripAttrs(x.outerHTML||x.innerHTML||''))}
    if(txts.length){thinking=htmls.join('\n\n');thinkingText=txts.join('\n\n');break}p=p.parentElement}}

  const answerHtml=stripAttrs(body.outerHTML||body.innerHTML||'');

  return {id:m.dataset.messageId||'',answer:answerHtml,thinking,sources};
}).filter(Boolean)"""

# 注入 JS 用于「展开」折叠的思考区域：遍历消息里所有像按钮的可点击元素，找到带
# 「思考 / 深度思考 / 搜索 N 个关键词」文案的那个点开。同样不写死具体选择器，
# 靠文案匹配，改版后依然大概率能命中，返回实际点击次数。
EXPAND = r"""id => {
  const clean=s=>(s||'').replace(/\s+/g,' ').trim(), m=[...document.querySelectorAll('[data-message-id]')].find(x=>x.dataset.messageId===id);
  if(!m)return 0;
  const bodySelectors=['.md-box-root','[class*="md-box-root"]','[class*="prose"]','[class*="markdown"]','[class*="answer"]','.ProseMirror','[data-message-body]'];
  let body=null; for(const s of bodySelectors){body=m.querySelector(s);if(body)break}
  if(!body)return 0; let info=null,n=body;
  while(n&&n!==m){const p=n.parentElement;if(!p||p===m.parentElement)break;const a=[...p.children],i=a.findIndex(x=>x===body||x.contains(body));
    if(i>=0&&(p.className||'').includes('flex-col')){info={p,i};break}n=p}
  let count=0;
  const clickable=[...m.querySelectorAll('button,[role="button"],[class*="cursor-pointer"],div[class*="clickable"]')];
  const thought=clickable.find(el=>{const t=clean(el.innerText);return t.length<100&&/思考|深度思考/.test(t)});
  if(thought){thought.click();count++}
  else for(const el of clickable){const t=clean(el.innerText);if(t.length<100&&/^搜索\s*\d+\s*个关键词/.test(t)){el.click();count++;break}}
  if(count)return count;
  if(!info)return 0;
  const labels=/^搜索\s*\d+\s*个关键词|思考|深度思考|已完成|展开|查看/;
  for(const s of [...info.p.children].slice(0,info.i)){const label=[...s.querySelectorAll('*')].find(x=>labels.test(clean(x.innerText)));
    if(!label)continue;const button=label.closest('[class*="cursor-pointer"],button,[role="button"]')||label.parentElement;
    if(button&&clean(button.innerText).length<=clean(label.innerText).length+20){button.click();count++}}
  return count;
}"""


def human_wait(a=1, b=3):
    """随机停顿，模拟真人节奏，降低被反爬识别/弹验证码的概率。"""
    time.sleep(random.randint(a, b))


def messages(page):
    """把页面里所有 AI 回答消息块解析成结构化列表，供后续定位和判稳使用。"""
    value = page.evaluate(MESSAGES)
    return value if isinstance(value, list) else []


def verify(page):
    """检测当前页面是否弹出了验证码 / 安全验证。

    设计思路：豆包偶尔会弹验证码，此时脚本继续操作也没用。所以用几个关键词
    （captcha / 安全验证 / 请完成验证 / 拖拽到这里）在页面文本里做一次粗检，
    发现问题就交给上层 ready() 暂停、等人工处理。
    """
    try:
        text = page.locator("body").inner_text(timeout=2000).lower()
        return any(x in text for x in ("captcha", "安全验证", "请完成验证", "拖拽到这里"))
    except Exception:
        return False


def input_box(page):
    """定位输入框。按优先级依次尝试多个候选选择器，取第一个可用的。

    设计思路：豆包不同版本/不同页面输入框的实现会变（contenteditable 的 div 或
    textarea）。把候选选择器列成表逐个 try，第一个能匹配到可见元素的就返回，
    比死磕单一选择器稳得多。
    """
    selectors = [
        f'div[contenteditable="true"]:has(p[data-placeholder="{PLACEHOLDER}"]):visible',
        f'div[contenteditable="true"][role="textbox"]:visible',
        f'div.tiptap.ProseMirror:visible',
        f'textarea[placeholder="{PLACEHOLDER}"]:visible',
    ]
    last_err = None
    for sel in selectors:
        try:
            box = page.locator(sel).last
            box.wait_for(state="visible", timeout=5000)
            return box
        except Exception as e:
            last_err = e
    raise last_err if last_err else RuntimeError("未找到输入框")


def ready(page):
    """保证「能开始提问」：没验证码就返回输入框；有验证码就暂停等人处理。

    设计思路（处理卡住的关键）：
      1. 先 verify 检查是否弹验证码；
      2. 没验证码 -> 尝试取输入框并点击聚焦，成功就返回；
      3. 有验证码（或取不到输入框）-> 调 input() 阻塞等待，让用户去浏览器里
         手动完成登录/验证，确认后再按 Enter 继续。
    这样脚本遇到验证码不会陷入"反复点击失败"的死循环，而是把控制权交回给真人。
    """
    while True:
        if not verify(page):
            try:
                box = input_box(page)
                box.click(trial=True, timeout=3000)
                return box
            except Exception:
                pass
        input("请完成豆包登录/验证，确认输入框可用后按 Enter：")


def expand(page, message_id):
    """展开某个回答的折叠思考区域（失败只记日志、不中断流程）。"""
    try:
        clicked = page.evaluate(EXPAND, message_id)
    except Exception as e:
        log(f"展开思考失败：{e}")
        return
    if clicked:
        log("已展开思考区域")
        page.wait_for_timeout(1200)


def wait_answer(page, old_ids):
    """等待新的 AI 回答生成完毕并稳定。

    设计思路（和 deepseek 的 wait_answer_finish 同一套"稳定性判据"思想）：
      1. old_ids 是提问前已存在的消息 id 集合，之后每轮只关注"新出现"的消息；
      2. 取最新那条新消息，比较它的 id 和正文文本，连续 6 次不变（stable>=6）
         且长度>=10 才认为生成结束；
      3. 等正文稳定的同时，抽空展开该回答的折叠思考区再返回完整消息。
    额外：如果中途 verify() 发现弹了验证码，立刻抛异常交给上层 ask() 处理.
    """
    end, last_id, last_text, stable = time.time() + 300, "", "", 0
    while time.time() < end:
        if verify(page):
            raise RuntimeError("出现验证页面")
        fresh = [m for m in messages(page) if m["id"] not in old_ids]
        if fresh:
            m, text = fresh[-1], fresh[-1]["answer"].strip()
            if m["id"] == last_id and text == last_text:
                stable += 1
            else:
                last_id, last_text, stable = m["id"], text, 0
            if len(text) >= 10 and stable >= 6:
                expand(page, m["id"])
                page.wait_for_timeout(800)
                return next((x for x in messages(page) if x["id"] == m["id"]), m)
        time.sleep(1)
    raise TimeoutError("等待豆包回答超时")


def ask(page, question):
    """发送一个问题并等它出结果，是整个豆包工人的核心动作。

    设计思路（为什么这么长、这么多分支）：
      1. 输入框有两种实现：contenteditable 的 div 和 textarea。前者不能直接 fill，
         需要用 JS 往 DOM 里塞一个 <p> 并触发 input 事件，让框架感知到输入；
      2. 发送也有多种途径：Enter、发送按钮、最后 JS 兜底点击，逐个尝试；
      3. 外层 for attempt in range(2) 做重试：如果等待回答时遇到验证码抛异常，
         第一次先调 ready() 让用户人工处理 + 等 20 秒，再重试当前问题；
         第二次再失败才真正抛出去（避免同一问题无限重试）。
    这套"多途径输入/发送 + 遇验证码人工介入后重试"是本脚本最抗折腾的地方。
    """
    for attempt in range(2):
        page.goto(URL, wait_until="domcontentloaded", timeout=60000)
        time.sleep(random.uniform(2, 5))
        box = ready(page)
        old = {m["id"] for m in messages(page)}

        try:
            box.click(timeout=3000)
        except Exception:
            pass
        page.wait_for_timeout(300)

        box.fill(question, force=True)

        time.sleep(random.uniform(2, 5))

        sent = False
        send_selectors = [
            'button#flow-end-msg-send:visible',
            'button[data-dbx-name="button"][aria-label="发送"]:visible',
            'button[aria-label="发送"]:visible',
            '[aria-label="发送"][data-dbx-name]:visible',
            'button:has(svg.text-g-send-msg-btn-text):visible',
            '[id*="send"][aria-label*="发送"]:visible',
        ]
        try:
            box.press("Enter", timeout=3000)
            sent = True
        except Exception:
            pass
        if not sent:
            for s in send_selectors:
                try:
                    btn = page.locator(s).first
                    btn.wait_for(state="visible", timeout=1500)
                    btn.click(timeout=1500)
                    sent = True
                    break
                except Exception:
                    continue
        if not sent:
            try:
                js_click = page.evaluate("""() => {
                    const btns = document.querySelectorAll('button, [role="button"], [aria-label="发送"], [id*="send"]');
                    for (const b of btns) {
                        const t = (b.innerText || b.getAttribute('aria-label') || '').trim();
                        const svg = b.querySelector('svg.text-g-send-msg-btn-text') || b.querySelector('svg');
                        if (/发送|send/i.test(t) || (svg && (svg.getAttribute('aria-label') || '').includes('发送')) || b.id === 'flow-end-msg-send') {
                            b.click();
                            return true;
                        }
                    }
                    return false;
                }""")
                sent = bool(js_click)
            except Exception:
                pass

        if not sent:
            raise RuntimeError("无法发送问题，未找到发送按钮且Enter失败")

        log("问题已发送")
        try:
            return wait_answer(page, old)
        except RuntimeError as e:
            if attempt == 1:
                raise
            log(f"{e}，请完成验证后重试当前问题")
            ready(page)
            time.sleep(20)
    raise RuntimeError("回答失败")


def sources_text(items):
    """把来源列表（[{title, url}]）转成固定格式的文本，方便存库/展示。"""
    result, seen = [], set()
    for item in items or []:
        title, url = str(item.get("title", "")).strip(), str(item.get("url", "")).strip()
        if title and url and url not in seen:
            seen.add(url)
            result.append(f"{len(result) + 1}. {title}\n网址：{url}")
    return "\n\n".join(result)


def safe_name(s):
    """把任意字符串变成可安全用于文件名的形式（去非法字符、截断长度）。"""
    return "".join("_" if c in '<>:"/\\|?*' else c for c in " ".join(str(s).split()))[:80]


def shot(page, message_id, save_path):
    """给某个回答截图，采用「克隆 DOM 再截图」的方案。

    设计思路（绕开豆包的虚拟滚动列表）：
      豆包聊天页用虚拟滚动渲染，视口外的内容根本不进 DOM，所以 full_page 截图
      会截不到完整回答。这里的做法是注入 JS：找到该回答所在的"行"，连同上一条
      （问题行）一起 cloneNode 复制到一个绝对定位、固定宽度、强制展开所有折叠/
      隐藏样式的新容器 #doubao-shot-clone 里，再只对这个容器截图。
      这样既绕开了虚拟滚动，又能只截"问题 + 回答"这段，图又全又干净。
    若克隆截图失败，就退回到 full_page 整页截图兜底。
    """
    try:
        loc = page.locator(f'[data-message-id="{message_id}"]').last
        loc.wait_for(state="visible", timeout=10000)
        page.evaluate(
            """id => {
                document.getElementById('doubao-shot-clone')?.remove();
                const m=[...document.querySelectorAll('[data-message-id]')]
                    .find(x=>x.getAttribute('data-message-id')===id);
                if(!m)return;
                let answerRow=m.closest('.v_list_row');
                if(!answerRow){
                    let n=m;
                    while(n&&n.parentElement){
                        const p=n.parentElement;
                        const cls=(p.className||'').toString();
                        if(/(?:^|\s)(?:flex|v_list_row|message-row|chat-row)(?:\s|$)/.test(cls)||(p.dataset&&p.dataset.messageRow!==undefined)){
                            answerRow=p;break;
                        }
                        if(p.tagName==='MAIN'||p.id==='app'||p===document.body)break;
                        n=p;
                    }
                }
                if(!answerRow)answerRow=m;
                let questionRow=answerRow.previousElementSibling;
                const isRow=(el)=>(el&&((el.classList&&(el.classList.contains('v_list_row')||/\b(?:flex|message-row|chat-row)\b/.test(el.className.toString())))||el.querySelector&&el.querySelector('[data-message-id]')));
                while(questionRow&&!isRow(questionRow))
                    questionRow=questionRow.previousElementSibling;
                if(!questionRow){
                    const all=[...document.querySelectorAll('[data-message-id]')];
                    const idx=all.findIndex(x=>x.getAttribute('data-message-id')===id);
                    if(idx>0){
                        let prev=all[idx-1];
                        let prevRow=prev.closest('.v_list_row');
                        if(!prevRow){
                            let n=prev;
                            while(n&&n.parentElement){
                                const p=n.parentElement;
                                const cls=(p.className||'').toString();
                                if(/(?:^|\s)(?:flex|v_list_row|message-row|chat-row)(?:\s|$)/.test(cls)){prevRow=p;break}
                                if(p.tagName==='MAIN'||p.id==='app'||p===document.body)break;
                                n=p;
                            }
                        }
                        questionRow=prevRow||prev;
                    }
                }

                const w=Math.max(answerRow.getBoundingClientRect().width||600, 600);
                const box=document.createElement('div');
                box.id='doubao-shot-clone';
                box.style.cssText='position:absolute;left:0;top:0;z-index:2147483647;'+
                    'width:'+w+'px;background:white;padding:16px;';
                const style=document.createElement('style');
                style.textContent=`
                    #doubao-shot-clone, #doubao-shot-clone * {
                        max-height:none !important;
                    }
                    #doubao-shot-clone .v_list_row,
                    #doubao-shot-clone [class*="flex-col"] {
                        position:relative !important;
                        inset:auto !important;
                        top:auto !important;
                        left:auto !important;
                        transform:none !important;
                        height:auto !important;
                        min-height:0 !important;
                        width:100% !important;
                        overflow:visible !important;
                    }
                    #doubao-shot-clone [data-message-id],
                    #doubao-shot-clone [data-container-type="block-v2"] {
                        position:relative !important;
                        transform:none !important;
                        height:auto !important;
                        min-height:0 !important;
                        overflow:visible !important;
                    }
                    #doubao-shot-clone [class*="sticky"],
                    #doubao-shot-clone [class*="fixed"] {
                        position:relative !important;
                        inset:auto !important;
                    }
                    #doubao-shot-clone textarea,
                    #doubao-shot-clone [contenteditable="true"],
                    #doubao-shot-clone [data-foundation-type="receive-message-action-bar"] {
                        display:none !important;
                    }
                `;
                box.appendChild(style);
                if(questionRow)box.appendChild(questionRow.cloneNode(true));
                box.appendChild(answerRow.cloneNode(true));
                document.body.appendChild(box);
            }""",
            message_id,
        )
        page.wait_for_timeout(500)
        clone = page.locator("#doubao-shot-clone")
        clone.wait_for(state="visible", timeout=5000)
        clone.screenshot(path=str(save_path), animations="disabled")
    except Exception as e:
        log(f"克隆截图失败，退回整页截图：{e}")
        page.screenshot(path=str(save_path), full_page=True)
    finally:
        page.evaluate("() => document.getElementById('doubao-shot-clone')?.remove()")
    log(f"截图：{save_path}")


def handle_unit(page, unit):
    """处理单个任务单元（1 问题 x 1 豆包 = 1 条 task_result）。由 worker 循环调用。

    单元即后端任务池派发的最小工作项，含 id/taskNo/aiPlatform/questionText。

    流程：ask 发送并等回答 -> 取下 answer/thinking/sources -> shot 截图 ->
          上传截图 -> 回调结果。
    异常设计同 deepseek：无论成败都必须回调（SUCCESS/FAILED），让单元有明确终态，
    否则后端只能等租约超时回收、白白多等。
    """
    task_no = str(unit.get("taskNo"))
    agent_name = unit.get("aiPlatform") or "doubao"
    question = str(unit.get("questionText") or "")

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
        sources = result.get("sources") or []
        source_pairs = [[s.get("title", ""), s.get("url", "")] for s in sources]
        source_info = json.dumps(source_pairs, ensure_ascii=False) if source_pairs else ""

        shot(page, result["id"], screenshot_file)

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
    """进程入口：打开浏览器、就绪后进入认领循环。

    关键设计同 deepseek.py：persistent_context 持久化登录态；channel="msedge" 复用
    系统 Edge；headless=False 有头运行便于人工登录/验证/排查。区别是这里用
    no_viewport=True + --start-maximized 让窗口最大化，避免截图像素太小。
    """
    log("启动豆包 Worker（认领模式，无全局锁）")
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

        page.goto(URL, wait_until="domcontentloaded")
        log("豆包已打开")
        human_wait(2, 5)

        ready(page)
        log("已登录")
        human_wait(1, 3)

        log("开始从任务池认领单元...")
        try:
            run_worker_loop(PLATFORM_NAME, page, handle_unit)
        except KeyboardInterrupt:
            log("收到中断信号，退出")
        finally:
            browser.close()


if __name__ == "__main__":
    main()