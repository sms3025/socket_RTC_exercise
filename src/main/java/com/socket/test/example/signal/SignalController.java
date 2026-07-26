package com.socket.test.example.signal;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * =========================================================================
 * [2단계 → 3단계 개선] WebRTC 시그널링 컨트롤러
 * =========================================================================
 *
 * ■ 무엇이 바뀌었나? (핵심)
 *   [기존] 모든 신호를 /topic/signal/{roomId} 로 "방 전체에 방송" 하고,
 *          받는 쪽 브라우저가 receiver 필드를 보고 자기 것만 골라 썼다.
 *          → 나와 상관없는 OFFER/CANDIDATE 까지 전부 받아서 버리는 낭비가 있었다.
 *
 *   [개선] 신호를 종류에 따라 나눠 보낸다:
 *          · JOIN / LEAVE  → 여전히 "방 전체 방송" (누가 들어오고 나갔는지는 모두 알아야 하니까)
 *          · OFFER / ANSWER / CANDIDATE → convertAndSendToUser 로 "그 상대에게만" 전송
 *          → 관계없는 사람에겐 아예 안 감. 트래픽 절약 + 딴 사람 신호가 안 보임(깔끔/보안).
 *
 * ■ 왜 JOIN 은 방송이어야 하나?
 *   새로 들어온 사람은 "방에 누가 있는지" 를 아직 모른다. 그래서 일단 방 전체에 "나 왔어!"
 *   하고 외쳐야, 기존 참가자들이 그 존재를 알고 각자 1:1 OFFER를 걸어준다.
 *   즉 "처음 서로를 발견하는 신호"는 방송, "이미 상대를 아는 신호"는 1:1 이 자연스럽다.
 *
 * ■ convertAndSendToUser(username, dest, payload) 동작 원리:
 *   - 서버가 내부적으로 /user/{username}/{dest} 로 보낸다.
 *   - 스프링이 username 에 해당하는 Principal(이름표)을 가진 세션을 찾아 실제로 전달한다.
 *   - 그 사용자의 클라이언트는 "/user" + dest, 즉 /user/queue/signal 을 구독하고 있으면 받는다.
 *     (구독 주소의 {username} 부분은 스프링이 알아서 채워주므로 클라는 신경 쓸 필요 없다.)
 */
@Controller
public class SignalController {

    private final SimpMessagingTemplate messagingTemplate;

    public SignalController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/signal/{roomId}")
    public void relaySignal(@DestinationVariable String roomId, SignalMessage message) {
        message.setRoomId(roomId);

        System.out.printf("[SIGNAL] room=%s type=%s from=%s to=%s%n",
                roomId, message.getType(), message.getSender(), message.getReceiver());

        switch (message.getType()) {
            case JOIN, LEAVE ->
                // 방 전체 공지: 이 주소를 구독한 방 안의 모든 사람이 받는다.
                messagingTemplate.convertAndSend("/topic/signal/" + roomId, message);

            case OFFER, ANSWER, CANDIDATE ->
                // 1:1 전송: receiver 로 지정된 그 사람에게만 간다.
                //   · 첫 번째 인자 receiver  = 받을 사람의 이름(Principal name). 여기선 WebRTC용 랜덤 id.
                //   · 두 번째 인자 "/queue/signal" = 받는 쪽이 구독할 주소(앞에 /user 가 자동으로 붙음)
                //   → 받는 클라이언트는 "/user/queue/signal" 을 구독하고 있으면 이 메시지를 받는다.
                messagingTemplate.convertAndSendToUser(
                        message.getReceiver(), "/queue/signal", message);

            default ->
                System.out.println("[SIGNAL] 알 수 없는 타입: " + message.getType());
        }
    }
}
