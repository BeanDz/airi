<script setup lang="ts">
import type { CSSProperties } from 'vue'

import { WidgetStage } from '@proj-airi/stage-ui/components/scenes'

interface Props {
  cursorPosition: { x: number, y: number }
  paused: boolean
}

defineProps<Props>()

// The background remains full bleed. Only important stage content is inset so the character's
// face and body stay clear of the rear display's cutout and rounded physical corners.
const safeAreaStyle: CSSProperties = {
  boxSizing: 'border-box',
  paddingTop: 'max(0.5rem, env(safe-area-inset-top, 0px))',
  paddingRight: 'max(0.5rem, env(safe-area-inset-right, 0px))',
  paddingBottom: 'env(safe-area-inset-bottom, 0px)',
  paddingLeft: 'max(0.5rem, env(safe-area-inset-left, 0px))',
}
</script>

<template>
  <div
    :class="[
      'absolute inset-0 min-h-0 min-w-0 overflow-hidden',
    ]"
    :style="safeAreaStyle"
  >
    <WidgetStage
      compact-layout h-full w-full
      :cursor-position="cursorPosition"
      :enable-orbit-controls="false"
      :paused="paused"
    />
  </div>
</template>
