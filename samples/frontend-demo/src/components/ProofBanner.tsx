import type { ReactNode } from 'react'

/**
 * "이 화면이 증명하는 것" 배너 — **모든 화면 맨 위에 같은 모양으로 붙는다.**
 *
 * ## 왜 규격을 만드나
 * 이 콘솔의 각 화면에는 원래 검증 의도가 적혀 있었지만 본문 여기저기 `note` 로 흩어져 있었다.
 * 그러면 처음 여는 사람은 **"뭘 눌러서 뭘 확인해야 정상인지"** 를 화면을 다 읽어야 알 수 있다.
 *
 * 세 칸을 강제한다:
 * - **주장(claim)**: 이 화면이 참임을 보이려는 명제 하나
 * - **절차(how)**: 그걸 확인하는 순서
 * - **기대(expect)**: 무엇이 나와야 통과인가 — **여기가 비면 그 화면은 검증 장치가 아니다**
 *
 * `expect` 를 필수로 둔 것이 이 컴포넌트의 요점이다. "해 보세요" 로 끝나는 화면은
 * 관찰 결과를 판정할 수 없고, 판정할 수 없으면 회귀를 못 잡는다.
 */
export interface ProofBannerProps {
  /** 이 화면이 증명하려는 명제 하나. 문장으로 쓴다. */
  claim: ReactNode
  /** 확인 절차. 순서가 의미를 가지므로 배열이다. */
  how: ReactNode[]
  /** 통과 판정 기준. "무엇이 보이면 정상인가". */
  expect: ReactNode
  /** 근거가 되는 코드·문서 경로. 화면의 주장이 어디서 왔는지 되짚을 수 있게 한다. */
  refs?: string[]
}

export function ProofBanner({ claim, how, expect, refs }: ProofBannerProps) {
  return (
    <aside className="proof">
      <div className="proof-claim">
        <span className="proof-tag">이 화면이 증명하는 것</span>
        <strong>{claim}</strong>
      </div>
      <div className="proof-body">
        <div>
          <span className="proof-label">확인 절차</span>
          <ol>
            {how.map((step, i) => (
              // 절차는 정적 목록이라 순서가 곧 정체성이다. 재정렬·삽입이 없으므로 index 키가 안전하다.
              <li key={i}>{step}</li>
            ))}
          </ol>
        </div>
        <div>
          <span className="proof-label">통과 기준</span>
          <p>{expect}</p>
        </div>
      </div>
      {refs && refs.length > 0 && (
        <div className="proof-refs">
          근거:{' '}
          {refs.map((r, i) => (
            <span key={r}>
              {i > 0 && ' · '}
              <code>{r}</code>
            </span>
          ))}
        </div>
      )}
    </aside>
  )
}
