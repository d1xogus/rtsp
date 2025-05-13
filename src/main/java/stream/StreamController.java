//package stream;
//
//import com.amazonaws.auth.AWSStaticCredentialsProvider;
//import com.amazonaws.auth.BasicAWSCredentials;
//import com.amazonaws.regions.Regions;
//import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
//import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
//import com.amazonaws.services.kinesisvideo.model.ChannelType;
//import com.amazonaws.services.kinesisvideo.model.CreateSignalingChannelRequest;
//import com.amazonaws.services.kinesisvideo.model.CreateSignalingChannelResult;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.io.BufferedReader;
//import java.io.InputStreamReader;
//import java.util.List;
//import java.util.Map;
//
//@Slf4j
//@RestController
//@RequestMapping("/api/stream")
//@RequiredArgsConstructor
//public class StreamController {
//
//    @Value("${cloud.aws.credentials.access-key}")
//    private String accessKey;
//
//    @Value("${cloud.aws.credentials.secret-key}")
//    private String secretKey;
//
//    @Value("${cloud.aws.region.static}")
//    private String region;
//
//    @PostMapping("/start")
//    public ResponseEntity<?> createAndStart(@RequestBody StreamRequestDTO request) {
//        try {
//            String channelName = request.getStreamName();
//
//            AmazonKinesisVideo client = AmazonKinesisVideoClientBuilder.standard()
//                    .withRegion(Regions.fromName(region))
//                    .withCredentials(new AWSStaticCredentialsProvider(
//                            new BasicAWSCredentials(accessKey, secretKey)))
//                    .build();
//
//            // ✅ 채널 생성
//            CreateSignalingChannelRequest createRequest = new CreateSignalingChannelRequest()
//                    .withChannelName(channelName)
//                    .withChannelType(ChannelType.SINGLE_MASTER);
//
//            CreateSignalingChannelResult result = client.createSignalingChannel(createRequest);
//            String channelArn = result.getChannelARN();
//            log.info("Channel created: {}", channelArn);
//
//            // ✅ RTSP URL 구성
//            String rtspUrl = String.format("rtsp://%s:%s@%s:554/Streaming/Channels/101/",
//                    request.getCameraId(),
//                    request.getCameraPassword(),
//                    request.getCameraIp());
//
//            // ✅ WSL을 통해 GStreamer 실행 (환경변수 inline 전달)
//            List<String> command = List.of(
//                    "wsl", "--distribution", "Ubuntu-20.04", "--", "bash", "-c",
//                    String.format(
//                            "export AWS_ACCESS_KEY_ID='%s' AWS_SECRET_ACCESS_KEY='%s' AWS_DEFAULT_REGION='%s'; " +
//                                    "cd ~/amazon-kinesis-video-streams-webrtc-sdk-c/build/samples && " +
//                                    "./kvsWebrtcClientMasterGstSample \"%s\" video-only rtspsrc \"%s\"",
//                            accessKey, secretKey, region, channelName, rtspUrl
//                    )
//            );
//
//            ProcessBuilder builder = new ProcessBuilder(command);
//            builder.redirectErrorStream(true);
//            Process process = builder.start();
//
//            new Thread(() -> {
//                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
//                    String line;
//                    while ((line = reader.readLine()) != null) {
//                        System.out.println("[GStreamer] " + line);
//                    }
//                } catch (Exception ignored) {}
//            }).start();
//
//            return ResponseEntity.ok(Map.of(
//                    "message", "Channel created and stream started",
//                    "channelArn", channelArn,
//                    "channelName", channelName
//            ));
//
//        } catch (Exception e) {
//            log.error("Error starting stream", e);
//            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
//        }
//    }
//}
package stream;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
import com.amazonaws.services.kinesisvideo.model.ChannelType;
import com.amazonaws.services.kinesisvideo.model.CreateSignalingChannelRequest;
import com.amazonaws.services.kinesisvideo.model.CreateSignalingChannelResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
public class StreamController {

    @Value("${cloud.aws.credentials.access-key}")
    private String accessKey;

    @Value("${cloud.aws.credentials.secret-key}")
    private String secretKey;

    @Value("${cloud.aws.region.static}")
    private String region;

    private ScheduledExecutorService reconnectExecutor = Executors.newScheduledThreadPool(1);
    private Process gstreamerProcess;
    private static final int RECONNECT_INTERVAL_SECONDS = 10;
    private static final int MAX_RETRIES = 5;

    @PostMapping("/start")
    public ResponseEntity<?> createAndStart(@RequestBody StreamRequestDTO request) {
        try {
            String channelName = request.getStreamName();

            AmazonKinesisVideo client = AmazonKinesisVideoClientBuilder.standard()
                    .withRegion(Regions.fromName(region))
                    .withCredentials(new AWSStaticCredentialsProvider(
                            new BasicAWSCredentials(accessKey, secretKey)))
                    .build();

            // ✅ 채널 생성
            CreateSignalingChannelRequest createRequest = new CreateSignalingChannelRequest()
                    .withChannelName(channelName)
                    .withChannelType(ChannelType.SINGLE_MASTER);

            CreateSignalingChannelResult result = client.createSignalingChannel(createRequest);
            String channelArn = result.getChannelARN();
            log.info("Channel created: {}", channelArn);

            // ✅ RTSP URL 구성
            String rtspUrl = String.format("rtsp://%s:%s@%s:554/Streaming/Channels/101/",
                    request.getCameraId(),
                    request.getCameraPassword(),
                    request.getCameraIp());

            // ✅ GStreamer 프로세스 시작
            startGStreamerProcess(channelName, rtspUrl);

            return ResponseEntity.ok(Map.of(
                    "message", "Channel created and stream started",
                    "channelArn", channelArn,
                    "channelName", channelName
            ));

        } catch (Exception e) {
            log.error("Error starting stream", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @PostMapping("/reconnect")
    public ResponseEntity<?> reconnect(@RequestBody StreamRequestDTO request) {
        try {
            String channelName = request.getStreamName();

            if (gstreamerProcess != null && gstreamerProcess.isAlive()) {
                log.info("Stopping existing GStreamer process...");
                gstreamerProcess.destroy();
                gstreamerProcess.waitFor(); // 프로세스가 완전히 종료될 때까지 대기
            }

            // ✅ RTSP URL 구성
            String rtspUrl = String.format("rtsp://%s:%s@%s:554/Streaming/Channels/101/",
                    request.getCameraId(),
                    request.getCameraPassword(),
                    request.getCameraIp());

            // ✅ GStreamer 프로세스 다시 시작
            List<String> command = List.of(
                    "wsl", "--distribution", "Ubuntu-20.04", "--", "bash", "-c",
                    String.format(
                            "export AWS_ACCESS_KEY_ID='%s' AWS_SECRET_ACCESS_KEY='%s' AWS_DEFAULT_REGION='%s'; " +
                                    "cd ~/amazon-kinesis-video-streams-webrtc-sdk-c/build/samples && " +
                                    "./kvsWebrtcClientMasterGstSample \"%s\" video-only rtspsrc \"%s\"",
                            accessKey, secretKey, region, channelName, rtspUrl
                    )
            );

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            gstreamerProcess = builder.start();

            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(gstreamerProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[GStreamer] " + line);
                    }
                } catch (Exception ignored) {}
            }).start();

            return ResponseEntity.ok(Map.of(
                    "message", "GStreamer reconnected",
                    "channelName", channelName
            ));

        } catch (Exception e) {
            log.error("Error reconnecting GStreamer", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    private void startGStreamerProcess(String channelName, String rtspUrl) {
        try {
            List<String> command = List.of(
                    "wsl", "--distribution", "Ubuntu-20.04", "--", "bash", "-c",
                    String.format(
                            "export AWS_ACCESS_KEY_ID='%s' AWS_SECRET_ACCESS_KEY='%s' AWS_DEFAULT_REGION='%s'; " +
                                    "cd ~/amazon-kinesis-video-streams-webrtc-sdk-c/build/samples && " +
                                    "./kvsWebrtcClientMasterGstSample \"%s\" video-only rtspsrc \"%s\"",
                            accessKey, secretKey, region, channelName, rtspUrl
                    )
            );

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            gstreamerProcess = builder.start();

            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(gstreamerProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[GStreamer] " + line);
                    }
                } catch (Exception ignored) {}
            }).start();

            // ✅ 자동 재접속 스케줄 설정
            scheduleReconnect(channelName, rtspUrl);

        } catch (Exception e) {
            log.error("Error starting GStreamer process", e);
        }
    }

    private void scheduleReconnect(String channelName, String rtspUrl) {
        reconnectExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!gstreamerProcess.isAlive()) {
                    log.warn("GStreamer process is not alive. Attempting to reconnect...");
                    startGStreamerProcess(channelName, rtspUrl);
                }
            } catch (Exception e) {
                log.error("Failed to reconnect GStreamer process", e);
            }
        }, RECONNECT_INTERVAL_SECONDS, RECONNECT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void stopReconnectExecutor() {
        reconnectExecutor.shutdown();
        try {
            if (!reconnectExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                reconnectExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            reconnectExecutor.shutdownNow();
        }
    }
}
