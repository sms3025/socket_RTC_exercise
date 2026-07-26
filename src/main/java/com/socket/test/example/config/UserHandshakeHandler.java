package com.socket.test.example.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * =========================================================================
 * [3단계] 핸드셰이크 핸들러 - 접속 순간에 "이름표(Principal)"를 붙여준다
 * =========================================================================
 *
 * ■ 핸드셰이크(handshake)란?
 *   WebSocket 연결이 처음 맺어지는 "악수" 과정. 이 순간에 딱 한 번 실행된다.
 *   우리는 이 타이밍에 "이 연결의 주인이 누구인지" 를 정해서 Principal로 붙여둔다.
 *   한번 붙여두면, 이후 그 연결로 오가는 모든 메시지에 이 신원이 따라다닌다.
 *
 * ■ 클라이언트는 접속할 때 주소에 username 을 실어 보낸다:
 *       new SockJS('/ws?username=홍길동')
 *   서버(여기)는 그 username 을 꺼내 Principal 이름으로 삼는다.
 *
 * ■ determineUser() 를 오버라이드하면 스프링이 이 결과를 해당 세션의 Principal로 저장한다.
 *   그러면 이후 convertAndSendToUser("홍길동", ...) 가 이 연결을 정확히 찾아갈 수 있다.
 */
public class UserHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                      WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {

        // 접속 URL의 쿼리스트링에서 username 을 꺼낸다. 예: "/ws?username=%ED%99%8D..."
        String username = extractUsername(request);

        if (username == null || username.isBlank()) {
            // username 을 안 넘겼다면(혹은 전달 실패) 임의의 이름을 부여한다.
            // → 신원이 null 이 되면 convertAndSendToUser 가 조용히 실패하므로,
            //   이렇게 폴백을 두면 "왜 안 오지?" 하는 상황을 예방할 수 있다.
            username = "anonymous-" + UUID.randomUUID().toString().substring(0, 8);
            System.out.println("[HANDSHAKE] username이 없어 임시 이름 부여: " + username);
        } else {
            System.out.println("[HANDSHAKE] 접속 신원(Principal) 확정: " + username);
        }

        // 이 Principal이 곧 이 WebSocket 세션의 "이름표"가 된다.
        return new StompPrincipal(username);
    }

    /**
     * 쿼리스트링(raw)에서 username 값을 파싱해 URL 디코딩까지 해준다.
     * 예) rawQuery = "username=%ED%99%8D%EA%B8%B8%EB%8F%99" → "홍길동"
     * (한글/공백이 % 인코딩되어 오므로 반드시 디코딩해야 한다.)
     */
    private String extractUsername(ServerHttpRequest request) {
        String rawQuery = request.getURI().getRawQuery();
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals("username")) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
