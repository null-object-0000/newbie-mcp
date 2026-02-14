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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /** OSS 顶层：原始地址（raw_url）目录，其下为 {md5(raw_url)}/，内仅存指向 mp4 目录的说明 */
    private static final String RAW_URL_PREFIX = "raw_url/";
    /** OSS 顶层：实际 MP4 地址（mp4_url）目录，其下为 {md5(mp4_url)}/，内存 url.txt 与 video.mp4 */
    private static final String MP4_URL_PREFIX = "mp4_url/";
    /** 缓存目录下 url 文本文件名（内容为 mp4 地址） */
    private static final String CACHE_URL_TXT = "url.txt";
    /** 缓存目录下视频文件名 */
    private static final String CACHE_VIDEO_MP4 = "video.mp4";
    /** raw_url 目录下指向 mp4 目录的说明文件名，内容为 mp4_url/{md5(mp4_url)} */
    private static final String RAW_TARGET_TXT = "target.txt";

    /**
     * 从给定 MP4 地址下载并上传到 OSS。mp4 内容存于 mp4_url/{md5(mp4_url)}/；若传入 raw_url，则再建 raw_url/{md5(raw_url)}/ 仅存指向该 mp4 目录的说明。
     * 先查 mp4 目录是否完整，再查（若传了 raw_url）raw 目录是否完整；命中则直接返回，否则下载并写入两处。
     *
     * @param mp4Url       MP4 下载地址（必填）
     * @param rawUrl       原始地址（可选），传入则以 md5(raw_url) 为目录名，目录内只存指向 mp4 目录的说明
     * @param ossBucket    OSS 桶名（可选）
     * @param ossObjectKey 保留兼容，实际使用 mp4_url/ 与 raw_url/ 目录
     * @return JSON：success、message、cached、bucket、objectKey、path（视频在 OSS 上的路径，不含域名）
     */
    @Tool(description = "从 MP4 地址下载并上传到 OSS。mp4 存于 mp4_url/{md5}/；可传 raw_url，则建 raw_url/{md5}/ 仅存指向 mp4 目录的说明。先查缓存再决定是否下载。返回 JSON，其中 path 为视频路径（不含完整地址）。")
    public String downloadMp4AndUploadToOss(
            @ToolParam(required = true, description = "MP4 文件的下载地址") String mp4Url,
            @ToolParam(required = false, description = "原始地址；传入则以该地址的 MD5 为目录名，目录内只存指向 mp4 目录的说明") String rawUrl,
            @ToolParam(required = false, description = "OSS 桶名，不填则使用配置文件中的 oss.bucket-name") String ossBucket,
            @ToolParam(required = false, description = "保留兼容，实际使用 mp4_url/ 与 raw_url/ 目录") String ossObjectKey) {
        if (mp4Url == null || mp4Url.isBlank()) {
            return toJson(false, "mp4Url 不能为空", null, null, null, false);
        }
        String bucket = (ossBucket != null && !ossBucket.isBlank()) ? ossBucket.trim() : ossBucketName;
        if (bucket.isBlank() || ossEndpoint.isBlank() || ossAccessKeyId.isBlank() || ossAccessKeySecret.isBlank()) {
            return toJson(false, "OSS 未配置完整，请在 application.properties 或环境变量中配置 oss.endpoint、oss.access-key-id、oss.access-key-secret、oss.bucket-name", null, null, null, false);
        }
        String mp4Md5 = md5Hex(mp4Url.trim());
        String mp4Dir = MP4_URL_PREFIX + mp4Md5 + "/";
        String urlTxtKey = mp4Dir + CACHE_URL_TXT;
        String videoKey = mp4Dir + CACHE_VIDEO_MP4;

        boolean hasRawUrl = rawUrl != null && !rawUrl.isBlank();
        String rawDir = hasRawUrl ? RAW_URL_PREFIX + md5Hex(rawUrl.trim()) + "/" : null;
        String rawTargetKey = hasRawUrl ? rawDir + RAW_TARGET_TXT : null;
        String mp4FolderRef = MP4_URL_PREFIX + mp4Md5; // raw 目录里 target.txt 的内容，指向 mp4 目录

        OSS ossClient = null;
        try {
            ossClient = new OSSClientBuilder().build(ossEndpoint, ossAccessKeyId, ossAccessKeySecret);
            boolean mp4Complete = cacheExists(ossClient, bucket, urlTxtKey, videoKey);
            if (mp4Complete) {
                if (hasRawUrl && !ossClient.doesObjectExist(bucket, rawTargetKey)) {
                    putRawPointer(ossClient, bucket, rawTargetKey, mp4FolderRef);
                }
                return toJson(true, "命中缓存", bucket, videoKey, videoKey, true);
            }
            // mp4 目录不完整：若传了 raw_url，看 raw 目录是否存在且指向的 mp4 目录是否完整，都完整则直接返回该视频 URL
            if (hasRawUrl && ossClient.doesObjectExist(bucket, rawTargetKey)) {
                String pointedMp4Ref = readOssObjectUtf8(ossClient, bucket, rawTargetKey);
                if (pointedMp4Ref != null && !pointedMp4Ref.isBlank()) {
                    String pointedUrlTxt = pointedMp4Ref.trim() + "/" + CACHE_URL_TXT;
                    String pointedVideoKey = pointedMp4Ref.trim() + "/" + CACHE_VIDEO_MP4;
                    if (cacheExists(ossClient, bucket, pointedUrlTxt, pointedVideoKey)) {
                        return toJson(true, "命中缓存（通过 raw_url 指向）", bucket, pointedVideoKey, pointedVideoKey, true);
                    }
                }
            }
            ResponseEntity<Resource> response = restTemplate.getForEntity(URI.create(mp4Url.trim()), Resource.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return toJson(false, "下载失败：HTTP " + response.getStatusCode() + "，或响应体为空", null, null, null, false);
            }
            Resource resource = response.getBody();
            long contentLength = response.getHeaders().getContentLength();
            ObjectMetadata videoMeta = new ObjectMetadata();
            if (contentLength > 0) {
                videoMeta.setContentLength(contentLength);
            }
            videoMeta.setContentType("video/mp4");
            try (InputStream inputStream = resource.getInputStream()) {
                ossClient.putObject(bucket, videoKey, inputStream, videoMeta);
            }
            byte[] urlBytes = mp4Url.trim().getBytes(StandardCharsets.UTF_8);
            ObjectMetadata txtMeta = new ObjectMetadata();
            txtMeta.setContentLength(urlBytes.length);
            txtMeta.setContentType("text/plain; charset=utf-8");
            ossClient.putObject(bucket, urlTxtKey, new ByteArrayInputStream(urlBytes), txtMeta);

            if (hasRawUrl) {
                putRawPointer(ossClient, bucket, rawTargetKey, mp4FolderRef);
            }

            return toJson(true, "上传成功", bucket, videoKey, videoKey, false);
        } catch (Exception e) {
            return toJson(false, "失败: " + e.getMessage(), null, null, null, false);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    /** 从 OSS 读取对象内容为 UTF-8 字符串，失败或不存在返回 null */
    private static String readOssObjectUtf8(OSS ossClient, String bucket, String key) {
        try (com.aliyun.oss.model.OSSObject obj = ossClient.getObject(bucket, key);
             InputStream in = obj.getObjectContent();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void putRawPointer(OSS ossClient, String bucket, String rawTargetKey, String mp4FolderRef) {
        byte[] content = mp4FolderRef.getBytes(StandardCharsets.UTF_8);
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(content.length);
        meta.setContentType("text/plain; charset=utf-8");
        ossClient.putObject(bucket, rawTargetKey, new ByteArrayInputStream(content), meta);
    }

    private static boolean cacheExists(OSS ossClient, String bucket, String urlTxtKey, String videoKey) {
        return ossClient.doesObjectExist(bucket, urlTxtKey) && ossClient.doesObjectExist(bucket, videoKey);
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private String toJson(boolean success, String message, String bucket, String objectKey, String path, boolean cached) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("message", message);
        map.put("cached", cached);
        if (bucket != null) {
            map.put("bucket", bucket);
        }
        if (objectKey != null) {
            map.put("objectKey", objectKey);
        }
        if (path != null) {
            map.put("path", path);
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"message\":\"" + message.replace("\"", "\\\"") + "\",\"cached\":false}";
        }
    }

    public static void main(String[] args) {
        McpService client = new McpService();
        System.out.println(client.helloWorld());
    }
}