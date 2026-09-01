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
SCREENSHOT_DIR = BASE_DIR / "qianwen_screenshots"
PROFILE = BASE_DIR / "edge_qianwen_profile"

PLATFORM_NAME = "qianwen"

QWEN_URL = "https://www.qianwen.com/chat"

SCREENSHOT_DIR.mkdir(exist_ok=True)


def log(msg):
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def visible(locator):
    try:
        return locator.count() > 0 and locator.is_visible()
    except Exception:
        return False


def input_box(page):
    locator = page.locator(
        '[data-chat-input-body="true"] '
        '[role="textbox"][contenteditable="true"][data-slate-editor="true"]'
    )
    if locator.count() > 0 and locator.last.is_visible():
        return locator.last
    return None


def wait_for_login(page):
    log("等待登录后的输入框")
    deadline = time.time() + 300
    while time.time() < deadline:
        box = input_box(page)
        if box is not None:
            return
        page.wait_for_timeout(1000)
    raise RuntimeError("等待千问登录超时")


def ready(page):
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
    answers = page.locator(".answer-common-card")
    for index in range(answers.count() - 1, -1, -1):
        answer = answers.nth(index)
        if answer.is_visible():
            return answer
    return None


def wait_for_answer(page, timeout_minutes=20):
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
    """展开当前回答的思考区域，便于阅读和截图。"""
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
    """保存最新问题、完整思考、回答和右侧来源长截图。"""
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
    """处理单个任务单元（1 问题 x 1 千问 = 1 条 task_result）。由 worker 循环调用。"""
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


def main():
    log("启动 Qianwen Worker（认领模式，无全局锁）")
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

        page.goto(QWEN_URL, wait_until="domcontentloaded")
        log("千问已打开")

        ready(page)

        log("开始从任务池认领单元...")
        try:
            run_worker_loop("qianwen", page, handle_unit)
        except KeyboardInterrupt:
            log("收到中断信号，退出")
        finally:
            browser.close()


if __name__ == "__main__":
    main()