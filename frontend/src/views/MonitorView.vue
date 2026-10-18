<script setup lang="ts">
import {ref} from 'vue'
import DeadlineSearch from '../components/DeadlineSearch.vue'
import MonitorRecords from '../components/MonitorRecords.vue'
const tab=ref('deadlines')
</script>
<template><h1 class="page-title">5年間監視</h1><el-tabs v-model="tab"><el-tab-pane label="期限管理表" name="deadlines"><DeadlineSearch v-if="tab==='deadlines'" mode="monitor"/></el-tab-pane><el-tab-pane label="既存輸出の監視記録" name="legacy"><MonitorRecords v-if="tab==='legacy'"/></el-tab-pane></el-tabs></template>
