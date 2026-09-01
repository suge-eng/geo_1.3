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
    time.sleep(random.randint(a, b))


def wait_answer_finish(page):
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
    log("等待登录...")
    for i in range(120):
        if is_logged_in(page):
            log("已登录")
            return True
        time.sleep(1)
    log("登录超时")
    return False


def new_chat(page):
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
    """处理单个任务单元（1 问题 x 1 DeepSeek = 1 条 task_result）。由 worker 循环调用。"""
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
    log("启动 DeepSeek Worker（认领模式，无全局锁）")
    log("打开浏览器...")
    with sync_playwright() as p:
        browser = p.chromium.launch_persistent_context(
            user_data_dir=EDGE_PROFILE,
            channel="msedge",
            headless=False,
            viewport={"width": 1920, "height": 600},
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