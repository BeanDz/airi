import { registerPlugin } from '@capacitor/core'

export type RearScreenDisplay = 'main' | 'rear'

/** Snapshot confirmed by the Android task controller. */
export interface RearScreenState {
  /** Whether this APK was built with the rear-screen LSPosed entrypoint. */
  supported: boolean
  /** Whether the system_server control entrypoint answered this request. */
  available: boolean
  /** Display currently hosting AIRI's Android task. */
  display: RearScreenDisplay
  /** Optional diagnostic or transition result from system_server. */
  message?: string
}

/** Native boundary for moving the existing AIRI task without recreating the Vue application. */
export interface RearScreenPlugin {
  getState: () => Promise<RearScreenState>
  moveToRear: () => Promise<RearScreenState>
  moveToMain: () => Promise<RearScreenState>
}

export const rearScreenPlugin = registerPlugin<RearScreenPlugin>('RearScreen')
