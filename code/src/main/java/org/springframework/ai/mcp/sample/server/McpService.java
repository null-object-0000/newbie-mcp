package org.springframework.ai.mcp.sample.server;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class McpService {

    private final RestTemplate restTemplate = new RestTemplate();

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
     * @return 对象：success、message、cached、bucket、objectKey、path（视频在 OSS 上的路径，不含域名）
     */
    @Tool(description = "从 MP4 地址下载并上传到 OSS。mp4 存于 mp4_url/{md5}/；可传 raw_url，则建 raw_url/{md5}/ 仅存指向 mp4 目录的说明。先查缓存再决定是否下载。直接返回结果对象。")
    public Map<String, Object> downloadMp4AndUploadToOss(
            @ToolParam(required = true, description = "MP4 文件的下载地址") String mp4Url,
            @ToolParam(required = false, description = "原始地址；传入则以该地址的 MD5 为目录名，目录内只存指向 mp4 目录的说明") String rawUrl,
            @ToolParam(required = false, description = "OSS 桶名，不填则使用配置文件中的 oss.bucket-name") String ossBucket,
            @ToolParam(required = false, description = "保留兼容，实际使用 mp4_url/ 与 raw_url/ 目录") String ossObjectKey) {
        if (StrUtil.isBlank(mp4Url)) {
            return toResult(false, "mp4Url 不能为空", null, null, null, false);
        }
        String bucket = StrUtil.isNotBlank(ossBucket) ? ossBucket.trim() : ossBucketName;
        if (StrUtil.isBlank(bucket) || StrUtil.isBlank(ossEndpoint) || StrUtil.isBlank(ossAccessKeyId) || StrUtil.isBlank(ossAccessKeySecret)) {
            return toResult(false, "OSS 未配置完整，请在 application.properties 或环境变量中配置 oss.endpoint、oss.access-key-id、oss.access-key-secret、oss.bucket-name", null, null, null, false);
        }
        String mp4Md5 = DigestUtil.md5Hex(mp4Url.trim());
        String mp4Dir = MP4_URL_PREFIX + mp4Md5 + "/";
        String urlTxtKey = mp4Dir + CACHE_URL_TXT;
        String videoKey = mp4Dir + CACHE_VIDEO_MP4;

        boolean hasRawUrl = StrUtil.isNotBlank(rawUrl);
        String rawDir = hasRawUrl ? RAW_URL_PREFIX + DigestUtil.md5Hex(rawUrl.trim()) + "/" : null;
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
                return toResult(true, "命中缓存", bucket, videoKey, videoKey, true);
            }
            // mp4 目录不完整：若传了 raw_url，看 raw 目录是否存在且指向的 mp4 目录是否完整，都完整则直接返回该视频 URL
            if (hasRawUrl && ossClient.doesObjectExist(bucket, rawTargetKey)) {
                String pointedMp4Ref = readOssObjectUtf8(ossClient, bucket, rawTargetKey);
                if (StrUtil.isNotBlank(pointedMp4Ref)) {
                    String pointedUrlTxt = pointedMp4Ref.trim() + "/" + CACHE_URL_TXT;
                    String pointedVideoKey = pointedMp4Ref.trim() + "/" + CACHE_VIDEO_MP4;
                    if (cacheExists(ossClient, bucket, pointedUrlTxt, pointedVideoKey)) {
                        return toResult(true, "命中缓存（通过 raw_url 指向）", bucket, pointedVideoKey, pointedVideoKey, true);
                    }
                }
            }
            ResponseEntity<Resource> response = restTemplate.getForEntity(URI.create(mp4Url.trim()), Resource.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return toResult(false, "下载失败：HTTP " + response.getStatusCode() + "，或响应体为空", null, null, null, false);
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
            ObjectMetadata txtMeta = new ObjectMetadata();
            txtMeta.setContentLength(StrUtil.utf8Bytes(mp4Url.trim()).length);
            txtMeta.setContentType("text/plain; charset=utf-8");
            ossClient.putObject(bucket, urlTxtKey, new ByteArrayInputStream(StrUtil.utf8Bytes(mp4Url.trim())), txtMeta);

            if (hasRawUrl) {
                putRawPointer(ossClient, bucket, rawTargetKey, mp4FolderRef);
            }

            return toResult(true, "上传成功", bucket, videoKey, videoKey, false);
        } catch (Exception e) {
            return toResult(false, "失败: " + e.getMessage(), null, null, null, false);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    /** 从 OSS 读取对象内容为 UTF-8 字符串，失败或不存在返回 null */
    private static String readOssObjectUtf8(OSS ossClient, String bucket, String key) {
        try (com.aliyun.oss.model.OSSObject obj = ossClient.getObject(bucket, key);
             InputStream in = obj.getObjectContent()) {
            return IoUtil.readUtf8(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static void putRawPointer(OSS ossClient, String bucket, String rawTargetKey, String mp4FolderRef) {
        byte[] content = StrUtil.utf8Bytes(mp4FolderRef);
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(content.length);
        meta.setContentType("text/plain; charset=utf-8");
        ossClient.putObject(bucket, rawTargetKey, new ByteArrayInputStream(content), meta);
    }

    private static boolean cacheExists(OSS ossClient, String bucket, String urlTxtKey, String videoKey) {
        return ossClient.doesObjectExist(bucket, urlTxtKey) && ossClient.doesObjectExist(bucket, videoKey);
    }

    /**
     * 根据原始地址（raw_url）查询 OSS 中是否已存在对应视频。先查 raw_url/{md5}/target.txt，再查指向的 mp4 目录是否完整。
     *
     * @param rawUrl    原始地址（必填）
     * @param ossBucket OSS 桶名（可选）
     * @return 对象：success、exists（是否已存在视频）、path（存在时为视频路径）、message、bucket
     */
    @Tool(description = "根据原始地址（raw_url）查询是否已经存在对应视频。先查 raw_url 目录及指向的 mp4 目录是否完整，存在则返回 path。直接返回结果对象。")
    public Map<String, Object> existsVideoByRawUrl(
            @ToolParam(required = true, description = "原始地址（raw_url）") String rawUrl,
            @ToolParam(required = false, description = "OSS 桶名，不填则使用配置文件中的 oss.bucket-name") String ossBucket) {
        if (StrUtil.isBlank(rawUrl)) {
            return toResultExists(false, false, "rawUrl 不能为空", null, null);
        }
        String bucket = StrUtil.isNotBlank(ossBucket) ? ossBucket.trim() : ossBucketName;
        if (StrUtil.isBlank(bucket) || StrUtil.isBlank(ossEndpoint) || StrUtil.isBlank(ossAccessKeyId) || StrUtil.isBlank(ossAccessKeySecret)) {
            return toResultExists(false, false, "OSS 未配置完整", null, null);
        }
        String rawTargetKey = RAW_URL_PREFIX + DigestUtil.md5Hex(rawUrl.trim()) + "/" + RAW_TARGET_TXT;
        OSS ossClient = null;
        try {
            ossClient = new OSSClientBuilder().build(ossEndpoint, ossAccessKeyId, ossAccessKeySecret);
            if (!ossClient.doesObjectExist(bucket, rawTargetKey)) {
                return toResultExists(true, false, "该原始地址下暂无视频", bucket, null);
            }
            String pointedMp4Ref = readOssObjectUtf8(ossClient, bucket, rawTargetKey);
            if (StrUtil.isBlank(pointedMp4Ref)) {
                return toResultExists(true, false, "raw 指向内容无效", bucket, null);
            }
            String pointedUrlTxt = pointedMp4Ref.trim() + "/" + CACHE_URL_TXT;
            String pointedVideoKey = pointedMp4Ref.trim() + "/" + CACHE_VIDEO_MP4;
            if (!cacheExists(ossClient, bucket, pointedUrlTxt, pointedVideoKey)) {
                return toResultExists(true, false, "指向的 mp4 目录不完整", bucket, null);
            }
            return toResultExists(true, true, "已存在视频", bucket, pointedVideoKey);
        } catch (Exception e) {
            return toResultExists(false, false, "查询失败: " + e.getMessage(), null, null);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    private static Map<String, Object> toResultExists(boolean success, boolean exists, String message, String bucket, String path) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("exists", exists);
        map.put("message", message);
        if (bucket != null) {
            map.put("bucket", bucket);
        }
        if (path != null) {
            map.put("path", path);
        }
        return map;
    }

    private static Map<String, Object> toResult(boolean success, String message, String bucket, String objectKey, String path, boolean cached) {
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
        return map;
    }

    public static void main(String[] args) {
        McpService client = new McpService();
        System.out.println(client.helloWorld());
    }
}