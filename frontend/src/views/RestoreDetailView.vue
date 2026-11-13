<template>
  <div v-loading="loading">
    <div style="display:flex; justify-content:space-between; align-items:center">
      <h2 class="page-title" style="margin:0">恢复复查台 · {{ batch?.batch_no }}</h2>
      <el-button @click="$router.back()">返回批次列表</el-button>
    </div>

    <el-card v-if="batch" class="section-card">
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="批次状态">
          <el-tag :type="batchStatusType(batch.status)">{{ batchStatusLabel(batch.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="备份日期">{{ batch.backup_date }}</el-descriptions-item>
        <el-descriptions-item label="来源标识">{{ batch.source_ref || '—' }}</el-descriptions-item>
        <el-descriptions-item label="有效删除凭证数">{{ batch.credential_count }}</el-descriptions-item>
        <el-descriptions-item label="最近扫描">{{ batch.last_scan_at || '—' }}</el-descriptions-item>
        <el-descriptions-item label="开放前复核">{{ batch.reopen_check_at || '—' }}</el-descriptions-item>
        <el-descriptions-item label="开放时间">{{ batch.opened_at || '—' }}</el-descriptions-item>
        <el-descriptions-item label="登记人">{{ batch.created_by }}</el-descriptions-item>
      </el-descriptions>
      <div style="margin-top:14px; display:flex; flex-wrap:wrap; gap:8px">
        <el-button type="primary" :disabled="!['REGISTERED','BLOCKED'].includes(batch.status)" @click="act('restore','恢复装载进隔离区')">① 恢复进隔离区</el-button>
        <el-button type="warning" :disabled="['REGISTERED','OPEN_APPLIED','OPENED'].includes(batch.status)" @click="act('scan','执行复查扫描')">② 复查扫描（可重试）</el-button>
        <el-button :disabled="['REGISTERED','OPENED'].includes(batch.status)" @click="act('recheck-files','重扫文件目录')">重扫文件目录</el-button>
        <el-button type="success" plain :disabled="['REGISTERED','OPENED'].includes(batch.status)" @click="act('lineage','登记资产血缘')">③ 登记资产血缘</el-button>
        <el-button type="primary" plain :disabled="!['READY_TO_OPEN','PENDING_HUMAN','BLOCKED'].includes(batch.status)" @click="submitOpen">④ 提交开放申请</el-button>
      </div>
      <el-alert type="info" :closable="false" style="margin-top:12px"
        title="复查基准是当前仍有效的删除凭证：每次扫描实时提取主体指纹与处置结果，检查期间新办结的注销会追加进本次扫描；开放前再次核对凭证指纹，不能只拿启动时名单查一遍。" />
    </el-card>

    <el-tabs v-if="batch" type="border-card">
      <el-tab-pane :label="`隔离位置（${batch.locations.length}）`">
        <el-table :data="batch.locations" border size="small">
          <el-table-column prop="quarantine_ref" label="隔离位置" min-width="220" show-overflow-tooltip />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ row.location_type === 'FILE' ? '文件' : '表' }}</template>
          </el-table-column>
          <el-table-column prop="asset_code" label="资产编码" width="140" />
          <el-table-column label="主体关联" width="110">
            <template #default="{ row }">
              <el-tag :type="row.subject_linkable ? 'success' : 'danger'" size="small">
                {{ row.subject_linkable ? '可关联' : '缺字段' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="检查状态" width="130">
            <template #default="{ row }"><el-tag :type="locStatusType(row.check_status)">{{ locStatusLabel(row.check_status) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="restored_count" label="恢复数" width="80" />
          <el-table-column prop="suppressed_count" label="拦截" width="70" />
          <el-table-column prop="clean_count" label="放行" width="70" />
          <el-table-column prop="manual_pending_count" label="待人工" width="80" />
          <el-table-column label="血缘" width="90">
            <template #default="{ row }">
              <el-tag :type="row.lineage_registered ? 'success' : 'info'" size="small">
                {{ row.lineage_registered ? '已登记' : '未登记' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作 / 错误" min-width="200">
            <template #default="{ row }">
              <el-button v-if="row.check_status === 'PENDING_VERIFY'" size="small" type="warning" @click="openLinkage(row)">补齐来源映射</el-button>
              <span v-if="row.last_error" class="mono" style="color:#f56c6c">{{ row.last_error }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`文件清单差异（${batch.fileDiffs.length}）`">
        <el-alert type="warning" :closable="false" style="margin-bottom:10px"
          title="文件目录与备份清单不一致：缺件=清单有目录无，多件=目录有清单无。差异未消除前文件位置阻断，管理员补齐来源后才能重新检查。" />
        <el-table :data="batch.fileDiffs" border size="small">
          <el-table-column prop="file_path" label="文件路径" min-width="260" />
          <el-table-column label="差异类型" width="120">
            <template #default="{ row }">
              <el-tag :type="row.diff_type === 'MISSING' ? 'danger' : 'warning'">
                {{ row.diff_type === 'MISSING' ? '缺件' : '多件' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'OPEN' ? 'danger' : 'success'">{{ row.status === 'OPEN' ? '待补齐' : '已解决' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="resolution_note" label="解决说明" show-overflow-tooltip />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button v-if="row.status === 'OPEN'" size="small" type="primary" @click="resolveDiff(row.id)">补齐/说明</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`复查项（${batch.reviewItems.length}）`">
        <el-table :data="batch.reviewItems" border size="small" max-height="520">
          <el-table-column prop="item_ref" label="恢复条目" width="180" show-overflow-tooltip />
          <el-table-column label="类型" width="110">
            <template #default="{ row }">{{ itemKindLabel(row.item_kind) }}</template>
          </el-table-column>
          <el-table-column label="主体哈希" min-width="150">
            <template #default="{ row }"><span class="mono">{{ row.subject_hash ? row.subject_hash.substring(0, 16) + '…' : '—' }}</span></template>
          </el-table-column>
          <el-table-column label="判定" width="120">
            <template #default="{ row }"><el-tag :type="verdictType(row.verdict)">{{ verdictLabel(row.verdict) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="处理状态" width="120">
            <template #default="{ row }"><el-tag :type="reviewStatusType(row.review_status)">{{ reviewStatusLabel(row.review_status) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="disposition_snapshot" label="凭证处置结果" width="150" />
          <el-table-column prop="scan_round" label="扫描轮次" width="90" />
          <el-table-column prop="manual_reason" label="人工原因" min-width="200" show-overflow-tooltip />
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <template v-if="row.review_status === 'PENDING'">
                <el-button size="small" type="danger" @click="decideItem(row.id, 'SUPPRESS')">人工拦截</el-button>
                <el-button size="small" type="success" @click="decideItem(row.id, 'RELEASE')">人工放行</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="开放审批">
        <el-table :data="batch.openRequest" border size="small" style="margin-bottom:12px">
          <el-table-column prop="id" label="申请ID" width="90" />
          <el-table-column prop="basis" label="放行依据" min-width="260" show-overflow-tooltip />
          <el-table-column prop="credential_count" label="复核凭证数" width="120" />
          <el-table-column label="凭证指纹" min-width="160">
            <template #default="{ row }"><span class="mono">{{ row.credential_fingerprint.substring(0, 16) }}…</span></template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <template v-if="row.status === 'SUBMITTED'">
                <el-button size="small" type="success" @click="decideOpen(row.id, 'approve')">批准开放</el-button>
                <el-button size="small" type="danger" @click="decideOpen(row.id, 'reject')">驳回</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="success" :closable="false"
          title="批准开放后：CLEAN 数据按列复制上线，命中删除凭证的残留隔离拦截（不重新出现在搜索结果），残留与放行依据作为新记录附在原删除工单后，不更换当年证据说明。" />
      </el-tab-pane>

      <el-tab-pane :label="`附原工单残留记录（${batch.residualRecords.length}）`">
        <el-table :data="batch.residualRecords" border size="small">
          <el-table-column prop="original_ticket_id" label="原删除工单" width="120" />
          <el-table-column label="类型" width="160">
            <template #default="{ row }">{{ residualTypeLabel(row.residual_type) }}</template>
          </el-table-column>
          <el-table-column label="处理动作" width="170">
            <template #default="{ row }">{{ actionLabel(row.action_taken) }}</template>
          </el-table-column>
          <el-table-column prop="item_ref" label="恢复条目" width="160" show-overflow-tooltip />
          <el-table-column prop="basis" label="依据" min-width="280" show-overflow-tooltip />
          <el-table-column prop="recorded_by" label="记录人" width="110" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="linkageDialog" title="补齐主体关联来源映射" width="560px">
      <el-alert type="info" :closable="false" style="margin-bottom:10px"
        title="备份缺少主体关联字段，相关表停在待核验。请提供 item_ref → subject_hash 的映射，补齐来源后重新扫描。" />
      <el-form label-width="120px">
        <el-form-item label="资产编码"><el-input v-model="linkageForm.assetCode" placeholder="如 AST_LEGACY" /></el-form-item>
        <el-form-item label="映射（JSON）">
          <el-input v-model="linkageForm.linkageText" type="textarea" :rows="5"
            placeholder='{"dump#1":"<subject_hash>","dump#2":"<subject_hash>"}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="linkageDialog = false">取消</el-button>
        <el-button type="primary" @click="submitLinkage">提交并重检</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/http'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const batchId = route.params.id
const batch = ref(null)
const loading = ref(false)
const linkageDialog = ref(false)
const linkageForm = ref({ locationId: null, assetCode: 'AST_LEGACY', linkageText: '' })

async function load() {
  loading.value = true
  try {
    batch.value = (await http.get(`/api/restore/batches/${batchId}`)).data
  } finally {
    loading.value = false
  }
}
async function act(path, label) {
  const r = (await http.post(`/api/restore/batches/${batchId}/${path}`)).data
  ElMessage.success(`${label}完成`)
  if (path === 'scan') {
    ElMessage.info(`本轮扫描：拦截 ${r.suppressed} / 放行 ${r.clean} / 待人工 ${r.manualPending}，状态→${r.nextStatus}`)
  }
  await load()
}
async function resolveDiff(diffId) {
  const { value } = await ElMessageBox.prompt('补齐来源 / 差异说明', '解决文件缺件/多件', { inputType: 'textarea' })
  await http.post(`/api/restore/file-diffs/${diffId}/resolve`, { note: value })
  ElMessage.success('文件差异已解决')
  await load()
}
function openLinkage(row) {
  linkageForm.value = { locationId: row.id, assetCode: row.asset_code || 'AST_LEGACY', linkageText: '' }
  linkageDialog.value = true
}
async function submitLinkage() {
  let linkage
  try {
    linkage = JSON.parse(linkageForm.value.linkageText)
  } catch {
    ElMessage.error('映射必须是合法 JSON')
    return
  }
  await http.post(`/api/restore/locations/${linkageForm.value.locationId}/linkage`, {
    assetCode: linkageForm.value.assetCode, linkage
  })
  ElMessage.success('来源映射已补齐，请重新执行复查扫描')
  linkageDialog.value = false
  await load()
}
async function decideItem(itemId, decision) {
  const { value } = await ElMessageBox.prompt('人工判定依据说明', decision === 'SUPPRESS' ? '隔离拦截' : '放行',
    { inputType: 'textarea' })
  await http.post(`/api/restore/review-items/${itemId}/decide`, { decision, note: value })
  ElMessage.success(decision === 'SUPPRESS' ? '已在隔离区拦截' : '已标记放行')
  await load()
}
async function submitOpen() {
  const { value } = await ElMessageBox.prompt('放行依据（开放前会再次核对删除凭证指纹）', '提交开放申请',
    { inputType: 'textarea' })
  await http.post(`/api/restore/batches/${batchId}/open-request`, { basis: value })
  ElMessage.success('开放申请已提交，等待合规审批')
  await load()
}
async function decideOpen(requestId, action) {
  const { value } = await ElMessageBox.prompt('审批说明', action === 'approve' ? '批准开放' : '驳回',
    { inputType: 'textarea', inputValue: action === 'approve' ? '复查通过，同意开放' : '材料不足，退回' })
  await http.post(`/api/restore/open-requests/${requestId}/${action}`, { note: value })
  ElMessage.success(action === 'approve' ? '已开放：拦截残留、放行其余' : '已驳回')
  await load()
}
function batchStatusLabel(s) {
  return {
    REGISTERED: '已登记待装载', RESTORED: '已入隔离区', SCANNING: '扫描中', CHECKING: '检查中',
    BLOCKED: '阻断(待补齐)', PENDING_HUMAN: '待人工判定', READY_TO_OPEN: '待申请开放',
    OPEN_APPLIED: '开放审批中', OPENED: '已开放', ABANDONED: '已废弃'
  }[s] || s
}
function batchStatusType(s) {
  return { OPENED: 'success', READY_TO_OPEN: 'success', OPEN_APPLIED: 'primary', BLOCKED: 'danger', PENDING_HUMAN: 'warning' }[s] || 'info'
}
function locStatusLabel(s) {
  return { PENDING: '待检查', SCANNING: '扫描中', PENDING_VERIFY: '待核验(缺字段)', CHECKED: '已检查', BLOCKED: '阻断', RELEASED: '已释放', EXCLUDED: '已排除' }[s] || s
}
function locStatusType(s) {
  return { CHECKED: 'success', RELEASED: 'success', BLOCKED: 'danger', PENDING_VERIFY: 'warning', PENDING: 'info', SCANNING: '' }[s] || 'info'
}
function itemKindLabel(k) {
  return { ACCOUNT: '账号', ATTACHMENT: '附件', DERIVED: '派生记录' }[k] || k
}
function verdictLabel(v) {
  return { SUPPRESSED: '隔离拦截', CLEAN: '可放行', MANUAL: '待人工' }[v] || v
}
function verdictType(v) {
  return { SUPPRESSED: 'danger', CLEAN: 'success', MANUAL: 'warning' }[v] || 'info'
}
function reviewStatusLabel(s) {
  return { PENDING: '待处理', AUTO_DONE: '自动完成', MANUAL_DONE: '人工已判', SUPPRESSED: '已拦截', RELEASED: '已放行' }[s] || s
}
function reviewStatusType(s) {
  return { SUPPRESSED: 'danger', RELEASED: 'success', MANUAL_DONE: 'success', AUTO_DONE: 'info', PENDING: 'warning' }[s] || 'info'
}
function residualTypeLabel(t) {
  return { RESTORED_RESIDUAL: '恢复残留', MANUAL_SUPPRESSED: '人工拦截', MANUAL_RELEASED: '人工放行', OPEN_BASIS: '放行依据' }[t] || t
}
function actionLabel(a) {
  return { SUPPRESSED_IN_QUARANTINE: '隔离区拦截', RELEASED_ONLINE: '释放上线', KEPT_PENDING: '挂起待处理', BASIS_RECORDED: '依据登记' }[a] || a
}
onMounted(load)
</script>
