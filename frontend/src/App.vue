<template>
  <main class="app-shell">
    <aside class="sidebar" aria-label="工作区导航">
      <div class="brand">
        <span class="brand-mark">CR</span>
        <div>
          <strong>Codex Research</strong>
          <small>研报语义分析台</small>
        </div>
      </div>

      <nav class="nav-list">
        <button class="nav-item" :class="{ active: activeMainTab === 'upload' }" type="button" @click="activeMainTab = 'upload'">
          入库
        </button>
        <button class="nav-item" :class="{ active: activeMainTab === 'query' }" type="button" @click="activeMainTab = 'query'">
          检索
        </button>
        <button class="nav-item" :class="{ active: activeMainTab === 'observation' }" type="button" @click="activeMainTab = 'observation'">
          研报观测
        </button>
      </nav>

      <div class="runtime-panel">
        <span>运行状态</span>
        <strong>{{ runtimeText }}</strong>
      </div>
    </aside>

    <section class="workspace">
      <header class="workspace-header">
        <div>
          <p class="eyebrow">Spring AI + Milvus + Qwen</p>
          <h1>研报上传、语义切片与推荐分析</h1>
        </div>
        <div class="status-pill" :class="backendState">
          <span class="dot"></span>
          {{ statusText }}
        </div>
      </header>

      <section v-if="activeMainTab === 'upload'" id="upload" class="band upload-band">
        <div class="section-heading">
          <span>01</span>
          <div>
            <h2>上传研报</h2>
            <p>PDF 会进入后端解析、语义切片、MySQL 元数据落库和 Milvus 向量入库流程。</p>
          </div>
        </div>

        <div class="upload-layout">
          <label class="file-drop" :class="{ selected: selectedFile }">
            <input type="file" accept="application/pdf" @change="onFileChange" />
            <span class="file-icon">PDF</span>
            <strong>{{ selectedFile ? selectedFile.name : '选择研报 PDF' }}</strong>
            <small>{{ selectedFile ? formatBytes(selectedFile.size) : '支持券商研报、行业报告、公司深度 PDF' }}</small>
          </label>

          <form class="form-grid" @submit.prevent="uploadPdf">
            <label>
              <span>标题</span>
              <input v-model.trim="uploadForm.title" placeholder="例如：新能源行业2026年度策略" />
            </label>
            <label>
              <span>来源</span>
              <input v-model.trim="uploadForm.source" placeholder="例如：券商研报" />
            </label>
            <label>
              <span>机构</span>
              <input v-model.trim="uploadForm.institution" placeholder="例如：中信证券" />
            </label>
            <label>
              <span>发布日期</span>
              <input v-model="uploadForm.publishDate" type="date" />
            </label>
            <label>
              <span>主题</span>
              <input v-model.trim="uploadForm.themeTags" placeholder="例如：STORAGE:储能，AI_COMPUTE:AI算力" />
            </label>
            <label>
              <span>行业</span>
              <input v-model.trim="uploadForm.industryTags" placeholder="例如：POWER_EQUIPMENT:电力设备" />
            </label>
            <label>
              <span>公司</span>
              <input v-model.trim="uploadForm.companyTags" placeholder="例如：宁德时代，比亚迪" />
            </label>
            <label>
              <span>代码</span>
              <input v-model.trim="uploadForm.tickerTags" placeholder="例如：300750.SZ，002594.SZ" />
            </label>
            <button class="primary-btn" :disabled="uploading" type="submit">
              {{ uploading ? '正在入库...' : '上传并入库' }}
            </button>
          </form>
        </div>

        <p v-if="uploadMsg" class="feedback" :class="uploadTone">{{ uploadMsg }}</p>
        <div class="query-actions">
          <button class="ghost-btn" type="button" :disabled="!latestJobId" @click="checkLatestJob">
            查询最新任务状态
          </button>
        </div>
      </section>

      <section v-if="activeMainTab === 'query'" id="query" class="band query-band">
        <div class="section-heading">
          <span>02</span>
          <div>
            <h2>投研问题</h2>
            <p>问题会先走 Milvus ANN 召回，再由 Qwen 基于证据流式输出分析。</p>
          </div>
        </div>

        <div class="observation-layout">
          <div class="question-box">
            <textarea v-model.trim="query" rows="6" placeholder="输入你想分析的问题，例如：哪些研报看好储能板块，核心逻辑和风险是什么？"></textarea>
            <div class="query-actions">
              <button class="primary-btn" :disabled="loading" @click="runRecommend">
                {{ loading ? '分析中...' : '开始分析' }}
              </button>
              <button class="ghost-btn" @click="clearResult">清空</button>
            </div>
          </div>

          <div class="template-list">
            <button v-for="item in queryTemplates" :key="item" type="button" @click="query = item">
              {{ item }}
            </button>
          </div>
        </div>

        <p v-if="errMsg" class="feedback danger">{{ errMsg }}</p>
        <div class="section-heading">
          <span>03</span>
          <div>
            <h2>推荐结论</h2>
            <p>左侧是模型结论，右侧是 Milvus 召回的 Top5 证据片段。</p>
          </div>
        </div>

        <div v-if="!result" class="empty-state">
          <strong>等待一次分析</strong>
          <span>上传研报后输入问题，结果会显示在这里。</span>
        </div>

        <div v-else class="result-layout">
          <article class="analysis-panel">
            <div class="panel-block">
              <span>{{ result.streamStatus || 'Streaming Analysis' }}</span>
              <small>
                intent={{ result.inputIntent || '-' }} / level={{ result.outputLevel || '-' }}
              </small>
              <small v-if="result.degradationReasons?.length">
                reasons={{ result.degradationReasons.join(', ') }}
              </small>
              <div v-if="result.evidenceQuality" class="quality-grid">
                <span :class="{ danger: !result.evidenceQuality.themeCovered }">
                  主题覆盖：{{ result.evidenceQuality.themeCovered ? '通过' : '未覆盖' }}
                </span>
                <span :class="{ danger: !result.evidenceQuality.queryRelevant }">
                  相关性：{{ result.evidenceQuality.queryRelevant ? '通过' : '偏低' }}
                </span>
                <span v-if="result.evidenceQuality.structuredAnchors?.length">
                  锚点：{{ result.evidenceQuality.structuredAnchors.join(' / ') }}
                </span>
              </div>
              <p class="stream-text">{{ result.analysis || '等待模型输出...' }}</p>
            </div>
          </article>

          <div class="evidence-list">
            <article v-for="(item, idx) in result.top5 || []" :key="idx" class="evidence-item">
              <header>
                <strong>#{{ idx + 1 }} {{ item.title || '未命名研报' }}</strong>
                <span>{{ formatScore(item.score) }}</span>
              </header>
              <small>{{ item.source || 'unknown' }}</small>
              <div v-if="hasEvidenceTags(item)" class="tag-group compact-tags">
                <span v-for="tag in evidenceTags(item)" :key="tag">{{ tag }}</span>
              </div>
              <p v-if="item.diagnosticOnly" class="feedback danger">该候选仅用于检索诊断，不作为推荐证据。</p>
              <pre>{{ item.chunkText }}</pre>
            </article>
          </div>
        </div>
        <p v-if="errMsg" class="feedback danger">{{ errMsg }}</p>
      </section>

      <section v-if="activeMainTab === 'observation'" id="observations" class="band result-band">
        <div class="section-heading">
          <span>04</span>
          <div>
            <h2>全部研报</h2>
            <p>查看已入库研报和最新导入状态，可一键跳转到处理链路观测。</p>
          </div>
        </div>
        <div class="observation-layout">
          <div class="question-box">
            <div class="form-grid">
              <label>
                <span>标题关键词</span>
                <input v-model.trim="observationQuery.titleKeyword" placeholder="例如：新能源、AI 算力" />
              </label>
              <label>
                <span>返回数量</span>
                <input v-model.number="observationQuery.limit" type="number" min="1" max="200" />
              </label>
            </div>
            <div class="query-actions">
              <button class="primary-btn" type="button" :disabled="observationLoading" @click="loadObservations">
                {{ observationLoading ? '加载中...' : '刷新研报列表' }}
              </button>
            </div>
            <div class="tab-group">
              <button
                type="button"
                class="tab-btn"
                :class="{ active: observationView === 'table' }"
                @click="observationView = 'table'"
              >
                行列表
              </button>
              <button
                type="button"
                class="tab-btn"
                :class="{ active: observationView === 'card' }"
                @click="observationView = 'card'"
              >
                卡片
              </button>
            </div>
          </div>
          <div class="observation-pane">
            <div v-if="observationView === 'table'" class="observation-table-wrap">
              <table class="observation-table">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>标题</th>
                    <th>状态</th>
                    <th>来源/机构</th>
                    <th>发布日期</th>
                    <th>错误</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in reportObservations" :key="item.reportId">
                    <td>#{{ item.reportId }}</td>
                    <td :title="item.title || '未命名研报'" class="nowrap-ellipsis">{{ item.title || '未命名研报' }}</td>
                    <td>{{ item.ocrStatus || '-' }} / {{ item.chunkStatus || '-' }} / {{ item.vectorStatus || '-' }}</td>
                    <td>{{ item.source || '-' }} / {{ item.institution || '-' }}</td>
                    <td>{{ item.publishDate || '-' }}</td>
                    <td :title="item.lastErrorCode || '-'">{{ item.lastErrorCode || '-' }}</td>
                    <td>
                      <button class="ghost-btn small-btn" type="button" @click="observeReport(item)">观测链路</button>
                      <button class="ghost-btn small-btn" type="button" @click="observeChunks(item)">查询切片</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-else class="evidence-list">
              <article v-for="item in reportObservations" :key="item.reportId" class="evidence-item">
                <header>
                  <strong>#{{ item.reportId }} {{ truncateText(item.title || '未命名研报', 26) }}</strong>
                  <span>{{ item.ocrStatus || '-' }} / {{ item.chunkStatus || '-' }} / {{ item.vectorStatus || '-' }}</span>
                </header>
                <small>{{ item.source || '-' }} / {{ item.institution || '-' }} / {{ item.publishDate || '-' }}</small>
                <pre>error={{ item.lastErrorCode || '-' }}</pre>
                <div class="query-actions">
                  <button class="ghost-btn" type="button" @click="observeReport(item)">观测链路</button>
                  <button class="ghost-btn" type="button" @click="observeChunks(item)">查询切片</button>
                </div>
              </article>
            </div>
            <p v-if="!reportObservations.length && !observationLoading" class="feedback">暂无研报记录</p>
          </div>
        </div>
      </section>

      <section v-if="activeMainTab === 'observation'" ref="timelineSectionRef" class="band result-band">
        <div class="section-heading">
          <span>05</span>
          <div>
            <h2>处理链路</h2>
            <p>按 reportId 或标题关键词查看 OCR / Chunk / Vector 阶段事件。</p>
          </div>
        </div>
        <div class="observation-layout">
          <div class="question-box">
            <div class="form-grid">
              <label>
                <span>reportId</span>
                <input v-model.trim="timelineQuery.reportId" placeholder="例如：123" />
              </label>
              <label>
                <span>标题关键词</span>
                <input v-model.trim="timelineQuery.titleKeyword" placeholder="例如：新能源策略" />
              </label>
            </div>
            <div class="query-actions">
              <button class="primary-btn" type="button" :disabled="timelineLoading" @click="loadTimeline">
                {{ timelineLoading ? '查询中...' : '查询链路' }}
              </button>
            </div>
          </div>
          <div class="evidence-list">
            <article v-for="(item, idx) in stageEvents" :key="idx" class="evidence-item">
              <header>
                <strong>{{ item.stage }} / {{ item.status }}</strong>
                <span>{{ item.durationMs || 0 }}ms</span>
              </header>
              <small>{{ item.reportTitle || '-' }} / {{ item.modelName || '-' }}</small>
              <pre>
traceId={{ item.traceId || '-' }}  reportId={{ item.reportId || '-' }}  attempt={{ item.attempt }}  createdAt={{ formatDateTime(item.createdAt) }}
startedAt={{ formatDateTime(item.startedAt) }}  finishedAt={{ formatDateTime(item.finishedAt) }}
input={{ item.inputSize ?? '-' }}  output={{ item.outputSize ?? '-' }}  durationMs={{ item.durationMs ?? '-' }}
errorCode={{ item.errorCode || '-' }}  error={{ truncateText(item.errorMessageShort, 80) }}
              </pre>
              <div class="query-actions">
                <button class="ghost-btn" type="button" @click="openStageEventDetail(item)">查看全部</button>
              </div>
            </article>
            <p v-if="!timelineLoading && !stageEvents.length" class="feedback">暂无链路事件，请确认该研报已进入异步导入流程。</p>
          </div>
        </div>
        <p v-if="observationMsg" class="feedback">{{ observationMsg }}</p>
      </section>

      <section v-if="activeMainTab === 'observation'" ref="chunkObservationSectionRef" class="band result-band">
        <div class="section-heading">
          <span>06</span>
          <div>
            <h2>切片前后对照</h2>
            <p>展示每次切片前的原文（段落拼接）和切片后的文本，便于快速核查切片质量。</p>
          </div>
        </div>
        <p v-if="chunkObservationMsg" class="feedback">{{ chunkObservationMsg }}</p>
        <div class="evidence-list" v-if="chunkObservationData?.chunkPairs?.length">
          <article v-for="(item, idx) in chunkObservationData.chunkPairs" :key="item.chunkUid || idx" class="evidence-item">
            <header>
              <strong>#{{ idx + 1 }} {{ item.chunkType || '-' }} / chunkIndex={{ item.chunkIndex ?? '-' }}</strong>
              <span>{{ item.tokenCount ?? '-' }} tokens</span>
            </header>
            <small>
              chunkUid={{ item.chunkUid || '-' }} / 段落={{ item.startParagraphId ?? '-' }}-{{ item.endParagraphId ?? '-' }} / 页码={{ item.startPageNumber ?? '-' }}-{{ item.endPageNumber ?? '-' }}
            </small>
            <small>
              sectionPath={{ item.sectionPath || '-' }} / filterReason={{ item.filterReason || '-' }}
            </small>
            <p class="feedback" :class="item.sameContent ? 'success' : 'info'">{{ item.differenceSummary || '-' }}</p>
            <pre>切片前原文：
{{ item.sourceParagraphText || '-' }}</pre>
            <pre>切片后数据：
{{ item.chunkText || '-' }}</pre>
          </article>
        </div>
      </section>
    </section>

    <div v-if="stageEventDetail" class="event-modal-mask" @click.self="stageEventDetail = null">
      <section class="event-modal">
        <header>
          <strong>{{ stageEventDetail.stage }} / {{ stageEventDetail.status }}</strong>
          <button class="ghost-btn" type="button" @click="stageEventDetail = null">关闭</button>
        </header>
        <pre>{{ formatEventDetail(stageEventDetail) }}</pre>
      </section>
    </div>
  </main>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'

const uploadForm = ref({
  title: '',
  source: '',
  institution: '',
  publishDate: '',
  themeTags: '',
  industryTags: '',
  companyTags: '',
  tickerTags: ''
})
const selectedFile = ref(null)
const uploading = ref(false)
const uploadMsg = ref('')
const uploadTone = ref('info')
const latestJobId = ref('')
const stageEvents = ref([])
const timelineQuery = ref({ reportId: '', titleKeyword: '' })
const timelineSectionRef = ref(null)
const chunkObservationSectionRef = ref(null)
const stageEventDetail = ref(null)
const reportObservations = ref([])
const observationLoading = ref(false)
const observationQuery = ref({ titleKeyword: '', limit: 50 })
const observationView = ref('table')
const timelineLoading = ref(false)
const observationMsg = ref('')
const chunkObservationLoading = ref(false)
const chunkObservationMsg = ref('')
const chunkObservationData = ref(null)

const query = ref('')
const loading = ref(false)
const errMsg = ref('')
const result = ref(null)
const backendState = ref('idle')
const streamController = ref(null)
const activeMainTab = ref('upload')
let streamRequestId = 0

const queryTemplates = [
  '哪些研报看好储能板块，核心逻辑和风险是什么？',
  '请比较近期研报中对AI算力产业链的投资观点。',
  '哪些公司盈利预测上调，主要驱动因素是什么？',
  '请找出风险提示中反复出现的宏观或政策风险。'
]

const statusText = computed(() => {
  if (uploading.value) return '入库中'
  if (loading.value) return '分析中'
  if (backendState.value === 'ok') return '已连接'
  if (backendState.value === 'error') return '接口异常'
  return '待操作'
})

const runtimeText = computed(() => {
  if (loading.value && result.value?.streamStatus) return result.value.streamStatus
  if (selectedFile.value) return selectedFile.value.name
  return '前端代理 /api -> 8080'
})

const onFileChange = (event) => {
  selectedFile.value = event.target.files?.[0] || null
  uploadMsg.value = ''
}

const uploadPdf = async () => {
  if (!selectedFile.value) {
    setUploadMessage('请先选择 PDF 文件', 'danger')
    return
  }

  uploading.value = true
  backendState.value = 'idle'
  setUploadMessage('', 'info')

  try {
    const formData = new FormData()
    formData.append('file', selectedFile.value)
    appendIfPresent(formData, 'title', uploadForm.value.title)
    appendIfPresent(formData, 'source', uploadForm.value.source)
    appendIfPresent(formData, 'institution', uploadForm.value.institution)
    appendIfPresent(formData, 'publishDate', uploadForm.value.publishDate)
    appendIfPresent(formData, 'themeTags', uploadForm.value.themeTags)
    appendIfPresent(formData, 'industryTags', uploadForm.value.industryTags)
    appendIfPresent(formData, 'companyTags', uploadForm.value.companyTags)
    appendIfPresent(formData, 'tickerTags', uploadForm.value.tickerTags)

    const data = await requestJson('/api/reports/upload', { method: 'POST', body: formData })
    backendState.value = 'ok'
    latestJobId.value = data.jobId || ''
    setUploadMessage('任务已提交', 'success')
  } catch (error) {
    backendState.value = 'error'
    setUploadMessage(`入库失败：${error.message}`, 'danger')
  } finally {
    uploading.value = false
  }
}

const checkLatestJob = async () => {
  if (!latestJobId.value) return
  try {
    const data = await requestJson(`/api/reports/ingest-jobs/${latestJobId.value}`)
    setUploadMessage(
      `任务状态：OCR=${data.ocrStatus} / CHUNK=${data.chunkStatus} / VECTOR=${data.vectorStatus}` +
        (data.reportId ? `，reportId=${data.reportId}` : ''),
      'info'
    )
  } catch (error) {
    setUploadMessage(`任务查询失败：${error.message}`, 'danger')
  }
}

const loadTimeline = async () => {
  timelineLoading.value = true
  observationMsg.value = ''
  try {
    const reportId = timelineQuery.value.reportId
    const titleKeyword = timelineQuery.value.titleKeyword
    const queryString = reportId
      ? `reportId=${encodeURIComponent(reportId)}`
      : `titleKeyword=${encodeURIComponent(titleKeyword)}&limit=50`
    stageEvents.value = await requestJson(`/api/reports/ingest-stage-events?${queryString}`)
    observationMsg.value = stageEvents.value.length
      ? `已加载 ${stageEvents.value.length} 条链路事件`
      : '未查询到链路事件'
  } catch (error) {
    errMsg.value = `链路查询失败：${toUiErrorMessage(error, '后端服务可能未启动，请先启动 8080 后端')}`
  } finally {
    timelineLoading.value = false
  }
}

const loadObservations = async () => {
  observationLoading.value = true
  try {
    const query = new URLSearchParams()
    query.set('titleKeyword', observationQuery.value.titleKeyword || '')
    query.set('limit', String(normalizeLimit(observationQuery.value.limit)))
    reportObservations.value = await requestJson(`/api/reports/observations?${query.toString()}`)
  } catch (error) {
    errMsg.value = `研报列表加载失败：${toUiErrorMessage(error, '后端服务可能未启动，请先启动 8080 后端')}`
  } finally {
    observationLoading.value = false
  }
}

const observeReport = async (item) => {
  timelineQuery.value.reportId = String(item.reportId || '')
  timelineQuery.value.titleKeyword = ''
  observationMsg.value = `已选择 reportId=${timelineQuery.value.reportId}，正在查询链路...`
  await loadTimeline()
  await nextTick()
  timelineSectionRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const observeChunks = async (item) => {
  if (!item?.reportId) {
    chunkObservationMsg.value = '缺少 reportId，无法查询切片'
    return
  }
  chunkObservationLoading.value = true
  chunkObservationData.value = null
  chunkObservationMsg.value = `已选择 reportId=${item.reportId}，正在查询切片前后数据...`
  try {
    const data = await requestJson(`/api/reports/${encodeURIComponent(item.reportId)}/chunk-observation`)
    chunkObservationData.value = data
    chunkObservationMsg.value = data?.chunkPairs?.length
      ? `已加载 ${data.chunkPairs.length} 条切片对照数据`
      : '暂无切片数据'
    await nextTick()
    chunkObservationSectionRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch (error) {
    chunkObservationMsg.value = `切片查询失败：${toUiErrorMessage(error, '后端服务可能未启动，请先启动 8080 后端')}`
  } finally {
    chunkObservationLoading.value = false
  }
}

const openStageEventDetail = (item) => {
  stageEventDetail.value = item
}

const runRecommend = async () => {
  const currentQuery = query.value.trim()
  if (!currentQuery) {
    errMsg.value = '请输入投研问题'
    return
  }

  abortRecommendStream()
  const requestId = ++streamRequestId
  const controller = new AbortController()
  streamController.value = controller
  loading.value = true
  backendState.value = 'idle'
  errMsg.value = ''
  result.value = {
    query: currentQuery,
    top5: [],
    analysis: '',
    streamStatus: '准备分析',
    inputIntent: '',
    outputLevel: '',
    degradationReasons: [],
    evidenceQuality: null
  }

  try {
    await requestRecommendStream(currentQuery, controller.signal, requestId)
    if (requestId === streamRequestId) {
      backendState.value = 'ok'
    }
  } catch (error) {
    if (error.name !== 'AbortError' && requestId === streamRequestId) {
      backendState.value = 'error'
      errMsg.value = `分析失败：${error.message}`
    }
  } finally {
    if (requestId === streamRequestId) {
      loading.value = false
      streamController.value = null
    }
  }
}

const clearResult = () => {
  abortRecommendStream()
  query.value = ''
  errMsg.value = ''
  result.value = null
  loading.value = false
}

const abortRecommendStream = () => {
  streamRequestId += 1
  if (streamController.value) {
    streamController.value.abort()
    streamController.value = null
  }
}

const requestRecommendStream = async (queryText, signal, requestId) => {
  const response = await fetch('/api/reports/recommend/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: queryText }),
    signal
  })
  if (!response.ok) {
    const contentType = response.headers.get('content-type') || ''
    const data = contentType.includes('application/json') ? await response.json() : { message: await response.text() }
    throw new Error(data.message || `HTTP ${response.status}`)
  }
  if (!response.body) {
    throw new Error('浏览器不支持流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let done = false
  while (!done) {
    const readResult = await reader.read()
    done = readResult.done
    buffer += decoder.decode(readResult.value || new Uint8Array(), { stream: !done })
    buffer = consumeSseBuffer(buffer, requestId)
  }
  if (buffer.trim()) {
    consumeSseFrame(buffer, requestId)
  }
}

const consumeSseBuffer = (buffer, requestId) => {
  let normalized = buffer.replace(/\r\n/g, '\n')
  let separator = normalized.indexOf('\n\n')
  while (separator >= 0) {
    const frame = normalized.slice(0, separator)
    consumeSseFrame(frame, requestId)
    normalized = normalized.slice(separator + 2)
    separator = normalized.indexOf('\n\n')
  }
  return normalized
}

const consumeSseFrame = (frame, requestId) => {
  if (requestId !== streamRequestId) return
  const lines = frame.split('\n')
  let eventName = 'message'
  const dataLines = []
  for (const line of lines) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  if (!dataLines.length) return
  const data = JSON.parse(dataLines.join('\n'))
  applyStreamEvent(eventName, data, requestId)
}

const applyStreamEvent = (eventName, data, requestId) => {
  if (requestId !== streamRequestId || !result.value) return
  applyGuardrailMetadata(data)
  if (eventName === 'status') {
    result.value.streamStatus = data.message || data.stage || '分析中'
    return
  }
  if (eventName === 'evidence') {
    result.value.top5 = data.top5 || []
    result.value.streamStatus = data.message || '检索完成'
    return
  }
  if (eventName === 'delta') {
    result.value.analysis += data.text || ''
    result.value.streamStatus = '正在生成分析'
    return
  }
  if (eventName === 'error') {
    backendState.value = 'error'
    errMsg.value = data.message || '分析失败'
    result.value.streamStatus = '分析失败'
    loading.value = false
    return
  }
  if (eventName === 'done') {
    result.value.streamStatus = '分析完成'
    loading.value = false
  }
}

const applyGuardrailMetadata = (data) => {
  if (!data || !result.value) return
  if (data.inputIntent) result.value.inputIntent = data.inputIntent
  if (data.outputLevel) result.value.outputLevel = data.outputLevel
  if (Array.isArray(data.degradationReasons)) result.value.degradationReasons = data.degradationReasons
  if (data.evidenceQuality) result.value.evidenceQuality = data.evidenceQuality
}

const hasEvidenceTags = (item) => evidenceTags(item).length > 0

const evidenceTags = (item) => [
  ...(item.themeCodes || []).map((value) => `主题:${value}`),
  ...(item.industryCodes || []).map((value) => `行业:${value}`),
  ...(item.companyNames || []).map((value) => `公司:${value}`),
  ...(item.tickers || []).map((value) => `代码:${value}`)
]

const requestJson = async (url, options) => {
  const response = await fetch(url, options)
  const contentType = response.headers.get('content-type') || ''
  const data = contentType.includes('application/json') ? await response.json() : { message: await response.text() }
  if (!response.ok) {
    throw new Error(data.message || `HTTP ${response.status}`)
  }
  return data
}

const appendIfPresent = (formData, key, value) => {
  if (value) formData.append(key, value)
}

const setUploadMessage = (message, tone) => {
  uploadMsg.value = message
  uploadTone.value = tone
}

const formatBytes = (bytes) => {
  if (!bytes) return '0 B'
  const mb = bytes / 1024 / 1024
  return `${mb.toFixed(mb >= 10 ? 0 : 1)} MB`
}

const formatScore = (score) => {
  if (score === null || score === undefined || Number.isNaN(Number(score))) return 'score -'
  return `score ${Number(score).toFixed(4)}`
}

const normalizeLimit = (value) => {
  const parsed = Number(value)
  if (Number.isNaN(parsed)) return 50
  return Math.max(1, Math.min(200, Math.floor(parsed)))
}

onMounted(() => {
  loadObservations()
})

const truncateText = (value, maxLength) => {
  if (!value) return '-'
  return value.length > maxLength ? `${value.slice(0, maxLength)}...` : value
}

const formatDateTime = (value) => {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

const formatEventDetail = (item) => {
  return [
    `traceId: ${item.traceId || '-'}`,
    `reportId: ${item.reportId || '-'}`,
    `reportTitle: ${item.reportTitle || '-'}`,
    `stage: ${item.stage || '-'}`,
    `status: ${item.status || '-'}`,
    `attempt: ${item.attempt ?? '-'}`,
    `modelName: ${item.modelName || '-'}`,
    `inputSize: ${item.inputSize ?? '-'}`,
    `outputSize: ${item.outputSize ?? '-'}`,
    `durationMs: ${item.durationMs ?? '-'}`,
    `startedAt: ${formatDateTime(item.startedAt)}`,
    `finishedAt: ${formatDateTime(item.finishedAt)}`,
    `createdAt: ${formatDateTime(item.createdAt)}`,
    `errorCode: ${item.errorCode || '-'}`,
    `errorMessage: ${item.errorMessageShort || '-'}`].join('\n')
}

const toUiErrorMessage = (error, fallbackMessage) => {
  const message = error?.message || ''
  if (!message) return fallbackMessage
  if (message.includes('Failed to fetch') || message.includes('NetworkError')) return fallbackMessage
  return message
}
</script>
