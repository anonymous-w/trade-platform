<script setup lang="ts">
import {ref} from 'vue'
defineProps<{rows:any[],loading?:boolean,showSource?:boolean,containerFirst?:boolean}>()
const selected=ref<any>(null)
const display=(value:any)=>value===null||value===undefined||value===''?'—':typeof value==='number'?value.toLocaleString('ja-JP',{maximumSignificantDigits:15,useGrouping:false}):value
</script>
<template>
  <el-table :data="rows" v-loading="loading" stripe border max-height="520" :row-key="(row:any)=>row.sourceId??row.sourceRow">
    <el-table-column prop="sourceRow" label="Excel行" width="80" fixed/>
    <el-table-column label="確認" width="100" fixed><template #default="s"><el-button size="small" :type="s.row.warnings.length?'warning':'default'" @click="selected=s.row">{{s.row.warnings.length?'要確認':'原文'}}</el-button></template></el-table-column>
    <el-table-column prop="contractNo" label="契約番号" min-width="150" show-overflow-tooltip/>
    <el-table-column v-if="containerFirst" prop="containerText" label="コンテナ記載（原文）" min-width="190" show-overflow-tooltip/>
    <el-table-column prop="equipmentName" label="設備名称" min-width="200" show-overflow-tooltip/>
    <el-table-column prop="modelNo" label="型番" min-width="170" show-overflow-tooltip/>
    <el-table-column prop="partName" label="分解部名称" min-width="180" show-overflow-tooltip/>
    <el-table-column prop="partCode" label="包装番号" width="105"/>
    <el-table-column label="数量" width="125"><template #default="s">{{display(s.row.quantity)}} {{s.row.quantity!==null?s.row.quantityUnit:''}}</template></el-table-column>
    <el-table-column v-for="c in [{p:'widthMm',l:'W (mm)'},{p:'depthMm',l:'D (mm)'},{p:'heightMm',l:'H (mm)'},{p:'weightKg',l:'重量 (kg)'},{p:'volumeM3',l:'記載体積 (m³)'}]" :key="c.p" :label="c.l" min-width="125" align="right"><template #default="s">{{display(s.row[c.p])}}</template></el-table-column>
    <el-table-column v-if="!containerFirst" prop="containerText" label="コンテナ原文" min-width="160" show-overflow-tooltip/>
    <el-table-column prop="departurePort" label="出発港" min-width="130" show-overflow-tooltip/>
    <el-table-column prop="destinationPort" label="目的港" min-width="150" show-overflow-tooltip/>
    <el-table-column v-if="showSource" prop="fileName" label="取込元ファイル" min-width="200" show-overflow-tooltip/>
  </el-table>
  <el-dialog :model-value="!!selected" title="Excel原文・確認事項" width="min(900px, 94vw)" @close="selected=null">
    <template v-if="selected">
      <p v-if="selected.fileName">取込元：{{selected.fileName}} · 批次 #{{selected.batchId}}</p>
      <p>Excel {{selected.sourceRow}} 行 · {{selected.contractNo||'契約番号未記入'}}</p>
      <el-alert type="info" :closable="false" title="元資料の記載内容です。未確認のため、正式な出荷・会計集計には含めません。"/>
      <ul v-if="selected.warnings.length" class="source-warnings"><li v-for="(w,i) in selected.warnings" :key="i">{{w}}</li></ul>
      <p v-else>自動チェックで個別の疑点は検出されていません。内容の正確性を保証するものではありません。</p>
      <el-table :data="Object.entries(selected.rawCells).map(([column,value]:any)=>({column,...value}))" border max-height="420">
        <el-table-column prop="column" label="列" width="55"/>
        <el-table-column prop="value" label="セルの保存値" min-width="240"><template #default="s"><span class="source-cell">{{s.row.value||'（空欄）'}}</span></template></el-table-column>
        <el-table-column prop="mergedRange" label="結合範囲" width="140"/>
        <el-table-column prop="formula" label="数式（再計算なし）" min-width="230" show-overflow-tooltip/>
      </el-table>
      <p class="data-note">結合セルの値は結合範囲の左上にあります。設備等の文字項目は結合範囲内だけ継承し、数量・寸法・重量・体積は複製しません。</p>
    </template>
  </el-dialog>
</template>
<style scoped>.source-cell{white-space:pre-wrap;word-break:break-word}.source-warnings{line-height:1.8;color:#8c5600}</style>
