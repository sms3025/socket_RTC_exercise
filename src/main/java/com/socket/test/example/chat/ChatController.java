package com.socket.test.example.chat;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * =========================================================================
 * [1단계] STOMP 채팅 컨트롤러
 * =========================================================================
 *
 * ■ 흐름 한눈에 보기 (방 아이디가 "1" 일 때):
 *
 *   1) 브라우저 A, B, C 가 모두  /topic/chat/1  을 구독(SUBSCRIBE) 한다.
 *   2) 브라우저 A 가  /app/chat/1  로 메시지를 발행(SEND) 한다.
 *   3) "/app" 접두어이므로 아래 @MessageMapping("/chat/{roomId}") 메서드가 실행된다.
 *   4) 서버가  /topic/chat/1  로 메시지를 다시 뿌린다(broadcast).
 *   5) 그 주소를 구독 중인 A, B, C 모두가 동시에 메시지를 받는다.
 *
 *   => 이 "방 단위 구독 + 방 단위 방송" 구조가 마피아 게임 채팅의 뼈대가 된다.
 *
 * ■ REST 컨트롤러와의 차이:
 *   - @RestController + @GetMapping : HTTP 요청 1개당 응답 1개 (요청-응답)
 *   - @Controller   + @MessageMapping : 연결을 열어둔 채 메시지가 오갈 때마다 실행 (실시간)
 */
@Controller
public class ChatController {

    /**
     * SimpMessagingTemplate:
     *   서버가 "능동적으로" 특정 목적지로 메시지를 밀어 넣을 때 쓰는 도구.
     *   (@SendTo 어노테이션으로도 가능하지만, 직접 목적지를 계산해서 보낼 땐 이게 유연하다.)
     */
    private final SimpMessagingTemplate messagingTemplate;

    // 생성자 주입 (Lombok @RequiredArgsConstructor 로 줄일 수도 있지만, 학습을 위해 명시적으로 작성)
    public ChatController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 클라이언트가  /app/chat/{roomId}  로 SEND 하면 실행된다.
     *
     * @DestinationVariable : URL 의 {roomId} 부분을 꺼내온다 (REST 의 @PathVariable 과 비슷).
     * 두 번째 파라미터(ChatMessage) : 클라가 보낸 JSON 본문(payload)이 객체로 변환되어 들어옴.
     */
    @MessageMapping("/chat/{roomId}")
    public void handleChat(@DestinationVariable String roomId, ChatMessage message) {

        // 혹시 클라가 roomId 를 빠뜨렸어도 URL 값으로 채워준다 (데이터 정합성 확보).
        message.setRoomId(roomId);

        // 입장 메시지는 내용이 없을 수 있으니 서버에서 보기 좋게 만들어 준다.
        if (message.getType() == ChatMessage.MessageType.ENTER) {
            message.setMessage(message.getSender() + " 님이 입장했습니다.");
        } else if (message.getType() == ChatMessage.MessageType.LEAVE) {
            message.setMessage(message.getSender() + " 님이 퇴장했습니다.");
        }

        // 핵심: 같은 방을 구독 중인 모든 클라이언트에게 방송.
        // 목적지가 "/topic/..." 이므로 브로커가 구독자 전원에게 전달한다.
        messagingTemplate.convertAndSend("/topic/chat/" + roomId, message);
    }

    /**
     * =====================================================================
     * [3단계 추가] 1:1 귓속말 (특정 접속자 한 명에게만 전송)
     * =====================================================================
     * 클라이언트가  /app/chat/{roomId}/whisper  로 SEND 하면 실행된다.
     * message.target 에 "받을 사람 닉네임"이 담겨 온다.
     *
     * ■ 방송(convertAndSend)과의 결정적 차이:
     *   - convertAndSend("/topic/...")      → 그 주소를 구독한 "모두"에게
     *   - convertAndSendToUser("bob", ...)  → 이름표(Principal)가 "bob"인 "그 사람에게만"
     *
     * ■ 왜 sender 에게도 다시 보내나?
     *   귓속말은 받는 사람에게만 가므로, 정작 보낸 나는 내 화면에서 확인할 방법이 없다.
     *   그래서 "받는 사람"과 "보낸 나" 두 명 모두에게 보내 대화가 양쪽에 남게 한다.
     *   (카카오톡에서 내가 보낸 메시지도 내 화면에 뜨는 것과 같은 이치)
     */
    @MessageMapping("/chat/{roomId}/whisper")
    public void handleWhisper(@DestinationVariable String roomId, ChatMessage message) {
        message.setRoomId(roomId);
        message.setType(ChatMessage.MessageType.WHISPER);

        System.out.printf("[WHISPER] room=%s from=%s to=%s : %s%n",
                roomId, message.getSender(), message.getTarget(), message.getMessage());

        // 1) 받을 상대에게만 전송. target 은 그 사람이 접속할 때 넘긴 username(=Principal 이름).
        messagingTemplate.convertAndSendToUser(
                message.getTarget(), "/queue/chat", message);

        // 2) 보낸 나 자신에게도 전송 (내 화면에 "내가 보낸 귓속말"을 남기기 위해).
        //    나에게 나를 보내는 것이므로 target 이 곧 sender 인 것과 같다.
        messagingTemplate.convertAndSendToUser(
                message.getSender(), "/queue/chat", message);
    }
}
