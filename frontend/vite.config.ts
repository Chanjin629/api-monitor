import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const backend = 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // 운영의 Caddy처럼 화면과 API를 같은 주소로 묶어 CORS 없이 개발한다.
    proxy: {
      '/api': backend,
      '/oauth2': backend,
      '/login/oauth2': backend,
    },
  },
})
