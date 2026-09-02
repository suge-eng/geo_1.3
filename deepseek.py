"""
deepseek.py —— DeepSeek 平台的 RPA 自动化脚本，是整个 AI 品牌分析系统的「工人」之一。

一、角色定位
------------
这是系统里真正去跑 AI 回答的那一环。它不是一个后端服务，而是一个跑在
Windows 机器上的 Python 进程：用 Playwright 驱动本机 Edge 浏览器登录
DeepSeek 网页版，然后不断从后端「任务池」里认领一个个问题、让 AI 回答、
截图，最后把结果回调给后端。

二、整体流程（一个围绕 handle_unit 的死循环）
---------------------------------------------
1. main() 打开浏览器并登录（登录态持久化到本地 edge_profile 目录，下次免登录）。
2. 进入 run_worker_loop：反复调用服务端接口认领一个「任务单元」。
3. 认领到单元后交给 handle_unit()：
   新建对话 -> 输入问题 -> 开启深度思考 -> 等待回答完成 -> 展开思考/来源
   -> 长截图 -> 提取思考/回答/来源 -> 上传截图 -> 回调结果。
4. 回调成功后回到循环，继续认领下一个单元。

三、为什么这样设计（关键思路）
------------------------------
- 一个单元 = 1 个问题 × 1 个 DeepSeek，粒度最小。这样多台电脑、多用户提交的
  任务能公平并行。认领/回调的并发安全全部由后端 geo-rpa-service 的「租约 + 原子
  认领」保证，本脚本不持有任何全局锁，所以多台电脑跑同一个脚本也不会互相抢。
- 所有 HTTP 相关逻辑（认领 / 心跳 / 回调 / 上传）统一放 worker_lib.py 复用，本脚本
  只专注「DeepSeek 这个网页具体怎么点、怎么取内容」，职责单一、好维护。
- 页面元素操作基本写成「先判断、再操作、异常吞掉并记日志」的防御式写法：
  AI 平台网页经常改版，选择器可能失效，脚本要尽量"能跑就继续、跑不动就回调
  失败"，而不是把整个进程搞崩。
"""
import os
import time
import random
import json
import requests
from datetime import datetime
from playwright.sync_api import sync_playwright, TimeoutError
from PIL import Image

from worker_lib import (
    log,
    upload_screenshot as worker_upload,
    run_worker_loop,
    callback as worker_callback,
)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SCREENSHOT_DIR = os.path.join(BASE_DIR, "screenshots")
EDGE_PROFILE = os.path.join(BASE_DIR, "edge_profile")

PLATFORM_NAME = "deepseek"

os.makedirs(SCREENSHOT_DIR, exist_ok=True)


def human_wait(a=1, b=3):
    """在 [a, b] 秒之间随机停顿，模拟真人操作节奏。

    设计思路：AI 平台通常有反爬机制。如果每次操作间隔都精确固定，容易被识别成
    机器人；用随机间隔让浏览行为更像真人，能降低被封号 / 弹验证码的概率。
    """
    time.sleep(random.randint(a, b))


def wait_answer_finish(page):
    """等待「AI 回答生成完毕」。这是整个 RPA 里最关键、也最容易出问题的一步。

    设计思路：DeepSeek 页面没有标准的"回答完成"标志，所以我们用多个信号同时判断，
    哪个先满足就认为完成，提高鲁棒性（网页改版时不会全军覆没）：
      1. 出现「已思考」按钮       -> 深度思考阶段已结束（相对最可靠）。
      2. 发送按钮重新变为可用     -> AI 已停止生成、可以再次提问。
      3. 页面文本连续 N 次不变
         或页面高度连续 N 次不变  -> 内容不再变化，等价于生成完毕。
    为什么用"稳定计数"而不是"只判一次不变"？因为流式输出过程中文本会不断增长，
    一瞬间的"不变"可能是网络抖动，连续多次不变才代表真正结束了。
    """
    log("等待回答完成")
    start = time.time()
    last_text = ""
    stable_count = 0
    last_height = 0
    height_stable = 0

    while True:
        if time.time() - start > 600:
            log("回答超时")
            return False

        try:
            thinking_btn = page.get_by_text("已思考", exact=False)
            send_btn = page.locator('button[type="submit"]')

            if thinking_btn.count() > 0:
                log("检测到思考按钮，回答已完成")
                time.sleep(5)
                return True

            if send_btn.count() > 0:
                try:
                    if send_btn.first.is_enabled():
                        log("检测到发送按钮可用，回答已完成")
                        time.sleep(5)
                        return True
                except:
                    pass

            text = page.locator("body").inner_text(timeout=3000)
            current_length = len(text)

            if text == last_text:
                stable_count += 1
            else:
                stable_count = 0
                last_text = text

            try:
                current_height = page.evaluate("document.documentElement.scrollHeight")
                if current_height == last_height:
                    height_stable += 1
                else:
                    height_stable = 0
                    last_height = current_height
            except:
                pass

            if stable_count >= 10 or height_stable >= 15:
                log("内容不再变化，回答完成")
                time.sleep(5)
                return True

            elapsed = int(time.time() - start)
            if elapsed % 30 == 0:
                log(f"正在生成中... ({elapsed}秒)")

        except Exception as e:
            log(f"检测异常: {e}")

        time.sleep(2)


def wait_page_stable(page):
    """等待页面渲染稳定：滚动高度连续 3 次不变才算稳定。

    设计思路：AI 回答流式输出结束后，DOM 还会继续重排（图片懒加载、展开动画等），
    此时立刻截图可能截到残缺内容。这里轮询滚动高度、连续稳定 3 次再放行，
    保证后续的截图 / 提取操作都作用在页面最终形态上。
    """
    log("等待页面渲染")
    old_height = 0
    stable = 0

    for i in range(30):
        try:
            height = page.evaluate("document.body.scrollHeight")
            if height == old_height:
                stable += 1
            else:
                stable = 0

            if stable >= 3:
                log("页面稳定")
                return True

            old_height = height
        except:
            pass

        time.sleep(1)

    return False


def open_thinking(page):
    """主动点击展开「深度思考」的折叠区域。

    设计思路：思考内容默认是折叠的，不点开后续就提取不到。但按钮的 class / 文案
    经常变，所以不写死某个选择器，而是注入一段 JS 去"找所有长得像思考条的可见
    元素"逐个尝试点击——容错性最强，改版后依然大概率能命中。
    """
    log("尝试展开思考内容")
    try:
        result = page.evaluate(
            """
            () => {
                let clicked = 0;
                const tryClick = (selector) => {
                    const els = document.querySelectorAll(selector);
                    els.forEach(el => {
                        try {
                            const style = getComputedStyle(el);
                            if (style.display !== 'none' && style.visibility !== 'hidden') {
                                const rect = el.getBoundingClientRect();
                                if (rect.width > 0 && rect.height > 0) {
                                    el.click();
                                    clicked++;
                                }
                            }
                        } catch(e) {}
                    });
                };
                const thinkBars = Array.from(document.querySelectorAll('*')).filter(el => {
                    const t = (el.innerText || '').trim();
                    return t && (t.startsWith('已思考') || (t.includes('思考') && t.includes('用时'))) && 
                           (el.tagName === 'SPAN' || el.tagName === 'DIV' || el.tagName === 'BUTTON' || el.closest('button,[role="button"]'));
                });
                thinkBars.forEach(el => {
                    try {
                        const target = el.closest('button,[role="button"]') || el.parentElement || el;
                        const style = getComputedStyle(target);
                        if (style.display !== 'none' && style.visibility !== 'hidden') {
                            const rect = target.getBoundingClientRect();
                            if (rect.width > 0 && rect.height > 0) {
                                target.click();
                                clicked++;
                            }
                        }
                    } catch(e) {}
                });
                tryClick('div[class*="think" i]');
                tryClick('div[class*="collapsible" i]');
                tryClick('button[aria-expanded="false"]');
                return clicked;
            }
            """
        )
        time.sleep(3)
        log(f"已尝试展开思考区域，触发点击 {result} 次")
        return result > 0
    except Exception as e:
        log(f"展开思考内容失败: {e}")
        return False


def extract_thinking(page):
    """提取「深度思考」内容（保留 HTML 格式，而非纯文本）。

    设计思路：
      1. 保留 HTML 是为了尽量保住原始排版（标题/加粗/列表等），前端展示更好看；
         同时用 cleanHtml 把 script/style/按钮/表单等无关节点和属性剥掉，只留内容。
      2. 用 page.evaluate 注入 JS 而不是 Playwright 选择器，因为思考区容器是
         深层嵌套的特定 class（ds-think-content / ds-markdown），JS 在页面内部
         遍历更灵活，还能在同一个函数里完成"清理 + 拼接"。
      3. 先找标准容器，找不到再退而求其次找任何带 think 的 markdown 节点，
         双重兜底，改版后仍有较大概率拿到内容。
    """
    log("提取思考内容(HTML格式)")
    try:
        thinking_content = page.evaluate(
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
                const parts = [];
                const thinkContainers = document.querySelectorAll('div.ds-think-content');
                for (let tc of thinkContainers) {
                    const md = tc.querySelector('div.ds-markdown');
                    const target = md || tc;
                    const h = cleanHtml(target);
                    const text = (target.innerText || '').trim();
                    if (h && h.length > 10) {
                        parts.push(h);
                    } else if (text && text.length > 10) {
                        parts.push('<div>' + text.replace(/\\n/g, '<br>') + '</div>');
                    }
                }
                if (parts.length === 0) {
                    const allDivs = document.querySelectorAll('div[class*="think" i] div.ds-markdown');
                    for (let div of allDivs) {
                        const h = cleanHtml(div);
                        const text = (div.innerText || '').trim();
                        if (h && h.length > 10) {
                            parts.push(h);
                        } else if (text && text.length > 10) {
                            parts.push('<div>' + text.replace(/\\n/g, '<br>') + '</div>');
                        }
                    }
                }
                return parts.join('<hr>');
            })()
            """
        )
        return thinking_content
    except Exception as e:
        log(f"提取思考内容失败: {e}")
        return ""


def extract_answer(page):
    """提取「回答正文」（保留 HTML 格式）。

    设计思路：
      1. 优先精确定位主回答容器 ds-assistant-message-main-content；
         但为了排除"被插在正文里的思考块"，用 isInsideThink 向上回溯判断该节点
         是否属于思考区，是就跳过——否则会把思考内容也混进回答里。
      2. 精确容器找不到，就退化成"找整个页面里最后一段足够长的 ds-markdown"，
         并再次过滤思考块，作为兜底。
    """
    log("提取回答内容(HTML格式)")
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
                const isInsideThink = (el) => {
                    let node = el;
                    while (node) {
                        if (node.classList && (
                            node.classList.contains('ds-think-content') ||
                            Array.from(node.classList || []).some(c => /think/i.test(c))
                        )) {
                            return true;
                        }
                        node = node.parentElement;
                    }
                    return false;
                };
                const main = document.querySelector('div.ds-markdown.ds-assistant-message-main-content');
                if (main && !isInsideThink(main)) {
                    const h = cleanHtml(main);
                    if (h && h.length > 50) return h;
                }
                const divs = document.querySelectorAll('div.ds-markdown');
                for (let i = divs.length - 1; i >= 0; i--) {
                    const d = divs[i];
                    if (d.classList.contains('ds-assistant-message-main-content')) continue;
                    if (isInsideThink(d)) continue;
                    const text = (d.innerText || '').trim();
                    if (text.length > 100) {
                        const h = cleanHtml(d);
                        if (h && h.length > 50) return h;
                        return '<div>' + text.replace(/\\n/g, '<br>') + '</div>';
                    }
                }
                return '';
            })()
            """
        )
        return answer_content
    except Exception as e:
        log(f"提取回答内容失败: {e}")
        return ""


def extract_sources(page):
    """提取回答里「搜索结果」面板引用的网页来源（标题 + URL 列表）。

    设计思路：DeepSeek 搜索模式下网页来源在一个单独面板里，结构不固定。这里的
    做法是注入 JS：
      1. 先找到写着"搜索结果"的标题节点；
      2. 从标题往上回溯，找出包裹整个来源面板的容器（长得足够宽、足够高、文本够多）；
      3. 在面板里收集所有可见的超链接（a[href] 及 data-href/data-url 等变体），
         排除站内链接、去重，并尽量保留链接的文字作为标题。
    每一步都带"找不到就返回空"的兜底，保证网页改版后顶多是来源为空，不会报错崩溃。
    """
    log("提取网页来源")
    try:
        result = page.evaluate(
            """
            () => {
                const normalize = text =>
                    (text || "")
                    .replace(/\\r/g, "")
                    .replace(/[ \\t]+\\n/g, "\\n")
                    .trim();

                const visible = el => {
                    if (!el) return false;

                    const style = getComputedStyle(el);
                    const rect = el.getBoundingClientRect();

                    return (
                        style.display !== "none" &&
                        style.visibility !== "hidden" &&
                        rect.width > 5 &&
                        rect.height > 5
                    );
                };

                const headings = [
                    ...document.querySelectorAll("body *")
                ].filter(el =>
                    visible(el) &&
                    normalize(el.innerText) === "搜索结果"
                );

                const heading = headings.at(-1);

                if (!heading) {
                    return {
                        panelText: "",
                        urls: []
                    };
                }

                const headingRect = heading.getBoundingClientRect();

                let node = heading.parentElement;
                let panel = null;

                for (let i = 0; node && i < 12; i++) {
                    const rect = node.getBoundingClientRect();
                    const text = normalize(node.innerText);

                    if (
                        rect.width > 250 &&
                        rect.height > 200 &&
                        rect.left >= headingRect.left - 50 &&
                        text.length > 100
                    ) {
                        panel = node;
                        break;
                    }

                    node = node.parentElement;
                }

                if (!panel) {
                    return {
                        panelText: "",
                        urls: []
                    };
                }

                let panelText = normalize(panel.innerText);

                if (panelText.startsWith("搜索结果")) {
                    panelText = panelText
                        .slice("搜索结果".length)
                        .trim();
                }

                const elements = [
                    ...panel.querySelectorAll(
                        "a[href], [data-href], [data-url], [data-link]"
                    )
                ];

                const urls = [];
                const seen = new Set();

                for (const element of elements) {
                    if (!visible(element)) {
                        continue;
                    }

                    const rect = element.getBoundingClientRect();

                    if (
                        rect.left < headingRect.left - 50 ||
                        rect.right > window.innerWidth + 10
                    ) {
                        continue;
                    }

                    let rawUrl =
                        element.href ||
                        element.getAttribute("data-href") ||
                        element.getAttribute("data-url") ||
                        element.getAttribute("data-link") ||
                        "";

                    if (!rawUrl) {
                        continue;
                    }

                    try {
                        rawUrl = new URL(
                            rawUrl,
                            window.location.href
                        ).href;
                    } catch (error) {
                        continue;
                    }

                    if (
                        !rawUrl.startsWith("http://") &&
                        !rawUrl.startsWith("https://")
                    ) {
                        continue;
                    }

                    if (
                        rawUrl.includes("chat.deepseek.com") ||
                        rawUrl.includes("deepseek.com/a/")
                    ) {
                        continue;
                    }

                    if (seen.has(rawUrl)) {
                        continue;
                    }

                    seen.add(rawUrl);

                    urls.push([
                        normalize(element.innerText) || "网页",
                        rawUrl
                    ]);
                }

                return {
                    panelText: panelText,
                    urls: urls
                };
            }
            """
        )

        if not result:
            return []

        urls = result.get("urls") or []
        return urls

    except Exception as e:
        log(f"提取网页来源失败: {e}")
        return []


def open_sources(page):
    """点击展开「个网页」来源列表。

    设计思路：来源默认是折叠的，点开才能被 extract_sources 提取到。文案用"个网页"
    模糊匹配（而不是精确匹配"1个网页/5个网页"），因为数量会变，模糊匹配更稳。
    """
    log("等待来源按钮")
    for i in range(30):
        try:
            btn = page.get_by_text("个网页", exact=False)
            if btn.count() > 0:
                btn.first.click()
                time.sleep(3)
                log("来源已展开")
                return True
        except:
            pass

        time.sleep(1)

    log("没有来源")
    return False


def full_screenshot(page, path):
    """截整页长截图，并裁剪掉多余的空白区域。

    设计思路：
      1. 先滚动到页底若干次，触发懒加载，把未渲染的内容逼出来。
      2. 用 full_page=True 截取整页。
      3. 裁剪：去掉左侧固定约 280px 的侧边栏（那部分不是回答内容，留着又占宽度）；
         再从图片底部向上逐行扫描，把连续 50 行纯白的页脚空白裁掉——
         否则长图会拖着一大段白底，既大又丑。
    全部包在 try/except 里，截图失败就返回 False，由上层决定是否把该单元判失败。
    """
    log(f"长截图:{path}")

    try:
        log("步骤1: 等待页面完全加载")
        page.wait_for_load_state("networkidle")
        time.sleep(5)

        log("步骤2: 滚动到页面底部加载所有内容")
        for _ in range(5):
            page.evaluate("window.scrollTo(0, document.documentElement.scrollHeight)")
            time.sleep(4)

        log("步骤3: 等待所有内容渲染")
        time.sleep(10)

        log("步骤4: 获取最终页面尺寸")
        total_height = page.evaluate("document.documentElement.scrollHeight")
        log(f"最终页面高度: {total_height}px")

        log("步骤5: 截取整个页面")
        page.screenshot(path=path, full_page=True)

        log("步骤6: 裁剪图片")
        img = Image.open(path)
        log(f"原始图片尺寸: {img.width}x{img.height}")

        left_sidebar_width = 280

        pixels = img.load()
        content_bottom = img.height

        consecutive_empty_lines = 0
        max_empty_lines = 50
        content_threshold = 5

        for y in range(img.height - 1, 0, -1):
            content_pixels = 0
            for x in range(left_sidebar_width, img.width):
                r, g, b = pixels[x, y]
                if (r, g, b) != (255, 255, 255):
                    content_pixels += 1
                    if content_pixels >= content_threshold:
                        break

            if content_pixels >= content_threshold:
                consecutive_empty_lines = 0
            else:
                consecutive_empty_lines += 1

            if consecutive_empty_lines >= max_empty_lines:
                content_bottom = y + max_empty_lines + 30
                break

        cropped_img = img.crop((left_sidebar_width, 0, img.width, content_bottom))
        cropped_img.save(path)
        log(f"裁剪后图片尺寸: {cropped_img.width}x{cropped_img.height}")

        log(f"长截图完成: {path}")
        return True

    except Exception as e:
        log(f"截图失败:{e}")
        return False


def is_logged_in(page):
    """判断当前是否已登录 DeepSeek。

    设计思路：不依赖单一元素，而是"多个登录标志"做或判断——未登录会出现「登录 /
    Sign In」按钮；已登录则会出现输入框或「新对话」按钮。用多信号是因为登录态
    不同阶段页面元素不同，单靠一个标志容易误判。
    """
    try:
        login_btn = page.get_by_text("登录", exact=False)
        if login_btn.count() > 0:
            log("检测到登录按钮，未登录")
            return False

        signin_btn = page.get_by_text("Sign In", exact=False)
        if signin_btn.count() > 0:
            log("检测到Sign In按钮，未登录")
            return False

        textarea = page.locator("textarea")
        if textarea.count() > 0:
            log("检测到输入框，已登录")
            return True

        new_btn = page.get_by_text("新对话", exact=False)
        if new_btn.count() > 0:
            log("检测到新对话按钮，已登录")
            return True

        return False
    except Exception as e:
        log(f"登录检测异常: {e}")
        return False


def wait_for_login(page):
    """最多轮询 120 秒等待用户（或已保存的登录态）完成登录。

    设计思路：如果是首次运行，浏览器打开后需要用户手动扫码/输密码登录；
    这里不阻塞等待一个固定时间，而是每秒检查一次 is_logged_in，提前登录好就提前
    往下走，超时则放弃并退出。因为登录态已持久化到本地，通常之后都不用再等。
    """
    log("等待登录...")
    for i in range(120):
        if is_logged_in(page):
            log("已登录")
            return True
        time.sleep(1)
    log("登录超时")
    return False


def new_chat(page):
    """每处理一个新问题前「新建对话」，保证各问题互不干扰。

    设计思路：如果多个问题共用一个会话，前一个问题的上下文会污染后一个问题，
    导致 AI 的回答串味。每个单元独立开一个新对话，让每次提问都在干净上下文里进行，
    结果才可复现、可对比。
    """
    log("新建对话")
    try:
        new_btn = page.get_by_text("新对话", exact=False)
        if new_btn.count() > 0:
            new_btn.first.click()
            time.sleep(3)
            return True

        plus_btn = page.locator('button:has(svg)')
        if plus_btn.count() > 0:
            plus_btn.first.click()
            time.sleep(3)
            return True
    except Exception as e:
        log(f"新建对话失败:{e}")

    return False


def handle_unit(page, unit):
    """处理单个任务单元（1 问题 x 1 DeepSeek = 1 条 task_result）。由 worker 循环调用。

    单元即后端任务池派发过来的最小工作项，unit 包含 id/taskNo/aiPlatform/questionText。

    完整流程：
      新建对话 -> 输入问题 -> 开启深度思考 -> 发送 -> 等待回答完成 -> 展开思考/来源
      -> 长截图 -> 提取思考/回答/来源 -> 上传截图 -> 回调结果。

    异常处理设计（很重要）：
      无论成功还是失败，最终都必须调用 worker_callback 把该单元置为终态
      （SUCCESS 或 FAILED）。这样后端才能真正结束这个单元；如果这里抛异常又不回调，
      后端只能等租约超时再回收，白白多等很久。所以 try 里成功走 SUCCESS 回调，
      except 里兜底走 FAILED 回调，保证"每个认领到的单元一定有结局"。
    """
    task_no = str(unit.get("taskNo"))
    agent_name = unit.get("aiPlatform") or PLATFORM_NAME
    question = str(unit.get("questionText") or "")

    output_dir = os.path.join(SCREENSHOT_DIR, task_no)
    os.makedirs(output_dir, exist_ok=True)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    screenshot_file = os.path.join(output_dir, f"{task_no}_{timestamp}.png")

    thinking = ""
    answer = ""
    sources = []
    image_url = None

    try:
        new_chat(page)
        human_wait(2, 5)

        textarea = page.locator("textarea")
        textarea.wait_for(timeout=60000)
        textarea.fill(question)

        log("尝试启用深度思考")
        try:
            locator = page.get_by_text("深度思考", exact=True)
            for i in range(locator.count()):
                button = locator.nth(i)
                if not button.is_visible():
                    continue
                state = button.evaluate(
                    """
                    el => {
                        const node =
                            el.closest('button,[role="button"]')
                            || el.parentElement
                            || el;
                        const values = [
                            node.getAttribute('aria-pressed'),
                            node.getAttribute('aria-selected'),
                            node.getAttribute('data-selected'),
                            node.getAttribute('data-active'),
                            node.getAttribute('data-state')
                        ];
                        return values
                            .filter(Boolean)
                            .join('|')
                            .toLowerCase();
                    }
                    """
                )
                if any(x in state for x in ["true", "on", "active", "selected"]):
                    log("深度思考已启用")
                    break
                if any(x in state for x in ["false", "off", "inactive"]):
                    button.click()
                    time.sleep(0.8)
                    log("已开启深度思考")
                    break
                button.click()
                time.sleep(0.8)
                log("已尝试开启深度思考")
                break
        except Exception as e:
            log(f"启用深度思考失败: {e}")

        textarea.press("Enter")

        ok = wait_answer_finish(page)
        if not ok:
            raise Exception("回答生成超时")

        wait_page_stable(page)

        log("尝试展开思考内容...")
        open_thinking(page)
        time.sleep(5)

        log("尝试展开来源...")
        open_sources(page)
        time.sleep(5)

        log("再次等待页面稳定...")
        wait_page_stable(page)
        time.sleep(3)

        log("截图...")
        success = full_screenshot(page, screenshot_file)
        if not success or not os.path.exists(screenshot_file):
            raise Exception("截图失败")

        log("提取内容...")
        thinking = extract_thinking(page)
        answer = extract_answer(page)
        sources = extract_sources(page)

        source_info = json.dumps(sources, ensure_ascii=False) if sources else ""

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


def main():
    """进程入口：打开浏览器、登录、然后进入认领循环。

    关键设计：
      - launch_persistent_context(user_data_dir=EDGE_PROFILE, ...)
        用"持久化上下文"启动，把登录态/Cookie 存到本地 edge_profile 目录，
        下次启动免登录，这是 RPA 常驻脚本的标配做法。
      - channel="msedge"：直接复用系统自带的 Edge，不用 Playwright 额外下载浏览器。
      - headless=False：必须"有头"运行，因为要人工扫码登录，也便于出错时肉眼排查。
      - --disable-blink-features=AutomationControlled：隐藏浏览器自动化特征，
        降低被网站识别为机器人的概率。
    """
    log("启动 DeepSeek Worker（认领模式，无全局锁）")
    log("打开浏览器...")
    with sync_playwright() as p:
        browser = p.chromium.launch_persistent_context(
            user_data_dir=EDGE_PROFILE,
            channel="msedge",
            headless=False,
            viewport={"width": 1920, "height": 6000},
            args=[
                "--disable-blink-features=AutomationControlled",
                "--no-sandbox",
                "--disable-setuid-sandbox"
            ]
        )

        if browser.pages:
            page = browser.pages[0]
        else:
            page = browser.new_page()

        page.goto("https://chat.deepseek.com", wait_until="domcontentloaded")
        log("DeepSeek 已打开")

        if not wait_for_login(page):
            log("无法登录，退出")
            browser.close()
            return

        log("开始从任务池认领单元...")
        try:
            run_worker_loop(PLATFORM_NAME, page, handle_unit)
        except KeyboardInterrupt:
            log("收到中断信号，退出")
        finally:
            browser.close()


if __name__ == "__main__":
    main()