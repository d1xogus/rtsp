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

    @PostMapping("/start")
    public ResponseEntity<?> createAndStart(@RequestBody StreamRequestDTO request) {
        try {
            String channelName = request.getStreamName();

            AmazonKinesisVideo client = AmazonKinesisVideoClientBuilder.standard()
                    .withRegion(Regions.fromName(region))
                    .withCredentials(new AWSStaticCredentialsProvider(
                            new BasicAWSCredentials(accessKey, secretKey)))
                    .build();

            // ✅ Role 없이 채널 생성
            CreateSignalingChannelRequest createRequest = new CreateSignalingChannelRequest()
                    .withChannelName(channelName)
                    .withChannelType(ChannelType.SINGLE_MASTER);  // Role 없이도 충분

            CreateSignalingChannelResult result = client.createSignalingChannel(createRequest);
            String channelArn = result.getChannelARN();
            log.info("Channel created: {}", channelArn);

            // ✅ RTSP URL 구성
            String rtspUrl = String.format("rtsp://%s:%s@%s:554/Streaming/Channels/101/",
                    request.getCameraId(),
                    request.getCameraPassword(),
                    request.getCameraIp());

            // ✅ GStreamer 실행
            List<String> command = List.of(
                    "/bin/bash", "-c",
                    String.format("cd ~/amazon-kinesis-video-streams-webrtc-sdk-c/build && " +
                                    "./samples/kvsWebrtcClientMasterGstSample \"%s\" video-only rtspsrc \"%s\"",
                            channelName, rtspUrl)
            );

            ProcessBuilder builder = new ProcessBuilder(command);
            Map<String, String> env = builder.environment();
            env.put("AWS_ACCESS_KEY_ID", accessKey);
            env.put("AWS_SECRET_ACCESS_KEY", secretKey);
            env.put("AWS_DEFAULT_REGION", region);

            builder.redirectErrorStream(true);
            Process process = builder.start();

            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[GStreamer] " + line);
                    }
                } catch (Exception ignored) {}
            }).start();

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
}
