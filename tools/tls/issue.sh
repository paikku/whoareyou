#!/usr/bin/env bash
# 우리 도메인으로 진짜 인증서를 받아 폰에 심는다 (DNS-01, 차 없이·폰 없이 발급된다).
#
# 왜 DNS-01 인가: 인증서의 이름은 `100.99.9.9` 를 가리키는 A 레코드를 가진 도메인이라, Let's Encrypt
# 가 HTTP-01 로 그 주소에 접속할 방법이 없다(그 주소는 폰의 tun 이다). DNS 레코드로 증명하는 DNS-01 은
# 서버가 어디 있든 상관하지 않는다 — 그래서 이 스크립트는 아무 PC 에서나 돈다.
#
# 준비 (한 번):
#   1. 도메인 하나. 예: car.example.com
#   2. 그 이름의 A 레코드 → 100.99.9.9  (폰의 tun 주소. 공개 DNS 에 사설이 아닌 주소가 올라가는 것이고,
#      CGNAT 대역이라 인터넷에서는 아무 데도 안 간다. TeslaMirror 의 TSL6.com 이 같은 구조다.)
#   3. acme.sh 와 DNS 공급자 API 키:  curl https://get.acme.sh | sh
#
# 쓰기:
#   DOMAIN=car.example.com DNS=dns_cf CF_Token=... tools/tls/issue.sh
#   DOMAIN=car.example.com tools/tls/issue.sh --renew     # 갱신만 (cron 에 걸어 두면 자동)
#
# 만든 것을 폰에 넣기(둘 중 하나):
#   adb push out/tls/cert.pem /data/local/tmp/carcast/tls-cert.pem
#   adb push out/tls/key.pem  /data/local/tmp/carcast/tls-key.pem
#   또는 app/src/main/assets/tls/ 에 두고 APK 를 다시 빌드 (차에 PC 를 안 가져갈 때)
# 서버는 파일 → APK 자산 → 자체서명 순으로 고른다(StreamSession.tlsCredentials).
set -euo pipefail

: "${DOMAIN:?DOMAIN=car.example.com 처럼 도메인을 지정하세요}"
OUT=${OUT:-out/tls}
ACME=${ACME:-$HOME/.acme.sh/acme.sh}
[ -x "$ACME" ] || { echo "acme.sh 가 없습니다: $ACME (curl https://get.acme.sh | sh)"; exit 1; }

mkdir -p "$OUT"
if [ "${1:-}" = "--renew" ]; then
  "$ACME" --renew -d "$DOMAIN" --force
else
  : "${DNS:?DNS=dns_cf 처럼 acme.sh 의 DNS 플러그인 이름을 지정하세요 (공급자 API 키도 환경변수로)}"
  "$ACME" --issue --dns "$DNS" -d "$DOMAIN" --keylength 2048
fi

"$ACME" --install-cert -d "$DOMAIN" \
  --fullchain-file "$OUT/cert.pem" \
  --key-file "$OUT/key.pem"

echo
echo "받았습니다:"
openssl x509 -in "$OUT/cert.pem" -noout -subject -enddate
echo
echo "폰에 넣기:"
echo "  adb push $OUT/cert.pem /data/local/tmp/carcast/tls-cert.pem"
echo "  adb push $OUT/key.pem  /data/local/tmp/carcast/tls-key.pem"
echo "그 다음 서버를 다시 띄우면 /api/status.tlsHost 가 $DOMAIN 으로 바뀝니다."
