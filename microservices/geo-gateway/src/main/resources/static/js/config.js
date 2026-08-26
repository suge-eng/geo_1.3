/**
 * 前端运行配置。
 *
 * 正式联调/生产环境必须保持 DEMO_MODE: false，所有数据均从后端接口获取。
 * 仅在独立演示页面时临时改为 true，刷新页面后读取 mock-data.js。
 */
window.GEO_APP_CONFIG = Object.freeze({
    DEMO_MODE: false,
    API_BASE_URL: ''
})