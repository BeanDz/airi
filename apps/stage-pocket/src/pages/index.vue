<script setup lang="ts">
import type { ChatProvider } from '@xsai-ext/providers/utils'

import Header from '@proj-airi/stage-layouts/components/Layouts/Header.vue'
import InteractiveArea from '@proj-airi/stage-layouts/components/Layouts/InteractiveArea.vue'
import MobileHeader from '@proj-airi/stage-layouts/components/Layouts/MobileHeader.vue'
import MobileInteractiveArea from '@proj-airi/stage-layouts/components/Layouts/MobileInteractiveArea.vue'
import workletUrl from '@proj-airi/stage-ui/workers/vad/process.worklet?worker&url'

import { BackgroundProvider } from '@proj-airi/stage-layouts/components/Backgrounds'
import { useBackgroundThemeColor } from '@proj-airi/stage-layouts/composables/theme-color'
import { useBackgroundStore } from '@proj-airi/stage-layouts/stores/background'
import { IS_DEV } from '@proj-airi/stage-shared'
import { ViewControlSlider, WidgetStage } from '@proj-airi/stage-ui/components/scenes'
import { useAudioRecorder } from '@proj-airi/stage-ui/composables/audio/audio-recorder'
import { useVAD } from '@proj-airi/stage-ui/stores/ai/models/vad'
import { useChatOrchestratorStore } from '@proj-airi/stage-ui/stores/chat'
import { useConsciousnessStore } from '@proj-airi/stage-ui/stores/modules/consciousness'
import { useHearingSpeechInputPipeline } from '@proj-airi/stage-ui/stores/modules/hearing'
import { useProvidersStore } from '@proj-airi/stage-ui/stores/providers'
import { useSettings, useSettingsAudioDevice } from '@proj-airi/stage-ui/stores/settings'
import { breakpointsTailwind, useBreakpoints, useMouse } from '@vueuse/core'
import { storeToRefs } from 'pinia'
import { computed, onMounted, onUnmounted, shallowRef, useTemplateRef, watch } from 'vue'
import { toast } from 'vue-sonner'

import RearScreenControls from '../components/rear-screen/RearScreenControls.vue'
import RearScreenStage from '../components/rear-screen/RearScreenStage.vue'
import WebSocketStatusButton from '../components/websocket-status-button.vue'

import { useRearScreen } from '../composables/useRearScreen'

const paused = shallowRef(false)

function handleSettingsOpen(open: boolean) {
  paused.value = open
}

const positionCursor = useMouse()
const breakpoints = useBreakpoints(breakpointsTailwind)
const isMobile = breakpoints.smaller('md')
const {
  supported: rearScreenSupported,
  available: rearScreenAvailable,
  display: rearScreenDisplay,
  isRear,
  moving: rearScreenMoving,
  error: rearScreenError,
  moveToRear,
  moveToMain,
} = useRearScreen()

watch(rearScreenError, (message) => {
  if (message) {
    toast.error(message)
  }
})

const backgroundStore = useBackgroundStore()
const { selectedOption, sampledColor } = storeToRefs(backgroundStore)
const backgroundSurface = useTemplateRef<InstanceType<typeof BackgroundProvider>>('backgroundSurface')
const { stageModelRenderer } = storeToRefs(useSettings())

const { syncBackgroundTheme } = useBackgroundThemeColor({ backgroundSurface, selectedOption, sampledColor })
onMounted(() => syncBackgroundTheme())

// Audio + transcription pipeline (mirrors stage-tamagotchi)
const settingsAudioDeviceStore = useSettingsAudioDevice()
const { stream, enabled } = storeToRefs(settingsAudioDeviceStore)
const { startRecord, stopRecord, onStopRecord } = useAudioRecorder(stream)
const hearingPipeline = useHearingSpeechInputPipeline()
const { transcribeForRecording, transcribeForMediaStream, stopStreamingTranscription } = hearingPipeline
const { supportsStreamInput } = storeToRefs(hearingPipeline)
const providersStore = useProvidersStore()
const consciousnessStore = useConsciousnessStore()
const { activeProvider: activeChatProvider, activeModel: activeChatModel } = storeToRefs(consciousnessStore)
const chatStore = useChatOrchestratorStore()

const shouldUseStreamInput = computed(() => supportsStreamInput.value && !!stream.value)

const {
  init: initVAD,
  dispose: disposeVAD,
  start: startVAD,
  loaded: vadLoaded,
} = useVAD(workletUrl, {
  threshold: shallowRef(0.6),
  onSpeechStart: () => handleSpeechStart(),
  onSpeechEnd: () => handleSpeechEnd(),
})

let stopOnStopRecord: (() => void) | undefined

async function startAudioInteraction() {
  try {
    await initVAD()
    if (stream.value)
      await startVAD(stream.value)

    // Hook once
    stopOnStopRecord = onStopRecord(async (recording) => {
      const text = await transcribeForRecording(recording)
      if (!text || !text.trim())
        return

      try {
        const provider = await providersStore.getProviderInstance(activeChatProvider.value)
        if (!provider || !activeChatModel.value)
          return

        await chatStore.ingest(text, { model: activeChatModel.value, chatProvider: provider as ChatProvider })
      }
      catch (err) {
        console.error('Failed to send chat from voice:', err)
      }
    })
  }
  catch (e) {
    console.error('Audio interaction init failed:', e)
  }
}

async function handleSpeechStart() {
  if (shouldUseStreamInput.value && stream.value) {
    // Use both callbacks to support incremental updates and final transcript replacement.
    // ChatArea uses only onSentenceEnd to avoid re-adding deleted text.
    await transcribeForMediaStream(stream.value, {
      onSentenceEnd: (delta) => {
        const finalText = delta
        if (!finalText || !finalText.trim()) {
          return
        }

        void (async () => {
          try {
            const provider = await providersStore.getProviderInstance(activeChatProvider.value)
            if (!provider || !activeChatModel.value)
              return

            await chatStore.ingest(finalText, { model: activeChatModel.value, chatProvider: provider as ChatProvider })
          }
          catch (err) {
            console.error('Failed to send chat from voice:', err)
          }
        })()
      },
    })
    return
  }

  startRecord()
}

async function handleSpeechEnd() {
  if (shouldUseStreamInput.value) {
    // Keep streaming session alive; idle timer in pipeline will handle teardown.
    return
  }

  stopRecord()
}

function stopAudioInteraction() {
  try {
    stopOnStopRecord?.()
    stopOnStopRecord = undefined
    // Stop any active streaming transcription sessions to prevent session leakage
    void stopStreamingTranscription(true)
    disposeVAD()
  }
  catch {}
}

watch(enabled, async (val) => {
  if (val) {
    await startAudioInteraction()
  }
  else {
    stopAudioInteraction()
  }
}, { immediate: true })

onUnmounted(() => {
  stopAudioInteraction()
})

watch([stream, () => vadLoaded.value], async ([s, loaded]) => {
  if (enabled.value && loaded && s) {
    try {
      await startVAD(s)
    }
    catch (e) {
      console.error('Failed to start VAD with stream:', e)
    }
  }
})
</script>

<template>
  <BackgroundProvider
    ref="backgroundSurface"
    class="widgets top-widgets"
    :background="selectedOption"
    :top-color="sampledColor"
  >
    <div flex="~ col" relative z-2 h-100dvh w-100vw of-hidden py-safe>
      <!-- header -->
      <div v-if="!isRear" :class="['px-0 py-1 md:px-3 md:py-3']" w-full gap-2>
        <Header class="hidden md:flex" />
        <MobileHeader class="flex md:hidden" />
      </div>
      <!-- page -->
      <div relative flex="~ 1 row gap-y-0 gap-x-2 <md:col" min-h-0>
        <RearScreenStage
          v-if="isRear"
          :cursor-position="{
            x: positionCursor.x.value,
            y: positionCursor.y.value,
          }"
          :paused="paused"
        />
        <template v-else>
          <div relative min-w="1/2" min-h-0 flex-1>
            <div
              absolute left-0 z-15 px-3
              :class="[
                stageModelRenderer === 'live2d' ? 'top-0 h-full py-[20vh]' : 'top-1/2 -translate-y-1/2',
              ]"
            >
              <ViewControlSlider />
            </div>
            <WidgetStage
              h-full w-full
              :enable-orbit-controls="!isMobile"
              :paused="paused"
              :focus-at="{
                x: positionCursor.x.value,
                y: positionCursor.y.value,
              }"
            />
          </div>
          <InteractiveArea v-if="!isMobile" h="85dvh" absolute right-4 flex flex-1 flex-col max-w="500px" min-w="30%" />
          <MobileInteractiveArea v-if="isMobile" @settings-open="handleSettingsOpen">
            <template #status>
              <WebSocketStatusButton v-if="IS_DEV" />
              <RearScreenControls
                :supported="rearScreenSupported"
                :available="rearScreenAvailable"
                :display="rearScreenDisplay"
                :moving="rearScreenMoving"
                @move-to-rear="moveToRear"
                @move-to-main="moveToMain"
              />
            </template>
          </MobileInteractiveArea>
        </template>
      </div>
      <div
        v-if="isRear || !isMobile"
        :class="!isRear ? 'fixed bottom-4 left-4 z-30' : undefined"
      >
        <RearScreenControls
          :supported="rearScreenSupported"
          :available="rearScreenAvailable"
          :display="rearScreenDisplay"
          :moving="rearScreenMoving"
          @move-to-rear="moveToRear"
          @move-to-main="moveToMain"
        />
      </div>
    </div>
  </BackgroundProvider>
</template>

<route lang="yaml">
name: IndexScenePage
meta:
  layout: stage
  stageTransition:
    name: bubble-wave-out
</route>
