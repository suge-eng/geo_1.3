/**
 * 仅供 DEMO_MODE=true 时使用的演示数据。
 * 本文件不发起请求，也不会覆盖后端返回的数据。
 */
window.GEO_MOCK_DATA = {
    createDemoScreenshot(index, platform) {
        const palettes = [
            ['#edf4ff', '#2f7ef6'],
            ['#effbf4', '#13a764'],
            ['#fff6e8', '#f08a24']
        ]
        const [background, accent] = palettes[index % palettes.length]
        const question = ['网站建设公司哪家好？', '国内专业的 GEO 服务商有哪些？', '企业如何提升生成式搜索曝光？'][index % 3]
        const svg = `
            <svg xmlns="http://www.w3.org/2000/svg" width="960" height="600" viewBox="0 0 960 600">
                <rect width="960" height="600" fill="#f5f6f8"/>
                <rect x="42" y="34" width="876" height="532" rx="16" fill="#fff" stroke="#dfe4ea"/>
                <rect x="42" y="34" width="876" height="62" rx="16" fill="${background}"/>
                <circle cx="78" cy="65" r="12" fill="${accent}"/>
                <text x="102" y="72" font-family="Arial,Microsoft YaHei" font-size="22" font-weight="700" fill="#202731">${platform} 查询截图</text>
                <text x="78" y="139" font-family="Arial,Microsoft YaHei" font-size="18" font-weight="700" fill="#303844">用户提问</text>
                <rect x="78" y="160" width="804" height="60" rx="8" fill="#f7f8fa"/>
                <text x="98" y="197" font-family="Arial,Microsoft YaHei" font-size="18" fill="#454d58">${question}</text>
                <text x="78" y="266" font-family="Arial,Microsoft YaHei" font-size="18" font-weight="700" fill="#303844">AI 回答摘要</text>
                <rect x="78" y="286" width="804" height="190" rx="8" fill="#fbfcfd" stroke="#e8ebef"/>
                <text x="102" y="329" font-family="Arial,Microsoft YaHei" font-size="17" fill="#4e5662">综合服务经验、案例质量与持续优化能力，推荐关注丰泰美化、</text>
                <text x="102" y="365" font-family="Arial,Microsoft YaHei" font-size="17" fill="#4e5662">巧效 GEO 和增长研究院等品牌，并结合企业实际需求进行评估。</text>
                <rect x="102" y="402" width="116" height="34" rx="5" fill="${background}"/>
                <text x="121" y="425" font-family="Arial,Microsoft YaHei" font-size="15" fill="${accent}">品牌已展现</text>
                <text x="78" y="526" font-family="Arial,Microsoft YaHei" font-size="14" fill="#8b939e">演示截图 · 仅 DEMO_MODE=true 时显示</text>
            </svg>`
        return `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`
    },

    getTasks() {
        return [
            { taskNo: 'DEMO-20260811-01', title: '巧效 GEO 品牌核心词观测', scope: 'LOCAL', status: 'PENDING', totalCount: 30, completedCount: 0, intentCount: 10, questionCount: 30, executionFrequency: 'single', nextRunTime: '-', isDemo: true },
            { taskNo: 'DEMO-20260811-02', title: '品牌口碑周度观测', scope: 'LOCAL', status: 'COMPLETED', totalCount: 30, completedCount: 30, intentCount: 10, questionCount: 30, executionFrequency: 'weekly', nextRunTime: '2026/08/17', isDemo: true },
            { taskNo: 'DEMO-20260811-03', title: '产品内容趋势观测', scope: 'LOCAL', status: 'RUNNING', totalCount: 30, completedCount: 15, intentCount: 10, questionCount: 30, executionFrequency: 'daily', nextRunTime: '2026/08/12', isDemo: true },
            { taskNo: 'DEMO-20260811-04', title: '全局行业曝光观测', scope: 'GLOBAL', status: 'RUNNING', totalCount: 150, completedCount: 75, intentCount: 60, questionCount: 150, executionFrequency: 'weekly', nextRunTime: '2026/08/17', isDemo: true },
            { taskNo: 'DEMO-20260811-05', title: '竞品声量持续观测', scope: 'LOCAL', status: 'COMPLETED', totalCount: 30, completedCount: 30, intentCount: 10, questionCount: 30, executionFrequency: 'weekly', nextRunTime: '2026/08/17', isDemo: true },
            { taskNo: 'DEMO-20260811-06', title: 'AIGC 营销服务商推荐', scope: 'GLOBAL', status: 'COMPLETED', totalCount: 90, completedCount: 90, intentCount: 30, questionCount: 90, executionFrequency: 'daily', nextRunTime: '2026/08/12', isDemo: true },
            { taskNo: 'DEMO-20260811-07', title: '企业建站需求洞察', scope: 'LOCAL', status: 'PARTIAL_FAILED', totalCount: 45, completedCount: 38, intentCount: 15, questionCount: 45, executionFrequency: 'single', nextRunTime: '-', isDemo: true },
            { taskNo: 'DEMO-20260811-08', title: '生成式搜索品牌提及', scope: 'GLOBAL', status: 'PROCESSING', totalCount: 120, completedCount: 36, intentCount: 40, questionCount: 120, executionFrequency: 'daily', nextRunTime: '2026/08/12', isDemo: true },
            { taskNo: 'DEMO-20260811-09', title: '华东区域业务口碑', scope: 'LOCAL', status: 'COMPLETED', totalCount: 60, completedCount: 60, intentCount: 20, questionCount: 60, executionFrequency: 'weekly', nextRunTime: '2026/08/17', isDemo: true },
            { taskNo: 'DEMO-20260811-10', title: '重点竞品内容对比', scope: 'LOCAL', status: 'FAILED', totalCount: 30, completedCount: 8, intentCount: 10, questionCount: 30, executionFrequency: 'single', nextRunTime: '-', isDemo: true }
        ].map(task => ({
            ...task,
            aiPlatforms: ['doubao', 'deepseek', 'qianwen', 'kimi', 'wenxin']
        }))
    },

    getResults() {
        const longAnswer = `淡斑乳膏（药品类）\n⚠️淡斑药膏属于外用药物，有刺激性，建议先看皮肤科医生，不要自行长期乱用；无论用哪款，白天必须严格防晒，否则斑会加重。
1. 氢醌乳膏（千白，人人康）
效果：临床公认淡斑较强，针对黄褐斑、雀斑、炎症后黑印。
特点：处方药，2% 氢醌，抑制黑色素生成。
禁忌：孕妇、12 岁以下禁用；会有灼热、泛红，先小范围皮试；只涂斑的位置，必须防晒，连续 2 个月无效要停用。
2. 维 A 酸乳膏（山东方明、北京双吉、韩都）
效果：加速角质代谢，淡化黄褐斑、晒斑、痘印色沉，夜间使用。
选浓度：新手优先0.025%，刺激性更低；0.1% 力度大、脱皮泛红明显。
禁忌：孕妇严禁用；只晚上涂；初期会干、脱皮，建立耐受，白天严格防晒。
3. 壬二酸乳膏（15%‑20%）
效果：相对温和，适合黄褐斑、痘印黑印，还适合容易泛红敏感皮肤。
特点：刺激性比氢醌、维 A 酸低；部分人会短暂刺痒。
提示：多数壬二酸乳膏说明书适应症写痤疮，色素沉着属于临床拓展用法，建议遵医嘱使用。
4. 中药类祛斑软膏
丝白祛斑软膏（天峰）：活血化瘀，适合气血瘀滞型黄褐斑，中成药，刺激性小，但见效慢，需坚持使用。
参棘软膏（班丽净）：改善气虚血瘀黄褐斑，适合面色暗沉长斑人群。
护肤品类淡斑乳膏（非药，力度弱，相对安全）
成分看：烟酰胺、传明酸、377、熊果苷
常见品牌：修丽可、科颜氏、珀莱雅、OLAY 等，只能淡化浅层暗沉，对顽固黄褐斑效果有限。
简单怎么选
顽固黄褐斑、雀斑 →优先看医生，氢醌乳膏（处方药）
痘印、晒斑，皮肤耐受 →维 A 酸 0.025%（晚间）
敏感肌、怕刺激 →壬二酸，或者中成药丝白祛斑软膏
日常保养、浅层暗沉 →正规淡斑护肤品
重要提醒
1. 黄褐斑成因复杂（激素、熬夜、紫外线），单靠乳膏不一定能根治，防晒是第一要务；
2. 氢醌、维 A 酸孕期全部禁用；
3. 如果用了持续发红刺痛、反黑，立刻停用就医；
4. 网上来路不明 “速效祛斑霜” 很多非法添加激素、高浓度氢醌，不要买。
如果你愿意，可以说下：是什么斑（黄褐斑 / 晒斑 / 痘印）、皮肤敏不敏感、是否备孕怀孕，我帮你缩到最合适 1‑2 个选项。`
        const platforms = ['豆包', '通义千问', 'DeepSeek', 'Kimi', '文心一言']
        const questions = ['网站建设公司哪家好？', '国内专业的 GEO 服务商有哪些？', '企业如何提升生成式搜索曝光？', 'AI 搜索品牌优化应该怎么做？', '适合中小企业的营销公司有哪些？']
        return Array.from({ length: 20 }, (_, index) => ({
            id: `DEMO-RESULT-${index + 1}`,
            questionText: questions[index % questions.length],
            level: ['L5', 'L4', 'L3', 'L3', 'L2'][index % 5],
            tag: ['服务商推荐', '品牌认知', '解决方案', '行业对比'][index % 4],
            intentWord: ['网站建设公司', 'GEO 服务商', '品牌曝光', 'AI 搜索优化'][index % 4],
            showStatus: index % 3 === 1 ? 'FAILED' : 'SUCCESS',
            brand: index % 3 === 1 ? '-' : '巧效 GEO、增长研究院',
            referenceCount: index % 3 === 0 ? 2 : (index % 5 === 0 ? 1 : 0),
            sentiment: ['negative', 'positive', 'neutral'][index % 3],
            aiDisplayName: platforms[index % platforms.length],
            queryTime: `2026-08-11 10:${String(20 + index).padStart(2, '0')}`,
            status: index % 4 === 0 ? 'SUCCESS' : 'PENDING',
            thinkingContent: '系统对品牌提及、回答位置、引用来源和情感倾向进行了综合分析。',
            answerText: longAnswer,
            screenshotUrls: index % 4 === 0 ? [this.createDemoScreenshot(index, platforms[index % platforms.length])] : [],
            sources: [
                { siteName: '企业服务观察', author: '编辑部', publishTime: '2026-08-01', url: 'https://example.com/geo-observe', title: '企业生成式搜索优化服务观察' },
                { siteName: '数字营销周刊', author: '研究组', publishTime: '2026-07-26', url: 'https://example.com/ai-marketing', title: 'AI 搜索时代的品牌增长方法' }
            ]
        }))
    },

    getReport(task = {}) {
        const brands = [
            { rank: 1, name: '增长超人' }, { rank: 2, name: '智推时代' }, { rank: 3, name: 'PureblueAI 清蓝' },
            { rank: 4, name: '光引 GEO' }, { rank: 5, name: '欧博东方文化' }, { rank: 6, name: '百分点科技' }
        ]
        const availablePlatforms = [
            { value: 'doubao', label: '豆包' },
            { value: 'deepseek', label: 'DeepSeek' },
            { value: 'qianwen', label: '通义千问' },
            { value: 'kimi', label: 'Kimi' },
            { value: 'wenxin', label: '文心一言' }
        ].filter(platform => !Array.isArray(task.aiPlatforms) || task.aiPlatforms.length === 0 || task.aiPlatforms.includes(platform.value))
        const metricLabels = ['品牌竞争力', '品牌覆盖率', '首位率', '前三率', '前五率']
        const metricSeeds = [
            [35, 263, 166, 103, 75, 74],
            [11.97, 54.7, 32.48, 25.64, 21.37, 20.51],
            [0.85, 18.8, 15.38, 4.27, 1.71, 2.56],
            [5.13, 41.88, 23.08, 17.95, 12.82, 11.97],
            [8.55, 48.72, 28.21, 22.22, 17.95, 17.09]
        ]
        const voiceSeed = [263, 136, 105, 243, 147, 74]
        const platformBreakdown = Object.fromEntries(availablePlatforms.map((platform, platformIndex) => {
            const scale = 0.17 + platformIndex * 0.025
            const metrics = metricSeeds.map((row, rowIndex) => ({
                label: metricLabels[rowIndex],
                values: row.map((value, brandIndex) => {
                    if (rowIndex === 0) return String(Math.max(1, Math.round(value * scale + (brandIndex + platformIndex) % 4)))
                    const adjustment = ((platformIndex + 1) * (brandIndex % 2 ? 1 : -1)) * 0.75
                    return `${Math.max(0, Math.min(100, value + adjustment)).toFixed(2).replace(/\.00$/, '')}%`
                })
            }))
            return [platform.value, {
                platformCode: platform.value,
                platformName: platform.label,
                brands,
                metrics,
                voiceRanking: voiceSeed.map((value, brandIndex) => Math.max(1, Math.round(value * scale + (platformIndex + brandIndex) % 7)))
            }]
        }))
        return {
            id: `DEMO-REPORT-${Date.now()}`,
            title: `${task.title || '品牌观测'}数据报告`, brandName: '丰泰美化', taskNo: task.taskNo || 'DEMO-20260811-02',
            reportDate: new Date().toISOString(), totalQuestions: task.questionCount || 30,
            totalPlatforms: 6, completedResults: task.completedCount || 30, totalResults: task.totalCount || 30,
            overallScore: 72, scoreChange: -5, scoreLevel: '良好',
            availablePlatforms,
            exposureMetrics: { mentionCount: '72/180', coverageRate: 72, firstRate: 18.8, top3Rate: 41.88, top5Rate: 48.72 },
            competition: {
                brands,
                metrics: [
                    { label: '品牌竞争力', values: ['35', '263', '166', '103', '75', '74'] },
                    { label: '品牌覆盖率', values: ['11.97%', '54.7% ↓2.5%', '32.48%', '25.64%', '21.37%', '20.51%'] },
                    { label: '首位率', values: ['0.85% ↑0.35%', '18.8%', '15.38%', '4.27%', '1.71%', '2.56%'] },
                    { label: '前三率', values: ['5.13%', '41.88%', '23.08%', '17.95%', '12.82%', '11.97%'] },
                    { label: '前五率', values: ['8.55%', '48.72%', '28.21%', '22.22%', '17.95%', '17.09%'] }
                ],
                dimensions: [68, 57, 88, 61], voiceRanking: [263, 136, 105, 243, 147, 74],
                radarSeries: [
                    { name: '万方VanCheer', value: [31, 48, 40, 36, 28, 44] }, { name: '极智慕枫', value: [18, 32, 25, 22, 35, 29] },
                    { name: '增长超人', value: [47, 72, 61, 58, 50, 63] }, { name: '上海薇辰', value: [26, 42, 34, 29, 45, 37] }
                ],
                platformBreakdown
            },
            competitionRanking: [
                { rank: 1, brandName: '丰泰美化', score: 234, trend: '↑4', isSelfBrand: true },
                { rank: 2, brandName: '增长超人', score: 221, trend: '↑2' },
                { rank: 3, brandName: '智推时代', score: 196, trend: '↓1' },
                { rank: 4, brandName: 'PureblueAI 清蓝', score: 184 },
                { rank: 5, brandName: '光引 GEO', score: 173, trend: '↑1' },
                { rank: 6, brandName: '欧博东方文化', score: 162 },
                { rank: 7, brandName: '百分点科技', score: 151, trend: '↓2' },
                { rank: 8, brandName: '万方 VanCheer', score: 143 },
                { rank: 9, brandName: '极智慕枫', score: 132, trend: '↑3' },
                { rank: 10, brandName: '上海薇辰', score: 126 },
                { rank: 11, brandName: '营销兵法', score: 118, trend: '↑1' },
                { rank: 12, brandName: 'GEO 优化工场', score: 112 },
                { rank: 13, brandName: '数字增长实验室', score: 105, trend: '↓2' },
                { rank: 14, brandName: '内容引擎', score: 97 },
                { rank: 15, brandName: '品牌智库', score: 91, trend: '↑2' }
            ],
            citationPlatformRanking: [
                { rank: 1, sourceAddress: 'https://mp.weixin.qq.com', citationCount: 234, trend: '↑8' },
                { rank: 2, sourceAddress: 'https://zhihu.com', citationCount: 211, trend: '↑3' },
                { rank: 3, sourceAddress: 'https://baijiahao.baidu.com', citationCount: 196 },
                { rank: 4, sourceAddress: 'https://finance.sina.com.cn', citationCount: 184, trend: '↑2' },
                { rank: 5, sourceAddress: 'https://sohu.com', citationCount: 173 },
                { rank: 6, sourceAddress: 'https://163.com', citationCount: 162, trend: '↓1' },
                { rank: 7, sourceAddress: 'https://toutiao.com', citationCount: 151 },
                { rank: 8, sourceAddress: 'https://news.qq.com', citationCount: 143, trend: '↑4' },
                { rank: 9, sourceAddress: 'https://36kr.com', citationCount: 132 },
                { rank: 10, sourceAddress: 'https://brand.example.com', citationCount: 126 },
                { rank: 11, sourceAddress: 'https://xiaohongshu.com', citationCount: 119, trend: '↑3' },
                { rank: 12, sourceAddress: 'https://bilibili.com', citationCount: 113 },
                { rank: 13, sourceAddress: 'https://douyin.com', citationCount: 106, trend: '↑1' },
                { rank: 14, sourceAddress: 'https://thepaper.cn', citationCount: 98 },
                { rank: 15, sourceAddress: 'https://huxiu.com', citationCount: 91, trend: '↓2' }
            ],
            citationSourceDistribution: [
                { url: 'https://mp.weixin.qq.com/s/demo-article-01', citationCount: 286 },
                { url: 'https://zhihu.com/question/demo-answer-02', citationCount: 218 },
                { url: 'https://baijiahao.baidu.com/s?id=demo-03', citationCount: 164 },
                { url: 'https://brand.example.com/news/demo-04', citationCount: 132 },
                { url: 'https://finance.sina.com.cn/tech/demo-05', citationCount: 118 },
                { url: 'https://sohu.com/a/demo-06', citationCount: 96 },
                { url: 'https://163.com/dy/article/demo-07.html', citationCount: 82 },
                { url: 'https://36kr.com/p/demo-08', citationCount: 71 },
                { url: 'https://toutiao.com/article/demo-09', citationCount: 58 },
                { url: 'https://xiaohongshu.com/explore/demo-10', citationCount: 44 },
                { url: 'https://bilibili.com/read/demo-11', citationCount: 32 },
                { url: 'https://thepaper.cn/newsDetail_forward_demo-12', citationCount: 25 },
                { url: 'https://huxiu.com/article/demo-13.html', citationCount: 18 },
                { url: 'https://industry.example.com/article/demo-14', citationCount: 12 }
            ],
            keywordCloud: {
                positive: [
                    { text: '专业可靠', count: 46 }, { text: '服务周到', count: 43 },
                    { text: '响应及时', count: 39 }, { text: '技术领先', count: 36 },
                    { text: '交付稳定', count: 34 }, { text: '案例丰富', count: 31 },
                    { text: '值得信赖', count: 29 }, { text: '效果明显', count: 27 },
                    { text: '团队专业', count: 25 }, { text: '解决方案完善', count: 23 },
                    { text: '沟通顺畅', count: 21 }, { text: '持续优化', count: 20 },
                    { text: '性价比高', count: 18 }, { text: '品牌认可', count: 17 },
                    { text: '定位精准', count: 16 }, { text: '数据透明', count: 15 },
                    { text: '执行高效', count: 14 }, { text: '内容权威', count: 13 },
                    { text: '体验优秀', count: 12 }, { text: '增长明显', count: 11 },
                    { text: '覆盖广泛', count: 10 }, { text: '创新能力', count: 9 },
                    { text: '服务细致', count: 8 }, { text: '售后完善', count: 7 },
                    { text: '行业经验', count: 6 }, { text: '合规稳健', count: 5 },
                    { text: '反馈及时', count: 4 }, { text: '口碑良好', count: 3 }
                ],
                negative: [
                    { text: '价格门槛高', count: 48 }, { text: '成立时间较短', count: 45 },
                    { text: '报价偏高', count: 41 }, { text: '数据自说自话', count: 37 },
                    { text: '合规风险', count: 34 }, { text: '交付周期长', count: 31 },
                    { text: '效果不稳定', count: 29 }, { text: '案例不足', count: 27 },
                    { text: '响应较慢', count: 25 }, { text: '覆盖不足', count: 23 },
                    { text: '预算门槛', count: 22 }, { text: '缺少透明度', count: 20 },
                    { text: '服务波动', count: 18 }, { text: '行业经验有限', count: 17 },
                    { text: '沟通成本高', count: 16 }, { text: '方案同质化', count: 15 },
                    { text: '过度承诺', count: 14 }, { text: '售后不足', count: 13 },
                    { text: '数据更新慢', count: 12 }, { text: '定位模糊', count: 11 },
                    { text: '支持不足', count: 10 }, { text: '价格波动', count: 9 },
                    { text: '内容重复', count: 8 }, { text: '转化不明显', count: 7 },
                    { text: '依赖人工', count: 6 }, { text: '小微企业压力大', count: 5 },
                    { text: '试错成本高', count: 4 }, { text: '见效周期长', count: 3 }
                ]
            },
            rankings: [],
            aiSummary: {
                content: '本期品牌综合竞争力得分为 72 分，整体表现良好。品牌在声量正向维度表现突出，但平台覆盖和首位提及仍有提升空间。',
                highlights: ['品牌声量保持稳定，核心内容被多个平台引用', 'DeepSeek 与豆包平台的品牌覆盖贡献较高', '核心竞品在首位率上仍保持领先'],
                suggestions: ['加强高意图问题的权威内容建设', '针对薄弱平台补充差异化素材', '持续追踪竞品首位提及变化']
            }
        }
    },

    getGlobalReport(task = {}, history = []) {
        const report = this.getReport(task)
        const fallbackDates = [
            '2026-05-29', '2026-06-05', '2026-06-12', '2026-06-19',
            '2026-06-26', '2026-07-03', '2026-07-10', '2026-07-17',
            '2026-07-24', '2026-07-31', '2026-08-07', '2026-08-14'
        ]
        const historyDates = (history || [])
            .map(item => item.reportDate)
            .filter(Boolean)
            .sort((a, b) => new Date(a) - new Date(b))
            .map(value => {
                const date = new Date(value)
                return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
            })
        const dates = historyDates.length >= 2 ? historyDates : fallbackDates.map(value => value.slice(5))
        const historyReportCount = dates.length
        const completedResults = (history || []).reduce((sum, item) => sum + Number(item.completedResults || 0), 0) || historyReportCount * 30
        const totalResults = (history || []).reduce((sum, item) => sum + Number(item.totalResults || 0), 0) || historyReportCount * 30

        const align = values => Array.from({ length: historyReportCount }, (_, index) => values[index % values.length])
        return {
            ...report,
            id: `DEMO-GLOBAL-REPORT-${Date.now()}`,
            title: `${task.title || '品牌观测'}全局数据报告`,
            isGlobalReport: true,
            historyReportCount,
            rangeStart: '2026-05-29T10:35:00+08:00',
            rangeEnd: '2026-08-14T10:35:00+08:00',
            totalQuestions: historyReportCount * (task.questionCount || 30),
            completedResults,
            totalResults,
            overallScore: 78,
            scoreChange: 6,
            scoreLevel: '良好',
            exposureMetrics: { mentionCount: '914/2160', coverageRate: 76.4, firstRate: 21.7, top3Rate: 45.3, top5Rate: 52.8 },
            citationSourceDistribution: report.citationSourceDistribution.map((item, index) => ({
                ...item,
                citationCount: item.citationCount * historyReportCount + (index % 3) * 17
            })),
            keywordCloud: Object.fromEntries(Object.entries(report.keywordCloud).map(([mode, words]) => [
                mode,
                words.map((word, index) => ({
                    ...word,
                    count: word.count * historyReportCount + (index % 4) * 3
                }))
            ])),
            globalTrends: {
                dates,
                brandVoice: {
                    unit: '%',
                    series: [
                        { name: '丰泰美化', data: align([62, 65, 67, 66, 69, 71, 70, 73, 72, 75, 77, 79]) },
                        { name: '增长超人', data: align([74, 72, 70, 69, 71, 73, 72, 70, 69, 71, 70, 68]) },
                        { name: '智推时代', data: align([58, 60, 61, 63, 62, 64, 66, 65, 67, 66, 68, 69]) },
                        { name: 'PureblueAI 清蓝', data: align([42, 44, 43, 46, 47, 49, 48, 50, 52, 51, 53, 55]) },
                        { name: '光引 GEO', data: align([51, 50, 52, 54, 53, 55, 57, 56, 58, 59, 61, 60]) }
                    ]
                },
                citationHeat: {
                    unit: '次',
                    series: [
                        { name: 'DeepSeek', data: align([118, 132, 151, 143, 166, 179, 172, 188, 181, 196, 204, 218]) },
                        { name: '豆包', data: align([96, 111, 104, 126, 139, 132, 148, 155, 149, 168, 175, 184]) },
                        { name: '文心助手', data: align([82, 90, 101, 97, 112, 118, 126, 121, 137, 142, 151, 158]) },
                        { name: 'Kimi', data: align([75, 88, 84, 96, 102, 109, 105, 117, 124, 131, 138, 145]) },
                        { name: '通义千问', data: align([69, 77, 86, 82, 94, 99, 108, 113, 119, 126, 134, 141]) }
                    ]
                },
                positiveSentiment: {
                    unit: '%',
                    series: [
                        { name: '丰泰美化', data: align([56, 59, 63, 68, 65, 71, 74, 70, 76, 79, 77, 82]) }
                    ]
                },
                positioningFit: {
                    unit: '%',
                    series: [
                        { name: 'DeepSeek', data: align([61, 64, 67, 65, 70, 72, 74, 73, 77, 79, 81, 84]) },
                        { name: '豆包', data: align([58, 60, 62, 66, 64, 68, 69, 72, 71, 75, 77, 79]) },
                        { name: '文心助手', data: align([49, 53, 55, 57, 60, 59, 63, 65, 67, 66, 70, 73]) },
                        { name: 'Kimi', data: align([46, 48, 51, 50, 54, 57, 56, 60, 62, 64, 63, 68]) },
                        { name: '通义千问', data: align([52, 55, 54, 58, 61, 63, 62, 66, 68, 70, 72, 74]) }
                    ]
                }
            },
            aiSummary: {
                content: `本全局报告汇总了 ${historyReportCount} 份历史报告。品牌综合竞争力总体呈上升趋势，声量、正向评价和定位贴合度均有改善，引用热度在多个 AI 平台持续增长。`,
                highlights: ['品牌声量在最近 12 个观测周期内稳步提升', 'DeepSeek 与豆包的引用热度增长最明显', '声音正向率与定位贴合度同步改善'],
                suggestions: ['继续保持高频历史观测，避免趋势断点', '针对引用热度较低的平台补充权威内容', '结合单次报告定位异常波动的具体问句']
            }
        }
    },

    getReportHistory(task = {}) {
        const baseReport = this.getReport(task)
        const dates = [
            '2026-08-14T10:35:00+08:00', '2026-08-07T10:35:00+08:00', '2026-07-31T10:35:00+08:00',
            '2026-07-24T10:35:00+08:00', '2026-07-17T10:35:00+08:00', '2026-07-10T10:35:00+08:00',
            '2026-07-03T10:35:00+08:00', '2026-06-26T10:35:00+08:00', '2026-06-19T10:35:00+08:00',
            '2026-06-12T10:35:00+08:00', '2026-06-05T10:35:00+08:00', '2026-05-29T10:35:00+08:00'
        ]
        const coverageRates = [76.4, 74.8, 73.9, 72.1, 71.6, 70.8, 69.4, 68.7, 67.9, 66.8, 65.4, 63.9]
        return dates.map((reportDate, index) => {
            const exposureMetrics = {
                ...baseReport.exposureMetrics,
                coverageRate: coverageRates[index],
                firstRate: 21.7 - index * 0.28,
                top3Rate: 45.3 - index * 0.42,
                top5Rate: 52.8 - index * 0.46
            }
            const demoData = {
                ...baseReport,
                id: `DEMO-REPORT-${12 - index}`,
                reportDate,
                overallScore: 78 - Math.round(index * 0.55),
                scoreChange: index === 0 ? 2 : 1,
                exposureMetrics
            }
            return {
                id: demoData.id,
                title: demoData.title,
                reportDate,
                brandName: demoData.brandName,
                totalQuestions: demoData.totalQuestions,
                totalPlatforms: demoData.totalPlatforms,
                completedResults: demoData.completedResults,
                totalResults: demoData.totalResults,
                coverageRate: exposureMetrics.coverageRate,
                firstRate: exposureMetrics.firstRate,
                top3Rate: exposureMetrics.top3Rate,
                top5Rate: exposureMetrics.top5Rate,
                demoData
            }
        })
    }
}
