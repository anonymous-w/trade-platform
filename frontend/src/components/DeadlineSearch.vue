<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {api,query,errorMessage} from '../api/http'
const props=defineProps<{mode:'sales'|'monitor'}>(),router=useRouter()
const empty=()=>({contract:'',product:'',model:'',permit:'',person:'',dateType:props.mode==='monitor'?'validUntil':'permitDate',dateFrom:'',dateTo:''})
const form=ref(empty()),rows=ref<any[]>([]),total=ref(0),page=ref(1),busy=ref(false)
let applied={...form.value},seq=0
async function load(){const request=++seq;busy.value=true;try{const d=await api<any>('/deadlines?'+query({...applied,page:page.value-1}));if(request===seq){rows.value=d.rows;total.value=d.total}}catch(e){if(request===seq){rows.value=[];total.value=0;ElMessage.error(errorMessage(e))}}finally{if(request===seq)busy.value=false}}
function search(){applied={...form.value};page.value=1;void load()}
function clear(){form.value=empty();search()}
onMounted(load)
</script>
<template>
<div class="banner">期限管理表を一度取込むと、販売管理と5年間監視の両方で同じ記録を検索できます。監視状況・次回確認日は業務ルール確認待ちです。</div>
<section class="panel"><header class="panel-heading">検索条件</header><div class="panel-body"><el-form label-position="top" @submit.prevent="search">
<div class="deadline-filters"><el-form-item label="契約番号"><el-input v-model="form.contract" clearable/></el-form-item>
<template v-if="mode==='sales'"><el-form-item label="商品名"><el-input v-model="form.product" clearable/></el-form-item><el-form-item label="型番"><el-input v-model="form.model" clearable/></el-form-item></template>
<template v-else><el-form-item label="輸出許可番号"><el-input v-model="form.permit" clearable/></el-form-item><el-form-item label="担当者"><el-input v-model="form.person" clearable/></el-form-item></template>
<el-form-item label="日付種類"><el-select v-model="form.dateType" :disabled="mode==='monitor'"><el-option label="経済産業省輸出許可日" value="permitDate"/><el-option label="有効期限" value="validUntil"/><el-option label="貨物B/L日付" value="blDate"/></el-select></el-form-item>
<el-form-item label="開始日"><el-date-picker v-model="form.dateFrom" type="date" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item><el-form-item label="終了日"><el-date-picker v-model="form.dateTo" type="date" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item></div>
<el-button type="primary" native-type="submit" :loading="busy">検索</el-button><el-button @click="clear">クリア</el-button></el-form></div></section>
<section class="panel"><header class="panel-heading">検索結果（{{total}} 件）</header><div class="panel-body"><el-table :data="rows" v-loading="busy" border stripe row-key="permit_id">
<el-table-column prop="contract_no" label="契約番号" min-width="120"/>
<el-table-column v-if="mode==='sales'" prop="product_name" label="商品名" min-width="230" show-overflow-tooltip/><el-table-column v-if="mode==='sales'" prop="model_no" label="型番" min-width="180"/>
<el-table-column v-if="mode==='monitor'" prop="export_permit_no" label="輸出許可番号" min-width="180"/><el-table-column v-if="mode==='monitor'" prop="valid_until" label="有効期限" min-width="125"/><el-table-column v-if="mode==='monitor'" label="次回確認日" min-width="120"><template #default>要確認</template></el-table-column><el-table-column v-if="mode==='monitor'" prop="person_in_charge" label="担当者"/>
<el-table-column label="監視状況" width="110"><template #default>要確認</template></el-table-column><el-table-column label="操作" width="90"><template #default="s"><el-button size="small" @click="router.push(mode==='sales'?'/sales/contracts/'+s.row.permit_id:'/monitor/'+s.row.permit_id)">詳細</el-button></template></el-table-column></el-table>
<el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total,prev,pager,next" @current-change="load" style="margin-top:16px;justify-content:flex-end"/></div></section>
</template>
<style scoped>.deadline-filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 16px}@media(max-width:900px){.deadline-filters{grid-template-columns:1fr 1fr}}</style>
