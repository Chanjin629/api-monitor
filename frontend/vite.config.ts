import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/**
 * Backend로 넘길 때 원래 Host(localhost:5173)를 유지한다.
 * Spring이 이 Host로 GitHub 콜백 주소를 만들기 때문에, 바뀌면 OAuth App에 등록한 주소와 달라진다.
 * (문자열로만 지정하면 Vite가 changeOrigin을 켜서 Host가 localhost:8080으로 바뀐다)
 */
const backend = { target: 'http://localhost:8080', changeOrigin: false }

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
