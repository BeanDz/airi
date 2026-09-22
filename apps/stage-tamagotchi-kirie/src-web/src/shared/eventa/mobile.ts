import { defineEventa } from '@moeru/eventa'

/** Navigation in the main Android renderer, without a native child window. */
export const mobileNavigate = defineEventa<{
  route: string
  /** Replaces the completed onboarding page instead of retaining it in history. */
  replace: boolean
}>('eventa:event:airi:mobile:navigate')
