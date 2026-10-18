<script setup lang="ts">
import {ref,onMounted,computed} from 'vue'
import {ElMessage} from 'element-plus'
import {useRouter} from 'vue-router'
import {api,errorMessage,user,query} from '../api/http'
import PackingSourceTable from '../components/PackingSourceTable.vue'
const router=useRouter()
const canUpload=computed(()=>user.value?.roles.includes('ROLE_ADMIN'))
const xlsx=ref<File|null>(null),csv=ref<File|null>(null),busy=ref(false),preview=ref<any>(null),acknowledged=ref(false),result=ref(''),previewTab=ref('rows')
const batches=ref<any[]>([]),batchId=ref<number>(),rows=ref<any[]>([]),total=ref(0),page=ref(1),keyword=ref(''),appliedKeyword=ref(''),reviewOnly=ref(false),rowsLoading=ref(false)
const recent=ref<any[]>([]),historyLoading=ref(false)
let sequence=0
function fileChanged(e:Event,format:'xlsx'|'csv'){
  const input=e.target as HTMLInputElement;let f=input.files?.[0]||null
  if(format==='xlsx'){preview.value=null;acknowledged.value=false}result.value=''
  if(f&&(!f.name.toLowerCase().endsWith('.'+format)||!f.size||f.size>(format==='xlsx'?10:2)*1024*1024)){
    ElMessage.warning(format==='xlsx'?'10MB以下の .xlsx を選択してください':'2MB以下のUTF-8 CSVを選択してください');input.value='';f=null
  }
  if(format==='xlsx')xlsx.value=f;else csv.value=f
}
async function previewExcel(){
  if(!xlsx.value||busy.value)return
  busy.value=true;preview.value=null;acknowledged.value=false
  try{const form=new FormData();form.append('file',xlsx.value);preview.value=await api<any>('/imports/packing/preview',{method:'POST',body:form});previewTab.value='rows'}
  catch(e){ElMessage.error(errorMessage(e))}finally{busy.value=false}
}
async function saveExcel(){
  if(!xlsx.value||!preview.value||!acknowledged.value||busy.value||preview.value.duplicates.length)return
  busy.value=true
  try{
    const form=new FormData();form.append('file',xlsx.value);form.append('previewHash',preview.value.fileHash);form.append('acknowledgeReview','true')
    const saved=await api<any>('/imports/packing',{method:'POST',body:form})
    result.value=`原資料 ${saved.rowCount} 行・契約番号 ${saved.contractCount} 件を保存しました。要確認 ${saved.reviewRows} 行。正式な輸出レコードは作成していません。`
    preview.value=null;acknowledged.value=false;ElMessage.success('原資料を保存しました')
    await fetchBatches();batchId.value=saved.batchId;page.value=1;keyword.value='';appliedKeyword.value='';reviewOnly.value=false;await fetchRows()
  }catch(e){ElMessage.error(errorMessage(e))}finally{busy.value=false}
}
async function fetchBatches(){try{batches.value=await api<any[]>('/imports/packing/batches')}catch(e){ElMessage.error(errorMessage(e))}}
async function fetchRows(){
  const seq=++sequence;rows.value=[];total.value=0
  if(!batchId.value){rowsLoading.value=false;return}rowsLoading.value=true
  try{const data=await api<any>(`/imports/packing/batches/${batchId.value}/rows?`+query({keyword:appliedKeyword.value,reviewOnly:reviewOnly.value,page:page.value-1,size:20}));if(seq===sequence){rows.value=data.rows;total.value=data.total}}
  catch(e){if(seq===sequence)ElMessage.error(errorMessage(e))}finally{if(seq===sequence)rowsLoading.value=false}
}
function search(){appliedKeyword.value=keyword.value;page.value=1;void fetchRows()}
function selectBatch(){keyword.value='';appliedKeyword.value='';reviewOnly.value=false;page.value=1;void fetchRows()}
async function sendCsv(){
  if(!csv.value||busy.value)return;busy.value=true
  try{const form=new FormData();form.append('file',csv.value);const data=await api<any>('/imports/csv',{method:'POST',body:form});result.value=`輸出 ${data.createdExports} 件・包装明細 ${data.packingRows} 行をDRAFTで登録しました。元資料と照合してください。`;ElMessage.success('CSV取込み完了')}
  catch(e){ElMessage.error(errorMessage(e))}finally{busy.value=false}
}
async function fetchDocuments(){historyLoading.value=true;try{recent.value=await api<any[]>('/records/documents')}catch(e){ElMessage.error(errorMessage(e))}finally{historyLoading.value=false}}
onMounted(async()=>{await Promise.all([fetchBatches(),fetchDocuments()]);if(batches.value.length){batchId.value=batches.value[0].batch_id;await fetchRows()}})
</script>
<template>

  <div class="banner">パッキングリスト集計Excelを原資料として保存・検索できます。顧客・インボイス・日付などが不足するため、原資料として管理します。「輸出管理」「コンテナ実績」のExcel取込資料からも検索できます。</div>
  <el-alert v-if="!canUpload" title="閲覧ユーザーは保存済み資料を確認できます。取込みには管理者権限が必要です。" type="info" :closable="false"/>
  <div class="import-actions" style="margin-bottom:18px"><el-button type="primary" plain @click="router.push('/exports?view=packing')">輸出管理で取込資料を検索</el-button><el-button @click="router.push('/containers?view=packing')">コンテナ記載を検索</el-button></div>
  <section v-if="canUpload" class="panel">
    <header class="panel-heading">パッキングリスト集計（Excel）</header>
    <div class="panel-body">
      <p>① Excelを選択 → ② プレビューで確認 → ③ 原資料として保存</p>
      <input aria-label="パッキングリストExcel" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" :disabled="busy" @change="fileChanged($event,'xlsx')"/>
      <div class="import-actions"><el-button type="primary" :loading="busy" :disabled="!xlsx" @click="previewExcel">プレビュー</el-button></div>
      <p class="data-note">.xlsx・10MB以下・明細10,000行まで。対象シート1枚、3〜4行目の見出し、B:P列に対応。数式はExcelで保存された値を読み、外部リンクは読み込みません。</p>
      <template v-if="preview">
        <div class="preview-summary"><strong>{{preview.rowCount}} 行</strong><span>契約番号 {{preview.contractCount}} 件</span><span>要確認 {{preview.reviewRows}} 行</span><span>シート：{{preview.sheetName}}</span></div>
        <el-alert v-if="preview.duplicates.length" title="同じ内容が既に保存されています。下の取込み履歴から確認してください。" type="warning" :closable="false"/>
        <el-alert type="info" :closable="false" title="結合セル・列の変更を読み取り、疑点は「要確認」に表示します。寸法の単位、数量と体積の関係、コンテナの表記は元資料との照合が必要です。"/>
        <el-tabs v-model="previewTab">
          <el-tab-pane label="先頭100行" name="rows"><PackingSourceTable :rows="preview.rows"/></el-tab-pane>
          <el-tab-pane label="要確認の先頭100行" name="review"><PackingSourceTable :rows="preview.reviewSamples"/></el-tab-pane>
        </el-tabs>
        <p class="data-note">プレビューは各100行まで。保存対象は全 {{preview.rowCount}} 行です。保存後は全行を検索・ページ移動して確認できます。</p>
        <el-checkbox v-model="acknowledged" :disabled="busy||preview.duplicates.length>0" class="acknowledge">未確認・欠損項目を含む原資料として保存し、正式な輸出実績には計上しないことを確認しました。</el-checkbox>
        <div class="import-actions"><el-button type="primary" :loading="busy" :disabled="!acknowledged||preview.duplicates.length>0" @click="saveExcel">原資料として保存</el-button></div>
      </template>
    </div>
  </section>
  <el-alert v-if="result" :title="result" type="success" :closable="false" show-icon class="import-result"/>
  <section class="panel">
    <header class="panel-heading">保存済み包装資料（取込み履歴・最新50件）</header>
    <div class="panel-body">
      <el-empty v-if="!batches.length" description="まだExcel原資料は保存されていません"/>
      <template v-else>
        <el-select v-model="batchId" aria-label="取込み履歴" class="batch-select" @change="selectBatch"><el-option v-for="b in batches" :key="b.batch_id" :value="b.batch_id" :label="`${b.file_name} · ${b.row_count} 行 · ${String(b.created_at).slice(0,10)} · #${b.batch_id}`"/></el-select>
        <div class="import-filters"><el-input v-model="keyword" placeholder="契約番号・設備・型番・分解部名称" aria-label="包装資料検索" maxlength="200" clearable @keyup.enter="search"/><el-checkbox v-model="reviewOnly" @change="page=1;fetchRows()">要確認のみ</el-checkbox><el-button type="primary" @click="search">検索</el-button></div>
        <PackingSourceTable :rows="rows" :loading="rowsLoading"/>
        <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" class="import-pagination" @current-change="fetchRows"/>
      </template>
    </div>
  </section>
  <section v-if="canUpload" class="panel"><div class="panel-body"><el-collapse><el-collapse-item title="従来のCSVテンプレートで取り込む（限定形式）" name="csv"><p><a href="/samples/export-import-template.csv" download>CSVテンプレートをダウンロード</a></p><input type="file" accept=".csv,text/csv" :disabled="busy" @change="fileChanged($event,'csv')"/><div class="import-actions"><el-button :loading="busy" :disabled="!csv" @click="sendCsv">CSVを取込む</el-button></div><p class="data-note">UTF-8 CSV・2MB以下・包装300行まで。1輸出につき1設備・1インボイス・1箱型設定の限定形式です。Excel原資料とは別にDRAFT輸出を作成します。</p></el-collapse-item></el-collapse></div></section>
  <section class="panel"><header class="panel-heading">登録済み書類（最新80件）</header><div class="panel-body"><p class="data-note">PDFの新規アップロードは現在停止しています。登録済み書類は引き続き閲覧できます。</p><el-table :data="recent" v-loading="historyLoading" stripe border><el-table-column prop="file_name" label="ファイル名" min-width="220" show-overflow-tooltip/><el-table-column prop="document_type" label="種別" width="125"/><el-table-column label="関連レコード" width="135"><template #default="s"><el-button size="small" @click="router.push(s.row.export_id?'/exports/'+s.row.export_id+'/documents':'/sales/'+s.row.order_id+'/documents')">原票を見る</el-button></template></el-table-column></el-table></div></section>
</template>
<style scoped>
.import-actions{margin-top:16px}.preview-summary{display:flex;gap:18px;flex-wrap:wrap;margin:22px 0 14px}.import-filters{display:flex;align-items:center;gap:12px;margin:18px 0;flex-wrap:wrap}.import-filters .el-input{flex:1;min-width:220px}.batch-select{width:100%;max-width:750px}.import-pagination{margin-top:18px;max-width:100%;overflow:auto}.import-result{margin-bottom:18px}.acknowledge{height:auto;align-items:flex-start;white-space:normal;margin-top:12px}:deep(.acknowledge .el-checkbox__label){white-space:normal;line-height:1.6}:deep(.acknowledge .el-checkbox__input){margin-top:4px}
</style>
