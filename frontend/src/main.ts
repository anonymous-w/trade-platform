import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import ja from 'element-plus/es/locale/lang/ja'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import router from './router'
createApp(App).use(ElementPlus,{locale:ja}).use(router).mount('#app')
