<template>
  <div class="container">
    <h1>研报推荐系统</h1>

    <section class="card">
      <h2>1. 上传研报 PDF</h2>
      <div class="grid">
        <input v-model="uploadForm.title" placeholder="标题（可选）" />
        <input v-model="uploadForm.source" placeholder="来源（可选）" />
        <input v-model="uploadForm.institution" placeholder="机构（可选）" />
        <input v-model="uploadForm.publishDate" type="date" />
        <input type="file" accept="application/pdf" @change="onFileChange" />
        <button :disabled="uploading" @click="uploadPdf">{{ uploading ? '上传中...' : '上传并入库' }}</button>
      </div>
      <p class="meta">{{ uploadMsg }}</p>
    </section>

    <section class="card">
      <h2>2. 检索 + 推荐</h2>
      <textarea v-model="query" rows="4" placeholder="请输入你的投研问题"></textarea>
      <div style="margin-top: 10px;">
        <button :disabled="loading" @click="runRecommend">{{ loading ? '分析中...' : '开始推荐' }}</button>
      </div>
      <p class="meta">{{ errMsg }}</p>
    </section>

    <section v-if="result" class="card">
      <h2>Top5 相似片段</h2>
      <div class="grid">
        <article class="top-item" v-for="(item, idx) in result.top5" :key="idx">
          <h3>#{{ idx + 1 }} {{ item.title }}</h3>
          <p class="meta">score: {{ item.score ?? '-' }} | source: {{ item.source }}</p>
          <pre>{{ item.chunkText }}</pre>
        </article>
      </div>
    </section>

    <section v-if="result" class="card">
      <h2>推荐分析</h2>
      <p><strong>analysis:</strong> {{ result.analysis }}</p>
      <p><strong>recommendation:</strong> {{ result.recommendation }}</p>
      <p><strong>risks:</strong> {{ (result.risks || []).join('；') }}</p>
      <p><strong>citations:</strong> {{ (result.citations || []).join('，') }}</p>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const uploadForm = ref({ title: '', source: '', institution: '', publishDate: '' })
const selectedFile = ref(null)
const uploading = ref(false)
const uploadMsg = ref('')

const query = ref('')
const loading = ref(false)
const errMsg = ref('')
const result = ref(null)

const onFileChange = (e) => {
  selectedFile.value = e.target.files?.[0] || null
}

const uploadPdf = async () => {
  if (!selectedFile.value) {
    uploadMsg.value = '请先选择 PDF 文件'
    return
  }

  uploading.value = true
  uploadMsg.value = ''
  try {
    const formData = new FormData()
    formData.append('file', selectedFile.value)
    if (uploadForm.value.title) formData.append('title', uploadForm.value.title)
    if (uploadForm.value.source) formData.append('source', uploadForm.value.source)
    if (uploadForm.value.institution) formData.append('institution', uploadForm.value.institution)
    if (uploadForm.value.publishDate) formData.append('publishDate', uploadForm.value.publishDate)

    const resp = await fetch('/api/reports/upload', { method: 'POST', body: formData })
    const data = await resp.json()
    if (!resp.ok) throw new Error(data.message || '上传失败')
    uploadMsg.value = `成功：reportId=${data.reportId}, chunkCount=${data.chunkCount}`
  } catch (e) {
    uploadMsg.value = `失败：${e.message}`
  } finally {
    uploading.value = false
  }
}

const runRecommend = async () => {
  if (!query.value.trim()) {
    errMsg.value = '请输入问题'
    return
  }

  loading.value = true
  errMsg.value = ''
  result.value = null

  try {
    const resp = await fetch('/api/reports/recommend', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: query.value })
    })
    const data = await resp.json()
    if (!resp.ok) throw new Error(data.message || '推荐失败')
    result.value = data
  } catch (e) {
    errMsg.value = e.message
  } finally {
    loading.value = false
  }
}
</script>
