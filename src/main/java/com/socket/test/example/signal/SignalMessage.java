package com.socket.test.example.signal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * =========================================================================
 * [2단계] WebRTC 시그널링 메시지 DTO
 * =========================================================================
 *
 * ■ WebRTC를 처음 배울 때 가장 헷갈리는 지점:
 *   "영상은 P2P(브라우저끼리 직접)로 간다면서 왜 서버가 필요하지?"
 *
 *   → 실제 영상/음성 데이터는 서버를 안 거치고 브라우저끼리 직접 흐른다. (그래서 서버 부담이 적음)
 *   → 하지만 "직접 연결을 맺기 위한 최초 협상"은 서로의 주소/코덱 정보를 교환해야 하는데,
 *     아직 P2P 연결이 없으니 이 정보를 대신 전달해 줄 "중개자"가 필요하다.
 *   → 이 중개 역할을 하는 서버가 "시그널링 서버"이고, 우리는 그 통로로 STOMP를 재사용한다.
 *
 * ■ 협상 과정(3종류 신호)을 택배에 비유하면:
 *   1) OFFER      : A가 B에게 "이 조건(영상 코덱 등)으로 연결하자!" 라고 제안 (SDP)
 *   2) ANSWER     : B가 A에게 "좋아, 나는 이 조건으로 받을게" 라고 응답 (SDP)
 *   3) CANDIDATE  : "나한테 도달할 수 있는 네트워크 경로(IP/포트)는 이거야" 라고 서로 계속 교환 (ICE)
 *
 *   OFFER/ANSWER 로 "무엇을" 주고받을지 합의하고,
 *   CANDIDATE 로 "어느 길로" 연결할지 찾는다고 이해하면 된다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SignalMessage {

    public enum SignalType {
        JOIN,       // 방에 새로 들어왔음을 알림 (기존 참가자들이 이 사람에게 OFFER를 보내는 계기가 됨)
        OFFER,      // 연결 제안 (SDP)
        ANSWER,     // 연결 응답 (SDP)
        CANDIDATE,  // 네트워크 경로 후보 (ICE candidate)
        LEAVE       // 방을 떠났음을 알림
    }

    private SignalType type;    // 신호 종류
    private String roomId;      // 어느 방인지
    private String sender;      // 보낸 사람의 고유 ID (여기선 브라우저마다 만든 랜덤 ID)
    private String receiver;    // 받을 사람의 고유 ID (특정 상대에게만 전달하기 위함. 비어있으면 방 전체 대상)

    /**
     * 실제 신호 데이터가 담기는 곳.
     *  - OFFER/ANSWER 일 때 : SDP 정보 객체
     *  - CANDIDATE 일 때    : ICE candidate 객체
     * 종류마다 형태가 다르므로 Object 로 열어두고, 실제 해석은 브라우저(JS)가 한다.
     * 서버는 내용을 들여다볼 필요 없이 "그대로 전달"만 하면 된다. (서버는 단순 우체부 역할)
     */
    private Object data;
}
