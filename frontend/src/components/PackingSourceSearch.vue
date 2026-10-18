<script setup lang="ts">
import {onMounted,ref,reactive} from 'vue'
import {ElMessage} from 'element-plus'
import {api,query,errorMessage} from '../api/http'
import PackingSourceTable from './PackingSourceTable.vue'
defineProps<{containerMode?:boolean}>()
const form=reactive({keyword:'',model:'',container:'',departurePort:'',destinationPort:'',reviewOnly:false})
const rows=ref<any[]>([]),total=ref(0),page=ref(1),loading=ref(false)
let sequence=0,applied={...form}
async function load(){
  const request=++sequence;loading.value=true
  try{const data=await api<any>('/packing-sources?'+query({...applied,page:page.value-1,size:20}));if(request===sequence){rows.value=data.rows;total.value=data.total}}
  catch(e){if(request===sequence){rows.value=[];total.value=0;ElMessage.error(errorMessage(e))}}
  finally{if(request===sequence)loading.value=false}
}
function search(){applied={...form};page.value=1;void load()}
function reset(){Object.assign(form,{keyword:'',model:'',container:'',departurePort:'',destinationPort:'',reviewOnly:false});search()}
onMounted(load)
</script>
<template>
  <div class="banner">Excel取込資料を全ファイル横断で検索できます。原文・確認事項は「原文／要確認」から開けます。顧客・インボイス・出荷日が未登録でも検索できます。</div>
  <section class="panel"><header class="panel-heading">Excel取込資料の検索</header><div class="panel-body">
    <el-form label-position="top" @submit.prevent="search">
      <el-form-item label="キーワード（契約番号・設備・型番・分解部名称・コンテナ・港）"><el-input v-model="form.keyword" clearable maxlength="200" placeholder="契約番号 / NX76 / 40HQ / YOKOHAMA など"/></el-form-item>
      <el-row :gutter="16">
        <el-col :xs="24" :sm="12" :lg="6"><el-form-item label="型番"><el-input v-model="form.model" clearable maxlength="200"/></el-form-item></el-col>
        <el-col :xs="24" :sm="12" :lg="6"><el-form-item label="コンテナ（原文の部分一致）"><el-input v-model="form.container" clearable maxlength="200" placeholder="40HQ / 20FR"/></el-form-item></el-col>
        <el-col :xs="24" :sm="12" :lg="6"><el-form-item label="出発港"><el-input v-model="form.departurePort" clearable maxlength="200"/></el-form-item></el-col>
        <el-col :xs="24" :sm="12" :lg="6"><el-form-item label="目的港"><el-input v-model="form.destinationPort" clearable maxlength="200"/></el-form-item></el-col>
      </el-row>
      <div class="source-actions"><el-button type="primary" native-type="submit" :loading="loading">検索</el-button><el-button @click="reset">クリア</el-button><el-checkbox v-model="form.reviewOnly">要確認のみ</el-checkbox></div>
    </el-form>
  </div></section>
  <section class="panel"><header class="panel-heading">検索結果（包装明細 {{total}} 行） · Excel原資料／未確認</header><div class="panel-body">
    <p v-if="containerMode" class="data-note">コンテナ欄は契約の記載内容を包装行に表示しています。同じ記載の繰返しを箱数として合算しないでください。確認済みの数量は「登録済みコンテナ」から検索できます。</p>
    <p v-else class="data-note">正式な輸出レコードとは区別して表示しています。記載のない顧客・インボイス・日付は推測していません。</p>
    <PackingSourceTable :rows="rows" :loading="loading" :container-first="containerMode" show-source/>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev,pager,next,total" class="source-pagination" @current-change="load"/>
    <p v-if="!loading&&!total" class="data-note">条件をクリアして再検索できます。まだ資料がない場合は「データ取込み」でExcelを保存してください。</p>
  </div></section>
</template>
<style scoped>.source-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.source-pagination{margin-top:16px;max-width:100%;overflow:auto}</style>
