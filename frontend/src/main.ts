import { createApp } from "vue";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";

import App from "./App.vue";
import router from "./router";
import "./styles/global.css";
import { TOKEN_KEY } from './utils/auth';
import { resetSession } from './state/session';

window.addEventListener('storage', event => {
  if (event.key === TOKEN_KEY || event.key === null) { resetSession(); window.location.reload(); }
});

createApp(App).use(router).use(ElementPlus).mount("#app");
