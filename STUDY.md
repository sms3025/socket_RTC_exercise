# 화상 마피아 게임 사전 연습 프로젝트

WebSocket(STOMP) 과 WebRTC 를 단계적으로 익히기 위한 연습용 프로젝트입니다.

## 실행 방법

```bash
# 프로젝트 루트에서
./gradlew bootRun      # (Windows: gradlew.bat bootRun)
```

서버가 뜨면 브라우저에서 접속 (**https / 8443 포트** 주의):

- **1단계 STOMP 채팅** : https://localhost:8443/chat.html
- **2단계 WebRTC 화상** : https://localhost:8443/webrtc.html
- **4단계 LiveKit SFU 화상** : https://localhost:8443/livekit.html  *(별도로 LiveKit 서버를 먼저 띄워야 함 — 아래 4단계 참고)*

> 처음 접속하면 자체서명 인증서라 **"안전하지 않음" 경고**가 뜹니다.
> → "고급 → localhost로 이동(안전하지 않음)" 을 눌러 수동 허용하세요. (연습용이라 정상입니다.)
>
> 두 페이지 모두 **탭을 2개 이상** 열고 같은 방 번호로 입장해야 동작을 확인할 수 있습니다.

## HTTPS / WSS (A방법 적용됨)

이 프로젝트는 로컬 자체서명 인증서로 HTTPS를 켜서 WebSocket이 **wss(암호화)** 로 동작합니다.

- 관련 파일: `application.properties`(SSL 설정), `src/main/resources/keystore.p12`(인증서)
- 핵심 개념:
  - **wss = HTTPS 위의 WebSocket.** 코드에서 정하는 게 아니라, 페이지가 https면 자동으로 wss가 된다.
    (프론트의 `new SockJS('/ws')` 는 상대 경로라 페이지 스킴을 그대로 따라감 → 하드코딩 불필요)
  - 그래서 "wss를 쓴다"는 건 결국 "서버에 HTTPS를 켠다"는 뜻.
- 왜 필요한가:
  1. 방화벽/프록시가 평문 ws를 끊는 경우가 많음 → wss는 일반 HTTPS처럼 보여 통과가 잘 됨
  2. **다른 기기에서** WebRTC 카메라(`getUserMedia`)를 쓰려면 https가 필수 (localhost만 예외)

### 인증서(keystore) 재생성 방법
`keystore.p12` 를 지웠거나 다시 만들고 싶을 때 (JDK의 keytool 사용):
```bash
keytool -genkeypair -alias mafia-practice -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore src/main/resources/keystore.p12 \
  -validity 3650 -storepass changeit \
  -dname "CN=localhost, OU=study, O=mafia, L=Seoul, C=KR" \
  -ext "SAN=dns:localhost,ip:127.0.0.1"
```
- `-storepass` 값은 `application.properties` 의 `server.ssl.key-store-password` 와 일치해야 함
- `-alias` 값은 `server.ssl.key-alias` 와 일치해야 함
- `SAN=dns:localhost` 가 있어야 인증서가 localhost 도메인과 매칭됨

> ⚠️ 자체서명 인증서/비밀번호는 **연습용**입니다. 실제 배포에서는 Let's Encrypt 등 정식 인증서를 쓰고,
> 비밀번호는 환경변수/시크릿으로 분리하세요. (keystore.p12 는 원래 git에 커밋하지 않는 게 원칙)

## 학습 순서

### 1단계 — STOMP 로 pub/sub 감 잡기 (`chat.html`)
- 관련 파일: `config/WebSocketConfig.java`, `chat/ChatController.java`, `chat/ChatMessage.java`
- 핵심 질문:
  - `/app`, `/topic` 접두어의 차이는? (클라→서버 vs 서버→클라)
  - 구독(SUBSCRIBE)과 발행(SEND)은 각각 언제 일어나는가?
  - 방(roomId)마다 채팅이 분리되는 원리는? → 구독 주소가 방마다 다르기 때문

### 2단계 — WebRTC 시그널링 (`webrtc.html`)
- 관련 파일: `signal/SignalController.java`, `signal/SignalMessage.java`
- 핵심 질문:
  - 영상은 P2P인데 왜 서버가 필요한가? → 최초 협상(SDP/ICE) 중개용
  - OFFER → ANSWER → CANDIDATE 순서가 왜 그런가?
  - 서버는 신호 내용을 해석하는가? → 아니오, 그냥 방에 중계만 함 (우체부)

> **관찰 팁**: 서버 콘솔에 `[SIGNAL] room=1 type=JOIN ...` 로그가 찍힙니다.
> 탭 2개를 열고 이 로그의 순서(JOIN → OFFER → ANSWER → CANDIDATE)를 눈으로 따라가 보세요.

### 3단계 — 1:1 전송 (`convertAndSendToUser` + Principal)
- 관련 파일: `config/StompPrincipal.java`, `config/UserHandshakeHandler.java`,
  `chat/ChatController.java`(귓속말), `signal/SignalController.java`(타겟 시그널링)
- **핵심 개념 — "특정 한 사람에게만" 보내려면 그 사람을 식별할 이름표가 필요하다:**
  1. 접속할 때 `?username=닉네임` 을 실어 보낸다. (`new SockJS('/ws?username=...')`)
  2. 서버의 `UserHandshakeHandler` 가 그 값을 읽어 그 연결에 **Principal(이름표)** 을 붙인다.
  3. 서버가 `convertAndSendToUser("닉네임", "/queue/xxx", 메시지)` 를 호출하면,
     스프링이 그 이름표를 가진 세션을 찾아 **그 사람에게만** 배달한다.
  4. 받는 클라이언트는 `/user/queue/xxx` 를 구독한다. (`/user` 접두어가 "나에게 온 것만" 필터)
- **방송 vs 1:1 언제 쓰나:**
  - 채팅: 전체 대화는 `/topic/chat/{room}` 방송, **귓속말은 `/user/queue/chat` 1:1**
  - 시그널링: **JOIN/LEAVE 는 방송**(모두가 알아야 함), **OFFER/ANSWER/CANDIDATE 는 1:1**(그 상대만)
- 핵심 질문:
  - `convertAndSend` 와 `convertAndSendToUser` 의 차이는? (모두에게 vs 그 사람에게만)
  - Principal 이 없으면 `convertAndSendToUser` 는 어떻게 되나? → 대상을 못 찾아 조용히 실패
  - 클라이언트가 구독하는 `/user/queue/chat` 의 `/user` 는 누가 채워주나? → 스프링이 세션별로

> **관찰 팁**: 서버 콘솔의 `[HANDSHAKE] 접속 신원(Principal) 확정: 홍길동`,
> `[WHISPER] from=... to=...` 로그로 신원 부여와 1:1 배달을 확인할 수 있습니다.
> 탭 3개(A/B/C)를 열고 A→B 귓속말이 **C에게는 안 보이는지** 확인해 보세요.

### (검증) 자동화 테스트
`src/test/java/.../PrivateMessagingTest.java` 에 "귓속말이 지정한 상대에게만 가고 제3자에겐
안 간다"를 검증하는 통합 테스트가 있습니다. 브라우저 없이 STOMP 클라이언트로 3명을 접속시켜
확인합니다.
```bash
./gradlew test --tests "com.socket.test.example.PrivateMessagingTest"
```

### 4단계 — SFU 로 인원 확장 (LiveKit) (`livekit.html`)
- 관련 파일: `livekit/LiveKitProperties.java`, `livekit/LiveKitTokenController.java`,
  `livekit/TokenResponse.java`, `static/livekit.html`, `application.properties`(livekit.* 설정)
- **왜 SFU 인가?** 2·3단계 Mesh 는 "모두가 모두에게 직접 영상을 올리는" 방식이라, 인원이
  늘수록 각자의 업로드 부담이 제곱으로 커진다(N명 → 각자 N-1개 업로드). 마피아 게임은 8~10명이
  한 방에 모이므로 Mesh 로는 버겁다. **SFU** 는 각자 서버에 딱 한 번만 올리고 서버가 나머지에게
  배분해서, 인원이 늘어도 각자의 업로드가 1개로 고정된다.
- **핵심 역할 분담 (Mesh 와 결정적으로 다른 점):**
  - 2·3단계에선 **우리 Spring 서버가 직접 시그널링을 중계**했다(SignalController = 우체부).
  - 4단계에선 **LiveKit 서버가 시그널링 + 미디어(SFU) 를 전부** 담당한다.
    우리 Spring 서버는 **"입장권(토큰) 발급소"** 역할만 한다.
- **왜 토큰(JWT)이 필요한가?** 아무나 LiveKit 방에 못 들어오게 하려면 "허가증"이 있어야 한다.
  우리 서버만 아는 `api-secret` 으로 서명한 토큰을 발급하면, LiveKit 서버가 그 서명을 검증해
  "우리 서버가 허락한 사람"만 입장시킨다. 토큰 안에는 **누가(identity)·어느 방(room)·무슨 권한
  (발행/구독)** 인지가 적혀 있다. → 여기에 로그인/게임 규칙을 붙이면 "관전자는 발행 금지",
  "마피아만 밤 채널 입장" 같은 통제를 서버가 강제할 수 있다.
- 흐름 요약:
  ```
  브라우저 ─① GET /api/livekit/token?room=1&identity=alice─▶ (우리 Spring 서버) 토큰 발급
  브라우저 ◀───────────────── { url, token } ─────────────────
  브라우저 ─② url(=LiveKit 서버)에 token 들고 직접 접속 ─▶ (LiveKit SFU 서버)
  브라우저 ─③ 카메라/마이크 업로드 1개 · 남들 영상은 SFU 가 배분 ◀
  ```
  → 미디어는 우리 Spring 서버를 **전혀 거치지 않는다.** 우리는 ①에서 토큰만 내주면 끝.
- 핵심 질문:
  - Mesh 와 SFU 에서 "내 영상 업로드 개수"는 각각 몇 개인가? 왜 그런가?
  - 4단계에서 시그널링은 누가 하는가? 우리 Spring 서버의 역할은 무엇으로 줄었는가?
  - 토큰 없이 LiveKit 서버에 붙으면 왜 거부되는가? `api-secret` 은 어디에 쓰이는가?

#### LiveKit 서버 띄우는 법 (먼저 실행해야 함)
LiveKit 서버는 이 Java 프로젝트와 **별개의 프로그램**이다. 우리 서버는 토큰만 발급하고,
실제 영상 중계는 아래로 띄운 LiveKit 서버가 한다. 개발용으로는 `--dev` 모드가 가장 간단하다.
(`--dev` 는 api-key=`devkey`, api-secret=`secret` 을 자동으로 쓴다 → `application.properties` 기본값과 일치)

```bash
# 방법 A) Docker (설치 불필요, 가장 쉬움)
docker run --rm \
  -p 7880:7880 -p 7881:7881 -p 7882:7882/udp \
  livekit/livekit-server --dev --bind 0.0.0.0

# 방법 B) 바이너리 직접 설치 후
#   (Windows: choco install livekit / macOS: brew install livekit)
livekit-server --dev
```

> **접속 순서**: ① 위 명령으로 LiveKit 서버(7880)를 먼저 띄운다 → ② `./gradlew bootRun` 으로
> 우리 서버(8443)를 띄운다 → ③ 브라우저 탭 2개 이상에서 `https://localhost:8443/livekit.html`
> 에 **이름을 다르게** 해서 같은 방으로 입장한다.
>
> **관찰 팁**: 우리 서버 콘솔의 `[LIVEKIT] 토큰 발급: room=1 identity=alice ...` 로그로 토큰이
> 나가는 걸 확인할 수 있고, LiveKit 서버 콘솔에서는 참가자 입장/트랙 발행 로그가 흐른다.
> Mesh(2단계)와 달리 탭을 3~4개로 늘려도 각 탭의 업로드가 1개로 유지되는 게 SFU 의 핵심이다.

## 이 연습이 마피아 게임과 어떻게 이어지나

| 연습에서 배운 것              | 마피아 게임에서의 쓰임                                  |
|------------------------------|-------------------------------------------------------|
| `/topic/chat/{roomId}` 방송  | 게임 방 전체 채팅, 낮 토론, 투표 결과 broadcast         |
| `/queue` + convertAndSendToUser | 마피아끼리만 보는 밤 대화, 개인에게만 가는 역할 정보 |
| `@MessageMapping` 이벤트 처리 | "투표한다", "능력 사용" 같은 게임 액션을 서버가 처리    |
| WebRTC Mesh 연결             | 소규모(4~6명) 플레이어의 실시간 얼굴(화상) 연결          |
| 시그널링 서버(STOMP 재사용)  | Mesh 화상 연결을 맺기 위한 신호 통로                    |
| LiveKit SFU + 토큰 발급      | 8~10명 대규모 방의 화상, 권한 기반 입장(관전자/마피아 통제) |

## 다음 단계로 확장해볼 것 (익숙해진 뒤)
1. 방 참가자 목록/입장·퇴장을 서버가 메모리에 관리 (`WebSocketEventListener` 로 연결 종료 감지)
   → 지금 귓속말 상대 목록은 "대화에서 본 닉네임"으로만 채워지는데, 이걸 실시간 접속자 목록으로 개선
2. ~~시그널링을 `convertAndSendToUser` 로 바꿔 특정 상대에게만 전송~~ ✅ (3단계에서 완료)
3. 로그인/시큐리티를 붙여 Principal 을 회원 ID로 대체 (지금은 접속 시 넘긴 username 을 그대로 사용)
   → 이 로그인 정보를 4단계 토큰 발급(`LiveKitTokenController`)에도 연결하면, 검증된 회원만
     LiveKit 방에 입장시키고 역할(관전자/마피아)에 따라 권한(발행/구독)을 다르게 줄 수 있다.
4. ~~인원이 많아지면 Mesh → **SFU**(mediasoup / LiveKit 등) 미디어 서버 도입~~ ✅ (4단계에서 LiveKit 으로 완료)
5. 게임 상태 머신(대기 → 밤 → 낮 → 투표 → 종료) 을 STOMP 이벤트로 구현
   → 마피아끼리의 밤 대화 = `convertAndSendToUser` 를 마피아 각자에게 보내는 것으로 구현 가능

## 폴더 구조
```
src/main/java/com/socket/test/example/
├── config/
│   ├── WebSocketConfig.java        # STOMP 엔드포인트 + 브로커 설정
│   ├── StompPrincipal.java         # (3단계) 접속자 이름표
│   └── UserHandshakeHandler.java   # (3단계) 접속 시 Principal 부여
├── chat/                           # 1단계: STOMP 채팅 (+3단계 귓속말)
│   ├── ChatController.java
│   └── ChatMessage.java
├── signal/                         # 2단계: WebRTC 시그널링 (+3단계 1:1 타겟)
│   ├── SignalController.java
│   └── SignalMessage.java
└── livekit/                        # 4단계: LiveKit SFU (토큰 발급소 역할)
    ├── LiveKitProperties.java      # livekit.* 설정값(서버 주소/키/시크릿)
    ├── LiveKitTokenController.java # /api/livekit/token → 입장권(JWT) 발급
    └── TokenResponse.java          # 토큰 발급 응답 DTO

src/main/resources/
├── application.properties          # 포트/SSL(wss)/로그 + livekit.* 설정
├── keystore.p12                    # 자체서명 인증서 (HTTPS용, 연습용)
└── static/
    ├── chat.html                   # 1단계 채팅 + 3단계 귓속말 UI
    ├── webrtc.html                 # 2단계 화상 테스트 페이지 (Mesh)
    └── livekit.html                # 4단계 화상 테스트 페이지 (SFU/LiveKit)

src/test/
├── java/com/socket/test/example/PrivateMessagingTest.java  # (3단계) 1:1 격리 검증
└── resources/application.properties                        # 테스트용(SSL off)
```
