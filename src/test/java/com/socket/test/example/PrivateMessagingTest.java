package com.socket.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.util.List;

import com.socket.test.example.chat.ChatMessage;

/**
 * =========================================================================
 * [학습용 통합 테스트] 1:1 귓속말이 "그 사람에게만" 가는지 검증
 * =========================================================================
 *
 * ■ 이 테스트가 증명하려는 것 (이번 단계의 핵심):
 *   1) 접속할 때 넘긴 ?username=... 이 실제로 Principal(이름표)이 된다.
 *   2) convertAndSendToUser 가 그 이름표를 가진 사람에게 정확히 배달한다.
 *   3) 관계없는 제3자(carol)에겐 그 귓속말이 가지 않는다. (격리 = 트래픽 절약/프라이버시)
 *
 * ■ 브라우저 없이 어떻게 테스트하나?
 *   Spring이 제공하는 STOMP 클라이언트(WebSocketStompClient)로 브라우저처럼 접속한다.
 *   SockJsClient 를 써서 실제 프론트(webrtc/chat.html)와 동일한 SockJS 경로로 붙는다.
 *   → "SockJS로 접속해도 username 이 서버까지 잘 전달되는가" 까지 함께 검증된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrivateMessagingTest {

    @LocalServerPort
    int port;

    /** 브라우저와 동일하게 SockJS로 붙는 STOMP 클라이언트를 만든다. */
    private WebSocketStompClient newStompClient() {
        SockJsClient sockJs = new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient client = new WebSocketStompClient(sockJs);
        // JSON <-> ChatMessage 변환기 장착 (프론트가 JSON을 주고받는 것과 동일)
        // Spring Boot 4 는 Jackson 3 기반의 JacksonJsonMessageConverter 를 쓴다.
        client.setMessageConverter(new JacksonJsonMessageConverter());
        return client;
    }

    /**
     * 지정한 username 으로 접속해 STOMP 세션을 연다. (이 username 이 Principal 이름이 된다)
     * SockJS 는 먼저 http로 '/ws/info' 를 조회한 뒤 WebSocket 으로 업그레이드하므로
     * 접속 URL은 반드시 http(s) 스킴을 쓴다. ?username=... 은 서버의
     * UserHandshakeHandler 가 읽어 Principal 로 삼는다.
     */
    private StompSession connectAs(String username) throws Exception {
        String url = "http://localhost:" + port + "/ws?username=" + username;
        return newStompClient()
                .connectAsync(url, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    /** '/user/queue/chat' 을 구독하고, 도착한 메시지를 큐에 쌓아 두는 헬퍼. */
    private BlockingQueue<ChatMessage> subscribePrivateInbox(StompSession session) {
        BlockingQueue<ChatMessage> inbox = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/chat", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return ChatMessage.class; // JSON 을 이 타입으로 역직렬화
            }
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                inbox.add((ChatMessage) payload);
            }
        });
        return inbox;
    }

    @Test
    void 귓속말은_지정한_상대에게만_가고_제3자에게는_가지_않는다() throws Exception {
        // given: 세 명이 접속한다. bob 과 carol 은 각자 자기 귓속말함을 구독한다.
        StompSession alice = connectAs("alice");
        StompSession bob = connectAs("bob");
        StompSession carol = connectAs("carol");

        BlockingQueue<ChatMessage> bobInbox = subscribePrivateInbox(bob);
        BlockingQueue<ChatMessage> carolInbox = subscribePrivateInbox(carol);

        // 구독 등록이 서버에 반영될 짧은 시간을 준다 (STOMP SUBSCRIBE 는 비동기).
        Thread.sleep(300);

        // when: alice 가 bob 에게만 귓속말을 보낸다.
        ChatMessage whisper = new ChatMessage(
                ChatMessage.MessageType.WHISPER, "room1", "alice", "bob에게만 보이는 비밀", "bob");
        alice.send("/app/chat/room1/whisper", whisper);

        // then: bob 은 받는다.
        ChatMessage received = bobInbox.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getSender()).isEqualTo("alice");
        assertThat(received.getMessage()).isEqualTo("bob에게만 보이는 비밀");

        // and: carol 은 (1초를 기다려도) 받지 못한다. → 1:1 격리가 지켜진다.
        ChatMessage leaked = carolInbox.poll(1, TimeUnit.SECONDS);
        assertThat(leaked).isNull();
    }
}
