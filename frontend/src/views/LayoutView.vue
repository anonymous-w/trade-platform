<script setup lang="ts">
import {ref,onMounted} from 'vue';import {useRoute,useRouter} from 'vue-router';import {ElMessage} from 'element-plus';import {currentUser,logout,errorMessage} from '../api/http'
const router=useRouter(),route=useRoute(),name=ref(''),role=ref('');onMounted(async()=>{try{const me=await currentUser();name.value=me.username;role.value=me.roles.join(',')}catch{}})
const menu=[['/exports','輸出管理'],['/containers','コンテナ実績'],['/sales','販売管理'],['/import','データ取込み'],['/monitor','5年間監視']]
function selected(){const path=route.path;if(path.startsWith('/exports'))return '/exports';if(path.startsWith('/sales'))return '/sales';return menu.find(x=>path.startsWith(x[0]))?.[0]||'/exports'}
async function signout(){try{await logout();router.replace('/login')}catch(e){ElMessage.error(errorMessage(e))}}
</script>
<template><el-container class="shell"><el-aside width="226px" class="sidebar"><div class="brand">データ管理<br/>プラットフォーム</div><el-menu :default-active="selected()" :key="selected()" router class="main-menu"><el-menu-item v-for="m in menu" :key="m[0]" :index="m[0]">{{m[1]}}</el-menu-item></el-menu><div class="side-bottom"><el-button text class="logout-link" @click="signout">↪　ログアウト</el-button></div></el-aside><el-container><el-header class="topbar"><span class="top-label">販売・輸出管理システム</span><span class="user-label">{{name}} <small>{{role}}</small></span></el-header><el-main class="content"><router-view/></el-main></el-container></el-container></template>
