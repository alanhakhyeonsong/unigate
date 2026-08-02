import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import { shouldRetry } from './queries/hooks'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: shouldRetry,
      // 이 앱의 목적은 관찰이라 화면 복귀 때마다 다시 물어보는 편이 낫다.
      refetchOnWindowFocus: true,
      staleTime: 0,
    },
    mutations: { retry: false },
  },
})

/**
 * v7 future flag 을 **미리 켠다.**
 *
 * 켜지 않으면 라우터가 콘솔에 경고 두 줄을 남기는데, 이 앱은 **콘솔이 관찰 도구**라
 * 상시 경고가 진짜 신호를 덮는다. 끄는 방법이 "무시하기" 밖에 없는 경고라면 원인을 없앤다.
 *
 * | flag | 무엇이 달라지나 | 이 앱에 미치는 영향 |
 * |---|---|---|
 * | `v7_startTransition` | 라우팅 상태 갱신이 `React.startTransition` 으로 감싸진다 | 없다 — 지연 로딩 라우트가 없다 |
 * | `v7_relativeSplatPath` | splat(`*`) 라우트 안의 **상대경로** 해석이 바뀐다 | 없다 — `NotFound` 는 절대경로 링크만 쓴다 |
 *
 * 둘 다 영향이 없음을 확인하고 켠 것이다. 상대경로를 쓰는 splat 화면이 생기면 재확인해야 한다.
 */
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
)
