<template>
  <div>
    <h2 class="page-title">备份恢复删除复查（隔离检查后开放）</h2>
    <el-card class="section-card">
      <el-alert type="warning" :closable="false" style="margin-bottom:12px"
        title="恢复内容先进入隔离区复查，绝不直接恢复到线上再补删——恢复与补删之间会暴露旧简历。所有位置通过检查并登记血缘后才能申请开放。" />
      <el-form inline>
        <el-form-item label="备份日期">
          <el-date-picker v-model="form.backupDate" type="date" value-format="YYYY-MM-DD" placeholder="选择备份日期" style="width:160px" />
        </el-form-item>
        <el-form-item label="来源标识"><el-input v-model="form.sourceRef" placeholder="如 snapshot-20260930" /></el-form-item>
        <el-form-item label="涉及表">
          <el-select v-model="form.involvedTables" multiple filterable style="width:300px" placeholder="默认全部表来源">
            <el-option v-for="c in tableCatalog" :key="c.id" :label="c.target_ref" :value="c.target_ref" />
          </el-select>
        </el-form-item>
        <el-form-item label="文件目录">
          <el-select v-model="form.fileDirectories" multiple filterable style="width:260px" placeholder="默认全部文件来源">
            <el-option v-for="c in fileCatalog" :key="c.id" :label="c.target_ref" :value="c.target_ref" />
          </el-select>
        </el-form-item>
        <el-form-item label="备份清单文件（每行一个相对路径）">
          <el-input v-model="manifestText" type="textarea" :rows="2" style="width:340px"
            placeholder="pdf/zhangwei-v1.pdf&#10;pdf/lina-v1.pdf" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="register">登记恢复批次</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <template #header>恢复批次（{{ batches.length }}）· 每次恢复独立批次号，失败重试接着处理未完成位置</template>
      <el-table :data="batches" border stripe v-loading="loading">
        <el-table-column prop="batch_no" label="批次号" width="200" />
        <el-table-column prop="backup_date" label="备份日期" width="120" />
        <el-table-column prop="source_ref" label="来源标识" width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="140">
          <template #default="{ row }"><el-tag :type="batchStatusType(row.status)">{{ batchStatusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="credential_count" label="有效删除凭证数" width="140" />
        <el-table-column prop="last_scan_at" label="最近扫描时间" min-width="180" />
        <el-table-column prop="created_by" label="登记人" width="110" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="$router.push(`/restore/${row.id}`)">复查台</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import http from '../api/http'
import { ElMessage } from 'element-plus'

const catalog = ref([])
const batches = ref([])
const loading = ref(false)
const manifestText = ref('pdf/zhangwei-v1.pdf\npdf/zhangwei-v2.pdf\npdf/lina-v1.pdf')
const form = reactive({ backupDate: '2026-09-30', sourceRef: '', involvedTables: [], fileDirectories: [] })

const tableCatalog = computed(() => catalog.value.filter((c) => c.source_type === 'TABLE'))
const fileCatalog = computed(() => catalog.value.filter((c) => c.source_type === 'FILE'))

async function load() {
  loading.value = true
  try {
    catalog.value = (await http.get('/api/restore/catalog')).data
    batches.value = (await http.get('/api/restore/batches')).data
  } finally {
    loading.value = false
  }
}
async function register() {
  if (!form.backupDate) {
    ElMessage.warning('必须登记备份日期')
    return
  }
  const body = {
    backupDate: form.backupDate,
    sourceRef: form.sourceRef,
    involvedTables: form.involvedTables,
    fileDirectories: form.fileDirectories,
    manifestFiles: manifestText.value.split('\n').map((s) => s.trim()).filter(Boolean)
  }
  const r = (await http.post('/api/restore/batches', body)).data
  ElMessage.success(`恢复批次已登记：${r.batchNo}，隔离位置 ${r.locationCount} 个`)
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
  return {
    OPENED: 'success', READY_TO_OPEN: 'success', OPEN_APPLIED: 'primary',
    BLOCKED: 'danger', PENDING_HUMAN: 'warning', PENDING_VERIFY: 'warning'
  }[s] || 'info'
}
onMounted(load)
</script>
