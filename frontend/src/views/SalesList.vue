<script setup lang="ts">
import {ref} from 'vue'
import DeadlineSearch from '../components/DeadlineSearch.vue'
import SalesRecords from '../components/SalesRecords.vue'
const tab=ref('deadlines')
</script>
<template><h1 class="page-title">販売管理</h1><el-tabs v-model="tab"><el-tab-pane label="契約・商品（期限管理表）" name="deadlines"><DeadlineSearch v-if="tab==='deadlines'" mode="sales"/></el-tab-pane><el-tab-pane label="既存受注・販売" name="orders"><SalesRecords v-if="tab==='orders'"/></el-tab-pane></el-tabs></template>
