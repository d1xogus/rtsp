package stream;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
public class StreamController {
    @Value("${cloud.aws.credentials.access-key}")
    private String accessKey;
    @Value("${cloud.aws.credentials.secret-key}")
    private String secretKey;

    @PostMapping("/start")
    public ResponseEntity<String> startStream(@RequestBody StreamRequestDTO request) {
        try {
            log.info("start");
            String rtspUrl = String.format("rtsp://%s:%s@%s:554/Streaming/Channels/101/",
                    request.getCameraId(),
                    request.getCameraPassword(),
                    request.getCameraIp());

            List<String> command = List.of(
                    "/bin/bash", "-c",
                    String.format("cd ~/amazon-kinesis-video-streams-webrtc-sdk-c/build && " +
                                    "./samples/kvsWebrtcClientMasterGstSample %s video-only rtspsrc %s",
                            request.getStreamName(), rtspUrl)
            );

            ProcessBuilder builder = new ProcessBuilder(command);
            Map<String, String> env = builder.environment();
            env.put("AWS_ACCESS_KEY_ID", accessKey);
            env.put("AWS_SECRET_ACCESS_KEY", secretKey);

            builder.redirectErrorStream(true);
            Process process = builder.start();

            // 비동기 로그 처리 (옵션)
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[GStreamer] " + line);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();

            return ResponseEntity.ok("Stream started for: " + request.getStreamName());

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to start stream: " + e.getMessage());
        }
    }
}
