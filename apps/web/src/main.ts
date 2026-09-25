import '@fontsource/jetbrains-mono/500.css';
import '@fontsource/space-grotesk/500.css';
import '@fontsource/space-grotesk/600.css';
import 'element-plus/theme-chalk/index.css';

import { ElLoading } from 'element-plus';
import { createPinia } from 'pinia';
import { createApp } from 'vue';

import App from './App.vue';
import router from './router';
import './styles/base.css';
import './windowFocusGuard';

const app = createApp(App);

app.use(createPinia());
app.use(router);
app.use(ElLoading);
app.mount('#app');
