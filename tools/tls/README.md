# 차가 넘길 수 없는 경고를 없애는 인증서

2026-09-17, Model Y(2026.26)에서 자체서명 인증서로 `https://100.99.9.9:3443` 을 열었더니
**넘길 수 없는** 경고가 떴다. `NET::ERR_CERT_AUTHORITY_INVALID`, "고급" 버튼 없음, 본문은
*"웹사이트가 Chromium이 처리할 수 없는 암호화된 사용자 인증 정보를 전송하였으므로 지금은
100.99.9.9에 방문할 수 없습니다"* — 크로미엄이 **우회를 막았을 때** 쓰는 문구다(차 브라우저에
`SSLErrorOverrideAllowed=false` 정책이 걸린 것으로 보인다).

그래서 이 차에서는 자체서명으로 secure context 에 닿을 방법이 없다. **공개 CA 가 서명한 인증서**만
경고 자체를 없앤다. 도메인을 사지 않고도 되는 길이 있다:

- `local-ip.sh` 는 `*.local-ip.sh` 의 **Let's Encrypt 와일드카드 인증서와 개인키를 공개**하고,
  그 도메인의 DNS 는 이름에 적힌 주소를 그대로 돌려준다: `100-99-9-9.local-ip.sh` → `100.99.9.9`.
  (같은 방식: `traefik.me`, `sslip.io`(DNS 만) — 이름만 다르다.)

```bash
mkdir -p app/src/main/assets/tls
curl -sS -o app/src/main/assets/tls/cert.pem https://local-ip.sh/server.pem
curl -sS -o app/src/main/assets/tls/key.pem  https://local-ip.sh/server.key
./gradlew :app:assembleDebug
```

그러면 서버가 자체서명 대신 이것을 쓰고(`StreamSession.tlsCredentials`), 차는
**`https://100-99-9-9.local-ip.sh:3443/diag.html`** 을 경고 없이 연다. `/api/status.tlsHost` 가 그
주소를 알려 주고, 평문 `/diag` 페이지가 링크로 걸어 준다.

## 이 개인키는 비밀이 아니다 — 그래서 git 에 넣지 않는다

`local-ip.sh` 가 키를 공개하므로 **누구나** 그 이름으로 서버를 세울 수 있다. 진단에는 충분하고
(우리는 "이 브라우저에 WebCodecs 가 있나"를 묻는 것뿐이다) 제품에는 절대 못 쓴다 — 그 인증서는
누가 답하고 있는지를 아무것도 증명하지 않는다. 제품으로 가려면 **우리 도메인 + 우리 키**다.

`app/src/main/assets/tls/` 는 `.gitignore` 에 있다. 저장소에는 개인키가 들어가지 않는다.
받아 둔 인증서는 90일마다 만료되므로, 만료되면 위 두 줄을 다시 돌린다.

## 남은 위험

차가 그 **이름을 해석할 수 있어야** 한다. Castla issue #51(docs/prior-art.md §4)에서
`192-0-0-8.sslip.io` 가 IPv6 전용 통신사에서 실패했다 — DNS64 가 A 레코드를 `64:ff9b::` 로 합성해
통신사 NAT64 로 보내 버렸기 때문이다. 그래서 차에서 이 주소가 열리는지는 그 자체로 하나의 실험이고,
열리지 않으면 **도메인을 사도 같은 이유로 깨진다**(TeslaMirror 의 `TSL6.com` 방식도 마찬가지).

---

# 제품으로 가려면: 우리 도메인, 우리 키

위의 `local-ip.sh` 는 **진단용**이다. 키가 공개돼 있어 누구나 그 이름으로 서버를 세울 수 있고,
그러면 그 인증서는 "누가 답하고 있는지"를 아무것도 증명하지 못한다. 그런데 2026-09-17 실차에서
**차가 하드웨어 디코더를 가지고 있다는 것이 확인됐으므로**(report #67, WebCodecs `prefer-hardware`
supported), HTTPS 는 이제 진단 도구가 아니라 **제품 요구사항**이다 — `VideoDecoder` 가
`[SecureContext]` 라 평문에서는 그 디코더에 닿을 수가 없다.

필요한 것은 셋이다.

1. **도메인 하나와 A 레코드** → `100.99.9.9`. 공개 DNS 에 CGNAT 주소를 올리는 것이고, 인터넷에서는
   아무 데도 닿지 않는다. TeslaMirror 의 `TSL6.com` 이 정확히 이 구조다(docs/prior-art.md §2).
   **차가 그 이름을 해석할 수 있는지는 이미 확인됐다** — 2026-09-17 에 차가
   `100-99-9-9.local-ip.sh` 를 해석했다. Castla #51 의 DNS64/NAT64 사망은 이 통신사에서는 없다.
2. **DNS-01 로 발급·갱신** — `tools/tls/issue.sh`. 인증서의 이름이 폰의 tun 주소를 가리키므로
   Let's Encrypt 가 HTTP-01 로 접속할 방법이 없고, DNS-01 은 서버 위치를 묻지 않는다.
   90일마다 `--renew` 를 돌린다(cron).
3. **폰에 심기** — `adb push` 로 `/data/local/tmp/carcast/tls-cert.pem`·`tls-key.pem`, 또는
   `app/src/main/assets/tls/` 에 두고 APK 를 다시 빌드. 서버는 파일 → 자산 → 자체서명 순으로 고른다.
   `/api/status.tlsNotAfter` 가 만료일을 싣는다 — 차에서 갑자기 경고가 뜨기 전에 보라고 있는 값이다.

남은 숙제: **갱신한 인증서를 PC 없이 폰에 넣는 길**. 지금은 `adb push`(PC 필요) 아니면 APK 재빌드뿐이다.
앱이 받아서 서버에 넘기는 경로(`POST /api/tls`, loopback 전용)를 두는 것이 다음 자리다.
