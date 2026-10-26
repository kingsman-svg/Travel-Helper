import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: Number(process.env.FRONTEND_PORT || 5173),
    strictPort: true,
    proxy: {
      '/api': {
        target: `http://127.0.0.1:${process.env.BACKEND_PORT || 8080}`,
        timeout: (Number(process.env.MODEL_TIMEOUT_SECONDS || 90) * 2 + 10) * 1000,
        proxyTimeout: (Number(process.env.MODEL_TIMEOUT_SECONDS || 90) * 2 + 10) * 1000,
      },
    },
  },
});
