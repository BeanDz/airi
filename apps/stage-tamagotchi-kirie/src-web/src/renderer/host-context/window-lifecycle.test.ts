import type { HostWindowState } from '@gd-kirie/platform'

import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useHostWindowLifecycle } from './window-lifecycle'

const platform = vi.hoisted(() => ({
  getState: vi.fn(),
}))

const listeners = vi.hoisted(() => new Set<(event: { body?: HostWindowState }) => void>())

vi.mock('./owner', () => ({
  initializeHostContext: () => ({
    context: {
      on: (_event: unknown, listener: (event: { body?: HostWindowState }) => void) => {
        listeners.add(listener)
        return () => listeners.delete(listener)
      },
    },
    platform: { hostWindow: platform },
    runtime: 'kirie',
  }),
}))

describe('kirie host window lifecycle', () => {
  beforeEach(() => {
    listeners.clear()
    platform.getState.mockReset()
  })

  it('gets a native snapshot with AIRI lifecycle metadata', async () => {
    const state = { focused: true, minimized: false, visible: true }
    platform.getState.mockResolvedValue(state)

    await expect(useHostWindowLifecycle().getState()).resolves.toMatchObject({
      ...state,
      reason: 'snapshot',
    })
  })

  it('propagates a native snapshot error', async () => {
    const error = new Error('Host window state is unavailable.')
    platform.getState.mockRejectedValue(error)

    await expect(useHostWindowLifecycle().getState()).rejects.toBe(error)
  })

  it('labels native state transitions for the AIRI store', async () => {
    platform.getState.mockResolvedValue({ focused: true, minimized: false, visible: true })
    const lifecycle = useHostWindowLifecycle()
    const listener = vi.fn()
    lifecycle.onChanged(listener)
    await lifecycle.getState()

    for (const notify of listeners)
      notify({ body: { focused: false, minimized: true, visible: true } })
    for (const notify of listeners)
      notify({ body: { focused: true, minimized: false, visible: true } })

    expect(listener).toHaveBeenNthCalledWith(1, expect.objectContaining({ reason: 'minimize' }))
    expect(listener).toHaveBeenNthCalledWith(2, expect.objectContaining({ reason: 'restore' }))
  })
})
