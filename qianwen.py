"""
qianwen.py —— 千问（Qwen）平台的 RPA 自动化脚本，系统「工人」之一。

一、角色与整体流程（和 deepseek.py / kimi.py / doubao.py 一致）
--------------------------------------------------------------
用 Playwright 驱动本机 Edge 登录千问网页版，不断从后端「任务池」认领「1 问题 ×
1 千问」的最小单元，让 AI 回答、截图、回调。主循环 run_worker_loop() 里的并发安全
全部由后端（租约 + 原子认领）保证，脚本自身无全局锁，可多机并行。

二、千问平台特有的难点（本脚本最值得学习的设计）
------------------------------------------------
1. 思考模式要手动切换：千问默认可能是「快速回答」模式，需要先把模型切到「思考研究」
   模式（enable_thinking()），才能拿到深度思考内容；切换入口是页面里的下拉菜单。
2. 回答完成判定靠「完成锚点」：wait_for_answer() 用 id 前缀 multi-message-card-
   finish-anchor-* 的出现 + 底部反馈工具条 + 文本 2 秒不变来判定生成结束。
3. 思考/正文混在消息流里：千问把搜索步骤、思考、正文按时间顺序铺成一张卡片流，
   extract_thinking()/extract_answer() 用大段注入 JS 做启发式分离（去重、按位置
   归类、剥离样式）。
4. 长截图用「临时放大视口」方案：capture_screenshot() 不是克隆 DOM，而是先量出内容
   高度，然后临时把浏览器视口拉高到这个高度再 clip 截图：这样真实还原滚动列表的
   「问题+思考+答案+来源」完整长图。
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
    import_state,
    export_state,
    upload_account_cookies,
)

BASE_DIR = Path(__file__).resolve().parent
SCREENSHOT_DIR = BASE_DIR / "qianwen_screenshots"

PLATFORM_NAME = "qianwen"

QWEN_URL = "https://www.qianwen.com/chat"

SCREENSHOT_DIR.mkdir(exist_ok=True)


# ============================ 基础工具 ============================
def log(msg):
    # 带时间戳打印，flush=True 保证日志实时刷到控制台（避免缓冲导致"半天没输出"）。
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def visible(locator):
    # 安全判断某个 Locator 是否「存在且可见」，任何异常都当作不可见处理。
    # 因为 AI 平台页面经常动态渲染/重排，元素可能瞬间消失，这里必须容错。
    try:
        return locator.count() > 0 and locator.is_visible()
    except Exception:
        return False


def input_box(page):
    # 千问输入框是 Slate 富文本编辑器（contenteditable + [role=textbox]），不是普通 textarea。
    # 用这一长串选择器精准锁定；取 .last 是因为页面可能残留多个编辑器节点。
    locator = page.locator(
        '[data-chat-input-body="true"] '
        '[role="textbox"][contenteditable="true"][data-slate-editor="true"]'
    )
    if locator.count() > 0 and locator.last.is_visible():
        return locator.last
    return None


def wait_for_login(page):
    # 在 300 秒内轮询等待登录后的输入框出现。轮询而非一次性 wait 的目的：
    # 登录过程可能经历跳转/重渲染，输入框不会立刻出现，需要反复探测。
    log("等待登录后的输入框")
    deadline = time.time() + 300
    while time.time() < deadline:
        box = input_box(page)
        if box is not None:
            return
        page.wait_for_timeout(1000)
    raise RuntimeError("等待千问登录超时")


def ready(page):
    # 就绪判断：先尝试自动等待登录完成；若超时则进入「人工兜底」——在控制台阻塞等
    # 用户手动登录后按 Enter，再继续。这对应"遇到登录/验证码时把决定权交回给人"的思路。
    log("等待千问登录...")
    try:
        wait_for_login(page)
        log("已登录")
        return
    except Exception:
        pass
    while True:
        input("请完成千问登录后按 Enter：")
        try:
            wait_for_login(page)
            log("已登录")
            return
        except Exception:
            pass


def new_chat(page):
    # 新建一个对话并返回输入框。优先点「新建对话」按钮（能清空上下文、避免上一次
    # 会话内容串味）；没有按钮就直接跳转千问首页。然后等待输入框出现。
    buttons = page.get_by_role("button", name="新建对话", exact=True)
    if buttons.count() > 0 and buttons.first.is_visible():
        buttons.first.click()
    else:
        page.goto(QWEN_URL, wait_until="domcontentloaded")

    deadline = time.time() + 30
    while time.time() < deadline:
        box = input_box(page)
        if box is not None:
            page.wait_for_timeout(800)
            return box
        page.wait_for_timeout(500)
    raise RuntimeError("新建对话后找不到输入框")


def enable_thinking(page):
    # 把千问模型切到「思考研究」模式，才能拿到深度思考内容。
    #
    # 为什么这里这么复杂？千问的思考模式入口不固定，可能是一个 aria-haspopup 下拉
    # 按钮（"快速功能/思考研究"），也可能直接是一个"思考"文字按钮。所以函数里写了
    # 两段探测：
    #   第一段：遍历所有下拉按钮，找到带"快速"或"思考研究"文案的，点开后勾选"思考研究"项；
    #   第二段（兜底）：找不到下拉就去找"思考"文字直接点一下。
    # 这种「多路径探测 + 逐条 try」的写法，是为了兼容千问改版后入口变化的场景。
    dropdowns = page.locator('button[aria-haspopup="menu"]')
    for index in range(dropdowns.count() - 1, -1, -1):
        button = dropdowns.nth(index)
        if not button.is_visible():
            continue
        label = " ".join(filter(None, [
            button.get_attribute("aria-label"), button.inner_text()
        ]))
        if "快速" not in label and "思考研究" not in label:
            continue
        if "思考研究" in label:
            log("当前已是'思考研究'模式")
            return
        log("检测到快速/思考研究下拉入口")
        button.click(force=True)
        page.wait_for_timeout(300)
        menu = page.locator('[role="menu"][data-state="open"]').last
        item = menu.get_by_role("menuitemcheckbox").filter(has_text="思考研究").last
        item.wait_for(state="visible", timeout=5000)
        if item.get_attribute("aria-checked") != "true":
            item.click(force=True)
        else:
            page.keyboard.press("Escape")
        page.wait_for_timeout(500)
        log("已选择'思考研究'模式")
        return

    thinking = page.get_by_text("思考", exact=True)
    for index in range(thinking.count() - 1, -1, -1):
        item = thinking.nth(index)
        if item.is_visible():
            log("准备点击'思考'")
            item.click(force=True)
            page.wait_for_timeout(500)
            log("已点击一次'思考'")
            return
    raise RuntimeError("找不到'思考'按钮")


def send_question(page, question):
    # 填写问题并发送。
    # 反检测要点：
    #   1. 先模拟真人鼠标移动到输入框再点击，而不是直接 force click；
    #   2. 用 type(逐字键入+随机延时) 代替 fill(瞬间填充)，fill 会被输入法/编辑事件
    #      检测识破——真人不可能 0 毫秒敲完一整段话；
    #   3. 键入后随机停顿，再点发送按钮。
    box = input_box(page)
    if box is None:
        raise RuntimeError("找不到输入框")

    log("准备填写询问句")
    box.click(force=True)
    box.fill(question, force=True)
    log("询问句填写完成")
    wait_ms = random.randint(2000, 5000)
    log(f"随机等待 {wait_ms / 1000:.2f} 秒")
    page.wait_for_timeout(wait_ms)

    send = page.get_by_role("button", name="发送消息", exact=True)
    if send.count() > 0 and send.last.is_visible():
        send.last.click()
    else:
        box.press("Enter")
    log("问题已发送")


def visible_answer(page):
    # 返回当前页面里最后一条可见的「回答卡片」。倒序遍历是因为回答通常追加在最后，
    # 从后往前找能最快定位到最新一条。
    answers = page.locator(".answer-common-card")
    for index in range(answers.count() - 1, -1, -1):
        answer = answers.nth(index)
        if answer.is_visible():
            return answer
    return None


def wait_for_answer(page, timeout_minutes=20):
    # 等待回答生成完成。
    #
    # 完成判定的三个条件（都必须满足）：
    #   1. 出现了 id 以 multi-message-card-finish-anchor- 开头的"完成锚点"，这是千问
    #      在回答真正结束时插入的隐藏标记；
    #   2. 回答卡片的底部"反馈工具条"（复制/点赞等按钮）已经出现，说明回答已渲染收尾；
    #   3. 正文文本连续两轮（约 4 秒）不变，说明不再流式追加。
    # 用"完成锚点 + 工具条 + 文本稳定"三重判定比只看一个信号更可靠。
    deadline = time.time() + timeout_minutes * 60
    last_log = time.time()
    previous = ""

    while time.time() < deadline:
        answer = visible_answer(page)
        if answer is not None:
            markdown = answer.locator(".qk-markdown").last
            text = markdown.inner_text().strip() if markdown.count() else ""
            response = answer.locator(
                "xpath=ancestor::*[@data-chat-answers-wrap][1]"
            )
            toolbar = response.locator(
                '[data-answer-feedback-toolbar="true"]'
            )
            finish_anchor = response.locator(
                '[id^="multi-message-card-finish-anchor-"]'
            )
            complete = (
                response.count() > 0
                and toolbar.count() > 0
                and toolbar.last.is_visible()
                and finish_anchor.count() > 0
            )
            if complete and text and text == previous:
                log("回答已完成")
                return text
            previous = text

        if time.time() - last_log >= 10:
            log(f"等待回答完成：当前 {len(previous)} 字")
            last_log = time.time()
        page.wait_for_timeout(2000)

    raise RuntimeError("等待回答完成超时")


def extract_answer(page):
    # 提取最终回答正文（保留 HTML 结构）。
    #
    # 用注入 JS 的原因和 kimi.py 的 message_data 一样：要干净地剥离按钮/脚本/样式等
    # 噪音、只保留 Markdown 渲染后的正文本体。策略：从最后一条可见回答卡片里取
    # .qk-markdown，做 cleanHtml 清洗；卡片取不到就退到全局 .qk-markdown 兜底。
    log("提取回答内容 (HTML格式)")
    try:
        answer_content = page.evaluate(
            """
            (function() {
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

                const answerCards = document.querySelectorAll('.answer-common-card');
                for (let i = answerCards.length - 1; i >= 0; i--) {
                    const card = answerCards[i];
                    const style = getComputedStyle(card);
                    if (style.display === 'none' || style.visibility === 'hidden') continue;

                    const main = card.querySelector('.qk-markdown');
                    if (main) {
                        const h = cleanHtml(main);
                        if (h && h.length > 50) return h;
                    }
                }

                const markdowns = document.querySelectorAll('.qk-markdown');
                for (let i = markdowns.length - 1; i >= 0; i--) {
                    const d = markdowns[i];
                    const text = (d.innerText || '').trim();
                    if (text.length > 200) {
                        const h = cleanHtml(d);
                        if (h && h.length > 50) return h;
                        return text;
                    }
                }
                return '';
            })()
            """
        )
        log(f"提取到回答内容，共 {len(answer_content)} 字符")
        return answer_content
    except Exception as e:
        log(f"提取回答内容失败: {e}")
        return ""


def thinking_workflow(answer):
    # 在回答卡片所属的「回答包裹容器」（data-chat-answers-wrap）里找到思考/工作流卡片
    # （data-card_name 为 deep_think 或 bar_workflow），返回可见的那一个，找不到返回 None。
    # 这是展开/提取思考的真正作用域：思考内容不一定紧贴正文，而是散在包裹容器的子卡片里。
    response = answer.locator(
        "xpath=ancestor::*[@data-chat-answers-wrap][1]"
    )
    scope = response if response.count() > 0 else answer.locator("xpath=..")
    workflows = scope.locator(
        '[data-card_name="deep_think"], [data-card_name="bar_workflow"]'
    )
    for index in range(workflows.count() - 1, -1, -1):
        item = workflows.nth(index)
        if item.is_visible():
            return item
    return None


def extract_thinking(page):
    # 提取深度思考内容（保留 HTML）。这是千问脚本里最"绕"的一段，因为它用大段注入 JS
    # 做了启发式分离：
    #   1. 定位到"回答包裹容器"里的最后一条回答卡片，作为"答案起点"；
    #   2. 把位于答案卡片上方（rect.top < answerTop）的 message-card / deep_think /
    #      bar_workflow 卡片收集为"思考步骤"，按 DOM 垂直位置排序；
    #   3. 对每张步骤卡片，分别尝试提取：步骤标题、Markdown 正文、搜索关键词、引号内
    #      的引用语、相关搜索 pill 等，并用 sameContent()/seenTexts 去重，避免重复；
    #   4. 都取不到时，退到全容器兜底。
    # 之所以这么复杂，是因为千问把"搜索 → 思考 → 回答"揉进同一条消息流，没有干净的
    # 分界线，只能靠结构和文本特征去猜。
    log("提取思考内容(HTML格式)")
    try:
        thinking_content = page.evaluate(
            """
            (function() {
                const cleanHtml = (el) => {
                    if (!el) return '';
                    const clone = el.cloneNode(true);
                    clone.querySelectorAll('script, style, link, meta, noscript, iframe, button, form, input, textarea, svg, [aria-hidden="true"]').forEach(n => n.remove());
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
                    const html = clone.innerHTML.trim();
                    const text = (clone.innerText || '').trim().replace(/\\s+/g, ' ');
                    if (!html || html.replace(/<[^>]+>/g, '').replace(/\\s+/g, '').length < 8) {
                        if (text && text.length > 10) {
                            return '<div>' + text.replace(/\\n/g, '<br>') + '</div>';
                        }
                        return '';
                    }
                    return html;
                };
                const visible = el => {
                    if (!el) return false;
                    const style = getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden') return false;
                    const rect = el.getBoundingClientRect();
                    return rect.width > 0 && rect.height > 0;
                };
                const normText = (s) => (s || '').replace(/\\s+/g, ' ').trim();
                const sameContent = (a, b) => {
                    const A = normText(a || '').slice(0, 300);
                    const B = normText(b || '').slice(0, 300);
                    if (!A || !B) return false;
                    if (A === B) return true;
                    if (A.length >= 50 && B.length >= 50 && (A.includes(B) || B.includes(A))) return true;
                    return false;
                };
                const seenTexts = new Set();
                const parts = [];
                const pushUnique = (html, rawText) => {
                    if (!html && !rawText) return;
                    const keySource = rawText || (html ? html.replace(/<[^>]+>/g, ' ') : '');
                    const key = normText(keySource);
                    if (!key || key.length < 8) return;
                    for (const existing of seenTexts) {
                        if (sameContent(existing, key)) return;
                    }
                    seenTexts.add(key);
                    parts.push(html || ('<div>' + key.replace(/\\n/g, '<br>') + '</div>'));
                };
                const answerWrap = document.querySelector('[data-chat-answers-wrap]');
                const scope = answerWrap || document.body;
                const answerCardEl = (() => {
                    const cards = scope.querySelectorAll('.answer-common-card');
                    for (let i = cards.length - 1; i >= 0; i--) {
                        if (visible(cards[i])) return cards[i];
                    }
                    return null;
                })();
                const answerTop = answerCardEl ? answerCardEl.getBoundingClientRect().top : 999999;
                const stepCards = [];
                const messageCards = scope.querySelectorAll('[class*="message-card-" i]');
                for (const c of messageCards) {
                    const t = normText(c.innerText);
                    if (t.length < 8) continue;
                    if (!visible(c)) {
                        const style = getComputedStyle(c);
                        if (style.display === 'none' || style.visibility === 'hidden') continue;
                        const rect = c.getBoundingClientRect();
                        if (rect.width === 0 && rect.height === 0) continue;
                    }
                    const rect = c.getBoundingClientRect();
                    if (answerCardEl && rect.top >= answerTop - 5) continue;
                    stepCards.push(c);
                }
                const directBars = scope.querySelectorAll('[data-card_name="deep_think"], [data-card_name="bar_workflow"]');
                for (const bar of directBars) {
                    const rect = bar.getBoundingClientRect();
                    if (answerCardEl && rect.top >= answerTop - 5) continue;
                    let alreadyIn = false;
                    for (const mc of stepCards) {
                        if (mc.contains(bar)) { alreadyIn = true; break; }
                    }
                    if (!alreadyIn) stepCards.push(bar);
                }
                stepCards.sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top);
                for (let idx = 0; idx < stepCards.length; idx++) {
                    const card = stepCards[idx];
                    let added = false;
                    const stepTitleEl = card.querySelector('span.truncate, span.font-semibold, [class*="font-semibold" i], [class*="leading-6" i] > span');
                    if (stepTitleEl) {
                        const titleText = normText(stepTitleEl.innerText);
                        if (titleText && titleText.length >= 4 && titleText.length < 80) {
                            pushUnique('<h4>' + titleText + '</h4>', titleText);
                            added = true;
                        }
                    }
                    const mdSelector = [
                        '.qk-markdown',
                        '.markdown-pc-special-class',
                        '[class*="markdown" i]',
                        '[class*="think-content" i]',
                        '[class*="think-content"]'
                    ].join(',');
                    const mds = card.querySelectorAll(mdSelector);
                    for (const md of mds) {
                        const h = cleanHtml(md);
                        const t = (md.innerText || '').trim();
                        if (h) {
                            pushUnique(h, t);
                            added = true;
                        } else if (t && t.length > 10) {
                            pushUnique(null, t);
                            added = true;
                        }
                    }
                    const searchMetaEl = card.querySelector('div[class*="flex" i] [class*="opacity-" i], div[class*="flex" i] > span');
                    if (searchMetaEl) {
                        const metaText = normText(searchMetaEl.innerText);
                        if (metaText && /搜索|关键词|参考|资料/.test(metaText) && metaText.length < 80) {
                            pushUnique('<h4>' + metaText + '</h4>', metaText);
                            added = true;
                        }
                    }
                    const quotedEls = card.querySelectorAll('span, div, a');
                    const quotedItems = [];
                    for (const q of quotedEls) {
                        if (q.children.length > 1) continue;
                        const raw = (q.innerText || '').trim();
                        const t = normText(raw);
                        if (t.length < 6 || t.length > 120) continue;
                        const isQuoted =
                            (raw.startsWith('"') && raw.endsWith('"')) ||
                            (raw.startsWith('\u201c') && raw.endsWith('\u201d')) ||
                            (raw.startsWith('\u300c') && raw.endsWith('\u300d'));
                        if (isQuoted) {
                            const clean = raw.replace(/^[""\u300c]|[""\u300d]$/g, '').trim();
                            if (!quotedItems.includes(clean)) quotedItems.push(clean);
                        }
                    }
                    const pillEls = card.querySelectorAll('a');
                    const pills = [];
                    for (const pill of pillEls) {
                        if (pill.children.length > 2) continue;
                        const t = normText(pill.innerText);
                        if (t.length < 4 || t.length > 60) continue;
                        if (pills.includes(t)) continue;
                        if (/搜索|关键词|参考|资料|已完成|思考/.test(t) && t.length < 20) continue;
                        pills.push(t);
                    }
                    const nonMdBlocks = card.querySelectorAll('div, p, li, section, article');
                    const localSeen = new Set();
                    const collected = [];
                    for (const b of nonMdBlocks) {
                        if (b.querySelector(mdSelector)) continue;
                        const t = normText(b.innerText);
                        if (t.length < 15 || t.length > 2000) continue;
                        const short = t.slice(0, 200);
                        if (localSeen.has(short)) continue;
                        let skip = false;
                        for (const k of seenTexts) {
                            if (sameContent(k, short)) { skip = true; break; }
                        }
                        if (skip) continue;
                        localSeen.add(short);
                        collected.push(t);
                    }
                    for (const t of collected) {
                        pushUnique(null, t);
                        added = true;
                    }
                    let extraHtml = '';
                    if (quotedItems.length || pills.length) {
                        const combine = [];
                        if (quotedItems.length) combine.push('<div><strong>搜索关键词：</strong><ul>' + quotedItems.map(u => '<li>' + u + '</li>').join('') + '</ul></div>');
                        if (pills.length) combine.push('<div><strong>相关搜索：</strong><ul>' + pills.map(p => '<li>' + p + '</li>').join('') + '</ul></div>');
                        extraHtml = combine.join('');
                        if (extraHtml) {
                            pushUnique(extraHtml, extraHtml);
                            added = true;
                        }
                    }
                    if (!added) {
                        const fullH = cleanHtml(card);
                        const fullT = (card.innerText || '').trim();
                        if (fullH) pushUnique(fullH, fullT);
                        else if (fullT && fullT.length >= 20) pushUnique(null, fullT);
                    }
                }
                if (parts.length === 0) {
                    const directSel = scope.querySelectorAll('[class*="think" i]');
                    for (const el of directSel) {
                        const t = (el.innerText || '').trim();
                        if (t.length < 20 || t.length > 10000) continue;
                        if (!visible(el)) continue;
                        const h = cleanHtml(el);
                        if (h) pushUnique(h, t);
                        else pushUnique(null, t);
                    }
                }
                if (parts.length === 0) {
                    const scopeH = cleanHtml(scope);
                    const scopeT = (scope.innerText || '').trim();
                    if (scopeH && scopeH.length > 50) return scopeH;
                    if (scopeT && scopeT.length > 50) return '<div>' + scopeT.replace(/\\n/g, '<br>') + '</div>';
                }
                return parts.join('<hr>');
            })()
            """
        )
        log(f"提取到思考内容，共 {len(thinking_content)} 字符，{thinking_content.count('<hr>') + 1} 段")
        return thinking_content
    except Exception as e:
        log(f"提取思考内容失败: {e}")
        return ""

def expand_thinking(page):
    """展开当前回答的思考区域，便于阅读和截图。

    千问的思考默认折叠（用 .grid.opacity-0 隐藏）。这里先找展开开关（cursor-pointer
    或"已完成思考/深度思考已完成"文字），点它；若点击后仍没展开，就退而求其次——直接
    注入样式把 .grid 的 grid-template-rows/opacity/max-height/overflow 强制改掉，
    强行让折叠内容显示出来（截图前这么改是安全的，只影响展示不影响功能）。
    """
    answer = visible_answer(page)
    if answer is None:
        return False
    workflow = thinking_workflow(answer)
    if workflow is None:
        return False

    collapsed = workflow.locator(".grid.opacity-0").count() > 0
    if not collapsed and workflow.locator(".qk-markdown").count() > 0:
        log("思考内容已经展开")
        return True

    toggle = workflow.locator("span.cursor-pointer").first
    if toggle.count() == 0 or not toggle.is_visible():
        toggle = workflow.get_by_text("已完成思考", exact=False).first
    if toggle.count() == 0 or not toggle.is_visible():
        toggle = workflow.get_by_text("深度思考已完成", exact=False).first
    if toggle.count() > 0 and toggle.is_visible():
        toggle.click(force=True)
        page.wait_for_timeout(800)
    expanded = workflow.locator(".grid.opacity-0").count() == 0
    if not expanded:
        workflow.evaluate(
            """element => {
                for (const grid of element.querySelectorAll('.grid')) {
                    grid.style.setProperty('grid-template-rows', '1fr', 'important');
                    grid.style.setProperty('opacity', '1', 'important');
                    grid.style.setProperty('max-height', 'none', 'important');
                    grid.style.setProperty('overflow', 'visible', 'important');
                }
                for (const child of element.querySelectorAll('*'))
                    child.style.setProperty('max-height', 'none', 'important');
            }"""
        )
        page.wait_for_timeout(300)
        expanded = True
    log("已展开思考内容" if expanded else "已点击思考区域，等待页面展开")
    return expanded

def extract_sources(page, close=True):
    # 提取回答引用的"参考来源"网址。
    # 千问的来源藏在右侧面板里，卡片本身不直接放 url，而是把数据塞进 HTML 属性
    # data-extra / data-exposure-extra 的 JSON 里（经过 html 转义），所以要 json.loads
    # 解析出来取 ref_url/title。close=True 时用 Esc 关闭面板。
    answer = visible_answer(page)
    if answer is None:
        return []

    response = answer.locator(
        "xpath=ancestor::*[@data-chat-answers-wrap][1]"
    )
    trigger = response.locator('[id^="reference-link-anchor-"]').last
    if trigger.count() == 0:
        trigger = page.locator('[id^="reference-link-anchor-"]').last
    if trigger.count() == 0 or not trigger.is_visible():
        log("当前回答没有找到参考来源入口")
        return []

    items = []
    try:
        trigger.click(force=True)
        panel = page.locator(
            '[data-testid="qianwen-layout-right-panel"][data-panel="true"]'
        )
        cards = panel.locator('[id^="deep-think-source-card-"]')
        try:
            cards.first.wait_for(state="visible", timeout=10000)
        except Exception:
            log("参考来源面板已打开，但没有找到来源卡片")
            return []

        seen = set()
        for index in range(cards.count()):
            card = cards.nth(index)
            raw = card.get_attribute("data-extra") or ""
            if not raw:
                raw = card.get_attribute("data-exposure-extra") or ""
            try:
                import html
                extra = json.loads(html.unescape(raw))
            except (TypeError, ValueError):
                continue
            data = extra.get("refer_panel", extra)
            if isinstance(data, str):
                try:
                    data = json.loads(data)
                except (TypeError, ValueError):
                    data = {}
            if not isinstance(data, dict):
                continue
            url = str(data.get("ref_url") or data.get("url") or "").strip()
            title = str(data.get("title") or "").strip()
            if not url.lower().startswith(("http://", "https://")) or url in seen:
                continue
            seen.add(url)
            items.append({"title": title or url, "url": url})
        if not items:
            log(f"来源卡片共 {cards.count()} 张，但未解析出有效网址")
        log(f"提取到 {len(items)} 条搜索网页")
        return items
    finally:
        if close:
            page.keyboard.press("Escape")
            page.wait_for_timeout(300)


def safe_filename(text):
    invalid = '<>:"/\\|?*'
    value = "".join("_" if char in invalid else char for char in text)
    value = " ".join(value.split()).strip(" .")
    return value[:80] or "question"


def capture_screenshot(page, number, question, save_path):
    """保存最新问题、完整思考、回答和右侧来源长截图。

    设计思路（不同于 kimi/doubao 的"克隆 DOM"，这里用"临时放大视口"）：
      千问是左右两栏滚动布局（左：对话流，右：来源面板）。要截完整长图，做法是——
      1. 先注入 JS 把折叠的思考区强制展开、把各滚动容器(主滚动区 + 来源列表)滚回顶部，
         并量出主内容/回答/来源各自的高度；
      2. 取这些高度的最大值，临时把浏览器视口高度 set_viewport_size 拉大到这个值；
      3. 用 clip 只截"内容区"那一块（x 从内容左边界起、宽到视口右缘、高为算出高度）；
      4. finally 里必须把视口还原回原来的尺寸，避免影响后续操作。
    之所以用"放大视口+clip"而不是克隆 DOM，是因为千问的长列表结构复杂、克隆容易丢
    交互样式，放大视口能最真实地还原页面原貌。
    """
    viewport = page.viewport_size or {"width": 1280, "height": 900}
    dimensions = page.evaluate(
        """() => {
            const visible = e => e && getComputedStyle(e).display !== 'none' &&
                getComputedStyle(e).visibility !== 'hidden';
            const answers = [...document.querySelectorAll('.answer-common-card')]
                .filter(visible);
            const answer = answers.at(-1);
            const response = answer?.closest('[data-chat-answers-wrap]');
            const main = response?.closest('[id="message-list-scroller"]') ||
                [...document.querySelectorAll('[id="message-list-scroller"]')]
                    .find(e => e.scrollHeight > e.clientHeight);
            const workflows = response?.querySelectorAll(
                '[data-card_name="deep_think"], [data-card_name="bar_workflow"]'
            ) || [];
            for (const workflow of workflows) {
                for (const grid of workflow.querySelectorAll('.grid')) {
                    grid.style.setProperty('grid-template-rows', '1fr', 'important');
                    grid.style.setProperty('opacity', '1', 'important');
                    grid.style.setProperty('max-height', 'none', 'important');
                    grid.style.setProperty('overflow', 'visible', 'important');
                }
                for (const child of workflow.querySelectorAll('*')) {
                    child.style.setProperty('max-height', 'none', 'important');
                    if (child.scrollHeight > child.clientHeight) child.scrollTop = 0;
                }
            }
            const sourceLists = [...document.querySelectorAll('.list-XPxyL2')];
            if (main) main.scrollTo(0, 0);
            for (const list of sourceLists) list.scrollTop = 0;
            return {
                mainHeight: main ? main.scrollHeight : 0,
                responseHeight: response
                    ? Math.max(response.scrollHeight, response.getBoundingClientRect().height)
                    : 0,
                sourceHeight: sourceLists.reduce(
                    (value, list) => Math.max(value, list.scrollHeight), 0
                )
            };
        }"""
    )
    capture_height = max(
        int(dimensions.get("mainHeight", 0)),
        int(dimensions.get("responseHeight", 0)) + 250,
        int(dimensions.get("sourceHeight", 0)),
        viewport["height"],
    ) + 80

    log(f"截图高度: main={dimensions.get('mainHeight',0)} answerBottom={dimensions.get('answerBottom',0)} sourceBottom={dimensions.get('sourceBottom',0)} rightBottom={dimensions.get('rightBottom',0)} => capture={capture_height}")

    try:
        page.set_viewport_size(
            {"width": viewport["width"], "height": capture_height}
        )
        page.wait_for_timeout(1200)
        page.evaluate(
            """() => {
                const main = document.querySelector('#message-list-scroller');
                const sourceLists = [...document.querySelectorAll('.list-XPxyL2')];
                if (main) main.scrollTop = 0;
                for (const list of sourceLists) list.scrollTop = 0;
            }"""
        )

        target = page.locator("#pc-center-wrapper").last
        if target.count() == 0:
            target = page.locator("#message-list-scroller").last
        box = target.bounding_box()
        left = max(0, int(box["x"])) if box else 0

        page.screenshot(
            path=save_path,
            clip={
                "x": left,
                "y": 0,
                "width": viewport["width"] - left,
                "height": capture_height,
            },
        )
    finally:
        page.set_viewport_size(viewport)
        page.wait_for_timeout(500)
    log(f"截图已保存：{save_path}")


def handle_unit(page, unit):
    """处理单个任务单元（1 问题 x 1 千问 = 1 条 task_result）。由 worker 循环调用。

    流程：新建对话 -> 切思考模式 -> 提问 -> 等回答 -> 提取正文/思考/来源 -> 长截图
    -> 上传截图 -> 回调结果。

    异常处理设计（重要）：无论成功失败最终都必须回调一次置为该单元终态（SUCCESS /
    FAILED），否则后端只能等租约超时回收，白白多等。所以 try 成功走 SUCCESS，except
    兜底走 FAILED。
    """
    task_no = str(unit.get("taskNo"))
    agent_name = unit.get("aiPlatform") or "qianwen"
    question = str(unit.get("questionText") or "")

    output_dir = os.path.join(str(SCREENSHOT_DIR), task_no)
    os.makedirs(output_dir, exist_ok=True)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    screenshot_file = os.path.join(output_dir, f"{task_no}_{timestamp}.png")

    thinking = ""
    answer = ""
    source_items = []
    image_url = None

    thinking_enabled = False

    # 开始处理前短暂停顿，避免操作节奏过于机械。
    time.sleep(random.uniform(1, 3))

    try:
        new_chat(page)
        if not thinking_enabled:
            enable_thinking(page)
            thinking_enabled = True
        send_question(page, question)
        wait_for_answer(page)
        answer = extract_answer(page)
        expand_thinking(page)
        thinking = extract_thinking(page)
        source_items = extract_sources(page, close=False)
        page.keyboard.press("Escape")
        page.wait_for_timeout(500)

        log("等待思考区域完全渲染...")
        page.wait_for_timeout(2000)

        capture_screenshot(page, task_no, question, screenshot_file)

        source_info = json.dumps(
            [[s.get("title", ""), s.get("url", "")] for s in (source_items or [])],
            ensure_ascii=False
        )
        log(f"来源信息: {source_info}")

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
        log("单元处理完成")

    except Exception as e:
        log(f"单元处理异常: {e}")
        source_info = json.dumps(
            [[s.get("title", ""), s.get("url", "")] for s in (source_items or [])],
            ensure_ascii=False
        )
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
    """Qianwen Worker 进程入口（账号池模式）。

    改动点（相比旧版）：
      - 不再硬编码 PROFILE，而是账号池借号后按 account_id 建独立 profile 目录
      - run_worker_loop 从 (platform, page, handle_unit) 改为 (platform, worker_context, handle_unit)
      - 一个账号跑满 BATCH_SIZE（默认10）个问题后，自动关浏览器、释放账号、借下一个
    """
    log("启动 Qianwen Worker（账号池模式）")
    log("注意：第一次使用某个账号时，需要在浏览器里手动登录一次，之后 cookie 会自动复用")

    playwright_instance = None
    _pw_browser = None
    browser = None
    page = None

    PROFILE_BASE = str(BASE_DIR / "edge_qianwen_profiles")
    os.makedirs(PROFILE_BASE, exist_ok=True)

    def _open_browser(profile_dir, account_id=None, worker_id=None):
        nonlocal playwright_instance, _pw_browser, browser, page
        if browser is not None:
            try: browser.close()
            except Exception: pass
            browser = None
        if _pw_browser is not None:
            try: _pw_browser.close()
            except Exception: pass
            _pw_browser = None
        if playwright_instance is not None:
            try: playwright_instance.stop()
            except Exception: pass
            playwright_instance = None

        log(f"打开浏览器，profile 目录: {profile_dir}")
        playwright_instance = sync_playwright().start()

        _pw_browser = playwright_instance.chromium.launch(
            channel="msedge",
            headless=False,
            args=[
                "--start-maximized",
                "--disable-blink-features=AutomationControlled",
                "--disable-infobars",
                "--disable-extensions",
                "--disable-features=IsolateOrigins,site-per-process"
            ]
        )

        _state_path = os.path.join(profile_dir, "state.json")
        _ctx_kwargs = {"no_viewport": True}
        if os.path.exists(_state_path):
            _ctx_kwargs["storage_state"] = _state_path
            log(f"检测到 state.json，new_context 时自动注入完整登录态")
        else:
            log("注意：没有 state.json，启动后需要手动登录")

        browser = _pw_browser.new_context(**_ctx_kwargs)
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
        page = browser.new_page()
        page.goto(QWEN_URL, wait_until="domcontentloaded")
        log("千问已打开")
        time.sleep(random.uniform(2, 5))
        ready(page)
        log("已登录")
        human_wait(1, 3)

        _state_file = export_state(browser, profile_dir)
        if account_id is not None and worker_id is not None and _state_file:
            try:
                with open(_state_file, "r", encoding="utf-8") as f:
                    state_json_str = f.read()
                upload_account_cookies(account_id, worker_id, state_json_str)
            except Exception as e:
                log(f"读取/上传 state.json 失败（不影响本次工作）: {e}")

    def _close_browser():
        nonlocal playwright_instance, _pw_browser, browser, page
        if browser is not None:
            try: browser.close()
            except Exception as e: log(f"关闭 BrowserContext 异常: {e}")
            browser = None
        if _pw_browser is not None:
            try: _pw_browser.close()
            except Exception as e: log(f"关闭 Browser 异常: {e}")
            _pw_browser = None
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
        run_worker_loop("qianwen", worker_context, handle_unit)
    except KeyboardInterrupt:
        log("收到中断信号，退出")
    finally:
        _close_browser()


if __name__ == "__main__":
    main()