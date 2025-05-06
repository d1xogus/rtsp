package stream;

import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.model.ChannelInfo;
import com.amazonaws.services.kinesisvideo.model.CreateSignalingChannelRequest;
import com.amazonaws.services.kinesisvideo.model.DescribeSignalingChannelRequest;
import com.amazonaws.services.kinesisvideo.model.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/stream")
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    // AWS Kinesis Video client (주입받거나 생성)
    private final AmazonKinesisVideo kinesisVideoClient;

    // GStreamer 실행 파일 경로 (예: AWS WebRTC SDK 샘플 실행 파일 또는 gst-launch 경로)
    @Value("${app.gstreamer.path}")
    private String gstreamerPath;

    // AWS 자격증명 및 지역 (필요시 설정)
    @Value("${cloud.aws.region.static}")
    private String awsRegion;
    @Value("${cloud.aws.credentials.access-key}")
    private String awsAccessKey;
    @Value("${cloud.aws.credentials.secret-key}")
    private String awsSecretKey;
    // 세션 토큰 등 필요한 경우 추가

    public StreamController(AmazonKinesisVideo kinesisVideoClient) {
        this.kinesisVideoClient = kinesisVideoClient;
    }

    @PostMapping("/start")
    public String startStreaming(@RequestBody StreamRequestDTO request) throws IOException, InterruptedException {
        String channelName = request.getStreamName();
        String cameraId = request.getCameraId();
        String cameraPassword = request.getCameraPassword();
        String cameraIp = request.getCameraIp();

        // 1. 신호 채널 존재 여부 확인 및 없으면 생성
        try {
            kinesisVideoClient.describeSignalingChannel(
                    new DescribeSignalingChannelRequest().withChannelName(channelName));
            log.info("Signaling channel '{}' already exists.", channelName);
        } catch (ResourceNotFoundException e) {
            log.info("Signaling channel '{}' not found. Creating new channel.", channelName);
            CreateSignalingChannelRequest createReq = new CreateSignalingChannelRequest()
                    .withChannelName(channelName)
                    .withChannelType("SINGLE_MASTER");
            kinesisVideoClient.createSignalingChannel(createReq);
            log.info("Signaling channel '{}' created.", channelName);
            // 채널 생성 후 잠시 대기하여 즉시 사용 가능하도록 함
            Thread.sleep(2000);
        }

        // 2. GStreamer/WebRTC 프로세스 명령어 구성 (List 형태로 인자 분리)
        List<String> command = new ArrayList<>();
        command.add(gstreamerPath);               // 실행 파일 경로 (예: "/usr/local/bin/kvsWebrtcClientMasterGstSample")
        command.add(channelName);                 // 채널 이름 인자
        command.add("video-only");                // 미디어 타입: video-only (또는 audio-video, 필요에 따라)
        command.add("rtspsrc");                   // 소스 타입: RTSP 소스 사용
        // RTSP URL 구성 (카메라 인증 정보 포함)
        String rtspUrl = String.format("rtsp://%s:%s@%s", cameraId, cameraPassword, cameraIp);
        command.add(rtspUrl);

        // ProcessBuilder 생성 및 환경 변수 설정
        ProcessBuilder pb = new ProcessBuilder(command);
        // AWS 관련 환경 변수 전달
        Map<String, String> env = pb.environment();
        env.put("AWS_ACCESS_KEY_ID", awsAccessKey);
        env.put("AWS_SECRET_ACCESS_KEY", awsSecretKey);
        env.put("AWS_DEFAULT_REGION", awsRegion);
        // 필요한 추가 환경 변수 설정 (예: AWS_SESSION_TOKEN, AWS_KVS_LOG_LEVEL 등)
        env.put("AWS_KVS_LOG_LEVEL", "3");  // 로그 레벨 (옵션)

        // 프로세스 실행
        log.info("Starting GStreamer process for channel '{}' ...", channelName);
        Process process = pb.start();

        // 3. 스트리밍 프로세스의 출력 로그를 비동기로 읽어서 로깅
        // 표준 출력 스트림 처리
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[GST-OUT] {}", line);
                }
            } catch (IOException io) {
                log.error("Error reading GStreamer output stream", io);
            }
        }).start();

        // 표준 오류 스트림 처리
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.error("[GST-ERR] {}", line);
                }
            } catch (IOException io) {
                log.error("Error reading GStreamer error stream", io);
            }
        }).start();

        // 바로 응답 반환 (스트리밍 프로세스는 백그라운드 실행 중)
        return "Streaming started for channel: " + channelName;
    }
}