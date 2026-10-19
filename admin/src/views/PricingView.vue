<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchEffectiveRule,
  fetchPricingRules,
  previewRule,
  publishRule,
  rollbackRule,
} from '@/api'
import type { Draft, View } from '@/api/schema-admin'
import { ApiError } from '@/api/http'
import { priceRuleStatusText } from '@/utils/dict'
import { fmtMinutes, fmtTime, pointsToYuan } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canPublish = computed(() => auth.hasAuthority('pricing:rule:publish'))

const loading = ref(false)
const rules = ref<View[]>([])
const effective = ref<View | null>(null)

// 草稿：数值点数字段保持字符串输入，提交前校验为整数（点数 = 分，不做浮点）
const draft = reactive<Draft>({
  siteId: '',
  effectiveAt: '',
  reason: '',
  depositPoints: '',
  freeMinutes: undefined,
  dailyCapHours: undefined,
  capDays: undefined,
  toleranceMinutes: undefined,
  remoteCloseHours: undefined,
  unitSmall: '',
  unitMedium: '',
  unitLarge: '',
})

const preview = ref<Record<string, unknown> | null>(null)
const previewing = ref(false)

const previewRows = computed(() => {
  const samples = preview.value?.samples as Record<string, number> | undefined
  if (!samples) return []
  return [30, 90, 1560, 4800].map((minutes) => ({
    label: fmtMinutes(minutes),
    small: samples[`SMALL@${minutes}min`] ?? 0,
    medium: samples[`MEDIUM@${minutes}min`] ?? 0,
    large: samples[`LARGE@${minutes}min`] ?? 0,
  }))
})

async function load(): Promise<void> {
  loading.value = true
  try {
    rules.value = await fetchPricingRules()
    effective.value = await fetchEffectiveRule(draft.siteId || undefined)
    // 押金是历史参数，后端发布校验仍要求 > 0；默认沿用当前生效值，不自己编一个数字
    if (!draft.depositPoints && effective.value?.depositPoints !== undefined) {
      draft.depositPoints = String(effective.value.depositPoints)
    }
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function isPositiveInt(value: unknown): boolean {
  const text = String(value ?? '').trim()
  return /^\d+$/.test(text) && Number(text) > 0
}

function isNonNegativeInt(value: unknown): boolean {
  if (value === undefined || value === null || value === '') return false
  const num = Number(value)
  return Number.isInteger(num) && num >= 0
}

/** 发布前把后端 validate 的必填项先挡一遍，避免半份草稿打到接口再报 400 */
function validateDraft(): string {
  if (!isPositiveInt(draft.depositPoints)) return '押金点数必须是正整数（历史参数，可沿用当前生效值）'
  if (!isPositiveInt(draft.unitSmall)) return '小格单价必须是正整数点数'
  if (!isPositiveInt(draft.unitMedium)) return '中格单价必须是正整数点数'
  if (!isPositiveInt(draft.unitLarge)) return '大格单价必须是正整数点数'
  if (!isNonNegativeInt(draft.freeMinutes)) return '免费时长必须是非负整数分钟'
  if (!isNonNegativeInt(draft.dailyCapHours)) return '每日封顶必须是非负整数小时'
  if (!isNonNegativeInt(draft.capDays)) return '封顶天数必须是非负整数'
  if (!isNonNegativeInt(draft.toleranceMinutes)) return '容差分钟必须是非负整数'
  if (!isNonNegativeInt(draft.remoteCloseHours)) return '远程结束加收小时必须是非负整数'
  if (
    draft.effectiveAt &&
    !/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(String(draft.effectiveAt))
  ) {
    return '生效时间格式应为 yyyy-MM-dd HH:mm:ss'
  }
  return ''
}

async function onPreview(): Promise<void> {
  const error = validateDraft()
  if (error) {
    ElMessage.warning(error)
    return
  }
  previewing.value = true
  try {
    preview.value = await previewRule({ ...draft })
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '预览失败')
  } finally {
    previewing.value = false
  }
}

async function onPublish(): Promise<void> {
  const error = validateDraft()
  if (error) {
    ElMessage.warning(error)
    return
  }
  if (!draft.reason?.trim()) {
    ElMessage.warning('发布原因必填：它是以后解释“为什么改价”的唯一凭据')
    return
  }
  try {
    await ElMessageBox.confirm(
      '发布一版新策略后，新单按新价、老单按各自快照价。确认发布？',
      '发布策略',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await publishRule({ ...draft })
    ElMessage.success('已发布')
    preview.value = null
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '发布失败')
  }
}

async function onRollback(rule: View): Promise<void> {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `回滚到版本 ${rule.version}，把原因写清楚。`,
      '回滚策略',
      {
        type: 'warning',
        inputValidator: (value: string) => (value && value.trim() ? true : '原因必填'),
      },
    )
    reason = result.value
  } catch {
    return
  }
  try {
    await rollbackRule(String(rule.id), { reason })
    ElMessage.success('已回滚')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '回滚失败')
  }
}

onMounted(() => {
  if (auth.hasAuthority('pricing:rule:list')) load()
})
</script>

<template>
  <el-row :gutter="12">
    <el-col :span="14">
      <el-card v-loading="loading" class="panel">
        <template #header>
          <div class="panel-header">
            <span>当前生效策略</span>
            <el-button link type="primary" @click="load">刷新</el-button>
          </div>
        </template>
        <el-descriptions v-if="effective" :column="2" border size="small">
          <el-descriptions-item label="版本">v{{ effective.version ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ priceRuleStatusText(effective.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="小格单价">
            {{ pointsToYuan(effective.unitSmall) }} 元/时
          </el-descriptions-item>
          <el-descriptions-item label="中格单价">
            {{ pointsToYuan(effective.unitMedium) }} 元/时
          </el-descriptions-item>
          <el-descriptions-item label="大格单价">
            {{ pointsToYuan(effective.unitLarge) }} 元/时
          </el-descriptions-item>
          <el-descriptions-item label="免费时长">
            {{ effective.freeMinutes ?? '—' }} 分
          </el-descriptions-item>
          <el-descriptions-item label="每日封顶">
            {{ effective.dailyCapHours ?? '—' }} 时
          </el-descriptions-item>
          <el-descriptions-item label="封顶天数">{{ effective.capDays ?? '—' }} 天</el-descriptions-item>
          <el-descriptions-item label="生效时间">{{ fmtTime(effective.effectiveAt) }}</el-descriptions-item>
          <el-descriptions-item label="发布原因">{{ effective.reason ?? '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-empty v-else description="当前范围还没有发布过定价策略" :image-size="60" />
      </el-card>
    </el-col>

    <el-col :span="10">
      <el-card v-if="canPublish" class="panel">
        <template #header>发布新版本</template>
        <el-form label-width="122px" size="small">
          <el-form-item label="点位 ID（可空）">
            <el-input v-model="draft.siteId" placeholder="留空为租户默认" />
          </el-form-item>
          <el-form-item label="小格（点/时）">
            <el-input v-model="draft.unitSmall" placeholder="正整数点数，1 点 = 1 分" />
          </el-form-item>
          <el-form-item label="中格（点/时）">
            <el-input v-model="draft.unitMedium" placeholder="正整数点数" />
          </el-form-item>
          <el-form-item label="大格（点/时）">
            <el-input v-model="draft.unitLarge" placeholder="正整数点数" />
          </el-form-item>
          <el-form-item label="押金点数">
            <el-input v-model="draft.depositPoints" placeholder="历史参数，沿用当前生效值" />
          </el-form-item>
          <el-form-item label="免费时长（分）">
            <el-input-number v-model="draft.freeMinutes" :min="0" :precision="0" />
          </el-form-item>
          <el-form-item label="每日封顶（时）">
            <el-input-number v-model="draft.dailyCapHours" :min="0" :precision="0" />
          </el-form-item>
          <el-form-item label="封顶天数">
            <el-input-number v-model="draft.capDays" :min="0" :precision="0" />
          </el-form-item>
          <el-form-item label="容差（分）">
            <el-input-number v-model="draft.toleranceMinutes" :min="0" :precision="0" />
          </el-form-item>
          <el-form-item label="远程关柜（时）">
            <el-input-number v-model="draft.remoteCloseHours" :min="0" :precision="0" />
          </el-form-item>
          <el-form-item label="生效时间">
            <el-date-picker
              v-model="draft.effectiveAt"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              placeholder="留空立即生效"
              class="date-picker"
            />
          </el-form-item>
          <el-form-item label="发布原因" required>
            <el-input
              v-model="draft.reason"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              placeholder="例如：旺季调价、成本变化"
            />
          </el-form-item>
          <div class="form-actions">
            <el-button :loading="previewing" @click="onPreview">发布前预览</el-button>
            <el-button type="primary" @click="onPublish">发布</el-button>
          </div>
        </el-form>
      </el-card>

      <el-card v-if="preview" class="panel">
        <template #header>
          <div class="panel-header">
            <span>预览结果</span>
            <el-tag v-if="preview.source" size="small" type="info">
              {{ preview.source === 'draft' ? '草稿' : String(preview.source) }}
            </el-tag>
          </div>
        </template>
        <p class="preview-note">
          按这张草稿算价：行是存放时长，列是尺寸，单位元。只用于核对，不落单。
        </p>
        <el-table :data="previewRows" size="small">
          <el-table-column label="存放时长" prop="label" width="110" />
          <el-table-column label="小格" min-width="90">
            <template #default="{ row }">{{ pointsToYuan(row.small) }} 元</template>
          </el-table-column>
          <el-table-column label="中格" min-width="90">
            <template #default="{ row }">{{ pointsToYuan(row.medium) }} 元</template>
          </el-table-column>
          <el-table-column label="大格" min-width="90">
            <template #default="{ row }">{{ pointsToYuan(row.large) }} 元</template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-col>
  </el-row>

  <el-card class="panel">
    <template #header>
      <div class="panel-header">
        <span>历史版本</span>
        <el-tag type="info" size="small">共 {{ rules.length }} 版</el-tag>
      </div>
    </template>
    <el-table :data="rules" size="small" scrollbar-always-on>
      <el-table-column label="版本" prop="version" width="70" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">{{ priceRuleStatusText(row.status) }}</template>
      </el-table-column>
      <el-table-column label="小/中/大（元/时）" width="170">
        <template #default="{ row }">
          {{ pointsToYuan(row.unitSmall) }} / {{ pointsToYuan(row.unitMedium) }} /
          {{ pointsToYuan(row.unitLarge) }}
        </template>
      </el-table-column>
      <el-table-column label="原因" prop="reason" min-width="200" show-overflow-tooltip />
      <el-table-column label="生效时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.effectiveAt) }}</template>
      </el-table-column>
      <el-table-column v-if="canPublish" label="操作" width="90">
        <template #default="{ row }">
          <el-button
            link
            type="warning"
            :disabled="row.status === 'LIVE'"
            @click="onRollback(row)"
          >
            回滚
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有历史版本" :image-size="60" />
      </template>
    </el-table>
  </el-card>
</template>

<style scoped>
.panel {
  margin-bottom: 12px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-title {
  margin: 18px 0 10px;
}

.date-picker {
  width: 100%;
}

.form-actions {
  display: flex;
  gap: 8px;
}

/* 窄卡片下两列描述会把“版本/状态”压成逐字竖排：label 不换行，超出时可横滚 */
.panel :deep(.el-descriptions__label) {
  white-space: nowrap;
  min-width: 84px;
}

.panel :deep(.el-descriptions__body) {
  overflow-x: auto;
}

.preview-note {
  margin: 0 0 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
