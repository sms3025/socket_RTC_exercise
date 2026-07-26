# 기술 이론 정리 (THEORY.md)

이 프로젝트(화상 마피아 연습)에서 실제로 쓰이는 기술들의 **배경 이론**을 정리한 문서입니다.
"이 프로젝트가 어떻게 동작하는가"는 [`STUDY.md`](./STUDY.md)에 있고,
여기서는 그 밑에 깔린 **개념 자체**를 공부하는 데 초점을 둡니다.

> 읽는 순서 추천: HTTP → WebSocket → STOMP → pub/sub → WebRTC → TLS/WSS → Spring 계층
> 각 절 끝의 **핵심 질문**에 스스로 답해보면 이해도를 점검할 수 있습니다.

---

## 0. 기술 스택 한눈에 보기

| 구분 | 사용 기술 | 이 프로젝트에서의 역할 |
|------|-----------|------------------------|
| 언어/런타임 | Java 21 | 애플리케이션 언어 (toolchain 21) |
| 프레임워크 | Spring Boot 4.1.0 | 웹 서버 + 의존성 관리 |
| 실시간 통신 | WebSocket | 양방향 상시 연결 통로 |
| 메시징 프로토콜 | STOMP | WebSocket 위의 pub/sub 규칙 |
| 폴백 | SockJS | WebSocket 미지원 환경 대체 |
| 영상 통신 | WebRTC | 브라우저 간 P2P 화상 (2·3단계 Mesh) |
| 미디어 서버(SFU) | LiveKit | 다수 인원 화상 중계 + 시그널링 (4단계) |
| 토큰 인증 | JWT (LiveKit AccessToken) | LiveKit 방 입장 허가증(권한 포함) 발급 |
| 보안 | TLS / WSS (HTTPS) | 암호화 + 카메라 권한 확보 |
| 빌드 | Gradle (wrapper) | 빌드/실행/테스트 |
| 보조 | Lombok | 보일러플레이트 축소 |
| 테스트 | JUnit 5 (JUnit Platform) | 통합 테스트 |

---

## 1. HTTP는 왜 실시간에 부족한가 (출발점)

전통적인 HTTP는 **요청(request) → 응답(response)** 한 쌍으로 끝나고 연결이 닫힙니다.

- 클라이언트가 물어봐야만 서버가 답한다. **서버가 먼저 말을 걸 수 없다.**
- 채팅·게임처럼 "서버가 아무 때나 밀어줘야(push) 하는" 상황에는 맞지 않는다.

이 한계를 우회하려던 옛 기법들:

- **폴링(Polling)**: 클라이언트가 "새 거 있어?"를 주기적으로 반복 요청 → 낭비 심함.
- **롱 폴링(Long Polling)**: 서버가 응답을 일부러 늦게 줘서 새 이벤트가 생길 때까지 대기 → 개선이지만 여전히 연결을 계속 맺었다 끊음.
- **SSE(Server-Sent Events)**: 서버→클라 단방향 스트리밍은 되지만 **클라→서버**는 안 됨.

→ **양방향 + 상시 연결**이 필요해서 등장한 것이 **WebSocket**.

**핵심 질문**
- HTTP에서 서버가 클라이언트에게 먼저 데이터를 보낼 수 없는 이유는?
- 롱 폴링과 WebSocket의 근본적 차이는?

---

## 2. WebSocket — 양방향 상시 연결 통로

WebSocket은 하나의 TCP 연결을 **계속 열어두고** 서버·클라이언트가 아무 때나 데이터를 주고받는 프로토콜입니다.

### 2.1 연결이 맺어지는 과정 (핸드셰이크)

1. 클라이언트가 **HTTP 요청**을 보내면서 `Upgrade: websocket` 헤더를 실어 보냄.
2. 서버가 `101 Switching Protocols`로 응답 → 이 순간부터 HTTP가 아니라 WebSocket으로 **프로토콜 전환(업그레이드)**.
3. 이후로는 같은 TCP 연결 위에서 HTTP 규칙 없이 자유롭게 프레임을 주고받음.

> 이 프로젝트에서 이 "악수" 순간을 가로채는 게 `UserHandshakeHandler`입니다.
> 접속 URL의 `?username=...`을 읽어 그 연결에 **Principal(이름표)**을 붙입니다.
> 핸드셰이크는 **연결당 딱 한 번** 일어나고, 이때 정해진 신원이 그 연결 내내 따라다닙니다.

### 2.2 WebSocket의 한계 — "그냥 통로일 뿐"

WebSocket 자체는 **저수준(low-level)** 입니다. 바이트/문자열이 오갈 뿐,

- "이 메시지는 어느 방으로 보내라"
- "이 사람은 이 채널을 구독한다"

같은 **의미(라우팅 규칙)가 전혀 없습니다.** 그걸 직접 다 짜야 합니다.
→ 그래서 그 위에 얹는 **약속(프로토콜)**이 필요한데, 그게 STOMP입니다.

**핵심 질문**
- WebSocket 연결은 왜 HTTP 요청으로 "시작"하는가?
- WebSocket이 "저수준 통로"라는 말의 의미는? 무엇이 없어서 불편한가?

---

## 3. STOMP — WebSocket 위의 메시징 규칙

**STOMP(Simple Text Oriented Messaging Protocol)**는 WebSocket이라는 통로 위에 얹는 **문자 기반 메시징 프로토콜**입니다.

비유: **HTTP 위에 REST 규칙을 얹듯이, WebSocket 위에 STOMP를 얹는다.**

STOMP가 생기면 다음 개념들이 생깁니다:

- **명령(command)**: `CONNECT`, `SUBSCRIBE`, `SEND`, `MESSAGE`, `UNSUBSCRIBE` …
- **목적지(destination)**: `/topic/chat/1` 같은 **주소**. 이 주소를 기준으로 메시지가 라우팅됨.
- **프레임(frame)**: 명령 + 헤더 + 본문(body)으로 이루어진 메시지 단위. (HTTP 메시지와 구조가 비슷)

### 3.1 이 프로젝트의 주소 규칙

`WebSocketConfig`에서 정한 접두어(prefix)로 메시지 방향이 갈립니다.

| 접두어 | 방향 | 의미 | 예시 |
|--------|------|------|------|
| `/app` | 클라 → 서버 | 서버 **로직 호출** (`@MessageMapping`으로 감) | `/app/chat/1` |
| `/topic` | 서버 → 클라 | **방송(broadcast)**, 여러 구독자가 함께 받음 | `/topic/chat/1` |
| `/queue` | 서버 → 클라 | **1:1**, 특정 한 명에게 | `/queue/chat` |
| `/user` | 서버 → 클라 | 특정 유저 전용 (스프링이 세션별로 채워줌) | `/user/queue/chat` |

```
[클라 A] --SEND /app/chat/1--> [서버 @MessageMapping] --convertAndSend /topic/chat/1--> [구독자 A,B,C 모두]
```

### 3.2 SockJS — 폴백(fallback) 계층

`.withSockJS()`가 붙어 있습니다. SockJS는 **WebSocket을 지원하지 않는 환경**(구형 브라우저, 특정 프록시)에서 롱 폴링 등 다른 방식으로 **자동 대체**해주는 라이브러리입니다. 프론트는 `new SockJS('/ws')` 한 줄로 쓰면 되고, 내부적으로 최선의 방식을 골라줍니다.

**핵심 질문**
- WebSocket과 STOMP의 관계를 "HTTP와 REST"에 빗대어 설명해보라.
- `/app`과 `/topic`의 차이는? 왜 방향을 접두어로 구분하는가?
- SockJS는 "무엇이 없을 때"를 대비하는가?

---

## 4. Pub/Sub(발행-구독) 패턴 — 실시간 방의 뼈대

STOMP의 핵심 사고방식은 **Publish/Subscribe**입니다.

- **구독(Subscribe)**: 클라이언트가 "나는 `/topic/chat/1`을 듣겠다"고 등록.
- **발행(Publish)**: 누군가 `/topic/chat/1`로 메시지를 보냄.
- **브로커(Broker)**: 그 주소를 구독 중인 **모든** 클라이언트에게 자동 배달.

### 4.1 방(room)이 분리되는 원리

이 프로젝트에서 "1번 방"과 "2번 방" 채팅이 섞이지 않는 이유는 간단합니다.
**구독 주소가 방마다 다르기 때문**입니다.

- 1번 방 사람들 → `/topic/chat/1` 구독
- 2번 방 사람들 → `/topic/chat/2` 구독

서버가 `/topic/chat/1`로 발행하면 1번 방 구독자만 받습니다. 별도 필터 로직이 필요 없습니다.

### 4.2 방송 vs 1:1

| 방식 | API | 대상 | 이 프로젝트 예 |
|------|-----|------|----------------|
| 방송 | `convertAndSend("/topic/...")` | 그 주소 구독자 **전원** | 방 전체 채팅, JOIN/LEAVE 공지 |
| 1:1 | `convertAndSendToUser("bob", "/queue/...")` | 이름표가 `bob`인 **그 사람만** | 귓속말, WebRTC OFFER/ANSWER |

`convertAndSendToUser`의 동작 원리:
1. 서버가 내부적으로 `/user/{bob}/queue/chat`으로 보냄.
2. 스프링이 Principal 이름이 `bob`인 세션을 찾음.
3. 그 클라이언트가 `/user/queue/chat`을 구독 중이면 배달됨.
   (구독 주소의 `{username}` 부분은 스프링이 세션별로 자동으로 채워줌)

> ⚠️ Principal이 없으면(신원 미상) `convertAndSendToUser`는 **대상을 못 찾아 조용히 실패**합니다.
> 그래서 `UserHandshakeHandler`가 접속 시 반드시 이름표를 붙여주는 것.

### 4.3 내장 브로커 vs 외부 브로커

이 프로젝트는 `enableSimpleBroker`(스프링 **내장** 브로커)를 씁니다.

- **내장(Simple)**: 서버 1대의 메모리에서 구독 목록을 관리. 연습·소규모엔 충분.
- **외부(RabbitMQ, Kafka 등)**: 서버를 **여러 대로 확장**하면 각 서버의 구독 정보가 공유돼야 하므로 외부 메시지 브로커가 필요.

**핵심 질문**
- 방이 여러 개일 때 채팅이 섞이지 않는 이유는? (한 문장으로)
- 서버를 2대로 늘리면 내장 브로커에 어떤 문제가 생기는가?
- `/user` 접두어의 `{username}`은 누가 채워주는가?

---

## 5. WebRTC — 브라우저 간 P2P 영상

**WebRTC(Web Real-Time Communication)**는 브라우저끼리 **서버를 거치지 않고 직접(P2P)** 오디오·비디오·데이터를 주고받는 기술입니다.

### 5.1 "그럼 서버는 왜 필요한가?" — 시그널링(Signaling)

영상 데이터 자체는 P2P로 직접 흐르지만, **최초에 서로를 찾고 협상하는 단계**에서는 중개자가 필요합니다. 그 중개를 **시그널링 서버**가 합니다.

- 두 브라우저는 아직 서로의 IP·코덱·암호화 키를 모른다.
- 이 정보를 교환하려면 "이미 연결된 통로"가 필요한데, 이 프로젝트는 **STOMP를 그 통로로 재사용**합니다. (`SignalController`)
- 시그널링 서버는 신호 **내용을 해석하지 않습니다.** 그냥 방에 중계만 함 → **우체부** 비유.

```
[브라우저 A] ──신호(SDP/ICE)──> [시그널링 서버(STOMP)] ──신호──> [브라우저 B]
      └──────────────── 영상/음성은 여기로 직접(P2P) ────────────────┘
```

### 5.2 SDP와 ICE — 협상에 오가는 두 가지

- **SDP(Session Description Protocol)**: "나는 이런 코덱·해상도·암호화를 쓸 수 있어"라는 **명세서**.
  - **OFFER**: A가 "이렇게 하자"고 제안 (SDP 담김)
  - **ANSWER**: B가 "좋아, 나는 이렇게 맞출게"라고 응답 (SDP 담김)
- **ICE(Interactive Connectivity Establishment) / Candidate**: "나한테 닿는 실제 네트워크 경로(IP:포트) 후보"들. NAT/방화벽 때문에 경로가 여러 개일 수 있어 **후보를 계속 주고받으며** 연결 가능한 경로를 찾음.

**협상 순서가 `OFFER → ANSWER → CANDIDATE`인 이유**: 먼저 "무엇을 할지(코덱 등)"를 합의(OFFER/ANSWER)한 뒤, "어떤 길로 연결할지(경로)"를 찾기(CANDIDATE) 때문. 다만 실제로는 candidate가 협상과 병행해 흘러오기도 함(Trickle ICE).

### 5.3 JOIN은 방송, OFFER/ANSWER/CANDIDATE는 1:1인 이유

`SignalController`를 보면 신호 종류에 따라 전송 방식이 갈립니다.

- **JOIN / LEAVE → 방송**: 새로 들어온 사람은 "방에 누가 있는지" 모름. 일단 전체에 "나 왔어!"를 외쳐야 기존 참가자들이 그를 발견하고 각자 1:1 OFFER를 건다.
- **OFFER / ANSWER / CANDIDATE → 1:1**: 이미 상대를 특정한 신호이므로 그 상대에게만. 관계없는 사람에게 안 가서 트래픽·보안상 유리.

### 5.4 NAT, STUN, TURN (개념만)

대부분의 기기는 공유기(NAT) 뒤에 있어 **외부에서 바로 닿는 IP가 없습니다.**

- **STUN 서버**: "너의 공인 IP:포트가 뭔지 알려줄게" → 대개 이걸로 P2P 직결 성공.
- **TURN 서버**: STUN으로도 못 뚫는 엄격한 방화벽일 때, **영상을 대신 중계**해주는 서버 (P2P 실패 시 폴백). 대역폭 비용이 큼.

> 이 연습은 localhost/동일망 위주라 STUN/TURN 없이도 동작하지만, 실제 서비스에선 필요합니다.

### 5.5 Mesh vs SFU vs MCU — 인원이 늘어날 때

화상 회의의 위상(topology)은 크게 셋으로 나뉜다.

| 방식 | 누가 영상을 나르나 | 내 업로드 개수(N명) | 서버 부담 | 적합 규모 |
|------|--------------------|---------------------|-----------|-----------|
| **Mesh(그물망)** | 브라우저끼리 직접 P2P | **N-1개** (모두에게 각각) | 없음(시그널링만) | 4~6명 (2·3단계) |
| **SFU(선택적 전달)** | 서버가 받아서 그대로 전달 | **1개** (서버에만) | 중간(전달만, 디코딩 X) | 수십~수백 명 (4단계) |
| **MCU(믹싱)** | 서버가 여러 영상을 **하나로 합성** 후 전송 | 1개 | 높음(디코딩·합성·인코딩) | 특수 목적 |

- **Mesh** 는 서버가 미디어를 안 거쳐서 서버 비용이 싸지만, N명이면 각자 N-1개를 **동시에 업로드**해야 해서 인원이 조금만 늘어도 각 브라우저의 상행 대역폭·CPU가 폭증한다. (10명이면 각자 9개 업로드)
- **SFU** 는 각자 서버에 **1개만** 올리고, 서버가 그 스트림을 **디코딩하지 않고 그대로** 다른 참가자들에게 복사·전달한다. 서버는 "똑똑한 중계기"라 부담이 합성(MCU)보다 훨씬 적으면서도, 각 브라우저의 업로드는 1개로 고정된다. **LiveKit·mediasoup·Janus** 등이 여기 속한다.
- **MCU** 는 모든 영상을 서버가 하나의 화면으로 합쳐(디코딩→합성→재인코딩) 내려주므로 클라이언트는 아주 가볍지만, 서버 CPU 비용이 크고 개별 레이아웃 제어가 어렵다. 요즘은 대부분 SFU 를 쓴다.

**핵심 질문**
- 영상은 P2P인데 서버(시그널링)가 왜 필요한가?
- SDP와 ICE candidate는 각각 "무엇"에 대한 정보인가?
- 10명이 화상하면 Mesh는 왜 힘든가? SFU는 그걸 어떻게 푸는가?
- SFU와 MCU의 차이는? (서버가 영상을 "전달만" 하나 "합성"하나)
- STUN과 TURN의 차이는?

---

## 5-B. LiveKit — SFU 를 실제로 붙이기 (4단계)

4단계에서는 위의 **SFU** 를 직접 구현하지 않고 오픈소스 SFU 서버인 **LiveKit** 을 도입한다.
SFU 는 미디어 스트림을 다루는 매우 정교한 서버라 직접 만들기 어렵기 때문에, 검증된 서버를
가져다 쓰고 우리는 **인증(입장 허가)** 만 담당하는 것이 현실적인 구조다.

### 5-B.1 역할 분담 — "미디어는 LiveKit, 인증은 우리"

```
        ┌───────── 우리 Spring 서버 (8443, HTTPS) ─────────┐
        │  · LiveKitTokenController: 입장권(JWT) 발급소       │  ← 미디어를 절대 안 거침
        └───────────────────────────────────────────────────┘
                    ▲ ① 토큰 요청        │ ② { url, token }
                    │                    ▼
        ┌───────────────── 브라우저 (livekit.html) ──────────┐
        │  livekit-client SDK 가 WebRTC·시그널링·재연결 처리   │
        └───────────────────────────────────────────────────┘
                    │ ③ token 들고 접속        ▲ ④ 남들 영상 배분
                    ▼                          │
        ┌───────── LiveKit 서버 (7880, 별도 프로세스) ───────┐
        │  · 시그널링 + SFU(미디어 중계) 를 모두 담당          │
        └───────────────────────────────────────────────────┘
```

- **2·3단계와의 가장 큰 차이**: 시그널링을 **우리 서버(SignalController)가 하지 않는다.**
  LiveKit 서버가 시그널링과 미디어 중계를 모두 처리하고, 우리 서버는 토큰만 발급하고 빠진다.
- 그래서 4단계 코드에는 OFFER/ANSWER/CANDIDATE 를 손으로 다루는 부분이 **아예 없다.**
  `livekit-client` SDK 가 그 협상을 내부에서 알아서 처리한다. (2단계에서 손으로 짠 것과 대조)

### 5-B.2 왜 토큰(JWT)이 필요한가 — 인증과 인가

LiveKit 서버에 아무나 접속하면 안 되므로, 접속하려면 **입장권(access token)** 이 필요하다.
이 토큰은 **JWT(JSON Web Token)** 형식이다.

- **JWT 구조**: `헤더.페이로드.서명` 세 부분을 점(.)으로 이은 문자열.
  - **페이로드(payload)**: "누가(identity)·어느 방(room)·무슨 권한(grant)·언제까지(exp)" 같은 내용.
  - **서명(signature)**: 페이로드를 **비밀키(api-secret)로 HMAC-SHA256** 해서 만든 값.
- **핵심 원리 — 공유 비밀(shared secret)**:
  1. 우리 서버와 LiveKit 서버는 같은 `api-secret` 을 미리 공유한다.
  2. 우리 서버가 그 secret 으로 토큰에 서명한다.
  3. LiveKit 서버는 받은 토큰의 서명을 같은 secret 으로 다시 계산해 **일치하는지** 검사한다.
  4. 일치하면 "우리 서버가 정식 발급한 위조되지 않은 토큰"으로 신뢰하고 입장을 허락한다.
  → secret 을 모르면 유효한 서명을 만들 수 없으므로, 아무나 토큰을 위조해 들어올 수 없다.
- **인증(Authentication) vs 인가(Authorization)**:
  - *인증* = "너 누구야?" → 토큰의 `identity` 로 참가자를 식별.
  - *인가* = "너 뭘 할 수 있어?" → 토큰의 **grant** 로 권한 부여.
    - `RoomJoin(true)` + `RoomName("1")`: 1번 방에 입장 가능
    - `CanPublish(true)`: 내 카메라/마이크를 올릴 수 있음(발행)
    - `CanSubscribe(true)`: 남의 영상/음성을 받아볼 수 있음(구독)
  - ▶ **마피아 게임 응용**: 관전자에게는 `CanPublish(false)` 를 주면 "보기만 가능"이 되고,
    이런 규칙을 **토큰 발급 시 서버가 강제**하므로 클라이언트가 조작해도 소용없다.
    (Mesh 방식에선 이런 중앙 통제가 어렵다 — 서버가 미디어 경로에 없기 때문)

### 5-B.3 왜 자체 신호 서버(STOMP)를 안 쓰고 토큰만?

2·3단계의 STOMP 시그널링은 "우리가 신호를 직접 나른다"였다. 4단계에서 LiveKit 을 쓰면
**신호를 나르는 일 자체가 LiveKit 서버로 넘어간다.** 우리 서버가 할 일은 "이 사람이 이 방에
들어갈 자격이 있는가"를 판단해 토큰을 찍어주는 **한 번의 REST 호출**뿐이다(`/api/livekit/token`).
연결이 맺어진 뒤의 모든 실시간 신호·미디어는 브라우저 ↔ LiveKit 서버 사이에서 오간다.

> **혼합 콘텐츠(mixed content) 주의**: 페이지는 https(8443)인데 개발용 LiveKit 은 ws(7880)로
> 뜬다. 브라우저는 보통 https 페이지에서 평문 ws 접속을 막지만, **localhost(루프백)** 는
> "신뢰 가능한 출처"로 예외 처리돼 로컬 학습에선 동작한다. 실제 배포에선 LiveKit 에도 TLS 를
> 붙여 `wss://` 로 접속해야 한다.

**핵심 질문**
- 4단계에서 시그널링과 미디어 중계는 각각 누가 하는가? 우리 서버의 역할은?
- JWT 의 "서명"은 어떻게 위조를 막는가? `api-secret` 은 누가누가 아는가?
- 인증과 인가의 차이를, 이 프로젝트의 `identity` 와 `grant` 로 설명해보라.
- 관전자에게 발행을 금지하는 규칙을 SFU 에서는 왜 안전하게 강제할 수 있는가?

---

## 6. TLS / WSS — 왜 HTTPS를 켰나

이 프로젝트는 로컬 자체서명 인증서로 **HTTPS(8443)**를 켰습니다.

### 6.1 ws vs wss

- **ws://** : 평문 WebSocket
- **wss://** : TLS로 암호화된 WebSocket = **"HTTPS 위의 WebSocket"**

**중요한 사실: wss는 코드로 정하는 게 아니라, 페이지가 https면 자동으로 wss가 됩니다.**
프론트의 `new SockJS('/ws')`는 상대 경로라 페이지 스킴을 그대로 따릅니다.
→ 그래서 "wss를 쓴다" = "서버에 HTTPS를 켠다"와 같은 말.

### 6.2 왜 굳이 HTTPS를?

1. **방화벽/프록시 통과**: 평문 ws를 끊는 네트워크가 많음. wss는 일반 HTTPS처럼 보여 잘 통과됨.
2. **WebRTC 카메라 권한**: 브라우저는 보안상 **https에서만** `getUserMedia`(카메라/마이크)를 허용. (예외: localhost) 다른 기기에서 접속해 화상을 쓰려면 https 필수.

### 6.3 인증서(keystore)와 TLS 개념

- **TLS 핸드셰이크**: 접속 시 서버가 인증서로 신원을 증명하고, 대칭키를 교환해 이후 통신을 암호화.
- **자체서명(self-signed)**: 공인 기관(CA)이 아니라 내가 직접 서명한 인증서. 그래서 브라우저가 "안전하지 않음" 경고를 띄움 → **연습용이라 수동 허용**. 실제 배포에선 Let's Encrypt 등 정식 CA 인증서 사용.
- **keystore.p12**: 인증서+개인키를 담는 PKCS12 형식 파일. `application.properties`의 비밀번호·alias와 일치해야 함.

**핵심 질문**
- 프론트 코드를 안 고쳤는데 wss로 연결되는 이유는?
- WebRTC 카메라를 다른 기기에서 쓰려면 왜 https가 필요한가?
- 자체서명 인증서는 왜 브라우저 경고를 유발하는가?

---

## 7. Spring 메시징 계층 (프레임워크 관점)

STOMP를 직접 파싱하지 않고 Spring이 다 처리해줍니다. 핵심 구성요소:

| 요소 | 역할 | 이 프로젝트 위치 |
|------|------|------------------|
| `@EnableWebSocketMessageBroker` | STOMP 브로커 기능 ON (핵심 스위치) | `WebSocketConfig` |
| `WebSocketMessageBrokerConfigurer` | 엔드포인트·브로커·라우팅 설정 | `WebSocketConfig` |
| `@MessageMapping` | 클라의 `/app/...` SEND를 받는 핸들러 (REST의 `@GetMapping` 격) | `ChatController`, `SignalController` |
| `@DestinationVariable` | 목적지 경로 변수 추출 (REST의 `@PathVariable` 격) | `/chat/{roomId}` |
| `SimpMessagingTemplate` | 서버가 능동적으로 특정 목적지에 push | 두 컨트롤러 |
| `Principal` / `HandshakeHandler` | 연결에 신원(이름표) 부여 → 1:1 전송 근거 | `UserHandshakeHandler`, `StompPrincipal` |

### 7.1 REST 컨트롤러와의 대비

- `@RestController` + `@GetMapping`: HTTP **요청 1개당 응답 1개** (요청-응답 모델)
- `@Controller` + `@MessageMapping`: 연결을 열어둔 채 **메시지가 올 때마다** 실행 (실시간 모델)

### 7.2 Spring Boot / Gradle / Lombok (주변 도구)

- **Spring Boot**: 내장 톰캣 + 자동 설정(auto-configuration) + starter 의존성으로 "설정 최소화, 실행 즉시". `spring-boot-starter-websocket`이 STOMP 관련 라이브러리를 한 번에 끌어옴.
- **Gradle wrapper(`gradlew`)**: 각자 Gradle을 안 깔아도 프로젝트에 박힌 버전으로 동일하게 빌드. `./gradlew bootRun`으로 실행.
- **Lombok**: `@RequiredArgsConstructor`, `@Getter` 등으로 보일러플레이트(게터·생성자)를 컴파일 시점에 생성. (이 프로젝트는 학습을 위해 일부러 생성자를 명시적으로 쓴 곳도 있음)

**핵심 질문**
- `@MessageMapping`과 `@GetMapping`의 실행 모델 차이는?
- `SimpMessagingTemplate`은 언제 필요한가? (`@SendTo`만으론 부족한 경우)
- `@EnableWebSocketMessageBroker`를 빼면 무슨 일이 생기나?

---

## 8. 전체 그림 — 한 장으로 잇기

```
                      브라우저 (chat.html / webrtc.html)
                                   │
                 https(8443) 페이지 로드 → SockJS('/ws')
                                   │  (페이지가 https라 자동 wss)
                     ┌─────────────▼──────────────┐
                     │  WebSocket 핸드셰이크        │  ← UserHandshakeHandler가
                     │  (Upgrade → 101)            │     Principal(이름표) 부여
                     └─────────────┬──────────────┘
                                   │  STOMP 프레임(SUBSCRIBE/SEND)
                     ┌─────────────▼──────────────┐
                     │  Spring 메시지 브로커        │
                     │  /app  → @MessageMapping     │
                     │  /topic → 방송               │
                     │  /queue,/user → 1:1          │
                     └──────┬───────────────┬──────┘
                            │               │
            ┌───────────────┘               └──────────────┐
     [1단계] ChatController              [2·3단계] SignalController
      방 채팅 방송 + 귓속말                  WebRTC 신호 중계(우체부)
                                                    │
                                     신호로 협상이 끝나면 ↓
                            [브라우저 A] ═══ P2P 영상/음성 ═══ [브라우저 B]
                                     (WebRTC, 서버 안 거침)
```

---

## 9. 더 공부하면 좋을 키워드

- WebSocket: `RFC 6455`, 프레임 구조, ping/pong keep-alive
- STOMP: 프레임 명세, ACK/heartbeat, 외부 브로커 릴레이(`enableStompBrokerRelay`)
- Spring: `WebSocketEventListener`(연결/해제 감지), 인터셉터(`ChannelInterceptor`)로 인증
- WebRTC: `RTCPeerConnection`, `getUserMedia`, DataChannel, Trickle ICE, SFU(mediasoup/LiveKit)
- LiveKit/SFU: `AccessToken`/VideoGrant, simulcast, `adaptiveStream`·`dynacast`, RoomServiceClient(서버측 방 관리), 웹훅(webhook), TURN 내장
- JWT: `헤더.페이로드.서명` 구조, HMAC-SHA256 서명 검증, `exp`(만료)·`nbf`(유효 시작), 인증 vs 인가
- 보안: TLS 1.3 핸드셰이크, CA 체인, Let's Encrypt(ACME), 비밀번호를 환경변수/시크릿으로 분리
- 확장: 게임 상태 머신(대기→밤→낮→투표→종료)을 STOMP 이벤트로 모델링

> 실습 팁: `application.properties`의 `logging.level...=DEBUG` 덕분에 서버 콘솔에
> SUBSCRIBE/SEND 프레임과 `[SIGNAL]`, `[HANDSHAKE]`, `[WHISPER]` 로그가 흐릅니다.
> 탭을 2~3개 열고 이 로그의 **순서**를 눈으로 따라가면 이론이 몸에 붙습니다.
