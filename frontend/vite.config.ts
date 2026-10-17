import { fileURLToPath, URL } from "node:url";

import vue from "@vitejs/plugin-vue";
import { defineConfig, loadEnv } from "vite";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  return {
    plugins: [vue()],
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url))
      }
    },
    optimizeDeps: {
      include: [
        "dayjs",
        "dayjs/plugin/customParseFormat",
        "dayjs/plugin/customParseFormat.js",
        "dayjs/plugin/localeData.js",
        "dayjs/plugin/advancedFormat.js",
        "dayjs/plugin/weekOfYear.js",
        "dayjs/plugin/weekYear.js",
        "dayjs/plugin/dayOfYear.js",
        "dayjs/plugin/isSameOrAfter.js",
        "dayjs/plugin/isSameOrBefore.js"
      ],
      noDiscovery: true
    },
    server: {
      port: 5173,
      open: false,
      proxy: {
        "/api": {
          target: env.VITE_GATEWAY_URL || "http://localhost:8080",
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api(?=\/|$)/, "")
        }
      }
    }
  };
});
