import { reactive, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { Paged } from '@/api/contracts'
import { ApiError } from '@/api/http'

export interface PageParams {
  pageNum: number
  pageSize: number
}

/**
 * 分页列表的公共状态。
 *
 * 六个页面（工单、订单、异常台账、资金、账号、死信）原本各自抄一遍
 * `rows / total / loading / pageNum / pageSize / load / current-change`，
 * 逻辑完全相同，差别只在"加载时要带上哪些筛选条件"。
 *
 * 所以这里只收公共的那部分——翻页、加载态、错误提示、空数组兜底。
 * **筛选条件不进这个函数**：由调用方在自己的 loader 闭包里带上，
 * 它不该知道"关键字"和"状态"这种业务词。
 */
export function usePagedList<T>(
  loader: (params: PageParams) => Promise<Paged<T>>,
  options: { pageSize?: number; errorText?: string } = {},
) {
  const rows = ref([]) as Ref<T[]>
  const total = ref(0)
  const loading = ref(false)
  const page = reactive<PageParams>({ pageNum: 1, pageSize: options.pageSize ?? 20 })

  async function load(): Promise<void> {
    loading.value = true
    try {
      const result = await loader({ pageNum: page.pageNum, pageSize: page.pageSize })
      rows.value = result.records ?? []
      total.value = Number(result.total ?? 0)
    } catch (e) {
      ElMessage.error(e instanceof ApiError ? e.message : (options.errorText ?? '加载失败'))
    } finally {
      loading.value = false
    }
  }

  /** 条件变了要先回第一页，否则会停在一个已经没有数据的第 5 页 */
  async function search(): Promise<void> {
    page.pageNum = 1
    await load()
  }

  async function goTo(pageNum: number): Promise<void> {
    page.pageNum = pageNum
    await load()
  }

  return { rows, total, loading, page, load, search, goTo }
}
