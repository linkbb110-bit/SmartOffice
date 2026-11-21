<template><ElAlert v-if="message" type="error" :closable="false" show-icon class="load-error"><template #title>{{ message }}</template><ElButton link type="primary" @click="$emit('retry')">重试</ElButton></ElAlert></template>
<script setup lang="ts">defineProps<{ message: string }>(); defineEmits<{ retry: [] }>();</script>
