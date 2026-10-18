<script setup lang="ts">
import {ref,onMounted,onBeforeUnmount} from 'vue'
import {api,errorMessage,user} from '../api/http'
type Notice={id:number;type:string;message:string;createdAt:string;readAt:string|null}
const opened=ref(false),unread=ref(0),rows=ref<Notice[]>([]),more=ref(false),loading=ref(false),saving=ref(false),error=ref('')
const labels:Record<string,string>={FILE_UPLOAD:'ファイルアップロード',DATA_IMPORT:'データ取込み',DATA_UPDATE:'データ更新',DATA_DELETE:'データ削除',OPERATION_FAILED:'操作失敗'}
let timer:ReturnType<typeof setInterval>|undefined,disposed=false
async function refresh(append=false){
  if(loading.value||disposed||!user.value)return
  loading.value=true;error.value=''
  try{
    const before=append&&rows.value.length?`&before=${rows.value[rows.value.length-1].id}`:''
    const data=await api<{rows:Notice[];hasMore:boolean;unreadCount:number}>('/notifications?size=20'+before)
    if(disposed)return
    rows.value=append?[...rows.value,...data.rows]:data.rows;more.value=data.hasMore;unread.value=data.unreadCount
  }catch(e){if(!disposed)error.value=errorMessage(e)}finally{loading.value=false}
}
async function poll(){
  if(disposed||!user.value||document.hidden||loading.value||saving.value)return
  try{
    const data=await api<{unreadCount:number}>('/notifications/unread-count')
    if(disposed)return
    const changed=unread.value!==data.unreadCount;unread.value=data.unreadCount
    if(opened.value&&changed)await refresh()
  }catch(e){if(opened.value&&!disposed)error.value=errorMessage(e)}
}
async function read(item?:Notice){
  if(saving.value||loading.value||(item?.readAt))return
  saving.value=true;error.value=''
  try{
    await api(item?`/notifications/${item.id}/read`:'/notifications/read-all',{method:'POST'})
    if(disposed)return
    const now=new Date().toISOString()
    rows.value=rows.value.map(row=>!item||row.id===item.id?{...row,readAt:row.readAt||now}:row)
    unread.value=(await api<{unreadCount:number}>('/notifications/unread-count')).unreadCount
  }catch(e){if(!disposed)error.value=errorMessage(e)}finally{saving.value=false}
}
function visible(){if(!document.hidden)void poll()}
function date(value:string){return new Date(value).toLocaleString('ja-JP',{year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'})}
onMounted(()=>{void poll();timer=setInterval(poll,30000);document.addEventListener('visibilitychange',visible);window.addEventListener('business-operation-complete',poll)})
onBeforeUnmount(()=>{disposed=true;clearInterval(timer);document.removeEventListener('visibilitychange',visible);window.removeEventListener('business-operation-complete',poll)})
</script>
<template>
  <el-popover v-model:visible="opened" trigger="click" placement="bottom-end" :width="380" popper-class="notification-popover" @show="refresh()">
    <template #reference>
      <el-button class="notification-bell" :aria-label="`通知：未読 ${unread} 件`" :aria-expanded="opened" aria-haspopup="dialog">
        <el-badge :value="unread" :max="99" :hidden="unread===0">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9Z"/><path d="M10 21h4"/></svg>
        </el-badge>
      </el-button>
    </template>
    <section aria-label="通知センター" @keydown.esc="opened=false">
      <div class="notification-heading"><strong>通知</strong><el-button link type="primary" :disabled="!unread||saving||loading" @click="read()">すべて既読</el-button></div>
      <p class="notification-help">自分の操作通知 · 30秒ごとに更新</p>
      <div v-if="error" role="alert" class="notification-error">{{error}} <el-button link @click="refresh()">再試行</el-button></div>
      <div class="notification-list" :aria-busy="loading">
        <p v-if="!rows.length&&!error" class="notification-empty">{{loading?'読み込み中…':'通知はありません（暂无通知）'}}</p>
        <button v-for="item in rows" :key="item.id" type="button" class="notification-item" :class="{unread:!item.readAt}" :disabled="saving||loading" @click="read(item)">
          <span class="notification-meta"><strong>{{labels[item.type]||item.type}}</strong><span>{{item.readAt?'既読':'未読'}}</span></span>
          <span class="notification-message">{{item.message}}</span>
          <time :datetime="item.createdAt">{{date(item.createdAt)}}</time>
        </button>
        <el-button v-if="more" class="notification-more" :loading="loading" :disabled="saving" @click="refresh(true)">以前の通知を表示</el-button>
      </div>
    </section>
  </el-popover>
</template>
<style scoped>
.notification-bell{width:40px;padding:8px;color:#244563}.notification-bell svg{display:block}.notification-heading,.notification-meta{display:flex;align-items:center;justify-content:space-between;gap:12px}.notification-heading{font-size:16px}.notification-help{font-size:12px;color:#64748b;margin:8px 0 12px}.notification-list{max-height:min(480px,65vh);overflow-y:auto}.notification-item{display:block;width:100%;text-align:left;background:#fff;border:0;border-bottom:1px solid #e8edf3;padding:12px;cursor:pointer;color:#334155;font:inherit}.notification-item:hover,.notification-item:focus-visible{background:#edf4fc}.notification-item.unread{background:#f0f7ff;border-left:3px solid #409eff;padding-left:9px}.notification-meta{font-size:12px}.notification-meta>span{color:#64748b;white-space:nowrap}.notification-message{display:block;margin:7px 0;white-space:normal;overflow-wrap:anywhere;line-height:1.6}.notification-item time{font-size:12px;color:#64748b}.notification-empty{padding:24px 0;text-align:center;color:#64748b}.notification-error{color:#b42318;line-height:1.6}.notification-more{display:block;margin:12px auto}
</style>
<style>.notification-popover{max-width:calc(100vw - 24px);box-sizing:border-box}</style>
