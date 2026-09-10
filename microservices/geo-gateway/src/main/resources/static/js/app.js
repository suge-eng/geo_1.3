/**
 * geo-gateway 前端主逻辑（Vue3 + ECharts + axios）。
 *
 * 整份文件是一个 Vue 应用实例，负责：
 *   1. 管理页面状态（当前页签、任务列表、表单、报告数据、各类弹窗等）；
 *   2. 通过 axios 调用后端接口（由 geo-gateway 网关转发到各微服务）；
 *   3. 用轮询方式定时刷新任务进度与结果（配合后端 WebSocket 推送）；
 *   4. 用 ECharts 把报告数据绘制成图表。
 *
 * 顶部通过 window.GEO_APP_CONFIG 读取后端地址等配置（见 js/config.js），
 * 并据此判断是否处于“演示模式”（DEMO_MODE=true 时改用 mock-data.js 的假数据）。
 */
const { createApp, ref, computed } = Vue

// 读取全局配置；config.js 里没定义时用默认值兜底
const APP_CONFIG = window.GEO_APP_CONFIG || { DEMO_MODE: false, API_BASE_URL: '' }
const DEMO_MODE = APP_CONFIG.DEMO_MODE === true
const MOCK_DATA = window.GEO_MOCK_DATA || null

// 若配置了后端地址，就设为 axios 的统一前缀，之后接口路径只需写 /api/... 即可
if (APP_CONFIG.API_BASE_URL) {
    axios.defaults.baseURL = APP_CONFIG.API_BASE_URL
}
axios.defaults.headers.post['Content-Type'] = 'application/json'
// 请求拦截器：保证 POST 请求至少带一个空对象作为请求体，避免后端因缺失 body 报错
axios.interceptors.request.use(config => {
    if (config.method === 'post' && config.data === undefined) {
        config.data = {}
    }
    return config
})

const __geoApp = createApp({
    // data() 返回的对象里的每一项都是“响应式数据”：值变了，页面会自动跟着更新。
    // 这里集中管理全部页面状态，下面按用途分组说明几个最关键的：
    //   activeTab / currentView : 控制当前展示哪个页签 / 哪个子视图；
    //   taskForm               : 新建任务的表单数据（Vue 的 v-model 双向绑定对象）；
    //   taskList / taskResults : 任务列表、某任务的观测结果；
    //   reportData             : 数据报告的内容，是渲染图表的直接数据源。
    data() {
        return {
            demoMode: DEMO_MODE,
            activeTab: 'list',
            submitMode: 'manual',
            searchKeyword: '',
            detailKeyword: '',
            detailLevel: 'ALL',
            detailVisibility: 'ALL',
            detailSentiment: 'ALL',
            detailPlatform: 'ALL',
            detailStatus: 'ALL',
            taskForm: {
                title: '',
                aiPlatforms: [],
                questionsText: '',
                brandName: '',
                productName: '',
                competitors: ['', '', '', '', ''],
                executionFrequency: 'single',
                retryOnFailure: false
            },
            selectedFile: null,
            isDragOver: false,
            whitelistFile: null,
            whitelistUrls: [],
            isWhitelistDragOver: false,
            aiPlatforms: [
                { value: 'doubao', label: '豆包' },
                { value: 'doubao_app', label: '豆包APP' },
                { value: 'deepseek', label: 'DeepSeek' },
                { value: 'deepseek_app', label: 'DeepSeek APP' },
                { value: 'qianwen', label: '通义千问' },
                { value: 'qianwen_app', label: '通义千问 APP' },
                { value: 'tencent', label: '腾讯元宝' },
                { value: 'tencent_app', label: '腾讯元宝APP' },
                { value: 'kimi', label: 'Kimi' },
                { value: 'kimi_app', label: 'Kimi APP' },
                { value: 'wenxin', label: '文心一言' },
                { value: 'wenxin_app', label: '文心一言APP' }
            ],
            frequencies: [
                { value: 'single', label: '单次' },
                { value: 'daily', label: '每天' },
                { value: 'weekly', label: '每周' }
            ],
            taskList: [],
            selectedTask: null,
            taskResults: [],
            taskResultsTaskNo: null,
            reportPlatformFallbacks: [],
            selectedResultIds: [],
            progress: { total: 0, completed: 0, percentage: 0 },
            isSubmitting: false,
            isRetrying: false,
            toast: { show: false, message: '', type: 'success' },
            progressInterval: null,
            resultInterval: null,
            imageModal: {
                show: false,
                url: '',
                index: 0,
                zoom: 1,
                fitZoom: 1,
                naturalWidth: 0,
                naturalHeight: 0
            },
            imagePan: {
                active: false,
                pointerId: null,
                startX: 0,
                startY: 0,
                scrollLeft: 0,
                scrollTop: 0
            },
            qaModal: { show: false, data: {} },
            screenshotModal: { show: false, data: { screenshotUrls: [] } },
            networkModal: { show: false, data: { sources: [] } },
            rankingModal: {
                show: false,
                loading: false,
                resultId: null,
                questionText: '',
                aiDisplayName: '',
                status: '',
                answerText: '',
                brandRankings: []
            },
            _rankingCache: {},
            currentView: 'list',
            showReport: false,
            reportLoading: false,
            reportData: {},
            isGlobalReport: false,
            comparisonPlatform: 'ALL',
            voicePlatform: 'ALL',
            radarMetric: 'mentionRate',
            keywordCloudMode: 'positive',
            // ========== 任务池看板数据（不需要看板时，连同 methods.loadPool / mounted 轮询一起注释掉）==========
            pool: { statusCount: [], platformCount: [], workerCount: [], runningUnits: [] },
            poolLoading: false,
            poolInterval: null,
            poolDialect: { PENDING: '排队中', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败', PARTIAL_FAILED: '部分失败', CANCELLED: '已取消', TIMEOUT: '超时' },
            // ========== 任务池看板数据 END ==========
            activeChartTab: '',
            chartRefs: {},
            reportHistoryTask: null,
            reportHistoryList: [],
            reportHistoryLoading: false,
            reportHistoryFromView: 'list'
        }
    },
    computed: {
        // 计算属性：不直接存值，而是根据其它数据“计算”得出，且结果会被缓存。
        // 页面模板里用到的 filteredTaskResults、canSubmit、competitionRankingData 等都是此类。

        // 根据详情页的筛选条件（关键词/层级/展现/舆情/平台/状态）过滤观测结果
        filteredTaskResults() {
            const keyword = this.detailKeyword.trim().toLowerCase()
            return this.taskResults.filter(result => {
                const searchableText = [result.questionText, result.level, result.tag, result.intentWord, result.brand, result.aiDisplayName]
                    .filter(Boolean)
                    .join(' ')
                    .toLowerCase()
                return (!keyword || searchableText.includes(keyword)) &&
                    (this.detailLevel === 'ALL' || result.level === this.detailLevel) &&
                    (this.detailVisibility === 'ALL' || result.showStatus === this.detailVisibility) &&
                    (this.detailSentiment === 'ALL' || result.sentiment === this.detailSentiment) &&
                    (this.detailPlatform === 'ALL' || result.aiDisplayName === this.detailPlatform) &&
                    (this.detailStatus === 'ALL' || result.status === this.detailStatus)
            })
        },
        selectedTaskResults() {
            const selectedIds = new Set(this.selectedResultIds)
            return this.taskResults.filter(result => selectedIds.has(result.id))
        },
        allFilteredResultsSelected() {
            return this.filteredTaskResults.length > 0 && this.filteredTaskResults.every(result => this.selectedResultIds.includes(result.id))
        },
        someFilteredResultsSelected() {
            return this.filteredTaskResults.some(result => this.selectedResultIds.includes(result.id))
        },
        reportPlatformOptions() {
            const options = [{ value: 'ALL', label: '全局' }]
            const seen = new Set(['ALL'])
            const appendOption = rawPlatform => {
                const option = this.normalizeReportPlatformOption(rawPlatform)
                if (!option || seen.has(option.value)) return
                seen.add(option.value)
                options.push(option)
            }

            const taskPlatforms = this.selectedTask?.aiPlatforms
                || this.selectedTask?.aiPlatformList
                || this.selectedTask?.platformCodes
                || (typeof this.selectedTask?.platforms === 'string' || Array.isArray(this.selectedTask?.platforms)
                    ? this.selectedTask.platforms
                    : [])
            const reportPlatformSources = [
                this.reportData.availablePlatforms,
                this.reportData.platforms,
                this.reportData.aiPlatforms,
                this.reportData.platformList,
                this.reportData.platformNames,
                this.reportPlatformFallbacks
            ]
            const competition = this.reportData.competition || {}
            const platformContainers = [
                competition.platformBreakdown,
                competition.byPlatform,
                this.reportData.platformCompetition,
                this.reportData.platformReports,
                this.reportData.platformBreakdown,
                this.reportData.platformStats,
                this.reportData.byPlatform
            ].filter(Boolean)
            const taskPlatformList = Array.isArray(taskPlatforms)
                ? taskPlatforms
                : (typeof taskPlatforms === 'string'
                    ? taskPlatforms.replace(/^\[|\]$/g, '').split(',').map(item => item.trim().replace(/^['"]|['"]$/g, '')).filter(Boolean)
                    : (taskPlatforms ? [taskPlatforms] : []))

            taskPlatformList.forEach(appendOption)
            reportPlatformSources.forEach(source => {
                if (Array.isArray(source)) {
                    source.forEach(appendOption)
                } else if (source && typeof source === 'object') {
                    Object.entries(source).forEach(([value, item]) => appendOption({
                        value,
                        label: item?.platformName || item?.aiDisplayName || item?.label || item?.name
                    }))
                } else if (typeof source === 'string') {
                    source.replace(/^\[|\]$/g, '').split(',').map(item => item.trim()).filter(Boolean).forEach(appendOption)
                }
            })
            this.taskResults.forEach(result => appendOption({
                value: result.aiPlatform || result.platformCode || result.platform,
                label: result.aiDisplayName || result.platformName || result.aiPlatformName
            }))

            platformContainers.forEach(container => {
                if (Array.isArray(container)) {
                    container.forEach(appendOption)
                } else if (container && typeof container === 'object') {
                    Object.entries(container).forEach(([value, item]) => appendOption({
                        value,
                        label: item?.platformName || item?.aiDisplayName || item?.label || item?.name
                    }))
                }
            })

            return options
        },
        comparisonCompetition() {
            return this.getCompetitionForPlatform(this.comparisonPlatform)
        },
        voiceCompetition() {
            return this.getCompetitionForPlatform(this.voicePlatform)
        },
        competitionRankingData() {
            const platform = this.voicePlatform && this.voicePlatform !== 'ALL' ? this.voicePlatform : this.comparisonPlatform
            const comp = this.getCompetitionForPlatform(platform)
            if (!comp || !Array.isArray(comp.metrics) || !Array.isArray(comp.brands) || !comp.brands.length) {
                return this.reportData.competitionRanking || this.reportData.competitionTop10 || []
            }
            const row = comp.metrics.find(m => m.label === '品牌竞争力')
            if (!row || !Array.isArray(row.values)) {
                return this.reportData.competitionRanking || this.reportData.competitionTop10 || []
            }
            const selfBrand = (this.reportData.brandName || '').trim().toLowerCase()
            const list = comp.brands.map((b, idx) => {
                const raw = row.values[idx]
                let num = 0
                if (typeof raw === 'string') {
                    num = Number(raw.replace('%', '').trim())
                } else if (typeof raw === 'number') {
                    num = raw
                }
                if (!Number.isFinite(num)) num = 0
                return {
                    brandName: b.name,
                    isSelfBrand: selfBrand && b.name ? selfBrand === String(b.name).toLowerCase() : false,
                    score: Number.isInteger(num) ? String(num) : num.toFixed(2),
                    scoreNum: num
                }
            })
            list.sort((a, b) => b.scoreNum - a.scoreNum)
            return list.map((it, idx) => ({
                rank: idx + 1,
                brandName: it.brandName,
                isSelfBrand: it.isSelfBrand,
                score: it.score,
                trend: ''
            }))
        },
        platformScoreCards() {
            return this.getPlatformScoreCards()
        },
        // 判断“创建任务”按钮能否点击：手动模式要标题/平台/询问句/品牌填齐，Excel 模式要选文件
        canSubmit() {
            if (this.submitMode === 'manual') {
                return this.taskForm.title.trim() &&
                       this.taskForm.aiPlatforms.length > 0 &&
                       this.taskForm.questionsText.trim() &&
                       this.taskForm.brandName.trim()
            } else {
                return this.taskForm.title.trim() &&
                       this.taskForm.aiPlatforms.length > 0 &&
                       this.selectedFile &&
                       this.taskForm.brandName.trim()
            }
        }
    },
    // 组件挂载后立刻执行：先加载一次任务列表，并按 5 秒间隔轮询“任务池看板”
    mounted() {
        this.loadTaskList()
        // ========== 任务池看板轮询（不需要看板时，把下面 3 行注释掉即可）==========
        this.loadPool()
        this.poolInterval = setInterval(() => this.loadPool(), 5000)
        // ========== 任务池看板轮询 END ==========
    },
    watch: {
        // 监听数据变化后执行副作用：当报告数据整体变化时，把平台筛选重置为“全局”
        reportData() {
            this.comparisonPlatform = 'ALL'
            this.voicePlatform = 'ALL'
        },
        // 雷达图指标切换时重新绘制竞争图表
        radarMetric() {
            this.$nextTick(() => this.renderCompetitionCharts())
        },
        // 深度监听曝光指标：报告里的覆盖率/首位率等变化时，重新绘制环形图
        'reportData.exposureMetrics': {
            handler() {
                if (this.currentView === 'report' && !this.reportLoading) {
                    this.$nextTick(() => {
                        setTimeout(() => {
                            this.renderExposureRings()
                        }, 200)
                    })
                }
            },
            deep: true
        }
    },
    // 捕获子组件渲染时的错误并打日志，返回 false 表示“继续向上传递”，目的是避免整页白屏
    errorCaptured(err, vm, info) {
        console.error('[Vue errorCaptured] 阻止白屏，捕获渲染错误:', { err, info, message: err?.message, stack: err?.stack })
        try {
            const tags = vm?.$options?._componentTag || vm?.type?.name || vm?.$vnode?.tag || ''
            console.error('[Vue errorCaptured] 出错组件/实例标签:', tags, '父组件key:', vm?.$vnode?.key, 'props:', JSON.stringify(vm?.$props || {}).slice(0, 500))
        } catch (_) {}
        return false
    },
    // 当根组件本身渲染出错时，降级显示一段错误提示，避免白屏
    renderError(h, err) {
        console.error('[Vue renderError] 渲染阶段出错，降级为错误提示:', err?.message, err?.stack)
        return h('div', {
            style: { padding: '20px', color: '#c33', background: '#fff5f5', borderRadius: '8px', margin: '10px', fontSize: '14px' }
        }, ['页面渲染出错：' + (err?.message || '未知错误') + '，请打开控制台查看详细堆栈'])
    },
    // 组件销毁前清理所有定时器，避免页面关闭后后台还在轮询
    beforeUnmount() {
        if (this.progressInterval) clearInterval(this.progressInterval)
        if (this.resultInterval) clearInterval(this.resultInterval)
        if (this.poolInterval) clearInterval(this.poolInterval)
    },
    // methods 里是页面所有可调用的方法，模板中的 @click、@change 等事件都指向这里。
    // 大致分几类：任务提交/操作、结果筛选与导出、弹窗控制、报告加载、图表渲染、工具函数。
    methods: {
        // 创建任务：手动模式走 JSON 接口，Excel 模式用 multipart 表单上传文件
        async createTask() {
            if (!this.canSubmit) return
            if (DEMO_MODE) {
                this.showToast('演示模式不会创建真实任务', 'success')
                return
            }
            this.isSubmitting = true
            try {
                const competitors = this.taskForm.competitors.filter(c => c.trim())
                const whitelistUrls = this.whitelistUrls && this.whitelistUrls.length > 0 ? this.whitelistUrls : null

                if (this.submitMode === 'manual') {
                    const questions = this.taskForm.questionsText.split('\n').filter(q => q.trim())
                    await axios.post('/api/task/create', {
                        aiPlatforms: this.taskForm.aiPlatforms,
                        questions: questions,
                        title: this.taskForm.title,
                        brandName: this.taskForm.brandName,
                        productName: this.taskForm.productName,
                        competitors: competitors,
                        executionFrequency: this.taskForm.executionFrequency,
                        retryOnFailure: this.taskForm.retryOnFailure,
                        scope: 'LOCAL',
                        whitelistUrls: whitelistUrls
                    })
                } else {
                    const formData = new FormData()
                    formData.append('file', this.selectedFile)
                    this.taskForm.aiPlatforms.forEach(platform => {
                        formData.append('aiPlatforms', platform)
                    })
                    formData.append('title', this.taskForm.title)
                    formData.append('brandName', this.taskForm.brandName)
                    if (this.taskForm.productName) formData.append('productName', this.taskForm.productName)
                    competitors.forEach(c => {
                        formData.append('competitors', c)
                    })
                    formData.append('executionFrequency', this.taskForm.executionFrequency)
                    formData.append('retryOnFailure', this.taskForm.retryOnFailure)
                    formData.append('scope', 'LOCAL')
                    if (whitelistUrls && whitelistUrls.length > 0) {
                        formData.append('whitelistUrls', JSON.stringify(whitelistUrls))
                    }

                    await axios.post('/api/task/create/excel', formData, {
                        headers: {
                            'Content-Type': 'multipart/form-data'
                        }
                    })
                }

                this.showToast('任务创建成功', 'success')
                this.resetForm()
                this.activeTab = 'list'
                this.loadTaskList()
            } catch (error) {
                this.showToast(error.response?.data?.message || '创建失败', 'error')
            } finally {
                this.isSubmitting = false
            }
        },

        async submitTask(task) {
            if (DEMO_MODE && task.isDemo) {
                this.showToast('演示模式不会提交真实任务', 'success')
                return
            }
            this.isSubmitting = true
            try {
                await axios.post(`/api/task/${task.taskNo}/submit`, {
                    executionFrequency: task.executionFrequency || 'single',
                    retryOnFailure: task.retryOnFailure || false
                })
                this.showToast('任务已提交', 'success')
                this.loadTaskList()
            } catch (error) {
                this.showToast(error.response?.data?.message || '提交失败', 'error')
            } finally {
                this.isSubmitting = false
            }
        },

        async deleteTask(task) {
            if (!confirm(`确定要删除任务「${task.title || '未命名任务'}」吗？`)) return
            if (DEMO_MODE && task.isDemo) {
                this.taskList = this.taskList.filter(item => item.taskNo !== task.taskNo)
                this.showToast('演示任务已从当前页面移除，刷新后会恢复', 'success')
                return
            }
            try {
                await axios.delete(`/api/task/${task.taskNo}`)
                this.showToast('任务已删除', 'success')
                this.loadTaskList()
            } catch (error) {
                this.showToast(error.response?.data?.message || '删除失败', 'error')
            }
        },

        async submitTaskAgain(task) {
            if (DEMO_MODE && task.isDemo) {
                this.showToast('演示模式不会重新提交真实任务', 'success')
                return
            }
            this.isSubmitting = true
            try {
                await axios.post(`/api/task/${task.taskNo}/retry/all`)
                this.showToast('任务已重新提交', 'success')
                this.loadTaskList()
            } catch (error) {
                this.showToast(error.response?.data?.message || '提交失败', 'error')
            } finally {
                this.isSubmitting = false
            }
        },

        resetForm() {
            this.taskForm = {
                title: '',
                aiPlatforms: [],
                questionsText: '',
                brandName: '',
                productName: '',
                competitors: ['', '', '', '', ''],
                executionFrequency: 'single',
                retryOnFailure: false
            }
            this.selectedFile = null
            this.whitelistFile = null
            this.whitelistUrls = []
        },

        triggerFileInput() {
            const el = this.$refs.fileInput
            if (el) {
                el.value = ''
                setTimeout(() => el.click(), 0)
            }
        },

        handleFileSelect(event) {
            const file = event.target.files[0]
            if (file) {
                this.validateAndSetFile(file)
            }
        },

        handleDrop(event) {
            this.isDragOver = false
            const file = event.dataTransfer.files[0]
            if (file) {
                this.validateAndSetFile(file)
            }
        },

        validateAndSetFile(file) {
            const validExtensions = ['.xlsx', '.xls']
            const extension = file.name.toLowerCase().substring(file.name.lastIndexOf('.'))
            if (!validExtensions.includes(extension)) {
                this.showToast('请上传有效的Excel文件（.xlsx或.xls）', 'error')
                return
            }
            this.selectedFile = file
        },

        clearFile() {
            this.selectedFile = null
            if (this.$refs.fileInput) {
                this.$refs.fileInput.value = ''
            }
        },

        triggerWhitelistFileInput() {
            const el = this.$refs.whitelistFileInput
            if (el) {
                el.value = ''
                setTimeout(() => el.click(), 0)
            }
        },

        handleWhitelistFileSelect(event) {
            const file = event.target.files[0]
            if (file) {
                this.validateAndSetWhitelistFile(file)
            }
        },

        handleWhitelistDrop(event) {
            this.isWhitelistDragOver = false
            const file = event.dataTransfer.files[0]
            if (file) {
                this.validateAndSetWhitelistFile(file)
            }
        },

        async validateAndSetWhitelistFile(file) {
            const validExtensions = ['.xlsx', '.xls']
            const extension = file.name.toLowerCase().substring(file.name.lastIndexOf('.'))
            if (!validExtensions.includes(extension)) {
                this.showToast('请上传有效的Excel文件（.xlsx或.xls）', 'error')
                return
            }
            this.whitelistFile = file
            this.whitelistUrls = []

            try {
                const formData = new FormData()
                formData.append('file', file)
                const response = await axios.post('/api/task/parse-whitelist', formData, {
                    headers: { 'Content-Type': 'multipart/form-data' }
                })
                if (response.data && response.data.data) {
                    this.whitelistUrls = response.data.data
                    this.showToast(`白名单解析成功，共 ${this.whitelistUrls.length} 个网址`, 'success')
                }
            } catch (error) {
                console.error('白名单解析失败:', error)
                this.showToast(error.response?.data?.message || '白名单解析失败', 'error')
            }
        },

        clearWhitelistFile() {
            this.whitelistFile = null
            this.whitelistUrls = []
            if (this.$refs.whitelistFileInput) {
                this.$refs.whitelistFileInput.value = ''
            }
        },

        async downloadTemplate() {
            if (DEMO_MODE) {
                this.showToast('演示模式未连接 Excel 模板接口', 'error')
                return
            }
            try {
                const response = await axios.get('/api/task/excel-template', {
                    responseType: 'blob'
                })
                const url = window.URL.createObjectURL(new Blob([response.data]))
                const link = document.createElement('a')
                link.href = url
                const contentDisposition = response.headers['content-disposition']
                let fileName = 'Excel问题模板.xlsx'
                if (contentDisposition) {
                    const match = contentDisposition.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i)
                    if (match) {
                        fileName = decodeURIComponent(match[1].replace(/"/g, ''))
                    }
                }
                link.download = fileName
                document.body.appendChild(link)
                link.click()
                document.body.removeChild(link)
                window.URL.revokeObjectURL(url)
                this.showToast('模板下载成功', 'success')
            } catch (error) {
                console.error('下载模板失败:', error)
                this.showToast('下载模板失败', 'error')
            }
        },

        formatFileSize(bytes) {
            if (bytes < 1024) return bytes + ' B'
            if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
            return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
        },

        async stopTask(task) {
            if (DEMO_MODE && task.isDemo) {
                task.status = 'COMPLETED'
                task.completedCount = task.totalCount
                this.showToast('演示任务已在当前页面终止', 'success')
                return
            }
            try {
                await axios.post(`/api/task/${task.taskNo}/stop`)
                this.showToast('任务已终止', 'success')
                this.loadTaskList()
            } catch (error) {
                this.showToast(error.response?.data?.message || '终止失败', 'error')
            }
        },

        async pauseTask(task) {
            if (DEMO_MODE && task.isDemo) {
                task.status = 'PAUSED'
                this.showToast('演示任务已暂停', 'success')
                return
            }
            try {
                await axios.post(`/api/task/${task.taskNo}/pause`)
                this.showToast('任务已暂停', 'success')
                this.loadTaskList()
            } catch (error) {
                this.showToast(error.response?.data?.message || '暂停失败', 'error')
            }
        },

        async resumeTask(task) {
            if (DEMO_MODE && task.isDemo) {
                task.status = 'PROCESSING'
                this.showToast('演示任务已恢复', 'success')
                return
            }
            try {
                await axios.post(`/api/task/${task.taskNo}/resume`)
                this.showToast('任务已恢复', 'success')
                this.loadTaskList()
            } catch (error) {
                this.showToast(error.response?.data?.message || '恢复失败', 'error')
            }
        },

        async searchTasks() {
            if (DEMO_MODE) {
                const keyword = this.searchKeyword.trim().toLowerCase()
                const tasks = MOCK_DATA ? MOCK_DATA.getTasks() : []
                this.taskList = tasks.filter(task => !keyword || (task.title || '').toLowerCase().includes(keyword))
                return
            }
            try {
                const res = await axios.get('/api/task/list', {
                    params: { _t: Date.now() },
                    headers: { 'Cache-Control': 'no-cache', 'Pragma': 'no-cache' }
                })
                let tasks = res.data.data || []
                const keyword = this.searchKeyword.trim().toLowerCase()
                if (keyword) {
                    tasks = tasks.filter(task =>
                        (task.title || '').toLowerCase().includes(keyword)
                    )
                }
                this.taskList = tasks
            } catch (error) {
                this.taskList = []
                this.showToast(error.response?.data?.message || '搜索任务失败', 'error')
            }
        },

        async loadTaskList() {
            if (DEMO_MODE) {
                this.taskList = MOCK_DATA ? MOCK_DATA.getTasks() : []
                return
            }
            try {
                const res = await axios.get('/api/task/list', {
                    params: { _t: Date.now() },
                    headers: { 'Cache-Control': 'no-cache', 'Pragma': 'no-cache' }
                })
                this.taskList = res.data.data || []
            } catch (error) {
                this.taskList = []
                this.showToast(error.response?.data?.message || '加载任务列表失败', 'error')
            }
        },

        // ========== 任务池看板（不需要看板时，把本方法注释掉）==========
        async loadPool() {
            if (DEMO_MODE) return
            if (this.activeTab !== 'pool') return   // 不在任务池页时不请求，减轻压力
            this.poolLoading = true
            try {
                const res = await axios.get('/api/rpa/pool', { params: { _t: Date.now() } })
                const data = res.data?.data || {}
                this.pool = {
                    statusCount: data.statusCount || [],
                    platformCount: data.platformCount || [],
                    workerCount: data.workerCount || [],
                    runningUnits: data.runningUnits || []
                }
            } catch (error) {
                this.pool = { statusCount: [], platformCount: [], workerCount: [], runningUnits: [] }
            } finally {
                this.poolLoading = false
            }
        },
        poolStatusName(code) { return this.poolDialect[code] || code || '未知' },
        poolPlatformName(code) {
            const p = (this.aiPlatforms || []).find(x => x.value === code)
            return p ? p.label : code
        },
        // ========== 任务池看板 END ==========

        // 打开任务详情：先拉一次进度和结果，若任务仍在执行就用定时器轮询刷新
        async selectTask(task) {
            this.selectedTask = task
            this.taskResultsTaskNo = null
            this.reportPlatformFallbacks = []
            this.currentView = 'detail'
            this.selectedResultIds = []
            this.detailKeyword = ''
            this.detailLevel = 'ALL'
            this.detailVisibility = 'ALL'
            this.detailSentiment = 'ALL'
            this.detailPlatform = 'ALL'
            this.detailStatus = 'ALL'
            this.progress = { total: 0, completed: 0, percentage: 0 }
            await this.loadProgress()
            await this.loadResults()

            if (this.progressInterval) clearInterval(this.progressInterval)
            if (this.resultInterval) clearInterval(this.resultInterval)

            // 仅当任务还在跑的时候才轮询，避免对已结束任务做无意义请求
            if (task.status === 'RUNNING' || task.status === 'PENDING' || task.status === 'PROCESSING') {
                this.progressInterval = setInterval(() => this.loadProgress(), 2000)
                this.resultInterval = setInterval(() => this.loadResults(), 3000)
            }
        },

        closeDetail() {
            if (this.progressInterval) clearInterval(this.progressInterval)
            if (this.resultInterval) clearInterval(this.resultInterval)
            this.selectedTask = null
            this.taskResults = []
            this.taskResultsTaskNo = null
            this.reportPlatformFallbacks = []
            this.selectedResultIds = []
            this.isGlobalReport = false
            this.currentView = 'list'
        },

        // 拉取当前任务的进度（已完成/总数/百分比），失败时用 task 自带的数据兜底展示
        async loadProgress() {
            if (!this.selectedTask) return
            if (DEMO_MODE && this.selectedTask.isDemo) {
                this.progress = {
                    total: this.selectedTask.totalCount,
                    completed: this.selectedTask.completedCount,
                    failed: this.selectedTask.status === 'PARTIAL_FAILED' || this.selectedTask.status === 'FAILED'
                        ? Math.max(this.selectedTask.totalCount - this.selectedTask.completedCount, 1)
                        : 0,
                    percentage: this.selectedTask.totalCount > 0
                        ? Math.round(this.selectedTask.completedCount / this.selectedTask.totalCount * 100)
                        : 0
                }
                return
            }
            try {
                const res = await axios.get(`/api/task/${this.selectedTask.taskNo}/progress`)
                const data = res.data.data
                this.progress = {
                    total: data.totalCount || 0,
                    completed: data.completedCount || 0,
                    failed: data.failedCount || 0,
                    percentage: data.percentage || 0
                }
            } catch (error) {
                console.error('加载进度失败', error)
                this.progress = {
                    total: this.selectedTask.totalCount,
                    completed: this.selectedTask.completedCount,
                    failed: 0,
                    percentage: this.selectedTask.totalCount > 0 ? this.selectedTask.completedCount / this.selectedTask.totalCount * 100 : 0
                }
            }
        },

        // 拉取当前任务的观测结果列表（详情页表格的数据源）
        async loadResults() {
            if (!this.selectedTask) return
            if (DEMO_MODE && this.selectedTask.isDemo) {
                this.taskResults = MOCK_DATA ? MOCK_DATA.getResults() : []
                this.taskResultsTaskNo = this.selectedTask.taskNo
                return
            }
            try {
                const res = await axios.get(`/api/task/${this.selectedTask.taskNo}/results`)
                this.taskResults = res.data.data || []
                this.taskResultsTaskNo = this.selectedTask.taskNo
            } catch (error) {
                console.error('加载结果失败', error)
                this.showToast(error.response?.data?.message || '加载结果失败', 'error')
            }
        },

        toggleSelectAllFiltered(checked) {
            const filteredIds = this.filteredTaskResults.map(result => result.id)
            if (checked) {
                this.selectedResultIds = [...new Set([...this.selectedResultIds, ...filteredIds])]
            } else {
                const filteredIdSet = new Set(filteredIds)
                this.selectedResultIds = this.selectedResultIds.filter(id => !filteredIdSet.has(id))
            }
        },

        getResultSources(result) {
            if (Array.isArray(result.sources) && result.sources.length > 0) {
                return result.sources.map(source => ({
                    siteName: source.siteName || this.extractSiteName(source.url),
                    url: source.url || '',
                    title: source.title || ''
                }))
            }

            if (result.sourceInfo) {
                try {
                    const sourceData = typeof result.sourceInfo === 'string' ? JSON.parse(result.sourceInfo) : result.sourceInfo
                    if (Array.isArray(sourceData)) {
                        return sourceData.map(item => {
                            if (Array.isArray(item)) {
                                return {
                                    siteName: this.extractSiteName(item[1]),
                                    url: item[1] || '',
                                    title: item[0] || ''
                                }
                            }
                            return {
                                siteName: item.siteName || this.extractSiteName(item.url),
                                url: item.url || '',
                                title: item.title || ''
                            }
                        })
                    }
                } catch (error) {
                    console.error('解析联网记录失败', error)
                }
            }
            return []
        },

        // 导出详情/联网记录为 CSV 文件（在浏览器端生成并触发下载）
        exportDetailCsv(includeNetworkRecords = false) {
            const selectedResults = this.selectedTaskResults
            if (selectedResults.length === 0) {
                this.showToast('请先勾选需要导出的询问句', 'error')
                return
            }

            const csvEscape = value => `"${String(value ?? '').replace(/"/g, '""')}"`
            let rows
            let headers

            if (includeNetworkRecords) {
                headers = ['询问句', '网站名称', '地址', '标题']
                rows = selectedResults.flatMap(result => {
                    const sources = this.getResultSources(result)
                    const exportSources = sources.length > 0 ? sources : [{}]
                    return exportSources.map(source => [
                        result.questionText,
                        source.siteName || '',
                        source.url || '',
                        source.title || ''
                    ])
                })
            } else {
                headers = ['询问句', '层级', '标签', '意图词', '展现', '品牌', '引用数', '舆情', '平台', '查询时间', '查询状态']
                rows = selectedResults.map(result => [
                    result.questionText,
                    result.level || '-',
                    result.tag || '-',
                    result.intentWord || '-',
                    result.showStatus === 'SUCCESS' ? '是' : '否',
                    result.brand || '-',
                    result.referenceCount || 0,
                    this.getSentimentText(result.sentiment),
                    result.aiDisplayName || '-',
                    result.queryTime || '-',
                    this.getQueryStatusText(result.status)
                ])
            }

            const csvContent = '\uFEFF' + [headers, ...rows]
                .map(row => row.map(csvEscape).join(','))
                .join('\n')
            const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8' })
            const url = URL.createObjectURL(blob)
            const link = document.createElement('a')
            link.href = url
            link.download = `${this.selectedTask?.title || '观测任务'}-${includeNetworkRecords ? '联网记录' : '详情'}.csv`
            document.body.appendChild(link)
            link.click()
            document.body.removeChild(link)
            URL.revokeObjectURL(url)
            this.showToast(`已导出 ${selectedResults.length} 个询问句`, 'success')
        },

        // 把 HTML 字符串里的标签全部剥掉，只留纯文本
        // 用浏览器原生 DOMParser 解析后取 textContent，比正则稳得多（不会误杀 < 等字符）
        stripHtml(html) {
            if (!html) return ''
            const doc = new DOMParser().parseFromString(html, 'text/html')
            return doc.body.textContent || doc.body.innerText || ''
        },

        // 导出问答内容为 CSV 文件：只导出 问题 + 思考内容 + 回答内容（纯文本，剥掉 HTML 标签）
        exportQaContent() {
            const selectedResults = this.selectedTaskResults
            if (selectedResults.length === 0) {
                this.showToast('请先勾选需要导出的询问句', 'error')
                return
            }
            const csvEscape = value => `"${String(value ?? '').replace(/"/g, '""')}"`
            const headers = ['问题', '思考内容', '回答内容']
            const rows = selectedResults.map(result => [
                this.stripHtml(result.questionText),
                this.stripHtml(result.thinkingContent),
                this.stripHtml(result.answerText)
            ])
            const csvContent = '\uFEFF' + [headers, ...rows]
                .map(row => row.map(csvEscape).join(','))
                .join('\n')
            const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8' })
            const url = URL.createObjectURL(blob)
            const link = document.createElement('a')
            link.href = url
            link.download = `${this.selectedTask?.title || '观测任务'}-问答内容.csv`
            document.body.appendChild(link)
            link.click()
            document.body.removeChild(link)
            URL.revokeObjectURL(url)
            this.showToast(`已导出 ${selectedResults.length} 个问答`, 'success')
        },

        async copyDetailLink() {
            try {
                await navigator.clipboard.writeText(window.location.href)
                this.showToast('页面链接已复制', 'success')
            } catch (error) {
                this.showToast('复制失败，请手动复制浏览器地址', 'error')
            }
        },

        getStatusClass(status) {
            const classes = {
                'PENDING': 'status-pending',
                'PROCESSING': 'status-processing',
                'RUNNING': 'status-running',
                'COMPLETED': 'status-completed',
                'FAILED': 'status-failed',
                'PARTIAL_FAILED': 'status-partial-failed'
            }
            return classes[status] || 'status-pending'
        },

        getStatusText(status) {
            const texts = {
                'PENDING': '待处理',
                'PROCESSING': '处理中',
                'RUNNING': '执行中',
                'COMPLETED': '已完成',
                'FAILED': '失败',
                'PARTIAL_FAILED': '部分失败'
            }
            return texts[status] || status
        },

        getQueryStatusClass(status) {
            const classes = {
                'SUCCESS': 'query-status-completed',
                'PENDING': 'query-status-pending',
                'RUNNING': 'query-status-running',
                'FAILED': 'query-status-failed',
                'TIMEOUT': 'query-status-failed'
            }
            return classes[status] || 'query-status-pending'
        },

        getQueryStatusText(status) {
            const texts = {
                'SUCCESS': '已完成',
                'PENDING': '待开始',
                'RUNNING': '执行中',
                'FAILED': '失败',
                'TIMEOUT': '超时'
            }
            return texts[status] || status
        },

        getSentimentText(sentiment) {
            const texts = {
                'positive': '正面',
                'negative': '负面',
                'neutral': '中性',
                'none': '未提及'
            }
            return texts[sentiment] || '未提及'
        },

        getFrequencyText(freq) {
            const texts = {
                'single': '单次',
                'daily': '每天',
                'weekly': '每周'
            }
            return texts[freq] || freq
        },

        async retryAllTasks() {
            if (!this.selectedTask) return
            if (DEMO_MODE && this.selectedTask.isDemo) {
                this.showToast('演示模式不会调用批量重试接口', 'success')
                return
            }
            this.isRetrying = true
            try {
                await axios.post(`/api/task/${this.selectedTask.taskNo}/retry/all`)
                this.showToast('所有任务已重新调度', 'success')
                this.selectedTask.status = 'PROCESSING'
                this.taskResults.forEach(r => {
                    r.status = 'PENDING'
                    r.errorMsg = null
                    r.answerText = null
                    r.screenshotUrls = []
                })
                if (this.progressInterval) clearInterval(this.progressInterval)
                if (this.resultInterval) clearInterval(this.resultInterval)
                this.progressInterval = setInterval(() => this.loadProgress(), 2000)
                this.resultInterval = setInterval(() => this.loadResults(), 3000)
            } catch (error) {
                this.showToast(error.response?.data?.message || '重试失败', 'error')
            } finally {
                this.isRetrying = false
            }
        },

        async retrySingleResult(taskResultId) {
            if (DEMO_MODE && this.selectedTask?.isDemo) {
                this.showToast('演示模式不会调用单条重试接口', 'success')
                return
            }
            this.isRetrying = true
            try {
                await axios.post(`/api/task/result/${taskResultId}/retry`)
                this.showToast('任务已重新调度', 'success')
                const result = this.taskResults.find(r => r.id === taskResultId)
                if (result) {
                    result.status = 'PENDING'
                    result.errorMsg = null
                }
                if (this.selectedTask.status !== 'PROCESSING') {
                    this.selectedTask.status = 'PROCESSING'
                }
                if (this.progressInterval) clearInterval(this.progressInterval)
                if (this.resultInterval) clearInterval(this.resultInterval)
                this.progressInterval = setInterval(() => this.loadProgress(), 2000)
                this.resultInterval = setInterval(() => this.loadResults(), 3000)
            } catch (error) {
                this.showToast(error.response?.data?.message || '重试失败', 'error')
            } finally {
                this.isRetrying = false
            }
        },

        deleteSingleResult: async function (id, questionText) {
            const result = this.taskResults.find(r => r.id === id)
            if (result && result.status === 'RUNNING') {
                this.showToast('正在执行中的问题无法删除', 'error')
                return
            }
            const display = questionText || (result ? result.questionText : '')
            if (!confirm('确定要删除该问题吗？\n\n"' + display + '"\n\n删除后该问题将不再出现在报告中。')) return
            if (DEMO_MODE && this.selectedTask && this.selectedTask.isDemo) {
                this.taskResults = this.taskResults.filter(r => r.id !== id)
                this.selectedResultIds = this.selectedResultIds.filter(sid => sid !== id)
                this.showToast('演示模式下已从当前页面移除', 'success')
                return
            }
            try {
                await axios.delete('/api/task/result/' + id)
                this.taskResults = this.taskResults.filter(r => r.id !== id)
                this.selectedResultIds = this.selectedResultIds.filter(sid => sid !== id)
                this.showToast('问题已删除，报告生成时将不包含此问题', 'success')
                await this.loadProgress()
            } catch (error) {
                const msg = (error.response && error.response.data && error.response.data.message) || '删除失败'
                this.showToast(msg, 'error')
            }
        },

        batchDeleteResults: async function () {
            if (this.selectedResultIds.length === 0) {
                this.showToast('请先勾选要删除的问题', 'error')
                return
            }
            const selectedResults = this.taskResults.filter(r => this.selectedResultIds.indexOf(r.id) !== -1)
            const runningCount = selectedResults.filter(r => r.status === 'RUNNING').length
            if (runningCount > 0) {
                this.showToast('选中的问题中有 ' + runningCount + ' 个正在执行中，无法删除', 'error')
                return
            }
            if (!confirm('确定要删除选中的 ' + this.selectedResultIds.length + ' 个问题吗？\n\n删除后这些问题将不再出现在报告中。')) return
            if (DEMO_MODE && this.selectedTask && this.selectedTask.isDemo) {
                const selectedIdSet = new Set(this.selectedResultIds)
                this.taskResults = this.taskResults.filter(r => !selectedIdSet.has(r.id))
                this.selectedResultIds = []
                this.showToast('演示模式下已从当前页面移除选中问题', 'success')
                return
            }
            try {
                await axios.delete('/api/task/result/batch', { data: this.selectedResultIds })
                const selectedIdSet = new Set(this.selectedResultIds)
                this.taskResults = this.taskResults.filter(r => !selectedIdSet.has(r.id))
                this.selectedResultIds = []
                this.showToast('已批量删除选中的问题，报告生成时将不包含这些问题', 'success')
                await this.loadProgress()
            } catch (error) {
                const msg = (error.response && error.response.data && error.response.data.message) || '批量删除失败'
                this.showToast(msg, 'error')
            }
        },

        _stripDuplicateListNumbers(html) {
            if (!html) return ''
            return html.replace(/(<li[^>]*>\s*)(\d+[\.、\)]\s*)/gi, (match, openTag, numPrefix) => openTag)
        },

        _cleanAiText(text) {
            if (!text) return ''
            const decoder = document.createElement('textarea')
            decoder.innerHTML = String(text)
            let cleaned = decoder.value
            cleaned = cleaned.replace(/[\u200B\u200C\u200D\u2060\uFEFF\u00AD\u034F\u180E\u2061\u2062\u2063\u2064]/g, '')
            cleaned = cleaned.replace(/\u00A0/g, ' ')
            cleaned = cleaned.replace(/\u3000/g, ' ')
            cleaned = cleaned.replace(/\u0009/g, ' ')
            cleaned = cleaned.replace(/\r\n?/g, '\n')
            cleaned = cleaned.replace(/\\n/g, '\n')
            cleaned = cleaned.replace(/[ \u0020]+\n/g, '\n')
            cleaned = cleaned.replace(/\n[ \u0020]+/g, '\n')
            cleaned = cleaned.replace(/\n{3,}/g, '\n\n')
            cleaned = cleaned.replace(/[ ]{2,}/g, '  ')
            return cleaned.trim()
        },

        _preprocessToMarkdown(text) {
            if (!text) return ''
            let prepared = text
            if (!/[\r\n]/.test(prepared)) {
                prepared = prepared.replace(/([。！？!?])\s*/g, '$1\n')
                prepared = prepared.replace(/([；;])\s*/g, '$1\n')
                prepared = prepared.replace(/\n{2,}/g, '\n\n').trim()
            }
            return prepared
        },

        _escapeHtml(text) {
            const container = document.createElement('div')
            container.textContent = String(text || '')
            return container.innerHTML
        },

        _sanitizeAiHtml(html) {
            const template = document.createElement('template')
            template.innerHTML = String(html || '')
            const allowedTags = new Set([
                'DIV', 'P', 'BR', 'STRONG', 'B', 'EM', 'I', 'U', 'S',
                'UL', 'OL', 'LI', 'BLOCKQUOTE', 'CODE', 'PRE',
                'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'A', 'SPAN',
                'TABLE', 'THEAD', 'TBODY', 'TR', 'TH', 'TD'
            ])
            const blockedTags = new Set([
                'SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'SVG', 'PATH',
                'FORM', 'INPUT', 'BUTTON', 'TEXTAREA', 'SELECT', 'OPTION',
                'IMG', 'VIDEO', 'AUDIO', 'CANVAS', 'LINK', 'META'
            ])

            const cleanChildren = parent => {
                Array.from(parent.childNodes).forEach(node => {
                    if (node.nodeType === Node.COMMENT_NODE) {
                        node.remove()
                        return
                    }
                    if (node.nodeType !== Node.ELEMENT_NODE) return
                    const tagName = node.tagName.toUpperCase()
                    if (blockedTags.has(tagName)) {
                        node.remove()
                        return
                    }
                    if (!allowedTags.has(tagName)) {
                        cleanChildren(node)
                        const fragment = document.createDocumentFragment()
                        while (node.firstChild) fragment.appendChild(node.firstChild)
                        node.replaceWith(fragment)
                        return
                    }

                    const href = tagName === 'A' ? (node.getAttribute('href') || '').trim() : ''
                    Array.from(node.attributes).forEach(attribute => node.removeAttribute(attribute.name))
                    if (tagName === 'A' && /^(?:https?:|mailto:|#|\/(?!\/))/i.test(href)) {
                        node.setAttribute('href', href)
                        node.setAttribute('target', '_blank')
                        node.setAttribute('rel', 'noopener noreferrer')
                    }
                    cleanChildren(node)
                })
            }

            cleanChildren(template.content)

            const allElements = template.content.querySelectorAll('li, p, div')
            allElements.forEach(el => {
                if (el.closest('pre, code')) return
                let modified = true
                let safety = 3
                while (modified && safety-- > 0) {
                    modified = false
                    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null)
                    let firstText = null
                    let firstLeadingText = null
                    let node
                    while (node = walker.nextNode()) {
                        if (!node.nodeValue) continue
                        if (!node.nodeValue.trim()) continue
                        if (!firstText) firstText = node
                        if (/^\s*\d+\s*[\.、\)）]/.test(node.nodeValue)) {
                            firstLeadingText = node
                            break
                        }
                    }
                    if (!firstLeadingText) continue
                    const raw = firstLeadingText.nodeValue
                    const prefixMatch = raw.match(/^(\s*\d+\s*[\.、\)）]\s*)/)
                    if (!prefixMatch) continue
                    const wholePrefix = prefixMatch[1]
                    const rest = raw.slice(wholePrefix.length)
                    const secondMatch = rest.match(/^(\d+\s*[\.、\)）])/)
                    if (secondMatch || (el.tagName === 'LI' && el.parentElement && el.parentElement.tagName === 'OL')) {
                        firstLeadingText.nodeValue = rest
                        modified = true
                        if (firstLeadingText.nodeValue === '' && firstLeadingText !== el.firstChild) {
                            const prev = firstLeadingText.previousSibling
                            firstLeadingText.remove()
                            if (prev && prev.nodeType === Node.ELEMENT_NODE && !prev.textContent.trim()) {
                                prev.remove()
                            }
                        }
                    }
                }
            })

            return template.innerHTML
        },

        _simpleMarkdownToHtml(md) {
            try {
                if (!md) return ''
                let html = String(md)
                html = html.replace(/\r\n?/g, '\n')
                const lines = html.split('\n')
                const out = []
                const self = this
                let inList = false
                let listType = null
                let listBuffer = []
                const flushList = () => {
                    if (!inList) return
                    const tag = listType === 'ol' ? 'ol' : 'ul'
                    try {
                        out.push('<' + tag + '><li>' + listBuffer.join('</li><li>') + '</li></' + tag + '>')
                    } catch (e) { /* ignore */ }
                    inList = false
                    listType = null
                    listBuffer = []
                }
                for (let idx = 0; idx < lines.length; idx++) {
                    const ln = lines[idx]
                    const isBlank = /^\s*$/.test(ln)
                    const headingMatch = ln.match(/^(#{1,6})\s+(.+?)\s*#*\s*$/)
                    if (headingMatch) {
                        flushList()
                        const level = Math.max(1, Math.min(6, headingMatch[1].length))
                        out.push('<h' + level + '>' + self._inlineMarkdown(headingMatch[2]) + '</h' + level + '>')
                        continue
                    }
                    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(ln)) {
                        flushList()
                        out.push('<hr>')
                        continue
                    }
                    const olMatch = ln.match(/^\s*(\d+)\s*[\.、\)）]\s*(.+)$/)
                    const ulMatch = !olMatch ? ln.match(/^\s*[-*+]\s+(.+)$/) : null
                    if (olMatch) {
                        if (inList && listType !== 'ol') flushList()
                        inList = true
                        listType = 'ol'
                        listBuffer.push(self._inlineMarkdown(olMatch[2]))
                        continue
                    }
                    if (ulMatch) {
                        if (inList && listType !== 'ul') flushList()
                        inList = true
                        listType = 'ul'
                        listBuffer.push(self._inlineMarkdown(ulMatch[1]))
                        continue
                    }
                    if (isBlank) {
                        flushList()
                        out.push('')
                        continue
                    }
                    flushList()
                    out.push('<p>' + self._inlineMarkdown(ln) + '</p>')
                }
                flushList()
                return out.join('\n')
            } catch (e) {
                console.warn('_simpleMarkdownToHtml 回退为纯文本:', e && e.message)
                return this._escapeHtml(String(md || '')).replace(/\n/g, '<br>')
            }
        },
        _inlineMarkdown(text) {
            try {
                if (!text) return ''
                let s = this._escapeHtml(text)
                const placeholderStart = '\u0002SB'
                const placeholderEnd = 'EB\u0002'
                const boldStore = []
                s = s.replace(/\*\*([\s\S]*?)\*\*/g, function (m, inner) {
                    boldStore.push(inner)
                    return placeholderStart + (boldStore.length - 1) + placeholderEnd
                })
                s = s.replace(/(^|[^*])\*([^*\n]+?)\*(?=$|[^*])/g, '$1<em>$2</em>')
                for (let i = 0; i < boldStore.length; i++) {
                    s = s.replace(placeholderStart + i + placeholderEnd, '<strong>' + boldStore[i] + '</strong>')
                }
                s = s.replace(/`([^`]+?)`/g, '<code>$1</code>')
                s = s.replace(/\[([^\]]+)\]\(([^)]+)\)/g, function (m, label, url) {
                    const safeUrl = /^(?:https?:|mailto:|#|\/(?!\/))/i.test(url) ? url : '#'
                    return '<a href="' + safeUrl + '" target="_blank" rel="noopener noreferrer">' + label + '</a>'
                })
                return s
            } catch (e) {
                console.warn('_inlineMarkdown 回退:', e && e.message)
                return this._escapeHtml(String(text || ''))
            }
        },
        // 把 AI 回答文本渲染成安全的 HTML：先清理/转换 Markdown，再做标签白名单过滤，
        // 防止 AI 返回的内容夹带脚本注入（见 _sanitizeAiHtml）。
        renderMarkdown(text) {
            if (!text || !String(text).trim()) return ''
            try {
                const clean = this._cleanAiText(text)
                const containsHtml = /<\/?(?:div|p|br|strong|b|em|i|u|s|ul|ol|li|blockquote|code|pre|h[1-6]|a|span|table|thead|tbody|tr|th|td|svg)\b/i.test(clean)
                let html
                if (containsHtml) {
                    html = clean
                } else {
                    const markdown = this._preprocessToMarkdown(clean).replace(/^\s*\d+\s*[\.、\)）]\s*(\d+\s*[\.、\)）])/gm, '$1')
                    if (window.marked && typeof window.marked.parse === 'function') {
                        html = window.marked.parse(markdown, { breaks: true, gfm: true })
                    } else {
                        html = this._simpleMarkdownToHtml(markdown)
                    }
                }
                return this._sanitizeAiHtml(html)
            } catch (error) {
                console.warn('回答内容格式化失败，已回退为纯文本', error)
                return this._escapeHtml(this._cleanAiText(text)).replace(/\n/g, '<br>')
            }
        },
        renderAiSummaryMarkdown(text) {
            try {
                if (text === undefined || text === null) return ''
                const str = String(text)
                if (!str.trim()) return ''
                return this.renderMarkdown(str)
            } catch (e) {
                console.error('renderAiSummaryMarkdown 失败，安全回退:', e && e.message)
                try {
                    return this._escapeHtml(this._cleanAiText(String(text || ''))).replace(/\n/g, '<br>')
                } catch (e2) {
                    return ''
                }
            }
        },
        getAiSummaryContent() {
            try {
                if (!this.reportData || !this.reportData.aiSummary) return ''
                const c = this.reportData.aiSummary.content
                return (c && String(c).trim()) ? c : ''
            } catch (e) {
                return ''
            }
        },

        openQAModal(result) {
            this.qaModal = { show: true, data: result }
        },

        openRankingModal(result) {
            if (!this.selectedTask) {
                this.showToast('任务信息丢失', 'error')
                return
            }
            this.rankingModal = {
                show: true,
                loading: true,
                resultId: result.id,
                questionText: result.questionText || '',
                aiDisplayName: result.aiDisplayName || result.aiPlatform || '',
                status: result.status || '',
                answerText: result.answerText || '',
                brandRankings: []
            }
            this.loadRankingData(result)
        },

        async loadRankingData(result) {
            if (DEMO_MODE && this.selectedTask?.isDemo) {
                this.rankingModal.brandRankings = [
                    { brandName: '倍路生', isSelfBrand: true, rank: 2, firstIndex: 18 },
                    { brandName: '可孚', isSelfBrand: false, rank: 1, firstIndex: 8 },
                    { brandName: '仙鹤', isSelfBrand: false, rank: 3, firstIndex: 31 }
                ]
                this.rankingModal.loading = false
                return
            }
            try {
                const taskNo = this.selectedTask.taskNo
                let rankingVO = this._rankingCache[taskNo]
                if (!rankingVO) {
                    const res = await axios.get(`/api/task/${taskNo}/rankings`)
                    rankingVO = res.data.data || {}
                    this._rankingCache[taskNo] = rankingVO
                }
                const qr = (rankingVO.questionRankings || []).find(x => x.resultId === result.id)
                if (qr && qr.brandRankings) {
                    this.rankingModal.brandRankings = qr.brandRankings
                    this.rankingModal.answerText = qr.answerText || result.answerText || ''
                } else {
                    const brands = []
                    if (rankingVO.brandName) brands.push(rankingVO.brandName)
                    ;(rankingVO.competitorBrands || []).forEach(b => brands.push(b))
                    this.rankingModal.brandRankings = brands.map(b => ({
                        brandName: b,
                        isSelfBrand: b === rankingVO.brandName,
                        rank: null,
                        firstIndex: -1,
                        matchedText: null
                    }))
                }
            } catch (e) {
                console.error('加载排名失败:', e)
                this.showToast(e.response?.data?.message || '加载排名失败', 'error')
            } finally {
                this.rankingModal.loading = false
            }
        },

        openScreenshotModal(result) {
            this.screenshotModal = { show: true, data: result }
        },

        openNetworkModal(result) {
            let sources = []
            if (result.sourceInfo) {
                try {
                    const sourceData = JSON.parse(result.sourceInfo)
                    if (Array.isArray(sourceData)) {
                        sources = sourceData.map((item, index) => ({
                            index: index + 1,
                            title: item[0] || '-',
                            url: item[1] || '-',
                            siteName: this.extractSiteName(item[1]),
                            author: '-',
                            publishTime: '-'
                        }))
                    }
                } catch (e) {
                    console.error('解析sourceInfo失败:', e)
                }
            }
            this.networkModal = { show: true, data: { sources } }
        },
        
        extractSiteName(url) {
            if (!url) return '-'
            try {
                const urlObj = new URL(url)
                const hostname = urlObj.hostname
                return hostname.startsWith('www.') ? hostname.slice(4) : hostname
            } catch (e) {
                return '-'
            }
        },

        openSourcesModal(result) {
            this.showToast('引用源功能开发中', 'success')
        },

        // 报告数据中的字段名/结构可能不完整，这里做“归一化”：
        // 把所有缺失字段补成安全的默认值，保证后续图表渲染不会因字段为空而报错。
        normalizeReportData() {
            if (!this.reportData || typeof this.reportData !== 'object') this.reportData = {}
            const rd = this.reportData

            if (!rd.competition || typeof rd.competition !== 'object') {
                rd.competition = {}
            }
            const comp = rd.competition
            if (!Array.isArray(comp.radarSeries) || comp.radarSeries.length === 0) {
                comp.radarSeries = [
                    { name: '自主品牌', value: [0,0,0,0,0,0], itemStyle: { color: '#2f7ef6' } },
                    { name: '竞品均值', value: [0,0,0,0,0,0], itemStyle: { color: '#f08a24' } },
                    { name: '行业均值', value: [0,0,0,0,0,0], itemStyle: { color: '#9aa0a8' } }
                ]
            }
            if (!Array.isArray(comp.brands)) comp.brands = []
            if (!Array.isArray(comp.metrics)) comp.metrics = []
            if (!Array.isArray(comp.voiceRanking)) comp.voiceRanking = []

            if (typeof rd.overallScore === 'number' && !rd.scoreLevel) {
                const s = rd.overallScore
                if (s >= 90) rd.scoreLevel = '优秀'
                else if (s >= 80) rd.scoreLevel = '良好'
                else if (s >= 70) rd.scoreLevel = '中等'
                else if (s >= 60) rd.scoreLevel = '及格'
                else rd.scoreLevel = '待提升'
            }
            if (typeof rd.overallScore === 'number' && typeof rd.scoreChange !== 'number') {
                rd.scoreChange = 0
            }
            if (typeof rd.overallScore !== 'number') {
                rd.overallScore = 0
                if (!rd.scoreLevel) rd.scoreLevel = '待提升'
                if (typeof rd.scoreChange !== 'number') rd.scoreChange = 0
            }

            if (!Array.isArray(rd.rankings)) rd.rankings = []
            if (!rd.exposureMetrics || typeof rd.exposureMetrics !== 'object') rd.exposureMetrics = {}
            const em = rd.exposureMetrics
            if (typeof em.coverageRate !== 'number') em.coverageRate = 0
            if (typeof em.firstRate !== 'number') em.firstRate = 0
            if (typeof em.top3Rate !== 'number') em.top3Rate = 0
            if (typeof em.top5Rate !== 'number') em.top5Rate = 0
            if (typeof em.mentionRate !== 'number') em.mentionRate = 0
            if (typeof em.positiveSentimentRate !== 'number') em.positiveSentimentRate = 0
            if (!Array.isArray(em.platformStats)) em.platformStats = []

            if (!rd.aiSummary || typeof rd.aiSummary !== 'object') rd.aiSummary = {}
            if (!Array.isArray(rd.aiSummary.highlights)) rd.aiSummary.highlights = []
            if (!Array.isArray(rd.aiSummary.suggestions)) rd.aiSummary.suggestions = []
            if (!Array.isArray(rd.aiSummary.riskAlerts)) rd.aiSummary.riskAlerts = []
            if (!Array.isArray(rd.aiSummary.trendObservations)) rd.aiSummary.trendObservations = []

            if (!rd.keywordCloud || typeof rd.keywordCloud !== 'object') rd.keywordCloud = {}
            if (!Array.isArray(rd.keywordCloud.positive)) rd.keywordCloud.positive = []
            if (!Array.isArray(rd.keywordCloud.negative)) rd.keywordCloud.negative = []

            if (!Array.isArray(rd.competitionRanking)) rd.competitionRanking = []
            if (!Array.isArray(rd.competitionTop10)) rd.competitionTop10 = []
            if (!Array.isArray(rd.citationPlatformRanking)) rd.citationPlatformRanking = []
            if (!Array.isArray(rd.citationPlatformTop10)) rd.citationPlatformTop10 = []
            if (!Array.isArray(rd.citationSourceDistribution)) rd.citationSourceDistribution = []
            if (!Array.isArray(rd.citationSources)) rd.citationSources = []
            if (!Array.isArray(rd.sourceDistribution)) rd.sourceDistribution = []

            if (!rd.globalTrends || typeof rd.globalTrends !== 'object') rd.globalTrends = {}
            const gt = rd.globalTrends
            if (!Array.isArray(gt.dates)) gt.dates = []
            ;['brandVoice','citationHeat','positiveSentiment','positioningFit'].forEach(k => {
                if (!gt[k] || typeof gt[k] !== 'object') gt[k] = { unit: '', series: [] }
                if (!Array.isArray(gt[k].series)) gt[k].series = []
            })

            if (!rd.perPlatformBrandComparison || typeof rd.perPlatformBrandComparison !== 'object') rd.perPlatformBrandComparison = {}
            if (!rd.brandComparison || typeof rd.brandComparison !== 'object') rd.brandComparison = null

            if (typeof rd.brandName !== 'string') rd.brandName = rd.brandName == null ? '' : String(rd.brandName)
            if (typeof rd.historyReportCount !== 'number') rd.historyReportCount = 0
            if (!Array.isArray(rd.screenshotUrls)) rd.screenshotUrls = []

            if (rd.aiSentimentMap && typeof rd.aiSentimentMap === 'object' && Array.isArray(this.taskResults)) {
                const aiMap = rd.aiSentimentMap
                for (const result of this.taskResults) {
                    const key = String(result.id)
                    if (aiMap[key]) {
                        result.sentiment = aiMap[key]
                        result.sentimentSource = 'ai'
                    }
                }
            }

            const rankings = rd.rankings
            const sourceRanking = rankings.find(r => r && r.id === 'source_platform_ranking')
            if (sourceRanking && Array.isArray(sourceRanking.items)) {
                if (!rd.citationPlatformRanking.length && !rd.citationPlatformTop10.length) {
                    rd.citationPlatformRanking = sourceRanking.items.map(item => ({
                        rank: item?.rank,
                        name: item?.name,
                        address: item?.name,
                        citationCount: Number(item?.value) || 0,
                        trend: item?.trend
                    }))
                    rd.citationPlatformTop10 = rd.citationPlatformRanking
                }

                if (!rd.citationSourceDistribution.length && !rd.sourceDistribution.length && !rd.citationSources.length) {
                    rd.citationSourceDistribution = sourceRanking.items.map(item => ({
                        name: item?.name,
                        address: item?.name,
                        value: Number(item?.value) || 0
                    }))
                }
            }
        },

        // 轻提示：弹出一条成功/错误信息，3 秒后自动消失
        showToast(message, type) {
            this.toast = { show: true, message, type }
            setTimeout(() => {
                this.toast.show = false
            }, 3000)
        },

        openImageModal(url, index) {
            this.stopImagePan()
            this.imageModal = {
                show: true,
                url,
                index,
                zoom: 1,
                fitZoom: 1,
                naturalWidth: 0,
                naturalHeight: 0
            }
        },

        closeImageModal() {
            this.stopImagePan()
            this.imageModal.show = false
        },

        handleImageLoad(event) {
            this.imageModal.naturalWidth = event.target.naturalWidth
            this.imageModal.naturalHeight = event.target.naturalHeight
            this.$nextTick(() => this.fitImageToScreen())
        },

        fitImageToScreen() {
            const stage = this.$refs.imageModalStage
            const { naturalWidth, naturalHeight } = this.imageModal
            if (!stage || !naturalWidth || !naturalHeight) return

            const availableWidth = Math.max(stage.clientWidth - 72, 1)
            const availableHeight = Math.max(stage.clientHeight - 128, 1)
            const fitZoom = Math.min(
                availableWidth / naturalWidth,
                availableHeight / naturalHeight,
                1
            )

            this.imageModal.fitZoom = Math.max(fitZoom, 0.03)
            this.imageModal.zoom = this.imageModal.fitZoom
            this.$nextTick(() => {
                stage.scrollLeft = 0
                stage.scrollTop = 0
            })
        },

        setImageZoom(nextZoom) {
            const stage = this.$refs.imageModalStage
            const oldScrollWidth = stage?.scrollWidth || 1
            const oldScrollHeight = stage?.scrollHeight || 1
            const centerXRatio = stage
                ? (stage.scrollLeft + stage.clientWidth / 2) / oldScrollWidth
                : 0.5
            const centerYRatio = stage
                ? (stage.scrollTop + stage.clientHeight / 2) / oldScrollHeight
                : 0.5

            this.imageModal.zoom = Math.min(Math.max(nextZoom, 0.03), 5)

            this.$nextTick(() => {
                if (!stage) return
                stage.scrollLeft = centerXRatio * stage.scrollWidth - stage.clientWidth / 2
                stage.scrollTop = centerYRatio * stage.scrollHeight - stage.clientHeight / 2
            })
        },

        zoomImageIn() {
            this.setImageZoom(this.imageModal.zoom * 1.25)
        },

        zoomImageOut() {
            this.setImageZoom(this.imageModal.zoom / 1.25)
        },

        resetImageZoom() {
            this.setImageZoom(1)
        },

        handleImageWheel(event) {
            if (event.deltaY < 0) {
                this.zoomImageIn()
            } else {
                this.zoomImageOut()
            }
        },

        toggleImageZoom() {
            const isAtFitSize = Math.abs(this.imageModal.zoom - this.imageModal.fitZoom) < 0.01
            this.setImageZoom(isAtFitSize ? 1 : this.imageModal.fitZoom)
        },

        startImagePan(event) {
            if (event.button !== 0) return
            const stage = this.$refs.imageModalStage
            if (!stage) return
            const canPan = stage.scrollWidth > stage.clientWidth || stage.scrollHeight > stage.clientHeight
            if (!canPan) return

            this.imagePan = {
                active: true,
                pointerId: event.pointerId,
                startX: event.clientX,
                startY: event.clientY,
                scrollLeft: stage.scrollLeft,
                scrollTop: stage.scrollTop
            }
            stage.setPointerCapture?.(event.pointerId)
            event.preventDefault()
        },

        moveImagePan(event) {
            if (!this.imagePan.active || event.pointerId !== this.imagePan.pointerId) return
            const stage = this.$refs.imageModalStage
            if (!stage) return

            stage.scrollLeft = this.imagePan.scrollLeft - (event.clientX - this.imagePan.startX)
            stage.scrollTop = this.imagePan.scrollTop - (event.clientY - this.imagePan.startY)
            event.preventDefault()
        },

        stopImagePan(event) {
            const stage = this.$refs.imageModalStage
            if (stage && this.imagePan.pointerId !== null) {
                try {
                    if (stage.hasPointerCapture?.(this.imagePan.pointerId)) {
                        stage.releasePointerCapture(this.imagePan.pointerId)
                    }
                } catch (error) {
                    // 指针已经释放时无需额外处理。
                }
            }
            this.imagePan = {
                active: false,
                pointerId: null,
                startX: 0,
                startY: 0,
                scrollLeft: 0,
                scrollTop: 0
            }
        },

        async openReportHistory(task) {
            this.reportHistoryFromView = this.currentView
            this.selectedTask = task
            this.reportHistoryTask = task
            this.currentView = 'reportHistory'
            this.reportHistoryList = []
            this.reportHistoryLoading = true
            if (DEMO_MODE && task.isDemo) {
                this.reportHistoryList = MOCK_DATA ? MOCK_DATA.getReportHistory(task) : []
                this.reportHistoryLoading = false
                return
            }
            try {
                const res = await axios.get(`/api/analysis/${task.taskNo}/reports/history`)
                this.reportHistoryList = res.data.data || []
            } catch (error) {
                console.error('加载历史报告列表失败', error)
                this.showToast(error.response?.data?.message || '加载历史报告列表失败', 'error')
            } finally {
                this.reportHistoryLoading = false
            }
        },

        closeReportHistory() {
            this.currentView = this.reportHistoryFromView || 'detail'
            this.reportHistoryTask = null
            this.reportHistoryList = []
        },

        async viewReportById(report) {
            try {
                this.isGlobalReport = false
                this.reportLoading = true
                this.currentView = 'report'
                this.reportData = {}
                this.normalizeReportData()
                const platformOptionsPromise = this.loadReportPlatformFallbacks()
                if (DEMO_MODE && (report.demoData || this.reportHistoryTask?.isDemo)) {
                    try {
                        this.reportData = report.demoData || (MOCK_DATA ? MOCK_DATA.getReport(this.reportHistoryTask) : {})
                        this.normalizeReportData()
                        await platformOptionsPromise
                    } catch (e) {
                        console.error('加载DEMO历史报告失败:', e)
                    } finally {
                        this.reportLoading = false
                    }
                    this.$nextTick(() => setTimeout(() => {
                        try { this.renderAllCharts() } catch (e) { console.error('渲染DEMO历史报告图表失败:', e) }
                    }, 100))
                    return
                }
                try {
                    const res = await axios.get(`/api/analysis/reports/${report.id}`)
                    this.reportData = res.data.data || {}
                    this.normalizeReportData()
                    await platformOptionsPromise
                } catch (error) {
                    console.error('加载历史报告失败', error)
                    this.showToast(error.response?.data?.message || '加载历史报告失败', 'error')
                    this.currentView = 'reportHistory'
                } finally {
                    this.reportLoading = false
                    this.$nextTick(() => {
                        setTimeout(() => {
                            try { this.renderAllCharts() } catch (e) { console.error('渲染历史报告图表失败:', e) }
                        }, 300)
                    })
                }
            } catch (error) {
                console.error('viewReportById 总体失败:', error)
                this.reportLoading = false
                this.showToast('打开历史报告失败: ' + (error.message || '未知错误'), 'error')
            }
        },

        // 打开单任务数据报告页（见下方 loadReport 的实现）
        async openReport() {
            if (!this.selectedTask) return
            try {
                this.isGlobalReport = false
                this.reportHistoryTask = null
                this.reportHistoryList = []
                this.currentView = 'report'
                await this.loadReport()
            } catch (error) {
                console.error('openReport 失败:', error)
                this.reportLoading = false
                this.showToast('打开报告失败: ' + (error.message || '未知错误'), 'error')
            }
        },

        async switchToObservationDetail() {
            const task = this.selectedTask || this.reportHistoryTask
            if (!task) {
                this.currentView = 'list'
                return
            }
            this.reportData = {}
            this.normalizeReportData()
            this.isGlobalReport = false
            this.reportHistoryTask = null
            this.reportHistoryList = []
            await this.selectTask(task)
        },

        closeReport() {
            if (this.reportHistoryTask) {
                if (DEMO_MODE && this.reportHistoryTask.isDemo) {
                    this.currentView = 'reportHistory'
                    this.reportHistoryList = MOCK_DATA ? MOCK_DATA.getReportHistory(this.reportHistoryTask) : []
                    this.reportHistoryLoading = false
                    this.reportData = {}
                    return
                }
                // 从历史报告列表进入的，返回历史报告列表
                this.currentView = 'reportHistory'
                this.reportHistoryLoading = true
                this.reportHistoryList = []
                axios.get(`/api/analysis/${this.reportHistoryTask.taskNo}/reports/history`)
                    .then(res => {
                        this.reportHistoryList = res.data.data || []
                    })
                    .catch(err => {
                        console.error('重新加载历史报告列表失败', err)
                    })
                    .finally(() => {
                        this.reportHistoryLoading = false
                    })
            } else {
                this.currentView = 'detail'
            }
            this.reportData = {}
            this.normalizeReportData()
        },

        // 加载报告：请求报告接口，接口可能立即返回报告，也可能返回“生成中”，
        // 后一种情况由 handleReportResponse 判断并进行轮询等待。
        async loadReport() {
            if (!this.selectedTask) return
            this.isGlobalReport = false
            this.reportLoading = true
            this.reportData = {}
            this.normalizeReportData()
            const platformOptionsPromise = this.loadReportPlatformFallbacks()
            if (DEMO_MODE && this.selectedTask.isDemo) {
                try {
                    this.reportData = MOCK_DATA ? MOCK_DATA.getReport(this.selectedTask) : {}
                    this.normalizeReportData()
                    await platformOptionsPromise
                } catch (e) {
                    console.error('加载DEMO报告失败:', e)
                } finally {
                    this.reportLoading = false
                }
                this.$nextTick(() => setTimeout(() => {
                    try { this.renderAllCharts() } catch (e) { console.error('渲染DEMO图表失败:', e) }
                }, 100))
                return
            }
            try {
                const res = await axios.get(`/api/analysis/${this.selectedTask.taskNo}/report`)
                await this.handleReportResponse(res, platformOptionsPromise)
            } catch (error) {
                console.error('加载报告失败', error)
                this.showToast(error.response?.data?.message || '加载报告失败', 'error')
                this.reportData = {}
                this.normalizeReportData()
                this.reportLoading = false
            }
        },

        // 报告接口的响应有多种形态，这里统一“分流”处理：
        //   1. 直接带有报告数据 → 直接用；
        //   2. 返回 202 或“生成中”状态 → 调用 pollReportUntilReady 轮询等待完成。
        async handleReportResponse(res, platformOptionsPromise) {
            const status = res.status
            const payload = res.data?.data

            const isAsyncAccepted = (status === 202) ||
                (payload && typeof payload === 'object' && payload.status === 'RUNNING')

            const payloadHasReportNested = payload && typeof payload === 'object' &&
                payload.report && typeof payload.report === 'object' &&
                (payload.report.title || payload.report.topMetrics || payload.report.brandName || payload.report.exposureMetrics || payload.report.overallScore !== undefined)

            const payloadIsDirectReport = payload && typeof payload === 'object' && !payload.report &&
                (payload.title || payload.topMetrics || payload.brandName || payload.exposureMetrics || payload.overallScore !== undefined || payload.rankings || payload.brandComparison)

            if (payloadHasReportNested) {
                this.reportData = payload.report
                this.normalizeReportData()
                await platformOptionsPromise
                this.finishReportLoading()
                return
            }

            if (payloadIsDirectReport) {
                this.reportData = payload
                this.normalizeReportData()
                await platformOptionsPromise
                this.finishReportLoading()
                return
            }

            if (isAsyncAccepted || (payload && typeof payload === 'object' &&
                (payload.status === 'IDLE' || payload.status === 'RUNNING') && !payloadIsDirectReport)) {
                const taskNo = this.selectedTask?.taskNo
                if (!taskNo) {
                    this.reportData = payload || {}
                    this.normalizeReportData()
                    await platformOptionsPromise
                    this.finishReportLoading()
                    return
                }
                const initStage = payload?.stage || '准备中'
                const initPct = payload?.progressPercent || 0
                this.showToast(`${initStage}(${initPct}%)... 报告生成中，请稍候`, 'info')
                const report = await this.pollReportUntilReady(taskNo)
                if (report) {
                    this.reportData = report
                    this.normalizeReportData()
                    await platformOptionsPromise
                    this.finishReportLoading()
                } else {
                    throw new Error('报告生成未完成或已失败')
                }
                return
            }

            this.reportData = payload || {}
            this.normalizeReportData()
            await platformOptionsPromise
            this.finishReportLoading()
        },

        finishReportLoading() {
            this.reportLoading = false
            this.$nextTick(() => {
                setTimeout(() => {
                    try {
                        this.renderAllCharts()
                    } catch (e) {
                        console.error('finishReportLoading 渲染图表总体失败:', e)
                    }
                }, 300)
            })
        },

        // 轮询等待后端异步生成报告：每隔 2 秒查一次状态，直到 COMPLETED / FAILED / 超时。
        async pollReportUntilReady(taskNo) {
            const maxAttempts = 600
            const intervalMs = 2000
            let attempts = 0

            while (attempts < maxAttempts) {
                attempts++
                try {
                    const statusRes = await axios.get(`/api/analysis/${taskNo}/report/status`)
                    const s = statusRes.data?.data || {}
                    const status = s.status

                    if (status === 'COMPLETED') {
                        if (s.report && typeof s.report === 'object' && s.report !== null) {
                            this.showToast('报告生成完成', 'success')
                            return s.report
                        }
                        const reportRes = await axios.get(`/api/analysis/${taskNo}/report`, {
                            params: { async: false }
                        })
                        const payload = reportRes.data?.data
                        if (payload && typeof payload === 'object' && payload.report) {
                            this.showToast('报告生成完成', 'success')
                            return payload.report
                        }
                        this.showToast('报告生成完成', 'success')
                        return payload
                    }

                    if (status === 'FAILED') {
                        const errMsg = s.errorMsg || '报告生成失败'
                        this.showToast(errMsg, 'error')
                        return null
                    }

                    if (status === 'RUNNING') {
                        const stage = s.stage || '处理中'
                        const pct = s.progressPercent || 0
                        console.debug(`报告进度: ${pct}% - ${stage}`)
                    }

                    await this.sleep(intervalMs)
                } catch (err) {
                    console.warn('查询报告状态失败，稍后重试:', err)
                    await this.sleep(intervalMs)
                }
            }

            this.showToast('报告生成超时，请稍后重试', 'error')
            return null
        },

        sleep(ms) {
            return new Promise(resolve => setTimeout(resolve, ms))
        },

        async regenerateReport() {
            if (!this.selectedTask) return
            try {
                this.isGlobalReport = false
                this.reportLoading = true
                this.reportData = {}
                this.normalizeReportData()
                const platformOptionsPromise = this.loadReportPlatformFallbacks()
                if (DEMO_MODE && this.selectedTask.isDemo) {
                    try {
                        this.reportData = MOCK_DATA ? MOCK_DATA.getReport(this.selectedTask) : {}
                        this.normalizeReportData()
                        await platformOptionsPromise
                    } catch (e) {
                        console.error('DEMO报告重新生成失败:', e)
                    } finally {
                        this.reportLoading = false
                    }
                    this.showToast('演示报告已重新生成', 'success')
                    this.$nextTick(() => setTimeout(() => {
                        try { this.renderAllCharts() } catch (e) { console.error('渲染DEMO图表失败:', e) }
                    }, 100))
                    return
                }
                try {
                    const res = await axios.post(`/api/analysis/${this.selectedTask.taskNo}/regenerate`, {})
                    await this.handleReportResponse(res, platformOptionsPromise)
                    this.showToast('报告已重新生成', 'success')
                } catch (error) {
                    console.error('重新生成报告失败', error)
                    this.showToast(error.response?.data?.message || '生成失败', 'error')
                    this.reportData = {}
                    this.normalizeReportData()
                    this.reportLoading = false
                }
            } catch (error) {
                console.error('regenerateReport 总体失败:', error)
                this.reportLoading = false
                this.showToast('重新生成报告失败: ' + (error.message || '未知错误'), 'error')
            }
        },

        setChartRef(id, el) {
            if (el) {
                this.chartRefs[id] = el
            }
        },

        // 渲染全部的 ECharts 图表。每个子渲染独立 try/catch，单个图失败不影响其它图。
        renderAllCharts() {
            try {
                this.renderExposureRings()
            } catch (e) {
                console.error('renderExposureRings 失败:', e)
            }
            try {
                this.renderCompetitionCharts()
            } catch (e) {
                console.error('renderCompetitionCharts 失败:', e)
            }
            try {
                this.renderCitationSourceTreemap()
            } catch (e) {
                console.error('renderCitationSourceTreemap 失败:', e)
            }
            try {
                if (this.isGlobalReport) this.renderGlobalTrendCharts()
            } catch (e) {
                console.error('renderGlobalTrendCharts 失败:', e)
            }
        },

        getKeywordCloudWords(mode = 'positive') {
            try {
            const cloudData = this.reportData.keywordCloud || this.reportData.sentimentKeywords || {}
            const sourceData = mode === 'negative'
                ? (cloudData.negative || this.reportData.negativeKeywords || [])
                : (cloudData.positive || this.reportData.positiveKeywords || [])
            const rawWords = Array.isArray(sourceData)
                ? sourceData
                : Object.entries(sourceData || {}).map(([text, count]) => ({ text, count }))
            const grouped = new Map()

            rawWords.forEach(item => {
                if (item === null || item === undefined) return
                const word = typeof item === 'object' ? item : { text: item, count: 1 }
                const text = String(word.text || word.keyword || word.word || word.name || '').trim()
                const rawCount = word.count ?? word.frequency ?? word.weight ?? word.value ?? 1
                const count = Number.isFinite(Number(rawCount))
                    ? Number(rawCount)
                    : Number.parseFloat(String(rawCount).replace(/[,次]/g, ''))
                if (!text || !Number.isFinite(count) || count <= 0) return
                grouped.set(text, (grouped.get(text) || 0) + count)
            })

            let words = Array.from(grouped, ([text, count]) => ({ text, count }))
                .sort((a, b) => b.count - a.count)
                .slice(0, 40)
            if (!words.length) return []

            const maxCount = words[0].count
            const minCount = words[words.length - 1].count
            const minRoot = Math.sqrt(minCount)
            const range = Math.sqrt(maxCount) - minRoot
            words = words.map(word => ({
                ...word,
                fontSize: Math.round(13 + (range ? (Math.sqrt(word.count) - minRoot) / range : 0.5) * 24)
            }))

            const STAGE_WIDTH_PX = 1100
            const STAGE_HEIGHT_PX = 380
            const toW = pxW => Math.min(42, (pxW / STAGE_WIDTH_PX) * 100)
            const toH = pxH => (pxH / STAGE_HEIGHT_PX) * 100

            const total = words.length
            const ellipseA = 0.76
            const ellipseB = 0.52
            const spiralRounds = total < 20 ? 2.2 : (total < 30 ? 2.8 : 3.4)
            const placed = []

            words.forEach((word, i) => {
                const t = i / Math.max(total - 1, 1)
                const angleStart = -Math.PI / 2 + (i % 2 ? 0.5 : -0.5)
                const angle = angleStart + t * Math.PI * 2 * spiralRounds
                const radiusFactor = 0.18 + t * 0.80
                const baseX = 50 + ellipseA * radiusFactor * Math.cos(angle) * 40
                const baseY = 50 + ellipseB * radiusFactor * Math.sin(angle) * 40

                let x = baseX
                let y = baseY
                let tries = 0
                const charCount = [...word.text].length
                let curFontSize = word.fontSize
                let pxW = curFontSize * 1.06 * charCount + 10
                let pxH = curFontSize * 1.35
                let estimatedW = toW(pxW)
                let estimatedH = toH(pxH)
                let shrinkFactor = 1

                while (tries < 90) {
                    let conflict = false
                    for (const p of placed) {
                        const dx = Math.abs(p.x - x)
                        const dy = Math.abs(p.y - y)
                        if (dx < (p.w + estimatedW) * 0.54 && dy < (p.h + estimatedH) * 0.64) {
                            conflict = true
                            break
                        }
                    }
                    if (!conflict) break

                    tries++
                    if (tries <= 55) {
                        const jitterAngle = tries * 0.59
                        const jitterR = 1.4 + tries * 1.05
                        x = baseX + Math.cos(jitterAngle) * jitterR
                        y = baseY + Math.sin(jitterAngle) * jitterR * 0.68
                    } else {
                        shrinkFactor = Math.max(0.58, 1 - (tries - 55) * 0.022)
                        curFontSize = Math.max(12, Math.round(word.fontSize * shrinkFactor))
                        pxW = curFontSize * 1.06 * charCount + 10
                        pxH = curFontSize * 1.35
                        estimatedW = toW(pxW)
                        estimatedH = toH(pxH)
                        const jitterAngle = tries * 0.77
                        const jitterR = 1.8 + tries * 1.2
                        x = baseX + Math.cos(jitterAngle) * jitterR
                        y = baseY + Math.sin(jitterAngle) * jitterR * 0.68
                    }
                }

                x = Math.max(9, Math.min(91, x))
                y = Math.max(12, Math.min(88, y))

                word.fontSize = curFontSize
                placed.push({ x, y, w: estimatedW, h: estimatedH })
                word._x = x
                word._y = y
            })

            return words
            } catch (e) {
                console.error('[getKeywordCloudWords] mode=' + mode + ' 出错返回空:', e?.message, e?.stack)
                return []
            }
        },

        getKeywordCloudWordStyle(word, index, mode = 'positive') {
            const positivePalette = ['#176fe5', '#0b9f78', '#4f67d7', '#239b98', '#3868b8', '#5a9b3d', '#7a5cd7', '#2e7dd1']
            const negativePalette = ['#b2385b', '#d24e64', '#873f75', '#d07a28', '#a84a3f', '#a96531', '#9b4077', '#c95e2f']
            const palette = mode === 'negative' ? negativePalette : positivePalette
            const rotateRange = 10
            const seed = (index * 9301 + 49297) % 233280
            const rotate = ((seed / 233280) - 0.5) * rotateRange
            const useRotate = word.fontSize < 20 && (index % 3 === 1)
            return {
                position: 'absolute',
                left: `${word._x ?? 50}%`,
                top: `${word._y ?? 50}%`,
                transform: `translate(-50%, -50%) rotate(${(useRotate ? rotate : 0).toFixed(1)}deg)`,
                color: palette[index % palette.length],
                fontSize: `${word.fontSize}px`,
                fontWeight: word.fontSize >= 28 ? 700 : (word.fontSize >= 20 ? 600 : 500),
                whiteSpace: 'nowrap',
                lineHeight: 1.15,
                pointerEvents: 'none'
            }
        },
        getPlatformScoreCards() {
            try {
            const report = this.reportData || {}
            const directSource = report.platformScores
                || report.platformScoreCards
                || report.platformPerformanceScores
                || report.platformReputationScores
                || report.platformEvaluations
                || report.platformPerformance
                || []
            const sourceItems = Array.isArray(directSource)
                ? directSource
                : (Array.isArray(directSource?.items)
                    ? directSource.items
                    : (directSource && typeof directSource === 'object'
                        ? Object.entries(directSource).map(([value, item]) => ({
                            value,
                            ...(item && typeof item === 'object' ? item : { score: item })
                        }))
                        : []))
            const breakdown = report.competition?.platformBreakdown
                || report.competition?.byPlatform
                || report.platformCompetition
                || report.platformBreakdown
                || report.platformStats
                || {}

            const normalizePlatformCode = code => {
                if (!code) return code
                let p = String(code).toLowerCase().trim()
                if (p.endsWith('_app')) p = p.substring(0, p.length - 4)
                if (['tencent_yuanbao', 'yuanbao', 'yuanbaoa', 'tenxun'].includes(p)) p = 'tencent'
                if (['wenxin_yiyan', 'ernie', 'yiyan'].includes(p)) p = 'wenxin'
                if (['tongyi', 'tongyi_qianwen', 'qianwen_max', 'qwen'].includes(p)) p = 'qianwen'
                if (p === 'moonshot') p = 'kimi'
                return p
            }

            const itemMap = new Map()
            const normalizedItemMap = new Map()

            const addItem = (item, fallbackValue = '') => {
                if (!item || typeof item !== 'object') return
                const payload = item.data && typeof item.data === 'object'
                    ? { ...item, ...item.data }
                    : item
                const option = this.normalizeReportPlatformOption({
                    value: payload.value
                        || payload.platformCode
                        || payload.aiPlatform
                        || payload.platform
                        || fallbackValue,
                    label: payload.label
                        || payload.platformName
                        || payload.aiDisplayName
                        || payload.aiPlatformName
                        || payload.name
                })
                if (option) {
                    itemMap.set(option.value, { ...payload, _option: option })
                    const normalizedKey = normalizePlatformCode(option.value)
                    if (normalizedKey) normalizedItemMap.set(normalizedKey, { ...payload, _option: option })
                }
            }

            sourceItems.forEach(item => addItem(item))
            if (Array.isArray(breakdown)) {
                breakdown.forEach(item => {
                    const option = this.normalizeReportPlatformOption(item)
                    if (option && !itemMap.has(option.value)) addItem(item)
                })
            } else if (breakdown && typeof breakdown === 'object') {
                Object.entries(breakdown).forEach(([value, item]) => {
                    const option = this.normalizeReportPlatformOption({
                        value,
                        label: item?.platformName || item?.aiDisplayName || item?.label || item?.name
                    })
                    if (option && !itemMap.has(option.value)) addItem(item || {}, value)
                })
            }

            const platformOptions = this.reportPlatformOptions.filter(option => option.value !== 'ALL')
            itemMap.forEach(item => {
                if (!platformOptions.some(option => option.value === item._option.value)) {
                    platformOptions.push(item._option)
                }
            })

            const formatText = value => {
                if (Array.isArray(value)) return value.filter(Boolean).join('、')
                if (value && typeof value === 'object') {
                    return value.text || value.content || value.summary || ''
                }
                return value === null || value === undefined ? '' : String(value).trim()
            }
            const getText = (payload, keys) => {
                for (const key of keys) {
                    const text = formatText(payload?.[key])
                    if (text) return text
                }
                return ''
            }
            const formatScore = value => {
                if (value === null || value === undefined || value === '') return '--'
                const number = Number(String(value).replace(/[分,%]/g, ''))
                return Number.isFinite(number) ? number.toFixed(1) : String(value).replace(/分$/, '')
            }

            return platformOptions.map((option, index) => {
                let payload = itemMap.get(option.value) || null
                if (!payload) {
                    const normalizedKey = normalizePlatformCode(option.value)
                    if (normalizedKey) payload = normalizedItemMap.get(normalizedKey) || null
                }
                payload = payload || {}
                const platformShortNames = {
                    doubao: '豆', doubao_app: '豆', deepseek: 'D', deepseek_app: 'D',
                    qianwen: '千', qianwen_app: '千', tencent: '元', tencent_app: '元',
                    kimi: 'K', kimi_app: 'K', wenxin: '文', wenxin_app: '文'
                }
                const rankValue = payload.rank ?? payload.platformRank ?? payload.ranking
                const scoreValue = payload.score
                    ?? payload.platformScore
                    ?? payload.overallScore
                    ?? payload.reputationScore
                    ?? payload.compositeScore
                const audience = getText(payload, [
                    'monthlyUsers', 'monthlyActiveUsers', 'userScale', 'audienceScale', 'sampleSize', 'sampleCount'
                ])
                const coreFeatures = getText(payload, [
                    'coreFeatures', 'coreFeature', 'features', 'characteristics', 'platformCharacteristics'
                ])
                const sourcePriority = getText(payload, [
                    'sourcePriority', 'sourcePreference', 'sourcePreferences', 'preferredSources'
                ])
                const optimizationFocus = getText(payload, [
                    'optimizationFocus', 'optimization', 'optimizationAdvice', 'recommendation'
                ])
                const brandPerformance = getText(payload, [
                    'brandPerformance', 'currentBrandPerformance', 'performance', 'gap', 'weakness'
                ])
                const hasBackendData = scoreValue !== null && scoreValue !== undefined && scoreValue !== ''
                    || Boolean(coreFeatures || sourcePriority || optimizationFocus || brandPerformance)

                return {
                    value: option.value,
                    label: option.label,
                    shortName: platformShortNames[option.value] || option.label.slice(0, 1),
                    rank: rankValue === null || rankValue === undefined || rankValue === '' ? '--' : rankValue,
                    score: formatScore(scoreValue),
                    audience: audience || (hasBackendData ? '暂未统计' : '平台评分数据待后端返回'),
                    coreFeatures: coreFeatures || '暂无平台特征分析',
                    sourcePriority: sourcePriority || '暂无信源偏好分析',
                    optimizationFocus: optimizationFocus || '暂无优化建议',
                    brandPerformance: brandPerformance || '暂无品牌表现分析',
                    dataMissing: !hasBackendData,
                    colorIndex: index % 6
                }
            }).sort((a, b) => {
                const rankA = Number(a.rank)
                const rankB = Number(b.rank)
                if (Number.isFinite(rankA) && Number.isFinite(rankB)) return rankA - rankB
                if (Number.isFinite(rankA)) return -1
                if (Number.isFinite(rankB)) return 1
                return 0
            })
            } catch (e) {
                console.error('[getPlatformScoreCards] 出错返回空数组:', e?.message, e?.stack)
                return []
            }
        },

        scrollPlatformScores(direction) {
            const track = this.$refs.platformScoreTrack
            if (!track) return
            track.scrollBy({
                left: direction * Math.max(track.clientWidth * 0.92, 280),
                behavior: 'smooth'
            })
        },
        getCitationSourceAddressInfo(source) {
            if (source === null || source === undefined) return { key: '', address: '' }
            const item = typeof source === 'object' ? source : { address: source }
            const nameCandidate = item.name || item.sourceName || item.siteName || item.platformName || ''
            const rawAddress = item.sourceAddress
                || item.url
                || item.address
                || item.sourceUrl
                || item.link
                || item.domain
                || item.host
                || (/^(?:https?:\/\/)?[^\s/]+\.[^\s/]+/i.test(String(nameCandidate)) ? nameCandidate : '')
            if (!rawAddress) return { key: '', address: '' }

            const normalizedInput = String(rawAddress).trim()
            if (!normalizedInput) return { key: '', address: '' }
            try {
                const parsed = new URL(/^[a-z][a-z\d+.-]*:\/\//i.test(normalizedInput) ? normalizedInput : `https://${normalizedInput}`)
                const hostname = parsed.hostname.toLocaleLowerCase().replace(/^www\./, '')
                if (!hostname) return { key: '', address: '' }
                const port = parsed.port ? `:${parsed.port}` : ''
                return {
                    key: `${hostname}${port}`,
                    address: `${parsed.protocol}//${hostname}${port}`
                }
            } catch (error) {
                return {
                    key: normalizedInput.toLocaleLowerCase(),
                    address: normalizedInput
                }
            }
        },

        getCitationSourceAddressLabel(source) {
            return this.getCitationSourceAddressInfo(source).address || '-'
        },

        getCitationSourceTreemapData() {
            try {
            const sourcePayload = this.reportData.citationSourceDistribution
                || this.reportData.sourceDistribution
                || this.reportData.citationSources
                || this.reportData.citationPlatformRanking
                || this.reportData.citationPlatformTop10
                || []
            const rawItems = Array.isArray(sourcePayload)
                ? sourcePayload
                : (Array.isArray(sourcePayload.items)
                    ? sourcePayload.items
                    : Object.entries(sourcePayload || {}).map(([address, value]) => ({ address, value })))
            const grouped = new Map()

            rawItems.forEach(item => {
                if (item === null || item === undefined) return
                const source = typeof item === 'object' ? item : { address: item, value: 1 }
                const addressInfo = this.getCitationSourceAddressInfo(source)
                const rawValue = source.citationCount ?? source.count ?? source.value ?? source.share ?? source.num ?? source.times ?? 1
                const value = Number.isFinite(Number(rawValue))
                    ? Number(rawValue)
                    : Number.parseFloat(String(rawValue).replace(/[%千,次]/g, ''))
                let safeValue = Number.isFinite(value) && value > 0 ? value : 0

                let key = addressInfo.key
                let name = addressInfo.address
                if (!key) {
                    const fallbackName = String(source.name || source.siteName || source.platformName || source.addressName || source.label || '').trim()
                    if (fallbackName) {
                        key = fallbackName
                        name = fallbackName
                    }
                }
                if (!key || !safeValue) return

                const current = grouped.get(key)
                if (current) {
                    current.value += safeValue
                } else {
                    grouped.set(key, {
                        name: name,
                        value: safeValue
                    })
                }
            })

            const allItems = Array.from(grouped.values())
                .filter(item => item && item.name && item.value > 0)
                .sort((a, b) => b.value - a.value)
            const total = allItems.reduce((sum, item) => sum + item.value, 0)
            if (!total) return { data: [], total: 0, sourceCount: 0 }

            const visibleItems = []
            let otherValue = 0
            let otherSourceCount = 0

            allItems.forEach((item, index) => {
                const share = item.value / total
                const shouldKeep = (index < 7) || (index < 10 && share >= 0.03)
                if (shouldKeep) {
                    visibleItems.push({
                        name: this.simplifyDomainLabel(item.name),
                        value: item.value
                    })
                } else {
                    otherValue += item.value
                    otherSourceCount += 1
                }
            })

            if (otherValue > 0 && otherSourceCount > 0) {
                visibleItems.push({
                    name: '其他',
                    value: otherValue,
                    sourceCount: otherSourceCount
                })
            }

            return { data: visibleItems, total, sourceCount: allItems.length }
            } catch (e) {
                console.error('getCitationSourceTreemapData 异常:', e)
                return { data: [], total: 0, sourceCount: 0 }
            }
        },

        simplifyDomainLabel(name) {
            try {
                if (!name) return '-'
                const str = String(name).trim()
                if (str === '其他') return str
                const withoutProtocol = str.replace(/^[a-z][a-z\d+.-]*:\/\//i, '')
                let simplified = withoutProtocol.replace(/\/.*$/, '').replace(/:\d+$/, '')
                if (simplified.length > 22) {
                    simplified = simplified.slice(0, 19) + '…'
                }
                return simplified || str
            } catch (e) {
                return String(name || '-')
            }
        },

        renderCitationSourceTreemap() {
            if (typeof echarts === 'undefined') return
            try {
                const el = document.getElementById('citationSourceTreemapChart')
                if (!el) return
                const oldChart = echarts.getInstanceByDom(el)
                if (oldChart) echarts.dispose(el)
                const chart = echarts.init(el)

                let treemapData = { data: [], total: 0, sourceCount: 0 }
                try {
                    treemapData = this.getCitationSourceTreemapData() || treemapData
                } catch (e) {
                    console.error('getCitationSourceTreemapData 失败:', e)
                }
                const { data, total, sourceCount } = treemapData

                if (!data || !data.length) {
                    chart.setOption({
                        title: {
                            text: '暂无引用源地址数据',
                            left: 'center',
                            top: 'middle',
                            textStyle: { color: '#9aa1aa', fontSize: 13, fontWeight: 400 }
                        }
                    })
                    return
                }

                const palette = ['#11b8a6', '#2f7ef6', '#7450d8', '#18a96b', '#f08a16', '#06a8bf', '#dd2458', '#6179e8', '#d43aa8', '#7a8898']
                const chartData = data.map((item, index) => ({
                    ...item,
                    itemStyle: { color: palette[index % palette.length] }
                }))
                chart.setOption({
                    animationDuration: 450,
                    aria: {
                        enabled: true,
                        description: `引用源地址占比分布，共统计 ${sourceCount} 个地址，方格面积代表引用次数占比。`
                    },
                    tooltip: {
                        trigger: 'item',
                        renderMode: 'richText',
                        backgroundColor: 'rgba(35,42,52,.94)',
                        borderWidth: 0,
                        padding: [9, 11],
                        textStyle: { color: '#fff', fontSize: 12 },
                        formatter: params => {
                            try {
                                const item = params.data || {}
                                const percent = total ? (Number(item.value || 0) / total * 100).toFixed(1) : '0.0'
                                const sourceText = item.sourceCount ? `\n包含地址：${item.sourceCount} 个` : ''
                                return `${item.name}\n引用次数：${item.value}\n占比：${percent}%${sourceText}`
                            } catch (e) { return '数据解析失败' }
                        }
                    },
                    series: [{
                        name: '引用源地址占比',
                        type: 'treemap',
                        left: 0,
                        right: 0,
                        top: 0,
                        bottom: 0,
                        roam: false,
                        nodeClick: false,
                        breadcrumb: { show: false },
                        squareRatio: 0.65,
                        data: chartData,
                        label: {
                            show: true,
                            position: 'insideTopLeft',
                            padding: [10, 11],
                            overflow: 'truncate',
                            ellipsis: '…',
                            formatter: params => {
                                try {
                                    const item = params.data || {}
                                    const percent = total ? (Number(item.value || 0) / total * 100).toFixed(1) : '0.0'
                                    const detail = item.name === '其他'
                                        ? `${item.sourceCount || 0} 个地址`
                                        : `${item.value} 次引用`
                                    return `{name|${item.name}}\n{domain|${detail}}\n{share|${percent}%}`
                                } catch (e) { return `{name|数据错误}` }
                            },
                            rich: {
                                name: { color: '#fff', fontSize: 14, fontWeight: 600, lineHeight: 23 },
                                domain: { color: 'rgba(255,255,255,.82)', fontSize: 10, lineHeight: 18 },
                                share: { color: '#fff', fontSize: 12, fontWeight: 600, lineHeight: 20 }
                            }
                        },
                        itemStyle: {
                            borderColor: '#fff',
                            borderWidth: 3,
                        gapWidth: 3
                    },
                    emphasis: {
                        itemStyle: { shadowBlur: 14, shadowColor: 'rgba(28,37,52,.28)' }
                    },
                    levels: [{
                        itemStyle: { borderColor: '#fff', borderWidth: 3, gapWidth: 3 }
                    }]
                }]
            })
            } catch (e) {
                console.error('renderCitationSourceTreemap 总体失败:', e)
            }
        },

        async generateReportFromHistory() {
            try {
                if (!this.reportHistoryTask) return
                this.selectedTask = this.reportHistoryTask
                this.isGlobalReport = false
                this.currentView = 'report'
                this.reportLoading = true
                this.reportData = {}
                this.normalizeReportData()
                if (DEMO_MODE && this.selectedTask.isDemo) {
                    try {
                        this.reportData = MOCK_DATA ? MOCK_DATA.getReport(this.selectedTask) : {}
                        this.normalizeReportData()
                    } finally {
                        this.reportLoading = false
                    }
                    this.showToast('演示报告生成成功', 'success')
                    this.$nextTick(() => setTimeout(() => {
                        try { this.renderAllCharts() } catch (e) { console.error('渲染图表失败:', e) }
                    }, 100))
                    return
                }
                await this.regenerateReport()
            } catch (error) {
                console.error('generateReportFromHistory 失败:', error)
                this.reportLoading = false
                this.showToast('生成报告失败: ' + (error.message || '未知错误'), 'error')
            }
        },

        async openGlobalReport() {
            try {
                const task = this.reportHistoryTask || this.selectedTask
                if (!task) return
                this.selectedTask = task
                this.isGlobalReport = true
                this.currentView = 'report'
                this.reportLoading = true
                this.reportData = {}
                this.normalizeReportData()
                const platformOptionsPromise = this.loadReportPlatformFallbacks()

                if (DEMO_MODE && task.isDemo) {
                    try {
                        this.reportData = MOCK_DATA ? MOCK_DATA.getGlobalReport(task, this.reportHistoryList) : {}
                        this.normalizeReportData()
                        await platformOptionsPromise
                    } catch (e) {
                        console.error('DEMO全局报告加载失败:', e)
                    } finally {
                        this.reportLoading = false
                    }
                    this.$nextTick(() => setTimeout(() => {
                        try { this.renderAllCharts() } catch (e) { console.error('渲染DEMO全局报告图表失败:', e) }
                    }, 100))
                    return
                }

                try {
                    const res = await axios.post(`/api/analysis/${task.taskNo}/reports/global/generate`, {})
                    this.reportData = res.data.data || {}
                    this.normalizeReportData()
                    await platformOptionsPromise
                } catch (error) {
                    console.error('生成全局数据报告失败', error)
                    this.isGlobalReport = false
                    this.currentView = 'reportHistory'
                    this.showToast(error.response?.data?.message || '生成全局数据报告失败', 'error')
                } finally {
                    this.reportLoading = false
                    if (this.currentView === 'report' && this.isGlobalReport) {
                        this.$nextTick(() => setTimeout(() => {
                            try { this.renderAllCharts() } catch (e) { console.error('渲染全局报告图表失败:', e) }
                        }, 300))
                    }
                }
            } catch (error) {
                console.error('openGlobalReport 总体失败:', error)
                this.reportLoading = false
                this.showToast('打开全局报告失败: ' + (error.message || '未知错误'), 'error')
            }
        },

        normalizeReportPlatformOption(rawPlatform) {
            if (rawPlatform === null || rawPlatform === undefined || rawPlatform === '') return null
            const rawValue = typeof rawPlatform === 'object'
                ? (rawPlatform.value
                    || rawPlatform.platformCode
                    || rawPlatform.aiPlatform
                    || rawPlatform.platform
                    || rawPlatform.platformId
                    || rawPlatform.code
                    || rawPlatform.key
                    || rawPlatform.platformName
                    || rawPlatform.aiDisplayName
                    || rawPlatform.label)
                : rawPlatform
            const rawLabel = typeof rawPlatform === 'object'
                ? (rawPlatform.label
                    || rawPlatform.platformName
                    || rawPlatform.aiDisplayName
                    || rawPlatform.aiPlatformName
                    || rawPlatform.displayName
                    || rawPlatform.name
                    || rawValue)
                : rawPlatform
            if (!rawValue) return null

            const normalizedValue = String(rawValue).trim()
            const normalizedLabel = String(rawLabel || rawValue).trim()
            const knownPlatform = this.aiPlatforms.find(platform =>
                platform.value.toLowerCase() === normalizedValue.toLowerCase()
                || platform.label.toLowerCase() === normalizedValue.toLowerCase()
                || platform.value.toLowerCase() === normalizedLabel.toLowerCase()
                || platform.label.toLowerCase() === normalizedLabel.toLowerCase()
            )
            return knownPlatform
                ? { value: knownPlatform.value, label: knownPlatform.label }
                : { value: normalizedValue, label: normalizedLabel }
        },

        // 兜底获取报告里可选的平台列表：优先用已加载的结果反推，否则再请求结果接口
        async loadReportPlatformFallbacks() {
            const task = this.selectedTask || this.reportHistoryTask
            const taskNo = task?.taskNo
            if (!taskNo) {
                this.reportPlatformFallbacks = []
                return
            }

            const mapResultsToPlatforms = results => {
                const unique = new Map()
                ;(Array.isArray(results) ? results : []).forEach(result => {
                    const option = this.normalizeReportPlatformOption({
                        value: result?.aiPlatform || result?.platformCode || result?.platform,
                        label: result?.aiDisplayName || result?.platformName || result?.aiPlatformName
                    })
                    if (option && !unique.has(option.value)) unique.set(option.value, option)
                })
                return Array.from(unique.values())
            }

            if (this.taskResultsTaskNo === taskNo && this.taskResults.length) {
                this.reportPlatformFallbacks = mapResultsToPlatforms(this.taskResults)
                return
            }

            try {
                const res = await axios.get(`/api/task/${taskNo}/results`)
                const results = res.data.data || []
                this.taskResults = results
                this.taskResultsTaskNo = taskNo
                this.reportPlatformFallbacks = mapResultsToPlatforms(results)
            } catch (error) {
                console.warn('加载报告平台列表失败，将使用报告接口已有字段', error)
                this.reportPlatformFallbacks = []
            }
        },

        extractCompetitionFromComparisonTable(comparison) {
            try {
            if (!comparison || !Array.isArray(comparison.columns) || !Array.isArray(comparison.rows)) return null

            const ranking = (this.reportData.rankings || []).find(item => item.id === 'mention_ranking')
            const rankingItems = Array.isArray(ranking?.items) ? ranking.items : []
            const brandNames = comparison.columns.slice(1)
            const metrics = comparison.rows.map(row => ({
                label: row.metric || row.label || '-',
                values: Array.isArray(row.values) ? row.values : []
            }))

            const mentionRow = metrics.find(m => m.label === '品牌覆盖率')
            const mentionRates = brandNames.map((_, i) => {
                const v = mentionRow?.values?.[i]
                let raw = v
                if (typeof raw === 'string') raw = raw.replace('%', '')
                const n = Number(raw)
                return Number.isFinite(n) ? n : 0
            })

            const indices = brandNames.map((_, i) => i)
                .sort((a, b) => mentionRates[b] - mentionRates[a])

            const sortedBrandNames = indices.map(i => brandNames[i])
            const sortedVoice = indices.map(i => mentionRates[i])

            const brands = sortedBrandNames.map((name) => {
                const rankingItem = rankingItems.find(item => item.name === name)
                return {
                    name,
                    rank: rankingItem?.rank || 1
                }
            })

            const sortedMetrics = metrics.map(m => ({
                label: m.label,
                values: indices.map(i => m.values[i] ?? '-')
            }))

            return {
                brands,
                metrics: sortedMetrics,
                voiceRanking: sortedVoice,
                radarSeries: []
            }
            } catch (e) {
                console.error('[extractCompetitionFromComparisonTable] 出错，返回null:', e?.message, e?.stack)
                return null
            }
        },

        getBackendBrandComparisonCompetition() {
            try {
                return this.extractCompetitionFromComparisonTable(this.reportData.brandComparison)
            } catch (e) {
                console.error('[getBackendBrandComparisonCompetition] 出错:', e?.message)
                return null
            }
        },

        findFirstOccurrence(text, keyword) {
            if (!text || !keyword) return -1
            return text.indexOf(keyword)
        },

        extractBrandPositions(combinedText, brands) {
            const positions = {}
            const lowerText = (combinedText || '').toLowerCase()
            const firstIndexes = []

            for (const rawBrand of brands) {
                const brand = (rawBrand || '').trim()
                if (!brand) continue
                const brandLower = brand.toLowerCase()
                const idx = lowerText.indexOf(brandLower)
                if (idx >= 0) {
                    firstIndexes.push({ brand, idx })
                }
            }

            firstIndexes.sort((a, b) => a.idx - b.idx)
            for (let i = 0; i < firstIndexes.length; i++) {
                positions[firstIndexes[i].brand] = i + 1
            }

            return positions
        },

        computeComparisonFromResults(results, brandNames, platformValue) {
            if (!Array.isArray(results) || !results.length || !Array.isArray(brandNames) || !brandNames.length) {
                return null
            }

            const rankingMap = this.reportData?.resultBrandRankings || {}

            const targetOption = this.normalizeReportPlatformOption(platformValue)
            const targetPlatformKey = targetOption?.value || platformValue

            const filtered = results.filter(r => {
                const rp = this.normalizeReportPlatformOption({
                    value: r.aiPlatform || r.platform || r.platformCode,
                    label: r.aiDisplayName || r.platformName || r.aiPlatform
                })
                return rp && rp.value === targetPlatformKey
            })

            if (!filtered.length) return null

            const validResults = filtered.filter(r => {
                const s = (r.status || '').toUpperCase()
                return s === 'SUCCESS' || s === 'COMPLETED' || r.success === true || r.completed === true || r.hasResult === true || (r.answerText && String(r.answerText).trim().length > 0)
            })

            const total = validResults.length || 0

            const brandStats = brandNames.map(() => ({
                mentionCount: 0,
                firstCount: 0,
                top3Count: 0,
                top5Count: 0,
                compSelfCount: 0,
                compOtherCount: 0
            }))

            const brandNamesLower = brandNames.map(b => (b || '').toLowerCase())

            for (const result of validResults) {
                const answerText = result.answerText || ''
                const combinedText = answerText
                const combinedLower = combinedText.toLowerCase()

                let positions = {}
                const aiRanking = result.id != null ? (rankingMap[String(result.id)] || result.brandRanking) : null
                if (aiRanking && Array.isArray(aiRanking) && aiRanking.length) {
                    aiRanking.forEach((brandName, idx) => {
                        if (brandName) positions[String(brandName)] = idx + 1
                    })
                } else {
                    positions = this.extractBrandPositions(combinedText, brandNames)
                }

                const mentionedFlags = brandNamesLower.map((brandLower, i) => {
                    const brandName = brandNames[i]
                    const inPositions = Object.prototype.hasOwnProperty.call(positions, brandName)
                    if (inPositions) return true
                    return brandLower ? this.findFirstOccurrence(combinedLower, brandLower) >= 0 : false
                })

                const posByIndex = brandNames.map(b => positions[b] || null)

                for (let i = 0; i < brandNames.length; i++) {
                    const pos = posByIndex[i]
                    const mentioned = mentionedFlags[i]

                    if (pos) {
                        brandStats[i].mentionCount++
                        if (pos === 1) brandStats[i].firstCount++
                        if (pos <= 3) brandStats[i].top3Count++
                        if (pos <= 5) brandStats[i].top5Count++
                    } else if (mentioned) {
                        brandStats[i].mentionCount++
                    }

                    if (mentioned) brandStats[i].compSelfCount++

                    let mentionedOther = false
                    for (let j = 0; j < brandNames.length; j++) {
                        if (j === i) continue
                        if (mentionedFlags[j]) {
                            mentionedOther = true
                            break
                        }
                    }
                    if (mentionedOther) brandStats[i].compOtherCount++
                }
            }

            const brandMetrics = brandStats.map(stats => {
                const mentionRate = total > 0 ? (stats.mentionCount * 100) / total : 0
                const firstRate = total > 0 ? (stats.firstCount * 100) / total : 0
                const top3Rate = total > 0 ? (stats.top3Count * 100) / total : 0
                const top5Rate = total > 0 ? (stats.top5Count * 100) / total : 0

                const compTotal = stats.compSelfCount + stats.compOtherCount
                const compScore = compTotal > 0 ? Math.round((stats.compSelfCount * 100) / compTotal) : 0

                return {
                    mentionRate: mentionRate.toFixed(2) + '%',
                    mentionRateNum: +mentionRate.toFixed(2),
                    firstRate: firstRate.toFixed(2) + '%',
                    top3Rate: top3Rate.toFixed(2) + '%',
                    top5Rate: top5Rate.toFixed(2) + '%',
                    compScore,
                    compScoreStr: String(compScore)
                }
            })

            const indices = brandNames.map((_, i) => i)
                .sort((a, b) => brandMetrics[b].mentionRateNum - brandMetrics[a].mentionRateNum)

            const sortedNames = indices.map(i => brandNames[i])
            const sortedMetricsRaw = indices.map(i => brandMetrics[i])

            const brands = sortedNames.map((name, index) => ({ name, rank: index + 1 }))
            const voiceRanking = sortedMetricsRaw.map(m => m.mentionRateNum)

            const metricLabels = ['品牌竞争力', '品牌覆盖率', '首位率', '前三率', '前五率']
            const metrics = metricLabels.map(label => {
                const key = label === '品牌竞争力' ? 'compScoreStr'
                    : label === '品牌覆盖率' ? 'mentionRate'
                    : label === '首位率' ? 'firstRate'
                    : label === '前三率' ? 'top3Rate'
                    : 'top5Rate'
                return {
                    label,
                    values: sortedMetricsRaw.map(bm => bm[key])
                }
            })

            return {
                brands,
                metrics,
                voiceRanking,
                radarSeries: []
            }
        },

        computeMultiBrandRadarSeries(metric) {
            const globalComparison = this.reportData.brandComparison
            const brandNames = (globalComparison && Array.isArray(globalComparison.columns))
                ? globalComparison.columns.slice(1)
                : []
            if (!brandNames.length) {
                return { indicators: [], series: [] }
            }

            const radarPlatformOrder = [
                { value: 'deepseek', label: 'DeepSeek' },
                { value: 'qianwen', label: '通义千问' },
                { value: 'kimi', label: 'Kimi' },
                { value: 'tencent', label: '元宝' },
                { value: 'wenxin', label: '文心助手' },
                { value: 'doubao', label: '豆包' }
            ]

            const metricLabelMap = {
                mentionRate: '品牌覆盖率',
                firstRate: '首位率',
                top3Rate: '前三率',
                top5Rate: '前五率'
            }
            const targetLabel = metricLabelMap[metric] || '覆盖率'

            const perPlatform = this.reportData.perPlatformBrandComparison || {}

            const resolvePlatformTable = code => {
                if (perPlatform[code]) return perPlatform[code]
                const hit = Object.entries(perPlatform).find(([k]) => {
                    const n1 = this.normalizeReportPlatformOption(k)
                    return n1 && n1.value === code
                })
                return hit ? hit[1] : null
            }

            const platformMetricMap = {}
            radarPlatformOrder.forEach(platformOpt => {
                const platformTable = resolvePlatformTable(platformOpt.value)
                const valuesByBrand = {}
                if (platformTable && Array.isArray(platformTable.rows) && Array.isArray(platformTable.columns)) {
                    const metricRow = platformTable.rows.find(r => (r.metric || r.label) === targetLabel)
                    brandNames.forEach((brand, idx) => {
                        const colIdx = idx + 1
                        let raw = metricRow?.values?.[colIdx - 1] ?? 0
                        if (typeof raw === 'string') {
                            raw = raw.replace('%', '')
                        }
                        const num = Number(raw)
                        valuesByBrand[brand] = Number.isFinite(num) ? num : 0
                    })
                } else {
                    const platformResult = this.computeComparisonFromResults(
                        this.taskResults,
                        brandNames,
                        platformOpt.value
                    )
                    const metricRow = (platformResult?.metrics || []).find(m => m.label === targetLabel)
                    brandNames.forEach((b, i) => {
                        let raw = metricRow?.values?.[i] ?? 0
                        if (typeof raw === 'string') {
                            raw = raw.replace('%', '')
                        }
                        const num = Number(raw)
                        valuesByBrand[b] = Number.isFinite(num) ? num : 0
                    })
                }
                platformMetricMap[platformOpt.value] = valuesByBrand
            })

            const palette = ['#2f7ef6', '#f08a24', '#13a764', '#7b68ee', '#e85d75', '#8a6d3b', '#23c6c8', '#f8ac59', '#1c84c6', '#1ab394']
            const series = brandNames.map((name, idx) => {
                const value = radarPlatformOrder.map(opt => platformMetricMap[opt.value]?.[name] ?? 0)
                return {
                    name,
                    value,
                    symbolSize: 5,
                    lineStyle: { width: 1.5 },
                    itemStyle: { color: palette[idx % palette.length] },
                    areaStyle: { opacity: 0.08 }
                }
            })

            const indicators = radarPlatformOrder.map(opt => ({ name: opt.label, max: 100 }))
            return { indicators, series }
        },

        getCompetitionForPlatform(platformValue = 'ALL') {
            try {
            const competition = this.reportData.competition || {}
            if (!platformValue || platformValue === 'ALL') {
                const backendComparison = this.getBackendBrandComparisonCompetition()
                if (!backendComparison) return competition

                const mergedBrands = Array.isArray(competition.brands) && competition.brands.length
                    ? competition.brands
                    : backendComparison.brands
                const mergedMetrics = Array.isArray(competition.metrics) && competition.metrics.length
                    ? competition.metrics
                    : backendComparison.metrics
                const mergedVoiceRanking = Array.isArray(competition.voiceRanking) && competition.voiceRanking.length
                    ? competition.voiceRanking
                    : backendComparison.voiceRanking
                const mergedRadarSeries = Array.isArray(competition.radarSeries) && competition.radarSeries.length
                    ? competition.radarSeries
                    : backendComparison.radarSeries

                return {
                    ...competition,
                    ...backendComparison,
                    brands: mergedBrands,
                    metrics: mergedMetrics,
                    voiceRanking: mergedVoiceRanking,
                    radarSeries: mergedRadarSeries
                }
            }

            const target = this.normalizeReportPlatformOption(platformValue)
            const targetKey = target?.value || platformValue

            if (this.reportData.perPlatformBrandComparison && targetKey) {
                let platformTable = this.reportData.perPlatformBrandComparison[targetKey]
                if (!platformTable) {
                    const hit = Object.entries(this.reportData.perPlatformBrandComparison).find(([k]) => {
                        const n1 = this.normalizeReportPlatformOption(k)
                        return n1 && target && n1.value === target.value
                    })
                    if (hit) platformTable = hit[1]
                }
                if (platformTable) {
                    const extracted = this.extractCompetitionFromComparisonTable(platformTable)
                    if (extracted) return extracted
                }
            }

            const containers = [
                competition.platformBreakdown,
                competition.byPlatform,
                this.reportData.platformCompetition,
                this.reportData.platformReports,
                this.reportData.platformBreakdown,
                this.reportData.platformStats,
                this.reportData.byPlatform
            ].filter(Boolean)

            for (const container of containers) {
                let matched = null
                if (Array.isArray(container)) {
                    matched = container.find(item => {
                        const option = this.normalizeReportPlatformOption(item)
                        return option && target && option.value === target.value
                    })
                } else if (typeof container === 'object') {
                    matched = container[platformValue]
                    if (!matched && target) {
                        const matchedEntry = Object.entries(container).find(([key, item]) => {
                            const option = this.normalizeReportPlatformOption({
                                value: key,
                                label: item?.platformName || item?.aiDisplayName || item?.label
                            })
                            return option && option.value === target.value
                        })
                        matched = matchedEntry ? matchedEntry[1] : null
                    }
                }

                if (matched) {
                    return matched.competition || matched.data?.competition || matched
                }
            }

            const globalComparison = this.reportData.brandComparison
            const globalBrands = globalComparison && Array.isArray(globalComparison.columns)
                ? globalComparison.columns.slice(1)
                : []
            const computed = this.computeComparisonFromResults(
                this.taskResults,
                globalBrands,
                platformValue
            )
            if (computed) return computed

            return {
                brands: [],
                metrics: [],
                voiceRanking: [],
                radarSeries: [],
                platformDataMissing: true
            }
            } catch (e) {
                console.error('[getCompetitionForPlatform] 出错，返回空安全结构 platformValue=' + platformValue, e?.message, e?.stack)
                return { brands: [], metrics: [], voiceRanking: [], radarSeries: [], platformDataMissing: true, error: e?.message }
            }
        },

        handleReportPlatformChange() {
            this.$nextTick(() => this.renderCompetitionCharts())
        },

        // 绘制竞争力相关的图表：综合得分仪表盘、品牌声量柱状图、多品牌雷达图
        renderCompetitionCharts() {
            if (typeof echarts === 'undefined') return
            const createChart = (id, option, onReady) => {
                try {
                    const el = document.getElementById(id)
                    if (!el) return
                    const oldChart = echarts.getInstanceByDom(el)
                    if (oldChart) echarts.dispose(el)
                    const chart = echarts.init(el)
                    chart.setOption(option || {})
                    if (typeof onReady === 'function') onReady(chart)
                } catch (e) {
                    console.error(`创建图表 ${id} 失败:`, e)
                }
            }
            const voiceCompetition = this.voiceCompetition || {}
            const voiceBrands = (voiceCompetition.brands || []).map(item => item.name)
            const rawOverallScore = Number(this.reportData.overallScore || 0)
            const overallScore = Number.isFinite(rawOverallScore) ? Math.min(100, Math.max(0, rawOverallScore)) : 0
            const rawScoreChange = Number(this.reportData.scoreChange || 0)
            const scoreChange = Number.isFinite(rawScoreChange) ? Math.abs(rawScoreChange) : 0
            const scoreTrendText = rawScoreChange > 0 ? `▲ ${scoreChange}` : (rawScoreChange < 0 ? `▼ ${scoreChange}` : '— 0')
            const scoreTrendColor = rawScoreChange > 0 ? '#11a861' : (rawScoreChange < 0 ? '#ff4d5a' : '#9aa0a8')
            const scoreLevel = this.reportData.scoreLevel || '良好'
            const scoreGaugeRadiusRatio = 1.28
            const scoreGaugeCenterYRatio = 0.66
            const scoreGaugeAxisWidth = 38
            createChart('overallScoreChart', {
                animation: false,
                series: [
                    {
                        type: 'gauge', startAngle: 180, endAngle: 0, min: 0, max: 100, radius: '128%', center: ['50%', '66%'],
                        pointer: { show: false },
                        progress: { show: false },
                        axisLine: { lineStyle: { width: scoreGaugeAxisWidth, color: [[0.35, '#ff4855'], [0.72, '#ffc533'], [1, '#11bd6b']] } },
                        axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false },
                        anchor: { show: false },
                        title: { show: true, offsetCenter: [0, '-23%'], color: '#9aa0a8', fontSize: 11 },
                        detail: {
                            valueAnimation: false,
                            offsetCenter: [0, '3%'],
                            formatter: value => `{score|${Math.round(value)}}`,
                            rich: { score: { width: 104, align: 'center', fontSize: 48, color: '#242932', fontWeight: 500, lineHeight: 52 } }
                        },
                        data: [{ value: 0, name: '综合得分' }]
                    },
                    {
                        type: 'custom',
                        coordinateSystem: 'none',
                        silent: true,
                        z: 10,
                        renderItem: (params, api) => {
                            const markerScoreRaw = Number(api.value(0))
                            const markerScore = Number.isFinite(markerScoreRaw) ? Math.min(100, Math.max(0, markerScoreRaw)) : 0
                            const markerBandColor = markerScore <= 35 ? '#ff4855' : (markerScore <= 72 ? '#ffc533' : '#11bd6b')
                            const centerX = api.getWidth() * 0.5
                            const centerY = api.getHeight() * scoreGaugeCenterYRatio
                            const radius = Math.min(api.getWidth(), api.getHeight()) * 0.5 * scoreGaugeRadiusRatio
                            const angle = Math.PI - (markerScore / 100) * Math.PI
                            // ECharts 的 gauge radius 表示圆环外沿；标记必须完全落在圆环厚度内。
                            const innerRadius = radius - scoreGaugeAxisWidth + 4
                            const outerRadius = radius - 4
                            const innerX = centerX + Math.cos(angle) * innerRadius
                            const innerY = centerY - Math.sin(angle) * innerRadius
                            const outerX = centerX + Math.cos(angle) * outerRadius
                            const outerY = centerY - Math.sin(angle) * outerRadius
                            return {
                                type: 'group',
                                children: [
                                    {
                                        type: 'line',
                                        shape: { x1: innerX, y1: innerY, x2: outerX, y2: outerY },
                                        style: { stroke: 'rgba(45, 55, 72, .16)', lineWidth: 8, lineCap: 'round', shadowBlur: 3, shadowColor: 'rgba(31, 42, 55, .18)' }
                                    },
                                    {
                                        type: 'line',
                                        shape: { x1: innerX, y1: innerY, x2: outerX, y2: outerY },
                                        style: { stroke: '#fff', lineWidth: 6, lineCap: 'round' }
                                    },
                                    {
                                        type: 'line',
                                        shape: { x1: innerX, y1: innerY, x2: outerX, y2: outerY },
                                        style: { stroke: markerBandColor, lineWidth: 2, lineCap: 'round' }
                                    }
                                ]
                            }
                        },
                        data: [0]
                    },
                    {
                        type: 'gauge', startAngle: 180, endAngle: 0, min: 0, max: 100, radius: '128%', center: ['50%', '66%'],
                        pointer: { show: false }, axisLine: { show: false }, axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false }, anchor: { show: false }, title: { show: false },
                        detail: {
                            show: true,
                            offsetCenter: ['28%', '4%'],
                            formatter: `{trend|${scoreTrendText}}`,
                            rich: { trend: { color: scoreTrendColor, fontSize: 12, fontWeight: 500 } }
                        },
                        data: [{ value: overallScore }]
                    },
                    {
                        type: 'gauge', startAngle: 180, endAngle: 0, min: 0, max: 100, radius: '128%', center: ['50%', '66%'],
                        pointer: { show: false }, axisLine: { show: false }, axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false }, anchor: { show: false }, title: { show: false },
                        detail: {
                            show: true,
                            offsetCenter: [0, '42%'],
                            formatter: `{level|${scoreLevel}}`,
                            rich: { level: { color: '#fff', backgroundColor: '#f5b51b', padding: [4, 9], borderRadius: 2, fontSize: 12, lineHeight: 16 } }
                        },
                        data: [{ value: overallScore }]
                    }
                ]
            }, chart => {
                const duration = 1400
                const startedAt = performance.now()
                const animateScore = now => {
                    if (chart.isDisposed()) return
                    const elapsed = Math.min(1, Math.max(0, (now - startedAt) / duration))
                    const eased = 1 - Math.pow(1 - elapsed, 3)
                    const currentScore = overallScore * eased
                    chart.setOption({
                        series: [
                            { data: [{ value: currentScore, name: '综合得分' }] },
                            { data: [currentScore] }
                        ]
                    }, { lazyUpdate: true, silent: true })
                    if (elapsed < 1) requestAnimationFrame(animateScore)
                }
                requestAnimationFrame(animateScore)
            })
            createChart('brandVoiceChart', voiceBrands.length ? {
                tooltip: { trigger: 'axis' }, grid: { left: 74, right: 24, top: 18, bottom: 28 }, xAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf0f4' } } }, yAxis: { type: 'category', inverse: true, data: voiceBrands, axisTick: { show: false }, axisLine: { show: false } },
                series: [{ type: 'bar', data: voiceCompetition.voiceRanking || [], barWidth: 12, itemStyle: { color: '#2f7ef6' }, label: { show: true, position: 'right', fontSize: 10, color: '#22b65f', formatter: params => params.dataIndex === 0 ? '▲ 11' : (params.dataIndex === 2 ? '▼ 5' : '') } }]
            } : {
                title: {
                    text: this.voicePlatform === 'ALL' ? '暂无品牌声量数据' : '该平台统计数据待后端返回',
                    left: 'center',
                    top: 'middle',
                    textStyle: { color: '#9aa1aa', fontSize: 13, fontWeight: 400 }
                }
            })
            let radar = { indicators: [], series: [] }
            try {
                radar = this.computeMultiBrandRadarSeries(this.radarMetric) || radar
            } catch (e) {
                console.error('计算雷达图数据失败:', e)
            }
            createChart('multiBrandRadarChart', (radar.indicators && radar.indicators.length) ? {
                tooltip: {
                    trigger: 'item',
                    confine: true,
                    position: function(point, params, el, elRect, size) {
                        const obj = { top: 10 };
                        obj[['left', 'right'][+(point[0] < size.viewSize[0] / 2)]] = 10;
                        return obj;
                    },
                    backgroundColor: 'rgba(255,255,255,0.95)',
                    borderColor: '#e0e0e0',
                    borderWidth: 1,
                    padding: [6, 10],
                    textStyle: { fontSize: 11, color: '#333' },
                    formatter: params => {
                        try {
                            if (!Array.isArray(params.value) || !Array.isArray(radar.indicators)) return params.name
                            const lines = radar.indicators.map((ind, i) => `${ind.name}: ${Number(params.value[i] || 0).toFixed(2)}%`)
                            return `${params.name}<br/>${lines.join('<br/>')}`
                        } catch (e) { return params.name }
                    }
                },
                legend: { right: 4, bottom: 4, orient: 'vertical', textStyle: { fontSize: 10 } },
                radar: {
                    center: ['42%', '52%'],
                    radius: '62%',
                    indicator: radar.indicators,
                    splitArea: { areaStyle: { color: ['#fff'] } },
                    axisLine: { lineStyle: { color: '#e7ebf1' } },
                    splitLine: { lineStyle: { color: '#e7ebf1' } }
                },
                series: [{ type: 'radar', data: radar.series }]
            } : {
                title: {
                    text: '暂无雷达图数据',
                    left: 'center',
                    top: 'middle',
                    textStyle: { color: '#9aa1aa', fontSize: 13, fontWeight: 400 }
                }
            })
        },

        // 绘制“全局报告”专属的历史趋势折线图（声量/引用热度/正向率/定位贴合度）
        renderGlobalTrendCharts() {
            if (typeof echarts === 'undefined') return
            try {
                const trends = this.reportData.globalTrends
                if (!trends || typeof trends !== 'object') return

                const palette = ['#2f7ef6', '#13a764', '#f08a24', '#7b68ee', '#e85d75']
                const chartConfigs = [
                    { id: 'brandVoiceTrendChart', key: 'brandVoice' },
                    { id: 'citationHeatTrendChart', key: 'citationHeat' },
                    { id: 'positiveSentimentTrendChart', key: 'positiveSentiment' },
                    { id: 'positioningFitTrendChart', key: 'positioningFit' }
                ]

                chartConfigs.forEach(({ id, key }) => {
                    try {
                        const el = document.getElementById(id)
                        const config = trends[key]
                        if (!el || !config) {
                            if (el) {
                                const oldChart = echarts.getInstanceByDom(el)
                                if (oldChart) echarts.dispose(el)
                                const chart = echarts.init(el)
                                chart.setOption({
                                    title: {
                                        text: '暂无趋势数据',
                                        left: 'center',
                                        top: 'middle',
                                        textStyle: { color: '#9aa1aa', fontSize: 13, fontWeight: 400 }
                                    }
                                })
                            }
                            return
                        }
                        const oldChart = echarts.getInstanceByDom(el)
                        if (oldChart) echarts.dispose(el)
                        const chart = echarts.init(el)
                        const dates = config.dates || trends.dates || []
                        let unit = config.unit || ''
                        if (key === 'positioningFit') unit = ''
                        const series = (config.series || []).map((item, index) => ({
                            name: item.name || ('系列' + (index + 1)),
                            type: 'line',
                            data: item.data || [],
                            smooth: 0.35,
                            symbol: 'circle',
                            symbolSize: 5,
                            showSymbol: false,
                            connectNulls: true,
                            lineStyle: { width: 2, color: item.color || palette[index % palette.length] },
                            itemStyle: { color: item.color || palette[index % palette.length] },
                            emphasis: { focus: 'series' }
                        }))

                        chart.setOption({
                            color: palette,
                            animationDuration: 450,
                            tooltip: {
                                trigger: 'axis',
                                backgroundColor: 'rgba(255,255,255,.96)',
                                borderColor: '#dfe4eb',
                                textStyle: { color: '#3e4651', fontSize: 11 },
                                valueFormatter: value => `${value}${unit}`
                            },
                            legend: {
                                top: 2,
                                right: 8,
                                itemWidth: 12,
                                itemHeight: 7,
                                textStyle: { color: '#66707c', fontSize: 10 }
                            },
                            grid: { left: 48, right: 22, top: series.length > 1 ? 48 : 24, bottom: 38 },
                            xAxis: {
                                type: 'category',
                                boundaryGap: false,
                                data: dates,
                                axisTick: { show: false },
                                axisLine: { lineStyle: { color: '#cfd5dd' } },
                                axisLabel: { color: '#7c8590', fontSize: 10, hideOverlap: true }
                            },
                            yAxis: {
                                type: 'value',
                                min: 0,
                                max: unit === '%' ? 100 : null,
                                axisLabel: { color: '#7c8590', fontSize: 10, formatter: value => `${value}${unit}` },
                                splitLine: { lineStyle: { color: '#edf0f4' } }
                            },
                            series
                        })
                    } catch (e) {
                        console.error(`渲染趋势图 ${id} 失败:`, e)
                    }
                })
            } catch (e) {
                console.error('renderGlobalTrendCharts 总体失败:', e)
            }
        },

        // 绘制品牌曝光区的几个环形图（覆盖率、首位率、前三率、前五率）
        renderExposureRings() {
            try {
                const metrics = this.reportData.exposureMetrics || {}
                const coverageRate = this.parseRate(metrics.coverageRate)
                const firstRate = this.parseRate(metrics.firstRate)
                const top3Rate = this.parseRate(metrics.top3Rate)
                const top5Rate = this.parseRate(metrics.top5Rate)

                this.renderRingChart('coverageRingChart', coverageRate, '#4f8df7', 160, '品牌覆盖率')
                this.renderRingChart('firstRateRingChart', firstRate, '#91CC75', 100)
                this.renderRingChart('top3RateRingChart', top3Rate, '#7B68EE', 100)
                this.renderRingChart('top5RateRingChart', top5Rate, '#FF69B4', 100)
            } catch (e) {
                console.error('renderExposureRings 失败:', e)
            }
        },

        renderRingChart(elementId, percentage, color, size, centerLabel = '') {
            try {
                const el = document.getElementById(elementId)
                if (!el) return
                if (typeof echarts === 'undefined') return
                if (echarts.getInstanceByDom(el)) {
                    echarts.dispose(el)
                }
                const safePct = Number.isFinite(+percentage) ? (+percentage) : 0
                const chart = echarts.init(el)
                chart.setOption({
                    series: [{
                        type: 'pie',
                        radius: size > 120 ? ['65%', '85%'] : ['60%', '82%'],
                        center: ['50%', '50%'],
                        startAngle: 90,
                        silent: true,
                        label: {
                            show: true,
                            position: 'center',
                            formatter: () => centerLabel
                                ? `{value|${safePct.toFixed(1)}%}\n{name|${centerLabel}}`
                                : `{small|${safePct.toFixed(1)}%}`,
                            color: '#1a1a2e',
                            rich: {
                                value: { color: '#3f4650', fontSize: 22, fontWeight: 600, lineHeight: 28 },
                                name: { color: '#767e88', fontSize: 10, lineHeight: 14 },
                                small: { color: '#59616c', fontSize: 10, fontWeight: 600 }
                            }
                        },
                        data: [
                            { value: Math.max(0.1, safePct), itemStyle: { color: color } },
                            { value: Math.max(0, 100 - safePct), itemStyle: { color: '#f0f0f0' } }
                        ]
                    }]
                })
            } catch (e) {
                console.error(`renderRingChart ${elementId} 失败:`, e)
            }
        },

        renderChart(chartConfig) {
            const el = this.chartRefs[chartConfig.id]
            if (!el) return
            const chart = echarts.init(el)
            const seriesData = chartConfig.series.map(s => ({
                name: s.name,
                type: chartConfig.type === 'BAR' ? 'bar' : 'line',
                data: s.data,
                smooth: true,
                itemStyle: { color: s.color },
                areaStyle: chartConfig.type === 'LINE' ? { opacity: 0.1 } : null,
                barMaxWidth: 30
            }))
            chart.setOption({
                tooltip: { trigger: 'axis' },
                legend: {
                    show: true,
                    bottom: 0,
                    textStyle: { fontSize: 11 },
                    data: seriesData.map(s => s.name)
                },
                grid: { left: '3%', right: '4%', bottom: '15%', containLabel: true },
                xAxis: {
                    type: 'category',
                    data: chartConfig.xAxis,
                    axisLabel: { fontSize: 11 }
                },
                yAxis: {
                    type: 'value',
                    axisLabel: { fontSize: 11 }
                },
                series: seriesData
            })
        },

        formatReportDate(dateStr) {
            if (!dateStr) return '--'
            const d = new Date(dateStr)
            if (isNaN(d.getTime())) return dateStr
            const pad = n => String(n).padStart(2, '0')
            return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
        },

        getTrendClass(direction) {
            if (direction === 'UP') return 'trend-up'
            if (direction === 'DOWN') return 'trend-down'
            return 'trend-flat'
        },

        getRankingBadgeClass(rank) {
            if (rank === 1) return 'top1'
            if (rank === 2) return 'top2'
            if (rank === 3) return 'top3'
            return 'normal'
        },

        parseRate(val) {
            if (!val) return 0
            const num = parseFloat(String(val).replace('%', ''))
            return isNaN(num) ? 0 : Math.min(100, Math.max(0, num))
        }
    }
})

// ===== 以下是对 Vue/浏览器全局错误的兜底拦截，核心目标是“任何异常都不能让页面白屏” =====

try {
    __geoApp.config.errorHandler = function (err, vm, info) {
        console.error('[Vue errorHandler 拦截-阻止白屏]', { message: err?.message, info, stack: err?.stack })
        const msg = err?.message || ''
        if (msg.indexOf('length') !== -1) {
            console.warn('[Vue] 命中length读取失败，尝试定位。reportData当前keys:', Object.keys((vm && vm.reportData) || (vm?.$data?.reportData) || {}).join(','))
        }
        return true
    }
    __geoApp.config.warnHandler = function (msg, vm, trace) {
        console.warn('[Vue warnHandler 拦截]', msg, trace?.slice(0, 300))
    }
} catch (_) {}

// 监听全局未捕获错误与未处理的 Promise 拒绝，统一打日志便于排查
window.addEventListener('error', function (e) {
    console.error('[window.error 兜底]', { message: e?.message, file: e?.filename, line: e?.lineno, col: e?.colno, stack: e?.error?.stack })
    const msg = String(e?.message || '')
    if (msg.indexOf('length') !== -1) {
        console.warn('[window.error] length类错误阻断冒泡')
        try { e.preventDefault() } catch (_) {}
        try { e.stopPropagation() } catch (_) {}
        return false
    }
}, true)
window.addEventListener('unhandledrejection', function (e) {
    console.error('[Promise未处理rejection]', e?.reason?.message, e?.reason?.stack)
})

// 最后把整个应用挂载到 index.html 的 #app 节点上，页面正式开始渲染
window.app = __geoApp.mount('#app')