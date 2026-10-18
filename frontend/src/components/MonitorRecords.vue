<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {useRouter} from 'vue-router'
import {api,errorMessage} from '../api/http'
import {ElMessage} from 'element-plus'
const router=useRouter()
const rows=ref<any[]>([]),loading=ref(false)
async function load(){loading.value=true;try{rows.value=await api<any[]>('/records/monitoring')}catch(e){ElMessage.error(errorMessage(e))}finally{loading.value=false}}
onMounted(load)
</script>
<template>

  <div class="banner">参考データ表示。監視の対象・周期・法令上の義務はまだ業務確認中です。SYN26 の記録は架空のテストデータです。</div>
  <section class="panel">
    <header class="panel-heading">監視記録（参考一覧）</header>
    <div class="panel-body">
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column prop="export_no" label="輸出No." min-width="160"/>
        <el-table-column prop="model_no" label="型番" min-width="120"/>
        <el-table-column prop="monitor_start_date" label="開始日" min-width="130"/>
        <el-table-column prop="monitor_end_date" label="終了日" min-width="130"/>
        <el-table-column prop="monitor_status" label="状態" min-width="135"/>
        <el-table-column prop="next_check_date" label="次回予定" min-width="130"/>
        <el-table-column label="操作" width="95"><template #default="s">
          <el-button size="small" @click="router.push('/exports/'+s.row.export_id)">詳細</el-button>
        </template></el-table-column>
      </el-table>
      <el-empty v-if="!loading&&!rows.length" description="データがありません"/>
      <p class="data-note">これは表示確認用の参照ページであり、実際の監視義務や点検実績を自動判定しません。</p>
    </div>
  </section>
</template>
