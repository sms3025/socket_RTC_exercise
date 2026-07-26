package com.socket.test.example.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * =========================================================================
 * [1단계] STOMP over WebSocket 설정
 * =========================================================================
 *
 * ■ WebSocket vs STOMP 가 헷갈릴 수 있어서 먼저 정리:
 *   - WebSocket : 브라우저와 서버가 "연결을 계속 열어두고" 양방향으로 데이터를
 *                 주고받는 "저수준(low-level) 통로". 그냥 바이트/문자열이 오갈 뿐,
 *                 "이 메시지는 어느 방으로 보내라" 같은 규칙이 없음.
 *   - STOMP     : 그 통로 위에서 쓰는 "약속(프로토콜)". HTTP 위에 REST 규칙을
 *                 얹듯이, WebSocket 위에 STOMP 규칙을 얹는다고 생각하면 됨.
 *                 SUBSCRIBE(구독) / SEND(발행) 같은 명령어와 "목적지(destination)"
 *                 주소 개념이 생겨서 pub/sub(발행-구독) 구조를 쉽게 만들 수 있음.
 *
 * ■ 왜 마피아 게임에 STOMP를 쓰나?
 *   - "1번 방"에 있는 사람들에게만 채팅/게임 이벤트를 뿌려야 함 → 방(topic) 단위 구독
 *   - 특정 유저에게만 몰래 메시지(예: 마피아끼리의 밤 대화) → user 단위 큐
 *   - WebRTC 영상 연결을 위한 신호(offer/answer/candidate) 전달 통로로도 재사용
 */
@Configuration
@EnableWebSocketMessageBroker // STOMP 메시지 브로커 기능 켜기 (이 어노테이션이 핵심 스위치)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * ① STOMP 접속 엔드포인트 등록
     *    - 브라우저(클라이언트)가 "여기로 WebSocket 연결 맺어줘" 하고 접속하는 주소.
     *    - 접속 스킴(ws vs wss)은 이 코드가 아니라 "페이지가 http냐 https냐"로 결정된다.
     *        · http://localhost:8080  으로 열면  →  ws://.../ws   (평문)
     *        · https://localhost:8443 으로 열면  →  wss://.../ws  (암호화)  ← 지금 설정
     *    - application.properties 에서 SSL을 켰으므로 실제로는 wss://localhost:8443/ws 로 연결된다.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // 개발 편의를 위해 모든 출처 허용. 실제 서비스에서는 프론트 도메인만 허용해야 함.
                .setAllowedOriginPatterns("*")
                // [3단계 추가] 접속 순간에 username 을 읽어 Principal(이름표)을 붙이는 핸들러.
                //   이게 있어야 convertAndSendToUser("특정유저", ...) 로 1:1 전송이 가능해진다.
                .setHandshakeHandler(new UserHandshakeHandler())
                // SockJS: WebSocket을 지원하지 않는 환경에서 폴백(fallback) 해주는 라이브러리.
                //         브라우저 호환성을 위해 켜두면 안전하다.
                .withSockJS();
    }

    /**
     * ② 메시지 라우팅(주소 체계) 설정
     *
     *   메시지는 크게 두 방향으로 흐른다:
     *
     *   [클라이언트 → 서버]  목적지가 "/app" 으로 시작
     *       예) 클라가 /app/chat/1 로 SEND  ->  @MessageMapping("/chat/{roomId}") 실행
     *
     *   [서버 → 클라이언트]  목적지가 "/topic" 또는 "/queue" 로 시작
     *       예) 서버가 /topic/chat/1 로 발행  ->  그 주소를 구독(SUBSCRIBE)한 모든 클라가 받음
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {

        // "/app" 으로 시작하는 목적지는 @MessageMapping 이 붙은 컨트롤러 메서드로 전달된다.
        // 즉 클라이언트가 서버의 "로직"을 호출하고 싶을 때 쓰는 접두어.
        registry.setApplicationDestinationPrefixes("/app");

        // 내장(Simple) 메시지 브로커 활성화.
        //  - /topic : 여러 명이 함께 받는 "방송(broadcast)" 채널 (예: 방 전체 채팅)
        //  - /queue : 특정 한 명에게 보내는 "1:1" 채널 (예: 마피아만 보는 밤 정보)
        //  ※ 지금은 스프링 내장 브로커라 서버 1대에서만 동작. 서버를 여러 대로 늘리면
        //    RabbitMQ / Kafka 같은 "외부 브로커"로 교체해야 하는데, 연습 단계에선 내장으로 충분.
        registry.enableSimpleBroker("/topic", "/queue");

        // convertAndSendToUser(...) 로 특정 유저에게 보낼 때 앞에 붙는 접두어. 기본값이 "/user".
        registry.setUserDestinationPrefix("/user");
    }
}
