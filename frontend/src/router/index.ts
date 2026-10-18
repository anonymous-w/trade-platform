import {createRouter,createWebHistory} from 'vue-router'
import {currentUser,ApiError} from '../api/http'
import LoginView from '../views/LoginView.vue'
import LayoutView from '../views/LayoutView.vue'
const ExportList=()=>import('../views/ExportList.vue')
const ExportDetail=()=>import('../views/ExportDetail.vue')
const DocumentPreview=()=>import('../views/DocumentPreview.vue')
const ContainerHistory=()=>import('../views/ContainerHistory.vue')
const SalesList=()=>import('../views/SalesList.vue')
const DeadlineDetail=()=>import('../views/DeadlineDetail.vue')
const SalesDetail=()=>import('../views/SalesDetail.vue')
const ImportView=()=>import('../views/ImportView.vue')
const MonitorView=()=>import('../views/MonitorView.vue')
const routes=[
  {path:'/login',component:LoginView,meta:{public:true}},
  {path:'/',component:LayoutView,children:[
    {path:'',redirect:'/exports'},
    {path:'exports',component:ExportList},
    {path:'exports/:id',component:ExportDetail},
    {path:'exports/:id/documents',component:DocumentPreview},
    {path:'containers',component:ContainerHistory},
    {path:'sales',component:SalesList},
    {path:'sales/contracts/:id',component:DeadlineDetail,props:{mode:'sales'}},
    {path:'sales/:id',component:SalesDetail},
    {path:'sales/:id/documents',component:DocumentPreview},
    {path:'import',component:ImportView},
    {path:'monitor',component:MonitorView},
    {path:'monitor/:id',component:DeadlineDetail,props:{mode:'monitor'}}
  ]},
  {path:'/:pathMatch(.*)*',redirect:'/exports'}
]
const router=createRouter({history:createWebHistory(),routes})
router.beforeEach(async to=>{
  if(to.meta.public)return true
  try{await currentUser();return true}catch(e){return {path:'/login',query:{redirect:to.fullPath,...(e instanceof ApiError&&e.status===401?{}:{unavailable:'1'})}}}
})
window.addEventListener('session-expired',()=>{if(router.currentRoute.value.path!=='/login')void router.replace({path:'/login',query:{redirect:router.currentRoute.value.fullPath,expired:'1'}})})
export default router
