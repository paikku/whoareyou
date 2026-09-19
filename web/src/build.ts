// 이 페이지가 어느 빌드의 것인가.
//
// 폰 서버는 HTML 을 줄 때 `__BUILD__` 를 APK 의 git sha 로 바꾼다(core `HttpServer.serveStatic`). 자산 URL
// 을 그 값으로 버전 지정하면 175 KB 워커를 차에 탈 때마다 다시 받지 않아도 되고(`?v=<sha>` 는 1 년 immutable),
// 동시에 **지금 도는 페이지가 폰과 같은 빌드인지**를 차 안에서 알 수 있다.
//
// 뒤의 것이 왜 필요한가: 실차 #83 은 폰만 새 빌드(`2c474d1`)였고 차의 탭은 39 분 전에 연 옛 페이지였다.
// APK 를 다시 깔면 서버가 다시 서고 소켓은 스스로 이어지므로 — 그것이 원래 원하는 동작이다 — 탭은 열린 채
// 옛 자바스크립트로 계속 돈다. 리포트에는 `폰 build=2c474d1` 만 남아서, 고친 것이 안 고쳐진 것처럼 보였다.
// 그래서 페이지의 빌드도 리포트에 싣고, 다르면 차에게 말한다(main.ts `checkBuild`).
const meta = (): string => document.querySelector('meta[name="carcast-build"]')?.getAttribute('content') ?? '';

/** 이 페이지의 빌드 sha. 폰이 안 채워 준 자리(가짜 폰, 데스크톱 드라이런)에서는 빈 문자열이다. */
export const WEB_BUILD = ((v) => (v && v !== '__BUILD__' ? v : ''))(meta());

/** 자산 URL 에 버전을 붙인다. 버전이 없으면 그대로 둔다. */
export function versioned(url: string): string {
  return WEB_BUILD ? `${url}?v=${encodeURIComponent(WEB_BUILD)}` : url;
}
