import { computed, watch, type ComputedRef, type Ref, type WatchSource } from 'vue'

type ListSource<T> = Ref<T[]> | ComputedRef<T[]>

export function usePagedItems<T>(items: ListSource<T>, currentPage: Ref<number>, pageSize: Ref<number>) {
  return computed(() => {
    const start = (currentPage.value - 1) * pageSize.value
    return items.value.slice(start, start + pageSize.value)
  })
}

export function useResetPageOnChange(currentPage: Ref<number>, sources: WatchSource<unknown>[]) {
  watch(sources, () => {
    currentPage.value = 1
  })
}

export function clampPageToTotal(currentPage: Ref<number>, pageSize: Ref<number>, total: number) {
  const maxPage = Math.max(1, Math.ceil(total / Math.max(pageSize.value, 1)))
  if (currentPage.value > maxPage) {
    currentPage.value = maxPage
  }
}
