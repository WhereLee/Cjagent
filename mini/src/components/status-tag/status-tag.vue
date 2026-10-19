<script setup lang="ts">
import type { Tone } from '@/utils/dict'

defineProps<{
  text: string
  tone?: Tone
}>()
</script>

<template>
  <text class="chip" :class="`chip-${tone ?? 'info'}`">{{ text }}</text>
</template>
