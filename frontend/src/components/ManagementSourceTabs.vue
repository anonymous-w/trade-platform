<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {api,errorMessage} from '../api/http'
import PackingSourceSearch from './PackingSourceSearch.vue'
const props=defineProps<{kind:'exports'|'containers'}>()
const route=useRoute(),router=useRouter(),active=ref('packing'),ready=ref(false),sourceCount=ref<number|null>(null),recordCount=ref<number|null>(null)
onMounted(async()=>{
  try{
    const [source,records]=await Promise.all([api<any>('/packing-sources?size=1'),api<any>('/'+props.kind+'?size=1')])
    sourceCount.value=source.total;recordCount.value=records.total
    active.value=route.query.view==='packing'?'packing':route.query.view==='records'?'records':records.total>0?'records':'packing'
  }catch(e){ElMessage.error(errorMessage(e))}finally{ready.value=true}
})
function changed(value:string|number){void router.replace({path:route.path,query:{...route.query,view:String(value)}})}
</script>
<template>
  <div v-loading="!ready" class="management-tabs">
    <el-tabs v-if="ready" v-model="active" @tab-change="changed">
      <el-tab-pane :label="`Excel取込資料${sourceCount===null?'':`（${sourceCount}行）`}`" name="packing"><PackingSourceSearch v-if="active==='packing'" :container-mode="kind==='containers'"/></el-tab-pane>
      <el-tab-pane :label="`${kind==='exports'?'登録済み輸出':'登録済みコンテナ'}${recordCount===null?'':`（${recordCount}件）`}`" name="records"><slot v-if="active==='records'"/></el-tab-pane>
    </el-tabs>
  </div>
</template>
<style scoped>.management-tabs{min-height:180px}:deep(.el-tabs__item){font-size:15px;font-weight:600}:deep(.el-tabs__header){margin-bottom:20px}</style>
