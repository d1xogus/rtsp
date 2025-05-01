package stream;

import lombok.Data;

@Data
public class StreamRequestDTO {
    private String streamName;
    private String cameraId;
    private String cameraPassword;
    private String cameraIp;
}
