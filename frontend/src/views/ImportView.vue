<script setup lang="ts">
import {ref} from 'vue'
import DeadlineImportPanel from '../components/DeadlineImportPanel.vue'
import PackingImportPanel from '../components/PackingImportPanel.vue'
const type=ref('deadlines')
</script>
<template><h1 class="page-title">データ取込み</h1><el-tabs v-model="type"><el-tab-pane label="期限管理表（.xlsx / .xls）" name="deadlines"><DeadlineImportPanel v-if="type==='deadlines'"/></el-tab-pane><el-tab-pane label="パッキングリスト・CSV" name="packing"><PackingImportPanel v-if="type==='packing'"/></el-tab-pane></el-tabs></template>
