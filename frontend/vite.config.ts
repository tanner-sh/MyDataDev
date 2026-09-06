import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    manifest: true,
    // 图标共享同一份压缩字典，减少大量独立小块的请求与 gzip 开销。编辑器继续独立懒加载。
    rolldownOptions: { output: { codeSplitting: { groups: [
      { name: 'workbench-icons', test: /node_modules\/@ant-design\/icons\/(?:es|lib)\/icons\// }
    ] } } }
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/mcp': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
});
