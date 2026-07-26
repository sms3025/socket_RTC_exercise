package com.socket.test.example.chat;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * =========================================================================
 * [1단계] 채팅 메시지 DTO (Data Transfer Object)
 * =========================================================================
 *
 * ■ 이 객체가 그대로 JSON <-> 자바 객체로 자동 변환된다.
 *   - 클라이언트가 보내는 JSON 예:  { "type": "TALK", "sender": "홍길동", "message": "안녕!" }
 *   - 스프링이 이 JSON을 ChatMessage 객체로 바꿔서 컨트롤러에 넘겨준다. (Jackson 라이브러리가 처리)
 *
 * ■ Lombok 어노테이션 정리 (build.gradle 에 lombok 이 이미 들어있음):
 *   - @Getter / @Setter        : get/set 메서드 자동 생성
 *   - @NoArgsConstructor        : 기본 생성자 (JSON -> 객체 변환 시 필요)
 *   - @AllArgsConstructor       : 모든 필드를 받는 생성자 (서버에서 객체 만들 때 편함)
 *   - @ToString                 : 로그 찍을 때 보기 좋게
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ChatMessage {

    /**
     * 메시지 종류.
     *  - ENTER   : 입장 (누가 방에 들어옴)
     *  - TALK    : 일반 대화 (방 전체 방송)
     *  - LEAVE   : 퇴장
     *  - WHISPER : 귓속말 (특정 상대 1명에게만) ← [3단계 추가]
     * enum 으로 관리하면 오타를 줄이고, 나중에 마피아 게임에서
     * VOTE(투표), NIGHT(밤 시작) 같은 이벤트 타입으로 확장하기 좋다.
     */
    public enum MessageType {
        ENTER, TALK, LEAVE, WHISPER
    }

    private MessageType type;  // 메시지 종류
    private String roomId;     // 어느 방인지
    private String sender;     // 보낸 사람 닉네임 (= 접속 시 넘긴 username = Principal 이름)
    private String message;    // 실제 내용

    /**
     * [3단계 추가] 귓속말을 받을 상대의 닉네임(username).
     *  - WHISPER 일 때만 사용된다. (TALK/ENTER/LEAVE 에선 비어 있음)
     *  - 서버는 이 값으로 convertAndSendToUser(target, ...) 를 호출해 그 사람에게만 보낸다.
     */
    private String target;
}
