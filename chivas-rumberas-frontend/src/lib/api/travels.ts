import type { Travel } from '@/lib/types'
import { apiFetch } from './client'

export function getTravels(revalidate: number | false = 300): Promise<Travel[]> {
  return apiFetch<Travel[]>('/travels', {
    tags: ['travels'],
    revalidate,
  })
}
