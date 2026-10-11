import {createRouter,createWebHistory} from 'vue-router'
import {currentUser} from '../api/http'
import LoginView from '../views/LoginView.vue'
import LayoutView from '../views/LayoutView.vue'
import ExportList from '../views/ExportList.vue'
import ExportDetail from '../views/ExportDetail.vue'
import DocumentPreview from '../views/DocumentPreview.vue'
import ContainerHistory from '../views/ContainerHistory.vue'
import SalesList from '../views/SalesList.vue'
import SalesDetail from '../views/SalesDetail.vue'
import ImportView from '../views/ImportView.vue'
import MonitorView from '../views/MonitorView.vue'
const routes=[
  {path:'/login',component:LoginView,meta:{public:true}},
  {path:'/',component:LayoutView,children:[
    {path:'',redirect:'/exports'},
    {path:'exports',component:ExportList},
    {path:'exports/:id',component:ExportDetail},
    {path:'exports/:id/documents',component:DocumentPreview},
    {path:'containers',component:ContainerHistory},
    {path:'sales',component:SalesList},
    {path:'sales/:id',component:SalesDetail},
    {path:'sales/:id/documents',component:DocumentPreview},
    {path:'import',component:ImportView},
    {path:'monitor',component:MonitorView}
  ]},
  {path:'/:pathMatch(.*)*',redirect:'/exports'}
]
const router=createRouter({history:createWebHistory(),routes})
router.beforeEach(async to=>{
  if(to.meta.public)return true
  try{await currentUser();return true}catch{return {path:'/login',query:{redirect:to.fullPath}}}
})
export default router
