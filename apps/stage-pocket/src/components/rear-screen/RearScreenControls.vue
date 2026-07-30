<script setup lang="ts">
import type { RearScreenDisplay } from '../../modules/rear-screen'

import { Button } from '@proj-airi/ui'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

interface Props {
  supported: boolean
  available: boolean
  display: RearScreenDisplay
  moving: boolean
}

interface Emits {
  moveToRear: []
  moveToMain: []
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const { t } = useI18n()

const label = computed(() => {
  if (!props.available) {
    return t('stage.rear-screen.unavailable')
  }
  if (props.moving) {
    return props.display === 'rear'
      ? t('stage.rear-screen.moving-to-main')
      : t('stage.rear-screen.moving-to-rear')
  }
  return props.display === 'rear'
    ? t('stage.rear-screen.move-to-main')
    : t('stage.rear-screen.move-to-rear')
})

const containerClasses = computed(() => [
  'z-30 w-fit',
  props.display === 'rear' ? 'fixed' : '',
])

const containerStyle = computed(() => props.display === 'rear'
  ? {
      right: 'max(1.5rem, env(safe-area-inset-right, 0px))',
      top: 'max(1.5rem, env(safe-area-inset-top, 0px))',
    }
  : undefined)

function handleMove() {
  if (props.display === 'rear') {
    emit('moveToMain')
    return
  }
  emit('moveToRear')
}
</script>

<template>
  <div
    v-if="supported"
    :class="containerClasses"
    :style="containerStyle"
  >
    <Button
      :aria-label="label"
      :title="label"
      :disabled="!available || moving"
      class="rounded-xl!"
      shape="square"
      size="sm"
      variant="secondary-muted"
      @click="handleMove"
    >
      <div v-if="moving" class="i-svg-spinners:ring-resize size-5" />
      <div v-else class="i-solar:transfer-horizontal-bold-duotone size-5" />
    </Button>
  </div>
</template>
