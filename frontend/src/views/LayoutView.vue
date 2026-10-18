<script setup lang="ts">
import NotificationCenter from '../components/NotificationCenter.vue'
import {ref,computed,watch} from 'vue';import {useRoute,useRouter} from 'vue-router';import {ElMessage} from 'element-plus';import {user,logout,errorMessage} from '../api/http'
const router=useRouter(),route=useRoute(),signingOut=ref(false);const name=computed(()=>user.value?.username||''),role=computed(()=>user.value?.roles.includes('ROLE_ADMIN')?'管理者':'閲覧ユーザー')
const main=ref<{$el:HTMLElement}|null>(null)
watch(()=>route.path,()=>{main.value?.$el.scrollTo({top:0,left:0})},{flush:'post'})
const menu=[['/exports','輸出管理'],['/containers','コンテナ実績'],['/sales','販売管理'],['/import','データ取込み'],['/monitor','5年間監視']]
function selected(){const path=route.path;if(path.startsWith('/exports'))return '/exports';if(path.startsWith('/sales'))return '/sales';return menu.find(x=>path.startsWith(x[0]))?.[0]||'/exports'}
async function signout(){if(signingOut.value)return;signingOut.value=true;try{await logout();await router.replace('/login')}catch(e){ElMessage.error(errorMessage(e))}finally{signingOut.value=false}}
</script>
<template>
  <el-container class="shell">
    <el-aside width="226px" class="sidebar">
      <div class="brand">データ管理<br/>プラットフォーム</div>
      <el-menu :default-active="selected()" router class="main-menu">
        <el-menu-item v-for="m in menu" :key="m[0]" :index="m[0]">{{m[1]}}</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container class="workspace">
      <el-header class="topbar">
        <span class="top-label">販売・輸出管理システム</span>
        <div class="user-actions">
          <NotificationCenter/>
          <span class="user-label"><span class="user-name" :title="name">{{name}}</span><small>{{role}}</small></span>
          <el-button class="logout-button" :loading="signingOut" @click="signout">ログアウト</el-button>
        </div>
      </el-header>
      <el-main ref="main" class="content"><router-view :key="route.path"/></el-main>
    </el-container>
  </el-container>
</template>
