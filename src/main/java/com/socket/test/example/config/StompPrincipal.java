package com.socket.test.example.config;

import java.security.Principal;

/**
 * =========================================================================
 * [3단계] 접속자 신원(Principal)
 * =========================================================================
 *
 * ■ 왜 이게 필요한가?  (이번 단계에서 가장 중요한 개념)
 *   지금까지는 /topic/... 으로 "방 전체에 방송"만 했다. 그런데 이제는
 *   "특정 한 사람에게만" 메시지를 보내고 싶다 (예: 귓속말, 마피아끼리의 밤 대화,
 *   WebRTC OFFER를 정확히 그 상대에게만).
 *
 *   서버가 convertAndSendToUser("bob", ...) 처럼 "bob에게 보내" 라고 하려면,
 *   "지금 열려 있는 수많은 WebSocket 연결 중에 누가 bob인지" 를 알아야 한다.
 *   그 "이름표" 역할을 하는 게 바로 Principal 이다.
 *
 * ■ Principal 이란?
 *   자바 표준(java.security.Principal)에서 "인증된 사용자 한 명"을 나타내는 인터페이스.
 *   메서드는 getName() 딱 하나뿐이다. 즉 "이 연결의 이름이 뭐냐"만 알려주면 된다.
 *
 * ■ 보통은 Spring Security가 로그인 사용자로 이 Principal을 자동으로 채워준다.
 *   하지만 이 연습 프로젝트엔 로그인/시큐리티가 없으므로,
 *   "접속할 때 넘긴 username" 을 그대로 이름으로 쓰는 아주 단순한 Principal을 직접 만든다.
 *   (실제 서비스라면 이 자리에 로그인된 회원 ID가 들어갈 것이다.)
 */
public class StompPrincipal implements Principal {

    private final String name;

    public StompPrincipal(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name; // 이 값이 곧 convertAndSendToUser(여기, ...) 의 "여기" 와 매칭된다.
    }
}
