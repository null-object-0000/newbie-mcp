package org.springframework.ai.mcp.sample.server;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class McpService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${oss.endpoint:}")
    private String ossEndpoint;

    @Value("${oss.access-key-id:}")
    private String ossAccessKeyId;

    @Value("${oss.access-key-secret:}")
    private String ossAccessKeySecret;

    @Value("${oss.bucket-name:}")
    private String ossBucketName;

    public McpService() {
    }

    /**
     * A sample tool. Return string 'Hello World!'
     *
     * @return String
     */
    @Tool(description = "Return string 'Hello World!'")
    public String helloWorld() {
        return "Hello World!";
    }

    /**
     * 从给定 URL 下载 MP4 并上传到配置的 OSS。
     *
     * @param mp4Url       MP4 文件的下载地址（必填）
     * @param ossBucket    OSS 桶名，不填则使用配置文件中的 oss.bucket-name
     * @param ossObjectKey OSS 对象键（路径），不填则从 mp4Url 中解析文件名，如 video.mp4
     * @return JSON 字符串，包含 success、message，成功时另有 bucket、objectKey、url
     */
    @Tool(description = "从给定的 MP4 下载地址下载文件并上传到指定的 OSS。入参：mp4Url 必填；ossBucket、ossObjectKey 可选，不填则使用配置的 bucket 和从 URL 解析的文件名。返回 JSON 结构。")
    public String downloadMp4AndUploadToOss(
            @ToolParam(required = true, description = "MP4 文件的下载地址") String mp4Url,
            @ToolParam(required = false, description = "OSS 桶名，不填则使用配置文件中的 oss.bucket-name") String ossBucket,
            @ToolParam(required = false, description = "OSS 对象键（存储路径/文件名），不填则从 mp4Url 解析文件名，如 video.mp4") String ossObjectKey) {
        if (mp4Url == null || mp4Url.isBlank()) {
            return toJson(false, "mp4Url 不能为空", null, null, null);
        }
        String bucket = (ossBucket != null && !ossBucket.isBlank()) ? ossBucket.trim() : ossBucketName;
        String objectKey = (ossObjectKey != null && !ossObjectKey.isBlank()) ? ossObjectKey.trim() : parseObjectKeyFromUrl(mp4Url);
        if (bucket.isBlank() || ossEndpoint.isBlank() || ossAccessKeyId.isBlank() || ossAccessKeySecret.isBlank()) {
            return toJson(false, "OSS 未配置完整，请在 application.properties 或环境变量中配置 oss.endpoint、oss.access-key-id、oss.access-key-secret、oss.bucket-name", null, null, null);
        }
        OSS ossClient = null;
        try {
            ResponseEntity<Resource> response = restTemplate.getForEntity(URI.create(mp4Url.trim()), Resource.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return toJson(false, "下载失败：HTTP " + response.getStatusCode() + "，或响应体为空", null, null, null);
            }
            Resource resource = response.getBody();
            long contentLength = response.getHeaders().getContentLength();
            ObjectMetadata metadata = new ObjectMetadata();
            if (contentLength > 0) {
                metadata.setContentLength(contentLength);
            }
            metadata.setContentType("video/mp4");
            ossClient = new OSSClientBuilder().build(ossEndpoint, ossAccessKeyId, ossAccessKeySecret);
            try (InputStream inputStream = resource.getInputStream()) {
                ossClient.putObject(bucket, objectKey, inputStream, metadata);
            }
            String url = "https://" + bucket + ".oss.aliyuncs.com/" + objectKey;
            return toJson(true, "上传成功", bucket, objectKey, url);
        } catch (Exception e) {
            return toJson(false, "失败: " + e.getMessage(), null, null, null);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    private String toJson(boolean success, String message, String bucket, String objectKey, String url) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("message", message);
        if (bucket != null) {
            map.put("bucket", bucket);
        }
        if (objectKey != null) {
            map.put("objectKey", objectKey);
        }
        if (url != null) {
            map.put("url", url);
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"message\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    private static String parseObjectKeyFromUrl(String url) {
        String path = url;
        try {
            path = URI.create(url).getPath();
        } catch (Exception ignored) {
        }
        if (path == null || path.isEmpty()) {
            return "video_" + System.currentTimeMillis() + ".mp4";
        }
        int last = path.lastIndexOf('/');
        String name = last >= 0 ? path.substring(last + 1) : path;
        name = name.trim().isEmpty() ? "video_" + System.currentTimeMillis() + ".mp4" : name;
        if (!name.toLowerCase().endsWith(".mp4")) {
            name = name + ".mp4";
        }
        return name;
    }

    public static void main(String[] args) {
        McpService client = new McpService();
        System.out.println(client.helloWorld());
    }
}