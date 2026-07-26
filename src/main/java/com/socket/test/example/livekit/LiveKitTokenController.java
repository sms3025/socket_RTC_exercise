package com.socket.test.example.livekit;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * =========================================================================
 * [4단계] LiveKit 접속 토큰(입장권) 발급 컨트롤러  ★이 프로젝트의 SFU 핵심★
 * =========================================================================
 *
 * ■ 먼저 큰 그림부터 (2·3단계 Mesh 와 무엇이 다른가)
 *
 *   [2·3단계 = Mesh]
 *     - 우리 Spring 서버(SignalController)가 직접 시그널링(OFFER/ANSWER/CANDIDATE)을 중계했다.
 *     - 영상은 브라우저끼리 P2P 로 직접 흘렀다. N명이면 각자 N-1개 연결 → 인원 늘면 부담 폭증.
 *
 *   [4단계 = SFU (LiveKit)]
 *     - 각 브라우저는 자기 영상을 "LiveKit 서버(SFU)" 한 곳에만 한 번 올린다(업로드 1개).
 *     - LiveKit 서버가 그 영상을 나머지 참가자들에게 대신 나눠준다(배분).
 *     - 그래서 인원이 많아져도(마피아 8~10명) 각자의 업로드 부담이 일정하게 유지된다.
 *     - ★중요: 시그널링(협상 신호)도 이제 우리 서버가 아니라 LiveKit 서버가 처리한다!
 *       LiveKit 서버 자체가 "시그널링 서버 + 미디어 서버(SFU)"를 겸한다.
 *
 * ■ 그럼 우리 Spring 서버는 4단계에서 뭘 하나? → "입장권(토큰) 발급소"
 *     - 브라우저가 LiveKit 서버에 아무나 막 접속하면 안 되므로, 접속하려면 "토큰(JWT)"이 필요하다.
 *     - 이 토큰은 우리 서버만 아는 api-secret 으로 서명되어 있어서, LiveKit 서버가 "정품"임을 검증한다.
 *     - 토큰 안에는 "이 사람은 누구(identity)이고, 어느 방(room)에, 무슨 권한(발행/구독)으로
 *       들어올 수 있다"는 허가 내용(grant)이 적혀 있다.
 *     - 즉 우리 서버는 "누가 어느 방에 들어갈 자격이 있는지"를 판단해 토큰을 찍어주는 역할만 한다.
 *       (실제 회원 인증/로그인/게임 규칙을 여기에 붙이면 "마피아만 밤 채널 입장" 같은 통제가 가능)
 *
 * ■ 전체 흐름 한 장 요약
 *     ┌ 브라우저 ────────────────────────────────────────────────────┐
 *     │  ① GET /api/livekit/token?room=1&identity=alice  ──▶ (우리 Spring 서버)
 *     │                                                     └ 토큰(JWT) 발급 ─┐
 *     │  ② { url, token } 응답 받음                        ◀────────────────┘
 *     │  ③ url(=LiveKit 서버)로 token 을 들고 직접 접속  ──▶ (LiveKit SFU 서버)
 *     │  ④ 카메라/마이크를 SFU 에 업로드, 남들 영상은 SFU 가 배분 ◀─────────┘
 *     └──────────────────────────────────────────────────────────────┘
 *   → 미디어는 우리 Spring 서버를 전혀 거치지 않는다. 우리는 ①에서 토큰만 내주면 끝.
 */
@RestController
public class LiveKitTokenController {

    private final LiveKitProperties props;

    public LiveKitTokenController(LiveKitProperties props) {
        this.props = props;
    }

    /**
     * 접속 토큰 발급 엔드포인트.
     *
     * 예) GET https://localhost:8443/api/livekit/token?room=1&identity=alice
     *
     * @param room     입장할 방 이름 (예: "1"). 같은 방 이름끼리 서로의 영상을 보게 된다.
     * @param identity 참가자 고유 식별자 (예: "alice"). 방 안에서 유일해야 한다.
     * @return url(LiveKit 서버 주소) + token(입장권 JWT) 를 담은 JSON
     */
    @GetMapping("/api/livekit/token")
    public TokenResponse createToken(@RequestParam String room,
                                     @RequestParam String identity) {

        // ── 1) 토큰 객체 생성 ─────────────────────────────────────────────
        //   생성자에 api-key 와 api-secret 을 넘긴다.
        //   - api-key    : "누가 발급했는지"를 나타내는 발급자(issuer) 표시로 토큰에 박힌다.
        //   - api-secret : 토큰 맨 끝의 "서명(signature)"을 만드는 데 쓰인다(HMAC-SHA256).
        //     LiveKit 서버는 같은 secret 으로 서명을 다시 계산해보고 일치하면 "위조 아님"으로 판단한다.
        AccessToken token = new AccessToken(props.getApiKey(), props.getApiSecret());

        // ── 2) "누구인지" 채우기 ──────────────────────────────────────────
        //   identity : 방 안에서 나를 구분하는 유일한 ID. 방 입장 토큰에는 반드시 있어야 한다.
        //   name     : 화면에 표시할 이름표(사람이 읽는 용도). 여기선 identity 와 같게 둔다.
        token.setIdentity(identity);
        token.setName(identity);

        // ── 3) 유효기간 설정 ──────────────────────────────────────────────
        //   입장권은 "오래 유효하면 위험"하므로 짧게 준다. (탈취돼도 금방 만료되도록)
        //   여기선 학습 편의상 1시간. (지정 안 하면 SDK 기본값은 6시간)
        token.setTtl(TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS));

        // ── 4) "무엇을 할 수 있는지" 허가(grant) 채우기 ───────────────────
        //   토큰 안에 이 사람의 권한을 적어 넣는다. LiveKit 서버는 이 grant 대로만 허용한다.
        //     · RoomJoin(true)     : 방에 입장할 수 있음        (이게 있어야 접속 자체가 됨)
        //     · RoomName(room)     : 입장 가능한 방 이름 지정   (join 을 줄 땐 방 이름이 필수)
        //     · CanPublish(true)   : 내 카메라/마이크를 올릴 수 있음(발행)
        //     · CanSubscribe(true) : 남의 영상/음성을 받아볼 수 있음(구독)
        //   ▶ 응용: 마피아 게임에서 "관전자"는 CanPublish(false) 로 주면 "볼 수만 있고 말/영상은 못 냄"이 된다.
        //           이렇게 권한을 토큰에 심는 게 SFU 방식의 강력한 점이다(서버가 규칙을 강제).
        token.addGrants(
                new RoomJoin(true),
                new RoomName(room),
                new CanPublish(true),
                new CanSubscribe(true)
        );

        // ── 5) JWT 문자열로 서명·직렬화 ──────────────────────────────────
        //   toJwt() 가 위 내용을 하나의 문자열(...header.payload.signature)로 만들어 준다.
        //   이 문자열을 브라우저가 들고 LiveKit 서버에 제출하면 입장 심사를 통과한다.
        String jwt = token.toJwt();

        System.out.printf("[LIVEKIT] 토큰 발급: room=%s identity=%s url=%s%n",
                room, identity, props.getUrl());

        // 브라우저가 접속에 필요한 두 가지(url, token)를 내려준다.
        return new TokenResponse(props.getUrl(), jwt, room, identity);
    }
}
