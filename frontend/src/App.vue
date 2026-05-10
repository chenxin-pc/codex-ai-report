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
        <a href="#upload" class="nav-item active">入库</a>
        <a href="#query" class="nav-item">检索</a>
        <a href="#result" class="nav-item">结论</a>
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

      <section id="upload" class="band upload-band">
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
            <button class="primary-btn" :disabled="uploading" type="submit">
              {{ uploading ? '正在入库...' : '上传并入库' }}
            </button>
          </form>
        </div>

        <p v-if="uploadMsg" class="feedback" :class="uploadTone">{{ uploadMsg }}</p>
      </section>

      <section id="query" class="band query-band">
        <div class="section-heading">
          <span>02</span>
          <div>
            <h2>投研问题</h2>
            <p>问题会先走 Milvus ANN 召回，再由 Qwen 基于证据输出结构化推荐。</p>
          </div>
        </div>

        <div class="query-layout">
          <div class="question-box">
            <textarea v-model.trim="query" rows="6" placeholder="输入你想分析的问题，例如：哪些研报看好储能板块，核心逻辑和风险是什么？"></textarea>
            <div class="query-actions">
              <button class="primary-btn" :disabled="loading" @click="runRecommend">
                {{ loading ? '分析中...' : '开始分析' }}
              </button>
              <button class="ghost-btn" :disabled="loading" @click="clearResult">清空</button>
            </div>
          </div>

          <div class="template-list">
            <button v-for="item in queryTemplates" :key="item" type="button" @click="query = item">
              {{ item }}
            </button>
          </div>
        </div>

        <p v-if="errMsg" class="feedback danger">{{ errMsg }}</p>
      </section>

      <section id="result" class="band result-band">
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
              <span>Analysis</span>
              <p>{{ result.analysis || '暂无分析' }}</p>
            </div>
            <div class="panel-block">
              <span>Recommendation</span>
              <p>{{ result.recommendation || '暂无推荐' }}</p>
            </div>
            <div class="tag-group">
              <span v-for="risk in result.risks || []" :key="risk">{{ risk }}</span>
            </div>
            <div class="citation-line">
              {{ citationText }}
            </div>
          </article>

          <div class="evidence-list">
            <article v-for="(item, idx) in result.top5 || []" :key="idx" class="evidence-item">
              <header>
                <strong>#{{ idx + 1 }} {{ item.title || '未命名研报' }}</strong>
                <span>{{ formatScore(item.score) }}</span>
              </header>
              <small>{{ item.source || 'unknown' }}</small>
              <pre>{{ item.chunkText }}</pre>
            </article>
          </div>
        </div>
      </section>
    </section>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'

const uploadForm = ref({ title: '', source: '', institution: '', publishDate: '' })
const selectedFile = ref(null)
const uploading = ref(false)
const uploadMsg = ref('')
const uploadTone = ref('info')

const query = ref('')
const loading = ref(false)
const errMsg = ref('')
const result = ref(null)
const backendState = ref('idle')

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
  if (selectedFile.value) return selectedFile.value.name
  return '前端代理 /api -> 8080'
})

const citationText = computed(() => {
  const citations = result.value?.citations || []
  return citations.length ? `引用：${citations.join('，')}` : '暂无引用'
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

    const data = await requestJson('/api/reports/upload', { method: 'POST', body: formData })
    backendState.value = 'ok'
    setUploadMessage(`入库成功：reportId=${data.reportId}，chunkCount=${data.chunkCount}`, 'success')
  } catch (error) {
    backendState.value = 'error'
    setUploadMessage(`入库失败：${error.message}`, 'danger')
  } finally {
    uploading.value = false
  }
}

const runRecommend = async () => {
  if (!query.value.trim()) {
    errMsg.value = '请输入投研问题'
    return
  }

  loading.value = true
  backendState.value = 'idle'
  errMsg.value = ''
  result.value = null

  try {
    result.value = await requestJson('/api/reports/recommend', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: query.value })
    })
    backendState.value = 'ok'
  } catch (error) {
    backendState.value = 'error'
    errMsg.value = `分析失败：${error.message}`
  } finally {
    loading.value = false
  }
}

const clearResult = () => {
  query.value = ''
  errMsg.value = ''
  result.value = null
}

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
</script>
