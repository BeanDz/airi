import type { RearScreenState } from '../modules/rear-screen'

import { Capacitor } from '@capacitor/core'
import { errorMessageFrom } from '@moeru/std'
import { computed, onActivated, onMounted, readonly, shallowRef } from 'vue'

import { rearScreenPlugin } from '../modules/rear-screen'

/**
 * Keeps Vue state synchronized with the Android task's actual display and exposes serialized moves.
 *
 * The native result is the source of truth. Callers cannot mutate display state optimistically,
 * which prevents the compact rear-screen UI from appearing when system_server rejects a move.
 */
export function useRearScreen() {
  const supported = shallowRef(false)
  const available = shallowRef(false)
  const display = shallowRef<RearScreenState['display']>('main')
  const moving = shallowRef(false)
  const error = shallowRef<string>()

  const isRear = computed(() => display.value === 'rear')

  function applyState(state: RearScreenState) {
    supported.value = state.supported
    available.value = state.available
    display.value = state.display
  }

  async function refresh() {
    if (Capacitor.getPlatform() !== 'android') {
      return
    }

    try {
      applyState(await rearScreenPlugin.getState())
    }
    catch (cause) {
      error.value = errorMessageFrom(cause) ?? 'Failed to read rear-screen state.'
    }
  }

  async function runMove(operation: () => Promise<RearScreenState>) {
    if (moving.value) {
      return
    }

    moving.value = true
    error.value = undefined
    try {
      applyState(await operation())
    }
    catch (cause) {
      error.value = errorMessageFrom(cause) ?? 'Failed to move AIRI between displays.'
    }
    finally {
      moving.value = false
    }
  }

  function moveToRear() {
    return runMove(() => rearScreenPlugin.moveToRear())
  }

  function moveToMain() {
    return runMove(() => rearScreenPlugin.moveToMain())
  }

  onMounted(() => void refresh())
  onActivated(() => void refresh())

  return {
    supported: readonly(supported),
    available: readonly(available),
    display: readonly(display),
    isRear,
    moving: readonly(moving),
    error: readonly(error),
    refresh,
    moveToRear,
    moveToMain,
  }
}
