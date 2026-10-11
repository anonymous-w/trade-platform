<script setup lang="ts">
import {ref,onMounted,computed} from 'vue';import {useRoute,useRouter} from 'vue-router';import {ElMessage} from 'element-plus';import {api,download,errorMessage} from '../api/http'
const router=useRouter(),route=useRoute(),sales=computed(()=>route.path.startsWith('/sales/'));const id=String(route.params.id)
const docs=ref<any[]>([]),picked=ref<number|null>(null),loading=ref(true),frame=ref<HTMLIFrameElement|null>(null)
const back=computed(()=>sales.value?'/sales/'+id:'/exports/'+id)
const current=computed(()=>docs.value.find(d=>d.document_id===picked.value))
async function load(){loading.value=true;try{const data=await api<any>((sales.value?'/sales/':'/exports/')+id);docs.value=data.documents;picked.value=docs.value[0]?.document_id??null}catch(e){ElMessage.error(errorMessage(e))}finally{loading.value=false}}
async function save(){if(!current.value)return;try{await download('/documents/'+current.value.document_id+'/download',current.value.file_name)}catch(e){ElMessage.error(errorMessage(e))}}
function printDocument(){try{frame.value?.contentWindow?.focus();frame.value?.contentWindow?.print()}catch{ElMessage.warning('PDFをダウンロードして印刷してください')}}
onMounted(load)
</script>
<template><h1 class="page-title">{{sales?'帳票プレビュー':'原票プレビュー'}}</h1><div class="toolbar"><el-button @click="router.push(back)">← 詳細に戻る</el-button><div class="actions"><el-select v-model="picked" placeholder="ファイル選択" style="width:320px" :disabled="!docs.length"><el-option v-for="d in docs" :key="d.document_id" :label="d.document_type+' : '+d.file_name" :value="d.document_id"/></el-select><el-button type="primary" :disabled="!picked" @click="save">ダウンロード</el-button><el-button :disabled="!picked" @click="printDocument">印刷</el-button></div></div><div v-loading="loading" class="panel panel-body"><iframe v-if="picked" :key="picked" ref="frame" class="pdf-frame" :src="'/api/documents/'+picked+'/content'" title="PDF原票プレビュー"/><el-empty v-else description="関連PDFがありません。データ取込みから追加してください。"/></div></template>
