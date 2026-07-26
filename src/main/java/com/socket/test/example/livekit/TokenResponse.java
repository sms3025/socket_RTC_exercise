package com.socket.test.example.livekit;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * =========================================================================
 * [4단계] 토큰 발급 응답 DTO
 * =========================================================================
 *
 * 브라우저(livekit.html)가 "/api/livekit/token" 을 호출하면 이 형태의 JSON 을 돌려준다.
 *
 *   {
 *     "url":      "ws://localhost:7880",   // 브라우저가 접속할 LiveKit 서버 주소
 *     "token":    "eyJhbGciOi...",         // 이 방·이 사람 전용 입장권(JWT)
 *     "room":     "1",                     // 어떤 방인지 (화면 표시용)
 *     "identity": "alice"                  // 나의 식별자 (화면 표시용)
 *   }
 *
 * ■ 프론트는 이 두 가지(url, token)만 있으면 LiveKit 서버에 곧바로 접속할 수 있다.
 *   room/identity 는 사실 token 안에도 들어있지만, 화면에 표시하기 편하라고 같이 내려준다.
 */
@Getter
@AllArgsConstructor
public class TokenResponse {
    private final String url;      // LiveKit 서버 WebSocket 주소
    private final String token;    // 접속용 JWT (이 방/이 사람 전용, 유효기간 있음)
    private final String room;     // 방 이름
    private final String identity; // 참가자 식별자
}
