package com.socket.test.example.livekit;

import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import retrofit2.Response;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * =========================================================================
 * [5단계] LiveKit 서버 강제 제어 컨트롤러 (진행자/관리자 기능)
 * =========================================================================
 *
 * ■ 무엇을 하나?
 *   4단계까지는 "각자 자기 카메라/마이크를 켜고 끄는" 것까지만 했다.
 *   5단계에서는 서버(진행자)가 "특정 참가자의 카메라/마이크를 강제로 끄거나 켜는" 것을 배운다.
 *     예) 마피아 게임에서 "죽은 사람은 말 못 함" → 진행자가 그 사람 마이크를 강제 OFF
 *         "밤이 되면 모두 카메라 OFF"                → 진행자가 전원 카메라를 강제 OFF
 *
 * ■ 왜 서버가 강제할 수 있나? (SFU 의 강력한 점)
 *   Mesh(2·3단계)에서는 미디어가 브라우저끼리 직접 흐르므로 서버가 중간에서 통제할 방법이 없었다.
 *   SFU(LiveKit)에서는 모든 미디어가 LiveKit 서버를 통과하므로, 서버가 "이 트랙 그만 보내"라고
 *   명령하면 실제로 그 사람의 영상/음성이 끊긴다. 클라이언트가 조작해도 소용없다(서버가 최종 결정).
 *
 * ■ 어떻게 명령하나? — RoomServiceClient (LiveKit 서버 관리 API)
 *   토큰 발급(LiveKitTokenController)은 브라우저에게 "입장권"을 내주는 일이었다.
 *   반면 여기서는 우리 Spring 서버가 "LiveKit 서버에게 직접 REST 명령"을 보낸다.
 *     · RoomServiceClient : LiveKit 서버의 관리용 REST API 를 호출하는 클라이언트(서버 SDK 제공).
 *     · api-key/secret 으로 인증되므로, 아무 서버나 이 명령을 못 내린다(우리 서버만 가능).
 *     · mutePublishedTrack(room, identity, trackSid, muted) : 지정한 트랙을 mute/unmute.
 *         - muted=true  → 강제 OFF (그 트랙 전송 중단)
 *         - muted=false → 강제 ON  (다시 전송) ※ 서버 정책에 따라 막힐 수 있음(아래 주의 참고)
 *
 * ■ 전체 흐름
 *     브라우저(진행자 UI) ─POST /api/livekit/mute?room=1&identity=bob&source=microphone&muted=true─▶ (우리 Spring 서버)
 *                                                                                                        │
 *                        우리 서버 ─RoomServiceClient.mutePublishedTrack(...)─▶ (LiveKit 서버) ◀────────┘
 *                                                                                     │
 *                        bob 의 브라우저는 "마이크가 꺼졌다"는 이벤트(TrackMuted)를 받아 UI 가 갱신됨 ◀
 *
 * ⚠️ 강제 "켜기(unmute)" 주의:
 *   프라이버시 때문에 LiveKit 은 서버가 참가자 트랙을 마음대로 "다시 켜는" 것을 기본적으로
 *   막아둔 버전이 많다. (동의 없이 남의 마이크를 켜면 도청이 되니까)
 *   개발(--dev) 모드에서는 대체로 동작하지만, 안 되면 "강제 켜기"는 무시될 수 있다.
 *   그럴 땐 서버가 "켜 달라"는 신호만 보내고 최종 켜기는 사용자가 누르게 설계하는 게 정석이다.
 */
@RestController
public class LiveKitAdminController {

    /**
     * LiveKit 서버의 관리 API 를 호출하는 클라이언트.
     * 생성자에서 딱 한 번 만들어 재사용한다(내부적으로 HTTP 커넥션 풀을 들고 있으므로 매 요청 새로 만들 필요 없음).
     */
    private final RoomServiceClient roomService;

    public LiveKitAdminController(LiveKitProperties props) {
        // ── RoomServiceClient 는 "관리용 REST" 이므로 http(s) 주소가 필요하다. ──
        //   application.properties 의 livekit.url 은 브라우저 접속용이라 ws:// 형태다(예: ws://localhost:7880).
        //   관리 API 는 같은 호스트의 http(s) 로 접근하므로 스킴만 바꿔준다. (ws→http, wss→https)
        String httpUrl = props.getUrl()
                .replaceFirst("^wss://", "https://")
                .replaceFirst("^ws://", "http://");

        // create(host, apiKey, apiSecret): 이 키/시크릿으로 관리 요청에 서명해 LiveKit 서버가 "정품 명령"임을 검증한다.
        this.roomService = RoomServiceClient.create(httpUrl, props.getApiKey(), props.getApiSecret());
    }

    /**
     * 특정 참가자의 카메라 또는 마이크를 서버가 강제로 끄거나 켠다.
     *
     * 예) POST /api/livekit/mute?room=1&identity=bob&source=microphone&muted=true
     *
     * @param room     대상이 있는 방 이름
     * @param identity 대상 참가자의 식별자(토큰 발급 때 준 identity 와 같아야 함)
     * @param source   "camera"(카메라) 또는 "microphone"(마이크)
     * @param muted    true=강제 OFF, false=강제 ON
     */
    @PostMapping("/api/livekit/mute")
    public ResponseEntity<Map<String, Object>> muteTrack(@RequestParam String room,
                                                         @RequestParam String identity,
                                                         @RequestParam String source,
                                                         @RequestParam boolean muted) throws IOException {

        // ── 1) source 문자열을 LiveKit 의 트랙 소스(enum)로 변환 ──────────────
        //   LiveKit 은 트랙마다 "출처(source)"를 구분한다: CAMERA(카메라), MICROPHONE(마이크),
        //   SCREEN_SHARE(화면공유) 등. 우리는 카메라/마이크만 다룬다.
        LivekitModels.TrackSource targetSource = switch (source.toLowerCase()) {
            case "camera", "cam", "video" -> LivekitModels.TrackSource.CAMERA;
            case "microphone", "mic", "audio" -> LivekitModels.TrackSource.MICROPHONE;
            default -> null;
        };
        if (targetSource == null) {
            return ResponseEntity.badRequest().body(error("source 는 camera 또는 microphone 이어야 합니다: " + source));
        }

        // ── 2) 대상 참가자 정보 조회 ──────────────────────────────────────────
        //   retrofit 의 Call 은 .execute() 를 호출해야 실제로 네트워크 요청이 나간다(동기 호출).
        //   응답 안에는 그 참가자가 지금 발행 중인 트랙 목록이 들어있다.
        Response<LivekitModels.ParticipantInfo> participantResp =
                roomService.getParticipant(room, identity).execute();

        if (!participantResp.isSuccessful() || participantResp.body() == null) {
            System.out.printf("[LIVEKIT-ADMIN] 참가자 없음: room=%s identity=%s (code=%d)%n",
                    room, identity, participantResp.code());
            return ResponseEntity.status(404).body(error("참가자를 찾을 수 없습니다: " + identity));
        }
        LivekitModels.ParticipantInfo participant = participantResp.body();

        // ── 3) 그 참가자의 트랙 중 원하는 source(카메라/마이크) 트랙을 찾는다 ──
        //   각 트랙에는 고유 ID(trackSid)가 있고, mute 명령에는 이 trackSid 가 필요하다.
        String trackSid = null;
        for (LivekitModels.TrackInfo track : participant.getTracksList()) {
            if (track.getSource() == targetSource) {
                trackSid = track.getSid();
                break;
            }
        }
        if (trackSid == null) {
            // 상대가 아직 그 장치를 켜지 않았거나(발행 전) 이미 트랙이 없는 상태.
            return ResponseEntity.status(404)
                    .body(error(identity + " 에게 " + source + " 트랙이 없습니다(아직 켜지 않았을 수 있음)."));
        }

        // ── 4) 실제 강제 mute/unmute 명령 전송 ────────────────────────────────
        Response<LivekitModels.TrackInfo> muteResp =
                roomService.mutePublishedTrack(room, identity, trackSid, muted).execute();

        if (!muteResp.isSuccessful()) {
            // unmute(강제 켜기)가 서버 정책상 막힌 경우 여기로 올 수 있다(위 클래스 주석의 ⚠️ 참고).
            System.out.printf("[LIVEKIT-ADMIN] mute 실패: room=%s identity=%s source=%s muted=%s (code=%d)%n",
                    room, identity, source, muted, muteResp.code());
            return ResponseEntity.status(502)
                    .body(error("LiveKit 서버가 명령을 거부했습니다(code=" + muteResp.code()
                            + "). 강제 켜기는 서버 정책상 막혀 있을 수 있습니다."));
        }

        System.out.printf("[LIVEKIT-ADMIN] 강제 %s: room=%s identity=%s source=%s trackSid=%s%n",
                muted ? "OFF" : "ON", room, identity, source, trackSid);

        // 성공 응답: 프론트가 어떤 조치가 됐는지 화면에 표시할 수 있게 정보를 담아 돌려준다.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("room", room);
        body.put("identity", identity);
        body.put("source", source);
        body.put("muted", muted);
        body.put("trackSid", trackSid);
        return ResponseEntity.ok(body);
    }

    /** 에러 응답 JSON({"error": "..."})을 만드는 작은 헬퍼. */
    private Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
