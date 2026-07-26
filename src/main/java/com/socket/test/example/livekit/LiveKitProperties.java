package com.socket.test.example.livekit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * =========================================================================
 * [4단계] LiveKit 접속 설정값 묶음
 * =========================================================================
 *
 * ■ 이 클래스가 하는 일
 *   application.properties 의 "livekit.*" 로 시작하는 설정값을 읽어와 담아두는 그릇이다.
 *   (Spring 의 @ConfigurationProperties 기능: 접두어가 같은 설정들을 객체 하나로 묶어줌)
 *
 *   livekit.url         → LiveKit 서버(SFU)의 WebSocket 주소.  예) ws://localhost:7880
 *   livekit.api-key     → LiveKit 서버와 약속한 API 키.       예) devkey
 *   livekit.api-secret  → API 키에 대응하는 비밀키.           예) secret
 *
 * ■ 왜 키/시크릿이 필요한가?
 *   - 우리 Spring 서버가 발급하는 "입장 토큰(JWT)"은 이 api-secret 으로 서명(sign)된다.
 *   - LiveKit 서버는 같은 api-secret 을 알고 있어서, 들어온 토큰의 서명을 검증한다.
 *   - 서명이 맞으면 "우리 서버가 정식으로 발급한 토큰"이라고 신뢰하고 입장을 허락한다.
 *   → 즉 api-secret 은 두 서버(우리 Spring ↔ LiveKit)만 아는 "공유 비밀"이다.
 *     이게 있어야 아무나 방에 못 들어오고, 우리 서버의 허락을 받은 사람만 들어올 수 있다.
 *
 * ■ 기본값(devkey/secret)은 LiveKit 을 "개발 모드(--dev)"로 띄웠을 때의 기본 키다.
 *   실제 서비스에서는 반드시 강력한 키/시크릿으로 바꾸고, 평문이 아니라 환경변수로 분리해야 한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "livekit")
public class LiveKitProperties {

    /** LiveKit 서버(SFU)의 WebSocket 접속 주소. 브라우저가 이 주소로 직접 붙는다. */
    private String url = "ws://localhost:7880";

    /** LiveKit API Key (개발 모드 기본값: devkey) */
    private String apiKey = "devkey";

    /** LiveKit API Secret (개발 모드 기본값: secret). 토큰 서명에 쓰인다. */
    private String apiSecret = "secret";
}
