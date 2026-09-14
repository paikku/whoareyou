# 핫스팟 전용으로 가는 길 (Wi-Fi 요구 제거)

작성 2026-09-05. 배경: 현재 운용 절차는 **재부팅할 때마다 집 Wi-Fi에서 앱을 한 번 켜야 한다**
(무선 디버깅이 Wi-Fi 클라이언트 연결 중에만 켜지므로). 제품으로 쓸 수 없다.

## 요약 (결론 먼저)

1. **테소르(Tesor)도 이 문제를 풀지 못했다.** 테소르는 Shizuku 위에서 돈다(§1.4) — 즉 우리와 같은 shell uid 경로다.
   화면 분할은 미러링이 아니라 shell 권한으로 freeform을 켜서 얻는 것이고, Shizuku는 **재부팅할 때마다 무선 디버깅으로 다시 시작**해야 한다.
   "설치 후엔 자동"은 재부팅 전까지만 참이다. 우리가 뒤처진 게 아니라 **같은 벽 앞에 있다.**
2. 그러나 커뮤니티가 실제로 쓰는 우회가 있다: **adbd를 TCP 모드로 전환(`tcpip`)**. 한 번 전환하면 adbd가 Wi-Fi와 무관하게
   포트를 열고 계속 살아 있어서, **차 안에서도 앱이 언제든 shell을 다시 얻어 서버를 재기동**할 수 있다(§2).
   USB 디버깅 토글로 adbd를 살려 두던 현재의 임시방편도 함께 사라진다.
3. 남는 것은 **콜드 부팅 1회**뿐이고, 그것마저 없앨 후보가 둘 있다(§3, 둘 다 우리가 이미 shell을 쥔 상태에서 공짜로 시험 가능).
4. 그래도 개발자 옵션 자체를 못/안 켜는 사용자를 위해 **미러 모드**를 하위 티어로 둔다(§5). 이게 다른 오픈소스들이 핫스팟만으로 되는 이유이고,
   대가는 "별도 화면"과 "폰 화면 완전 OFF"다.

---

## 1. 왜 Wi-Fi가 필요한가 — 사슬 분해

```
① 테슬라 브라우저가 사설 IP(10/8, 172.16/12, 192.168/16) 차단
        ↓ 그래서
② 폰에 비-사설 주소 필요 → 루트 없이는 VpnService tun(100.99.9.9)뿐
        ↓ 그런데
③ Android 14+ ingress-discard BPF: VPN 주소로 오는 패킷은 uid ≥ 10000 소켓에 배달 안 함
        ↓ 그래서
④ HTTP/WS 서버가 shell uid(2000)여야 함             ←┐
⑤ 별도 VD(ADD_TRUSTED_DISPLAY) + 타 앱 실행 +        │ 둘 다 shell을 요구
   입력 주입(INJECT_EVENTS) + 물리 패널 OFF + 화면분할←┘
        ↓ 그래서
⑥ 루트 없이 shell을 얻는 유일한 길 = 폰이 자기 자신에게 ADB
        ↓ 그래서
⑦ 무선 디버깅 필요 → **Wi-Fi 클라이언트 연결 필요**
```

### 1.1 고리 ①: 테슬라의 차단 범위

공개 사례(TMC, tesla-carplay 문서)에서 일관되게 보고되는 것은 **RFC1918 3개 대역**이고, 알려진 우회로는
`240.3.3.x`(클래스 E)로 DNAT하는 방식이다. 즉 **"사설이 아니면 통과"**. Chrome의 Private Network Access는 여기에
127/8, 169.254/16, fc00::/7, fe80::/10을 더한다. → **`192.0.0.0/24`(CLAT)는 어느 목록에도 없다**(§4.1).

### 1.2 고리 ③: ingress-discard를 앱 uid로 우회할 수 있나

verification-log §3.2 실험 7~13이 이미 다 해봤다(protect, LOCAL_NET_ID 바인드, allowBypass, 라우팅 규칙) — 전부 실패.
**주소가 VPN 인터페이스의 주소인 한 앱 uid로는 안 된다.**

남는 틈 하나: 규칙은 "**VPN 인터페이스에 붙은 주소**"를 검사한다. tun에는 `100.99.9.1/32`만 붙이고
`100.99.9.9/32`는 **주소가 아니라 라우트로만**(`addRoute`) 넣으면, 그 패킷은 로컬 배달이 아니라 **포워딩** 대상이 되어
tun fd로 떨어진다. 커널 소켓을 안 쓰므로 검사할 소켓이 없다. 대신 **유저스페이스 TCP**를 구현해야 한다(HTTP/WS만 받으면 되니 600~1000줄).
위험: 테더링의 `tetherctrl_FORWARD`가 swlan0→tun0을 막을 수 있다. **미검증. 플랜 B다.**

### 1.3 고리 ⑦: 무선 디버깅을 핫스팟만으로 켤 수 있나

- 공식 문서·커뮤니티 정리: *"Android normally requires the phone to be connected to a Wi-Fi network for wireless debugging.
  **A local hotspot may work on some devices, but manufacturer behavior differs.**"*
- **S26U / One UI 8에서는 ❌** (2026-09-05 실측). 일부 기기에서 된다는 보고는 우리 대상 기기에 적용되지 않는다.
- → 이 고리 자체는 못 끊는다. 대신 **adbd를 다른 모드로 돌리면 이 고리를 통과할 필요가 없어진다**(§2).

### 1.4 테소르는 어떻게 하나 (추론 아니라 확인)

> 다른 앱 3종까지 포함한 전체 조사는 [prior-art.md](prior-art.md)에 있다. 요약: **재부팅 후 Wi-Fi 1회는 업계 공통 제약이고,
> 가상 IP 경로는 2024-06 Google Play 시스템 업데이트 이후 앱 uid에서 죽었으며 아무도 복구하지 못했다.**

- 테소르 설치 안내의 2단계가 **"Shizuku 설치 후 권한 부여"** 다. 즉 테소르는 Shizuku가 띄워 준 shell uid 프로세스로 동작한다.
- 그래서 "미러링이 아니라 폰 화면과 독립"(제작사 설명: *"Unlike typical mirroring app, your phone's screen remains independent"*)이고,
  **화면 분할**도 가능하다 — 이건 MediaProjection으로는 절대 안 되는 것으로, shell 권한 + freeform(`enable_freeform_support`,
  `am start --windowingMode 5 --display N`)의 산물이다. 우리 `implementation-proposal.md` §2.6이 이미 같은 방식을 적어 뒀다.
- 요구사항도 동일하다: **개발자 옵션 필수, 5GHz 핫스팟 필수.**
- 그리고 Shizuku(비루팅)는 **재부팅하면 반드시 다시 시작해야 하고**, 그 시작에 무선 디버깅 = Wi-Fi가 필요하다.
  Shizuku 문서: *"The non-root Shizuku service normally stops when the phone restarts."*
  TCP 모드를 다룬 글의 결론도 같다: *"if you restart the OS itself, you will need a Wi-Fi environment to run Shizuku for the first time,
  so you cannot avoid this even with TCP mode."*

**결론: 테소르의 UX도 "재부팅하면 Wi-Fi 있는 곳에서 한 번"이다.** 다만 사용자가 폰을 자주 재부팅하지 않아서 체감이 덜할 뿐이다.
우리가 할 일은 테소르를 흉내내는 게 아니라, 아래 §2·§3으로 **테소르보다 나아지는 것**이다.

---

## 2. 방안 1 — adbd TCP 모드 (구현됨, **사용자 선택**, 폰 검증 대기)

> **2026-09-05 최종 결과: 동작한다.** **Wi-Fi를 끈 상태**(그래서 무선 디버깅도 꺼진 상태)에서 TCP 모드 포트로 붙어 **서버를 다시 띄웠다**
> (verification-log §3.8). 콜드 부팅 1회를 뺀 모든 상황에서 Wi-Fi가 사라졌다 — Tesor·Castla가 멈춘 지점을 넘었다.
>
> **2026-09-05 중간 결과: One UI 8의 adbd가 `tcpip:`를 받아 준다** — `restarting in TCP mode port: N` 후 1초 만에 그 포트로 shell 재접속, 2회 재현.
> 반면 `persist.adb.tcp.port`는 거부(§3) → **콜드 부팅 후 Wi-Fi 1회는 남는다.** 남은 확인은 "Wi-Fi를 끈 뒤에도 그 포트가 열려 있는가".
>
> 2026-09-05(그 전): 첫 폰 시도에서 재부팅 뒤 무선 디버깅 connect 포트가 계속 거부돼 앱이 아예 붙지 못했다(verification-log §3.7).
> 전환은 adbd를 재시작시키므로, 그것이 무선 디버깅을 되돌리지 못하는 기기에서는 **유일한 진입로를 끊는다.**
> 그래서 자동 전환을 철회하고 앱의 **"Wi-Fi 없이 쓰기 시도"** 버튼(경고 포함)으로 내렸다. 원인 규명 전까지 기본값은 꺼짐.

### 2.1 무엇인가

`adb tcpip <포트>`는 셸 명령이 아니라 **adbd에게 보내는 프로토콜 요청**(`tcpip:5555` 서비스)이다. 받은 adbd는
스스로를 재시작해 **모든 인터페이스의 TCP 포트에서 대기**한다. 이 모드는 무선 디버깅(TLS/mDNS) 경로가 아니라
구형 RSA 키 인증 경로여서 **Wi-Fi 연결 여부와 무관하다.**

Shizuku 이슈 #864(Samsung S21, Android 14 / One UI 6.1)의 보고가 정확히 이것이다 — `adb tcpip 5555` 이후
*"연결 후 Wi-Fi 연결 유지 불필요"*.

### 2.2 우리에게 무엇이 달라지나

| 지금 | TCP 모드 도입 후 |
|---|---|
| Wi-Fi가 끊기면 adbd가 멈추고 init이 **cgroup째 서버를 SIGKILL** → USB 디버깅 토글을 켜 두는 임시방편 필요 | adbd가 계속 살아 있음 → **cgroup 킬 없음, USB 디버깅 토글 요구 삭제** |
| 차에서 서버가 죽으면 **복구 불가**(집에 가야 함) | 앱이 `127.0.0.1:<포트>`로 adbd에 붙어 **차 안에서 서버 재기동** |
| 서버 수명 = "재부팅 전까지, 단 아무것도 안 죽으면" | 서버 수명 = "언제든 다시 띄울 수 있음" |

즉 **콜드 부팅을 제외한 모든 상황에서 Wi-Fi가 사라진다.**

### 2.3 구현 (2026-09-05)

- `AdbLink.tcpip(port)` / `usbOnly()` — `tcpip:<port>` · `usb:` 서비스를 열고 adbd의 한 줄 응답을 읽는다.
  포트 검증은 `AdbLink.tcpipService()`로 분리해 단위 테스트(`AdbServiceTest`).
- `ShellServerLink.openLink()` — ① 저장된 TCP 포트가 **실제로 열려 있을 때만** 그쪽으로 붙고(짧은 소켓 프로브로 먼저 확인해서
  헛된 5초 대기와 로그 소음을 없앰), ② 아니면 무선 디버깅으로 붙은 뒤 `switchToTcpMode()`로 전환한다.
- **순서:** `tcpip`는 adbd를 재시작하므로 adbd cgroup의 자식이 죽는다. 그래서 전환은 **서버 기동 전에만** 한다.
  전환에 실패하면 그 라운드의 링크도 잃으므로, `tcpModeFailures`가 2회에 이르면 더 시도하지 않고 무선 디버깅으로만 간다
  (앱의 "TCP 모드 끄기" 버튼이 이 카운터도 0으로 되돌려 재시도 수단이 된다).
- 인증: TLS 페어링 키와 구형 `adb_keys`는 저장소가 다르므로 첫 loopback 접속에서 **"USB 디버깅을 허용하시겠습니까?"** 가
  한 번 뜰 수 있다. "항상 허용"으로 끝 — 폰에서 1탭, Wi-Fi 불필요. 앱 로그가 이 문구를 그대로 안내한다.
- 포트는 5555 대신 **30000~44999 랜덤**(`SecureRandom`). adbd는 모든 인터페이스에 열므로 핫스팟에 붙은 기기도 포트를 볼 수 있다 —
  미인증 키는 다이얼로그가 막지만 노출 자체를 줄인다. 앱 화면의 `TCP 모드:` 줄에 포트를 표시하고, **"TCP 모드 끄기"** 버튼으로 되돌린다.
- 전환에 성공하면 §3의 `persist.adb.tcp.port` 실험을 자동으로 한 번 수행하고 결과를 로그에 남긴다 — 성공/실패 어느 쪽이든
  다음 부팅에서 답이 나온다(루프가 Wi-Fi를 요구하기 전에 TCP 포트를 먼저 본다).
- **아직 폰에서 검증 안 됨:** One UI 8의 adbd가 `tcpip:`를 받아 주는지, 전환 후 무선 디버깅이 꺼지는지, 다이얼로그가 뜨는지.

---

## 3. 방안 2 — 콜드 부팅까지 없애기 (둘 다 공짜 실험)

재부팅 직후에는 shell이 전혀 없으므로, **부팅 시점에 adbd가 스스로 열려 있게 만드는 영속 설정**만이 답이다. 후보 둘:

| 후보 | 명령(이미 shell을 쥔 상태에서) | 기대 | 확인 방법 |
|---|---|---|---|
| `persist.adb.tcp.port` | `setprop persist.adb.tcp.port 5555` | adbd가 부팅마다 TCP 포트를 연다(안드로이드 TV가 5555를 여는 방식). `service.adb.tcp.port`가 없으면 이 값을 읽는다 | 재부팅 → 핫스팟만 켠 채 `adb connect <핫스팟주소>:5555` |
| `adb_wifi_enabled` | `settings put global adb_wifi_enabled 1` | 무선 디버깅 토글의 실체가 이 전역 설정이고 **설정은 재부팅을 넘는다**. Wi-Fi 없이도 켜진 채로 남는지가 관건 | 재부팅 → Wi-Fi 없이 개발자 옵션에서 토글 상태·`_adb-tls-connect` mDNS 확인 |

둘 다 **최근 삼성에서 막혔을 가능성이 높다**(`setprop`은 SELinux `shell_prop` 쓰기 권한, 후자는 부팅 시 Wi-Fi 콜백에서 되돌려질 수 있음).
기대치는 낮지만 **비용이 명령 한 줄 + 재부팅 한 번**이라 안 해 볼 이유가 없다.

성공하면 **Wi-Fi 요구가 영구히 사라진다.** 실패해도 §2 덕분에 "재부팅했을 때만"으로 좁혀진 상태는 유지된다.

---

## 4. 방안 3 — 네트워크 고리 단순화 (실차 1분)

### 4.1 CLAT 주소(`192.0.0.x`)를 그대로 쓴다

verification-log에 이미 있는 관측:

- 폰 인터페이스에 `rmnet_data1 192.0.0.2`, `rmnet_data2 192.0.0.4` 존재(IPv6 전용 망 + 464XLAT).
- **§3.2 실험 5·6: 핫스팟 클라이언트 → `http://192.0.0.2:3333` 이 앱 uid 서버로도 성공.** VPN on/off 무관.
- `192.0.0.0/24`는 RFC1918도 PNA 차단 목록도 아니다(§1.1).

→ 차가 이 주소를 열 수 있으면 **VpnService를 통째로 걷어낼 수 있고**, 네트워크 때문에 shell이 필요하던 이유(고리 ③④)가 사라진다.
서버는 이미 `0.0.0.0` 바인드라 서버 코드 변경은 0. 앱은 후보 주소를 골라 **URL/QR로 표시**하면 된다.

전제·위험: CLAT 주소는 모바일 데이터가 IPv6 전용 망일 때만 생기고(핫스팟을 쓰려면 어차피 데이터가 켜져 있어야 하니 상황은 맞다),
통신사·로밍에 따라 없을 수 있으며, `.2`/`.4`가 부팅마다 바뀔 수 있다. **차에서 열리는지는 미검증.**

※ 이것만으로는 Wi-Fi가 안 없어진다. 고리 ⑤(VD·입력·화면분할)가 남기 때문이다. §2·§3과 독립적인 **구조 단순화**다.

---

## 5. 방안 4 — 미러 모드 (하위 티어, 개발자 옵션 0)

§2·§3이 다 실패해도, 그리고 성공하더라도 **개발자 옵션을 켜기 싫은 사용자**를 위한 티어가 필요하다.

| 기능 | shell 모드 | 미러 모드 (공개 API만) |
|---|---|---|
| 화면 | TRUSTED VD + `am start --display` + 분할 | `MediaProjection` → `VirtualDisplay`(**물리 화면 미러**) |
| 인코딩·먹싱·전송·웹 | `core`/`mux` | **그대로 재사용, 변경 0** |
| 터치·스크롤 | `injectInputEvent(displayId)` | `AccessibilityService.dispatchGesture` |
| 뒤로/홈/최근앱 | 키 주입 | `performGlobalAction(...)` |
| 오디오 | REMOTE_SUBMIX | `AudioPlaybackCapture` / 실패 시 **차와 블루투스 페어링** |
| 폰 화면 끄기 | `SurfaceControl.setDisplayPowerMode` | 불가 → 밝기 0 + `KEEP_SCREEN_ON` |

- 최초 1회: 접근성 서비스 켜기(딥링크 1탭). 이후 매번: MediaProjection 동의 1탭. **Wi-Fi·개발자 옵션·페어링 전부 불필요.**
- 잃는 것: **폰 화면과 차 화면이 같다**(폰을 따로 못 쓴다), **화면 분할 불가**, 물리 패널 완전 OFF 불가, 해상도·회전이 폰에 종속.
- `core`·`mux`·웹 클라이언트·`/diag`를 100% 공유하므로 유지비는 `VideoSource` 구현 하나 + 컨트롤 싱크 하나다.

---

## 6. 실행 순서 (싼 것부터)

| # | 실험/작업 | 비용 | 무엇이 결정되나 |
|---|---|---|---|
| 1 | ~~**adbd TCP 모드** 구현~~ **완료(§2.3)** → 폰 검증: Wi-Fi 끄고 핫스팟에서 "서버 종료" 후 **재기동**되는지, USB 디버깅 토글을 꺼도 유지되는지 | 폰 10분 | 차 안 복구 가능 여부, USB 디버깅 토글 요구 제거 (§2) |
| 2 | ~~`persist.adb.tcp.port`~~ **❌ 확정: 삼성이 거부** (`Failed to set property … See dmesg`). 남은 후보는 `settings put global adb_wifi_enabled 1` 하나 | — | 콜드 부팅 Wi-Fi 제거 (§3) — 현재로선 불가 |
| 3 | ~~`settings put global adb_wifi_enabled 1` → 재부팅 → Wi-Fi 없이 토글 상태 확인~~ **부분 해결(2026-09-14):** 부팅 때 0 으로 돌아가는 건 맞지만, 앱이 `WRITE_SECURE_SETTINGS` 로 **부팅 뒤에 다시 1 로 쓰면 AdbService 가 받아 준다** (verification-log). Wi-Fi 는 여전히 필요하고 토글은 필요 없다 | — | 재부팅 뒤 손작업 = Wi-Fi 켜기 하나 |
| 4 | 차에서 `http://192.0.0.2:3333` 열기 (안 되면 `192.0.0.4`, 대조군으로 핫스팟 `10.x`) | 실차 1분 | VpnService 제거 가능 여부 (§4) |
| 5 | 미러 모드 프로토타입(MediaProjection → 기존 `H264Encoder` → 앱 프로세스에서 `core` 기동) | 1일 | 개발자 옵션 0 티어 (§5) |
| 6 | (4가 실패했을 때만) tun 라우트-온리 + 유저스페이스 TCP 스파이크 | 반나절 | §1.2 플랜 B |

2·3번은 **다음에 폰을 만질 때 5분**이면 끝난다. 1번이 본 작업이다.

---

## 7. 이 문서가 바꾸는 기존 결정

- `dev-plan.md` M3의 **"USB 디버깅 토글 ON이 운용 조건"** 은 §2가 성공하면 삭제된다.
- M3의 "Wi-Fi에서 1회 기동"은 §2 이후 **"콜드 부팅 후 1회"** 로 좁혀지고, §3이 성공하면 사라진다.
- `implementation-proposal.md` §2.1("MediaProjection만으로는 목표를 못 이룬다")은 여전히 사실이다 —
  화면 분할이 그 증거다. 미러 모드는 목표를 낮춘 **별도 티어**이지 대체재가 아니다.
- 새 마일스톤 후보: **M8 adbd TCP 모드**(우선), **M9 미러 모드**.

## 8. 근거

- Tesor: [Google Play](https://play.google.com/store/apps/details?id=com.arter97.tesor), [설정 절차 정리(Shizuku 사용)](https://www.bblogggg.com/2026/03/tesla-android-tesor-app-guide.html), [Tparts 소개("phone's screen remains independent")](https://www.tparts.com/blogs/tesla-knowledge-blogs/android-screen-mirroring-breakthrough-for-tesla-korean-developer-creates-tesor-app)
- Shizuku 재부팅 제약·TCP 모드: [Shizuku 사용자 매뉴얼](https://shizuku.rikka.app/guide/setup/), [issue #864 "Keeping shizuku working after leaving wifi"](https://github.com/RikkaApps/Shizuku/issues/864), [TCP 모드 운용 정리](https://note.com/wise_tulip6598/n/n6b526e41a125?hl=en), [핫스팟은 기기마다 다름](https://shizukuhub.com/shizuku-wireless-debugging/)
- 테슬라 사설 IP 차단: [TMC 스레드](https://teslamotorsclub.com/tmc/threads/cant-access-private-websites-on-wifi.44134/), [tesla-carplay 문서](https://github.com/marcdubois71450/tesla-carplay/blob/master/tesla-doc.md)
- Chrome PNA 차단 대역: [Chrome for Developers](https://developer.chrome.com/blog/private-network-access-update)
