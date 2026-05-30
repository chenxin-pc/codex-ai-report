<template>
  <main class="app-shell">
    <aside class="sidebar" aria-label="工作区导航">
      <div class="brand">
        <span class="brand-mark">CR</span>
        <div>
          <strong>Codex Research</strong>
          <small>投研分析台</small>
        </div>
      </div>

      <nav class="nav-list">
        <button class="nav-item" :class="{ active: activeMainTab === 'upload' }" type="button" @click="activeMainTab = 'upload'">
          研报入库
        </button>
        <button class="nav-item" :class="{ active: activeMainTab === 'query' }" type="button" @click="activeMainTab = 'query'">
          投研分析
        </button>
        <button class="nav-item" :class="{ active: activeMainTab === 'observation' }" type="button" @click="activeMainTab = 'observation'">
          研报库
        </button>
      </nav>

      <div class="runtime-panel">
        <span>运行状态</span>
        <strong>{{ runtimeText }}</strong>
        <small>{{ runtimeHint }}</small>
      </div>
    </aside>

    <section class="workspace">
      <header class="workspace-header">
        <div>
          <p class="eyebrow">Research Workspace</p>
          <h1>投研研报分析台</h1>
        </div>
        <div class="status-pill" :class="backendState">
          <span class="dot"></span>
          {{ statusText }}
        </div>
      </header>

      <section v-if="activeMainTab === 'upload'" class="band">
        <div class="section-heading">
          <span>01</span>
          <div>
            <h2>研报入库</h2>
            <p>上传 PDF，补充基础信息与投研标签，提交后可查看任务状态。</p>
          </div>
        </div>

        <div class="upload-layout">
          <article class="question-box">
            <h3>文件</h3>
            <label class="file-drop" :class="{ selected: selectedFile }">
              <input type="file" accept="application/pdf" @change="onFileChange" />
              <span class="file-icon">PDF</span>
              <strong>{{ selectedFile ? selectedFile.name : '选择研报 PDF' }}</strong>
              <small>{{ selectedFile ? formatBytes(selectedFile.size) : '支持券商研报、行业报告、公司深度 PDF' }}</small>
            </label>
          </article>

          <form class="upload-form-stack" @submit.prevent="uploadPdf">
            <article class="question-box">
              <h3>基础信息</h3>
              <div class="form-grid">
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
              </div>
            </article>

            <article class="question-box">
              <h3>投研标签</h3>
              <div class="form-grid">
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
              </div>
              <div class="query-actions">
                <button class="primary-btn" :disabled="uploading" type="submit">
                  {{ uploading ? '正在入库...' : '上传并入库' }}
                </button>
              </div>
            </article>

            <article class="question-box">
              <h3>入库任务状态</h3>
              <div class="status-badge-row">
                <span class="state-badge">{{ formatStageBadge('OCR', ingestJobStatus?.ocrStatus) }}</span>
                <span class="state-badge">{{ formatStageBadge('切片', ingestJobStatus?.chunkStatus) }}</span>
                <span class="state-badge">{{ formatStageBadge('向量', ingestJobStatus?.vectorStatus) }}</span>
              </div>
              <small class="panel-note">jobId：{{ latestJobId || '-' }}{{ ingestJobStatus?.reportId ? ` / reportId：${ingestJobStatus.reportId}` : '' }}</small>
              <div class="query-actions">
                <button class="ghost-btn" type="button" :disabled="!latestJobId" @click="checkLatestJob">查询最新任务状态</button>
              </div>
            </article>
          </form>
        </div>

        <p v-if="uploadMsg" class="feedback" :class="uploadTone">{{ uploadMsg }}</p>
      </section>

      <section v-if="activeMainTab === 'query'" class="band">
        <div class="section-heading">
          <span>02</span>
          <div>
            <h2>投研分析</h2>
            <p>输入问题后，系统将召回相关证据并生成分析结论。</p>
          </div>
        </div>

        <div class="observation-layout">
          <div class="question-box">
            <textarea v-model.trim="query" rows="6" placeholder="输入你想分析的问题，例如：哪些研报看好储能板块，核心逻辑和风险是什么？"></textarea>
            <div class="query-actions">
              <button class="primary-btn" :disabled="loading" @click="runRecommend">{{ loading ? '分析中...' : '开始分析' }}</button>
              <button class="ghost-btn" @click="clearResult">清空</button>
            </div>
          </div>

          <div class="template-list grouped">
            <article v-for="group in queryTemplateGroups" :key="group.title" class="template-group">
              <h3>{{ group.title }}</h3>
              <button v-for="item in group.items" :key="item" type="button" @click="query = item">{{ item }}</button>
            </article>
          </div>
        </div>

        <p v-if="errMsg" class="feedback danger">{{ errMsg }}</p>

        <div class="section-heading">
          <span>03</span>
          <div>
            <h2>分析结果</h2>
            <p>结论、证据质量和证据片段分层展示，方便快速扫描与复核。</p>
          </div>
        </div>

        <div v-if="!result" class="empty-state">
          <strong>等待一次分析</strong>
          <span>上传研报后输入问题，结果会显示在这里。</span>
        </div>

        <div v-else class="result-layout">
          <article class="analysis-panel">
            <div class="panel-block">
              <span>分析状态：{{ analysisStatusText }}</span>
              <small>{{ analysisMetaText }}</small>
              <small v-if="analysisWarningText">{{ analysisWarningText }}</small>
              <div class="query-actions compact">
                <button class="ghost-btn small-btn" type="button" @click="copyAnalysis">复制结论</button>
              </div>
            </div>

            <div class="panel-block">
              <h3>分析结论</h3>
              <p class="stream-text">{{ result.analysis || '等待模型输出...' }}</p>
            </div>

            <div class="panel-block">
              <h3>证据质量</h3>
              <div class="quality-grid">
                <span>{{ qualityText.intent }}</span>
                <span>{{ qualityText.level }}</span>
                <span :class="{ danger: qualityText.themeDanger }">{{ qualityText.theme }}</span>
                <span :class="{ danger: qualityText.relevanceDanger }">{{ qualityText.relevance }}</span>
                <span v-if="qualityText.anchors">{{ qualityText.anchors }}</span>
                <span v-if="qualityText.warning" class="danger">{{ qualityText.warning }}</span>
              </div>
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
              <pre :class="{ collapsed: !expandedEvidence[idx] }">{{ item.chunkText || '-' }}</pre>
              <div class="query-actions compact">
                <button class="ghost-btn small-btn" type="button" @click="toggleEvidence(idx)">
                  {{ expandedEvidence[idx] ? '收起证据' : '展开证据' }}
                </button>
              </div>
            </article>
          </div>
        </div>
      </section>

      <section v-if="activeMainTab === 'observation'" class="band">
        <div class="section-heading">
          <span>04</span>
          <div>
            <h2>研报库</h2>
            <p>默认查看研报列表，按需展开处理链路和切片对照。</p>
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
              <button class="tab-btn" :class="{ active: observationView === 'table' }" type="button" @click="observationView = 'table'">行列表</button>
              <button class="tab-btn" :class="{ active: observationView === 'card' }" type="button" @click="observationView = 'card'">卡片</button>
            </div>
          </div>

          <div class="observation-pane">
            <div v-if="observationView === 'table'" class="observation-table-wrap">
              <table class="observation-table">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>标题</th>
                    <th>阶段状态</th>
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
                    <td>
                      <div class="status-badge-row">
                        <span class="state-badge">{{ formatStageBadge('OCR', item.ocrStatus) }}</span>
                        <span class="state-badge">{{ formatStageBadge('切片', item.chunkStatus) }}</span>
                        <span class="state-badge">{{ formatStageBadge('向量', item.vectorStatus) }}</span>
                      </div>
                    </td>
                    <td>{{ item.source || '-' }} / {{ item.institution || '-' }}</td>
                    <td>{{ item.publishDate || '-' }}</td>
                    <td :title="item.lastErrorCode || '-'">{{ item.lastErrorCode || '-' }}</td>
                    <td>
                      <button class="ghost-btn small-btn" type="button" @click="selectReport(item)">查看摘要</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-else class="evidence-list">
              <article v-for="item in reportObservations" :key="item.reportId" class="evidence-item">
                <header>
                  <strong>#{{ item.reportId }} {{ truncateText(item.title || '未命名研报', 26) }}</strong>
                </header>
                <small>{{ item.source || '-' }} / {{ item.institution || '-' }} / {{ item.publishDate || '-' }}</small>
                <div class="status-badge-row">
                  <span class="state-badge">{{ formatStageBadge('OCR', item.ocrStatus) }}</span>
                  <span class="state-badge">{{ formatStageBadge('切片', item.chunkStatus) }}</span>
                  <span class="state-badge">{{ formatStageBadge('向量', item.vectorStatus) }}</span>
                </div>
                <p class="panel-note">错误：{{ item.lastErrorCode || '-' }}</p>
                <div class="query-actions compact">
                  <button class="ghost-btn small-btn" type="button" @click="selectReport(item)">查看摘要</button>
                </div>
              </article>
            </div>

            <p v-if="!reportObservations.length && !observationLoading" class="feedback">暂无研报记录</p>
          </div>
        </div>

        <article v-if="selectedReportSummary" class="question-box report-summary-card">
          <h3>选中研报摘要</h3>
          <strong>#{{ selectedReportSummary.reportId }} {{ selectedReportSummary.title || '未命名研报' }}</strong>
          <small>{{ selectedReportSummary.source || '-' }} / {{ selectedReportSummary.institution || '-' }} / {{ selectedReportSummary.publishDate || '-' }}</small>
          <p class="panel-note">错误：{{ selectedReportSummary.lastErrorCode || '-' }}</p>
          <div class="status-badge-row">
            <span class="state-badge">{{ formatStageBadge('OCR', selectedReportSummary.ocrStatus) }}</span>
            <span class="state-badge">{{ formatStageBadge('切片', selectedReportSummary.chunkStatus) }}</span>
            <span class="state-badge">{{ formatStageBadge('向量', selectedReportSummary.vectorStatus) }}</span>
          </div>
          <div class="query-actions">
            <button class="ghost-btn" type="button" @click="observeReport(selectedReportSummary)">查看处理链路</button>
            <button class="ghost-btn" type="button" @click="observeChunks(selectedReportSummary)">查看切片对照</button>
          </div>
        </article>
      </section>

      <section v-if="activeMainTab === 'observation' && showTimelinePanel" ref="timelineSectionRef" class="band">
        <div class="section-heading">
          <span>05</span>
          <div>
            <h2>处理链路</h2>
            <p>优先展示导入链路解释卡，保留原始事件明细。</p>
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
              <button class="primary-btn" type="button" :disabled="timelineLoading" @click="loadTimeline">{{ timelineLoading ? '查询中...' : '查询链路' }}</button>
            </div>
          </div>

          <div class="chain-pane">
            <div v-if="chainObservation" class="chain-detail">
              <section class="report-summary">
                <div>
                  <strong>#{{ chainObservation.report?.reportId }} {{ chainObservation.report?.title || '未命名研报' }}</strong>
                  <small>{{ chainObservation.report?.source || '-' }} / {{ chainObservation.report?.institution || '-' }} / {{ chainObservation.report?.publishDate || '-' }}</small>
                </div>
                <div class="summary-status">
                  <span>{{ chainObservation.overallStatus || '-' }}</span>
                  <small>当前关注：{{ chainObservation.currentStage || '-' }}</small>
                  <small v-if="chainObservation.lastErrorCode">错误：{{ chainObservation.lastErrorCode }}</small>
                </div>
              </section>

              <div class="stage-nav">
                <button
                  v-for="stage in chainObservation.stages || []"
                  :key="stage.stage"
                  type="button"
                  class="stage-nav-item"
                  :class="{ active: selectedIngestStage === stage.stage, danger: isStageFailed(stage) }"
                  @click="selectIngestStage(stage.stage)"
                >
                  <strong>{{ stage.name }}</strong>
                  <span>{{ stage.status || '-' }}</span>
                </button>
              </div>

              <article v-if="selectedIngestStageData" class="stage-card">
                <header>
                  <div>
                    <strong>{{ selectedIngestStageData.name }}</strong>
                    <small>{{ selectedIngestStageData.status || '-' }} / {{ selectedIngestStageData.modelName || 'no model' }} / {{ selectedIngestStageData.durationMs ?? '-' }}ms</small>
                  </div>
                  <span v-if="selectedIngestStageData.errorCode" class="danger-text">{{ selectedIngestStageData.errorCode }}</span>
                </header>

                <div class="metric-grid">
                  <div v-for="metric in selectedIngestStageData.summary?.metrics || []" :key="metric.label" :class="['metric-item', metric.tone]">
                    <span>{{ metric.label }}</span>
                    <strong>{{ metric.value }}</strong>
                  </div>
                </div>

                <div class="explain-grid">
                  <section>
                    <h3>做了什么</h3>
                    <p>{{ selectedIngestStageData.explanation?.operationSummary || '-' }}</p>
                  </section>
                  <section>
                    <h3>执行前</h3>
                    <ul>
                      <li v-for="item in selectedIngestStageData.explanation?.beforeState || []" :key="item">{{ item }}</li>
                    </ul>
                  </section>
                  <section>
                    <h3>执行后</h3>
                    <ul>
                      <li v-for="item in selectedIngestStageData.explanation?.afterState || []" :key="item">{{ item }}</li>
                    </ul>
                  </section>
                  <section>
                    <h3>影响点</h3>
                    <p>{{ selectedIngestStageData.explanation?.impact || '-' }}</p>
                  </section>
                </div>

                <p v-if="selectedIngestStageData.explanation?.failureExplanation" class="feedback danger">
                  {{ selectedIngestStageData.explanation.failureExplanation }}
                </p>

                <div v-if="selectedIngestStage === 'OCR'" class="detail-block">
                  <details>
                    <summary>页级 OCR 预览（{{ selectedIngestStageData.details?.ocrPages?.length || 0 }}）</summary>
                    <article v-for="page in selectedIngestStageData.details?.ocrPages || []" :key="page.pageNumber" class="mini-row">
                      <strong>Page {{ page.pageNumber }}</strong>
                      <pre>raw={{ page.rawPreview || '-' }}
cleaned={{ page.cleanedPreview || '-' }}
diagnostics={{ page.diagnosticsPreview || '-' }}</pre>
                    </article>
                  </details>
                  <details>
                    <summary>段落 atom 预览（{{ selectedIngestStageData.details?.paragraphAtoms?.length || 0 }}）</summary>
                    <article v-for="atom in selectedIngestStageData.details?.paragraphAtoms || []" :key="atom.paragraphId" class="mini-row">
                      <strong>#{{ atom.paragraphId }} / page={{ atom.pageNumber }} / {{ atom.tokenCount ?? '-' }} tokens</strong>
                      <small>{{ atom.sectionPath || '-' }}</small>
                      <pre>{{ atom.textPreview || '-' }}</pre>
                    </article>
                  </details>
                </div>

                <div v-if="selectedIngestStage === 'CHUNK'" class="detail-block">
                  <div class="tab-group">
                    <button class="tab-btn" :class="{ active: chunkTreeFilter === 'all' }" type="button" @click="chunkTreeFilter = 'all'">全部 CHILD</button>
                    <button class="tab-btn" :class="{ active: chunkTreeFilter === 'pendingVector' }" type="button" @click="chunkTreeFilter = 'pendingVector'">未入库 CHILD</button>
                    <button class="tab-btn" :class="{ active: chunkTreeFilter === 'filtered' }" type="button" @click="chunkTreeFilter = 'filtered'">过滤项</button>
                  </div>
                  <div v-if="chunkTreeFilter !== 'filtered'" class="chunk-tree">
                    <article v-for="parent in visibleParentChunks" :key="parent.chunkUid || parent.sectionPath" class="parent-node">
                      <header>
                        <strong>{{ parent.sectionPath || 'PARENT' }}</strong>
                        <span>{{ parent.childCount }} children / {{ parent.vectorStoredCount }} vectorized</span>
                      </header>
                      <small>parent={{ parent.chunkUid || '-' }} / page={{ parent.pageRange || '-' }} / paragraph={{ parent.paragraphRange || '-' }} / {{ parent.tokenCount ?? '-' }} tokens</small>
                      <pre>{{ parent.textPreview || '-' }}</pre>
                      <div class="child-list">
                        <article v-for="child in visibleChildren(parent)" :key="child.chunkUid" class="child-node">
                          <strong>{{ child.chunkUid || '-' }}</strong>
                          <span :class="{ danger: !child.vectorStored }">{{ child.vectorStored ? '已入库' : '未入库' }}</span>
                          <small>parent={{ child.parentChunkUid || '-' }} / page={{ child.pageRange || '-' }} / paragraph={{ child.paragraphRange || '-' }} / {{ child.tokenCount ?? '-' }} tokens</small>
                          <pre>{{ child.textPreview || '-' }}</pre>
                        </article>
                      </div>
                    </article>
                  </div>
                  <div v-else class="evidence-list">
                    <article v-for="item in selectedIngestStageData.details?.filteredDiagnostics || []" :key="`${item.parentIndex}-${item.chunkIndexInParent}-${item.filterReason}`" class="evidence-item">
                      <header>
                        <strong>{{ item.chunkType || '-' }} / kept={{ item.kept }}</strong>
                        <span>{{ item.filterReason || 'no reason' }}</span>
                      </header>
                      <small>page={{ item.pageRange || '-' }} / paragraph={{ item.paragraphRange || '-' }} / persisted={{ item.persisted }}</small>
                      <pre>{{ item.textPreview || '-' }}
diagnostics={{ item.diagnosticsPreview || '-' }}</pre>
                    </article>
                  </div>
                </div>

                <div v-if="selectedIngestStage === 'VECTOR'" class="detail-block">
                  <div class="query-actions">
                    <button class="ghost-btn" type="button" @click="jumpToChunkCandidates('all')">查看候选 CHILD</button>
                    <button class="ghost-btn" type="button" @click="jumpToChunkCandidates('pendingVector')">查看未入库 CHILD</button>
                  </div>
                  <div class="evidence-list">
                    <article v-for="item in selectedIngestStageData.details?.vectorCandidates || []" :key="item.chunkUid" class="evidence-item">
                      <header>
                        <strong>{{ item.chunkUid }}</strong>
                        <span :class="{ danger: !item.vectorStored }">{{ item.status }}</span>
                      </header>
                      <small>parent={{ item.parentChunkUid || '-' }} / {{ item.parentSectionPath || item.sectionPath || '-' }} / page={{ item.pageRange || '-' }} / {{ item.tokenCount ?? '-' }} tokens</small>
                    </article>
                  </div>
                </div>
              </article>

              <details class="raw-events" v-if="stageEvents.length">
                <summary>原始阶段事件（{{ stageEvents.length }}）</summary>
                <article v-for="(item, idx) in stageEvents" :key="idx" class="evidence-item mini-row">
                  <header>
                    <strong>{{ item.stage }} / {{ item.status }}</strong>
                    <span>{{ item.durationMs || 0 }}ms</span>
                  </header>
                  <p class="panel-note">traceId={{ item.traceId || '-' }} / attempt={{ item.attempt }} / error={{ item.errorCode || '-' }}</p>
                  <button class="ghost-btn small-btn" type="button" @click="openStageEventDetail(item)">查看全部</button>
                </article>
              </details>
            </div>

            <div v-else class="empty-state">
              <strong>{{ chainLoading ? '正在加载链路解释' : '选择一篇研报' }}</strong>
              <span>从上方列表点击“查看处理链路”，或输入 reportId 查询。</span>
            </div>
          </div>
        </div>
        <p v-if="observationMsg" class="feedback">{{ observationMsg }}</p>
      </section>

      <section v-if="activeMainTab === 'observation' && showChunkPanel" ref="chunkObservationSectionRef" class="band">
        <div class="section-heading">
          <span>06</span>
          <div>
            <h2>切片前后对照</h2>
            <p>摘要优先展示，长文本可按需展开查看完整内容。</p>
          </div>
        </div>
        <p v-if="chunkObservationMsg" class="feedback">{{ chunkObservationMsg }}</p>
        <div class="evidence-list" v-if="chunkObservationData?.chunkPairs?.length">
          <article v-for="(item, idx) in chunkObservationData.chunkPairs" :key="item.chunkUid || idx" class="evidence-item">
            <header>
              <strong>#{{ idx + 1 }} {{ item.chunkType || '-' }} / chunkIndex={{ item.chunkIndex ?? '-' }}</strong>
              <span>{{ item.tokenCount ?? '-' }} tokens</span>
            </header>
            <small>chunkUid={{ item.chunkUid || '-' }} / 页码={{ item.startPageNumber ?? '-' }}-{{ item.endPageNumber ?? '-' }} / section={{ item.sectionPath || '-' }}</small>
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
import { computed, nextTick, onMounted, reactive, ref } from 'vue'

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
const ingestJobStatus = ref(null)

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
const chainObservation = ref(null)
const chainLoading = ref(false)
const selectedIngestStage = ref('')
const chunkTreeFilter = ref('all')
const selectedReportSummary = ref(null)
const showTimelinePanel = ref(false)
const showChunkPanel = ref(false)

const query = ref('')
const loading = ref(false)
const errMsg = ref('')
const result = ref(null)
const backendState = ref('idle')
const streamController = ref(null)
const activeMainTab = ref('upload')
let streamRequestId = 0

const expandedEvidence = reactive({})
const queryTemplateGroups = [
  {
    title: '行业观点',
    items: ['哪些研报看好储能板块，核心逻辑和风险是什么？', '请比较近期研报中对AI算力产业链的投资观点。']
  },
  {
    title: '公司研究',
    items: ['哪些公司盈利预测上调，主要驱动因素是什么？', '请比较宁德时代和比亚迪在储能产业链中的投资逻辑差异。']
  },
  {
    title: '风险追踪',
    items: ['请找出风险提示中反复出现的宏观或政策风险。', '哪些研报对估值与业绩错配给出了预警？']
  }
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

const runtimeHint = computed(() => '检索与分析接口通过 /api 代理访问后端')

const analysisStatusText = computed(() => result.value?.streamStatus || '等待分析')
const analysisMetaText = computed(() => {
  if (!result.value) return '尚未获得分析元数据'
  return `查询意图：${mapIntent(result.value.inputIntent)} / 输出等级：${mapOutputLevel(result.value.outputLevel)}`
})
const analysisWarningText = computed(() => {
  if (!result.value?.degradationReasons?.length) return ''
  return `提醒：${result.value.degradationReasons.map(mapDegradationReason).join('；')}`
})

const qualityText = computed(() => {
  const quality = result.value?.evidenceQuality || {}
  const anchors = Array.isArray(quality.structuredAnchors) && quality.structuredAnchors.length ? `结构化锚点：${quality.structuredAnchors.join(' / ')}` : ''
  return {
    intent: `查询意图：${mapIntent(result.value?.inputIntent)}`,
    level: `结论等级：${mapOutputLevel(result.value?.outputLevel)}`,
    theme: `主题覆盖：${quality.themeCovered ? '充分' : '不足'}`,
    relevance: `查询相关性：${quality.queryRelevant ? '较高' : '偏低'}`,
    themeDanger: quality.themeCovered === false,
    relevanceDanger: quality.queryRelevant === false,
    anchors,
    warning: analysisWarningText.value
  }
})

const selectedIngestStageData = computed(() => {
  if (!chainObservation.value?.stages?.length) return null
  return chainObservation.value.stages.find((stage) => stage.stage === selectedIngestStage.value) || chainObservation.value.stages[0]
})

const visibleParentChunks = computed(() => {
  const parents = selectedIngestStageData.value?.details?.parentChunks || []
  if (chunkTreeFilter.value !== 'pendingVector') return parents
  return parents
    .map((parent) => ({ ...parent, children: (parent.children || []).filter((child) => !child.vectorStored) }))
    .filter((parent) => parent.children.length > 0)
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
  ingestJobStatus.value = null

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
    setUploadMessage('任务已提交，可继续查询三阶段状态。', 'success')
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
    ingestJobStatus.value = data
    setUploadMessage(
      `已更新任务状态：${formatStageBadge('OCR', data.ocrStatus)} / ${formatStageBadge('切片', data.chunkStatus)} / ${formatStageBadge('向量', data.vectorStatus)}`,
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
    const queryString = reportId ? `reportId=${encodeURIComponent(reportId)}` : `titleKeyword=${encodeURIComponent(titleKeyword)}&limit=50`
    stageEvents.value = await requestJson(`/api/reports/ingest-stage-events?${queryString}`)
    if (reportId) {
      await loadIngestChainObservation(reportId)
    } else {
      chainObservation.value = null
    }
    observationMsg.value = stageEvents.value.length ? `已加载 ${stageEvents.value.length} 条链路事件` : '未查询到链路事件'
  } catch (error) {
    errMsg.value = `链路查询失败：${toUiErrorMessage(error, '后端服务可能未启动，请先启动 8080 后端')}`
  } finally {
    timelineLoading.value = false
  }
}

const loadIngestChainObservation = async (reportId) => {
  if (!reportId) return
  chainLoading.value = true
  try {
    const data = await requestJson(`/api/reports/${encodeURIComponent(reportId)}/ingest-chain-observation`)
    chainObservation.value = data
    selectedIngestStage.value = data.currentStage || data.stages?.[0]?.stage || ''
    chunkTreeFilter.value = selectedIngestStage.value === 'VECTOR' ? 'pendingVector' : 'all'
  } catch (error) {
    chainObservation.value = null
    observationMsg.value = `链路解释加载失败：${toUiErrorMessage(error, '后端服务可能未启动，请先启动 8080 后端')}`
  } finally {
    chainLoading.value = false
  }
}

const loadObservations = async () => {
  observationLoading.value = true
  try {
    const queryParam = new URLSearchParams()
    queryParam.set('titleKeyword', observationQuery.value.titleKeyword || '')
    queryParam.set('limit', String(normalizeLimit(observationQuery.value.limit)))
    reportObservations.value = await requestJson(`/api/reports/observations?${queryParam.toString()}`)
  } catch (error) {
    errMsg.value = `研报列表加载失败：${toUiErrorMessage(error, '后端服务可能未启动，请先启动 8080 后端')}`
  } finally {
    observationLoading.value = false
  }
}

const selectReport = (item) => {
  selectedReportSummary.value = item
  showTimelinePanel.value = false
  showChunkPanel.value = false
}

const observeReport = async (item) => {
  if (!item?.reportId) return
  timelineQuery.value.reportId = String(item.reportId || '')
  timelineQuery.value.titleKeyword = ''
  showTimelinePanel.value = true
  observationMsg.value = `已选择 reportId=${timelineQuery.value.reportId}，正在查询链路...`
  await loadTimeline()
  await nextTick()
  timelineSectionRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const selectIngestStage = (stage) => {
  selectedIngestStage.value = stage
  if (stage !== 'CHUNK') {
    chunkTreeFilter.value = 'all'
  }
}

const jumpToChunkCandidates = (filter) => {
  selectedIngestStage.value = 'CHUNK'
  chunkTreeFilter.value = filter
}

const visibleChildren = (parent) => {
  const children = parent?.children || []
  if (chunkTreeFilter.value === 'pendingVector') return children.filter((child) => !child.vectorStored)
  return children
}

const isStageFailed = (stage) => String(stage?.status || '').includes('FAILED') || Boolean(stage?.errorCode)

const observeChunks = async (item) => {
  if (!item?.reportId) {
    chunkObservationMsg.value = '缺少 reportId，无法查询切片'
    return
  }
  chunkObservationLoading.value = true
  chunkObservationData.value = null
  chunkObservationMsg.value = `已选择 reportId=${item.reportId}，正在查询切片前后数据...`
  showChunkPanel.value = true
  try {
    const data = await requestJson(`/api/reports/${encodeURIComponent(item.reportId)}/chunk-observation`)
    chunkObservationData.value = data
    chunkObservationMsg.value = data?.chunkPairs?.length ? `已加载 ${data.chunkPairs.length} 条切片对照数据` : '暂无切片数据'
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

  Object.keys(expandedEvidence).forEach((key) => delete expandedEvidence[key])

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

const toggleEvidence = (index) => {
  expandedEvidence[index] = !expandedEvidence[index]
}

const copyAnalysis = async () => {
  if (!result.value?.analysis?.trim()) return
  try {
    await navigator.clipboard.writeText(result.value.analysis)
    errMsg.value = ''
  } catch {
    errMsg.value = '复制失败：当前浏览器不支持剪贴板写入，请手动复制结论。'
  }
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

const mapIntent = (value) => {
  const mapping = {
    INDUSTRY: '行业观点',
    COMPANY: '公司研究',
    RISK: '风险追踪',
    COMPARISON: '对比分析'
  }
  return mapping[value] || value || '未识别'
}

const mapOutputLevel = (value) => {
  const mapping = {
    HIGH: '高可信结论',
    MEDIUM: '中等可信结论',
    LOW: '低可信结论',
    SAFE: '保守输出'
  }
  return mapping[value] || value || '未标注'
}

const mapDegradationReason = (value) => {
  const mapping = {
    INSUFFICIENT_EVIDENCE: '有效证据不足',
    LOW_RELEVANCE: '证据相关性偏低',
    GUARDRAIL_TRIGGERED: '触发输出护栏'
  }
  return mapping[value] || value || '存在降级信息'
}

const formatStageBadge = (name, status) => {
  return `${name}：${mapStageStatus(status)}`
}

const mapStageStatus = (status) => {
  const normalized = String(status || '').toUpperCase()
  if (!normalized) return '未开始'
  if (normalized.includes('SUCCESS')) return '成功'
  if (normalized.includes('FAILED') || normalized.includes('ERROR')) return '失败'
  if (normalized.includes('RUNNING') || normalized.includes('PROCESS')) return '进行中'
  if (normalized.includes('PENDING') || normalized.includes('WAIT')) return '等待中'
  return status
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

const formatEventDetail = (item) =>
  [
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
    `errorMessage: ${item.errorMessageShort || '-'}`
  ].join('\n')

const toUiErrorMessage = (error, fallbackMessage) => {
  const message = error?.message || ''
  if (!message) return fallbackMessage
  if (message.includes('Failed to fetch') || message.includes('NetworkError')) return fallbackMessage
  return message
}
</script>
