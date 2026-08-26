/**
 * 按运行模式加载业务脚本。
 * DEMO_MODE=false 时不会下载或执行 mock-data.js。
 */
(function bootstrap() {
    const config = window.GEO_APP_CONFIG || { DEMO_MODE: false }
    const version = '20260826_handoff_v5'

    function loadScript(src) {
        return new Promise((resolve, reject) => {
            const script = document.createElement('script')
            script.src = src
            script.onload = resolve
            script.onerror = () => reject(new Error(`脚本加载失败：${src}`))
            document.body.appendChild(script)
        })
    }

    const loadApp = () => loadScript(`js/app.js?v=${version}`)
    const start = config.DEMO_MODE
        ? loadScript(`js/mock-data.js?v=${version}`).then(loadApp)
        : loadApp()

    start.catch(error => {
        console.error(error)
        document.body.insertAdjacentHTML('beforeend', '<div style="padding:16px;color:#d93025">前端脚本加载失败，请检查静态资源路径。</div>')
    })
})()