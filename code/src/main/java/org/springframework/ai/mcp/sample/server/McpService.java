package org.springframework.ai.mcp.sample.server;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.aliyun.oss.OSS;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
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

	/** 抖音短链正则（与 Dify 中 [CODE] 检测抖音链接 一致） */
	private static final java.util.regex.Pattern DOUYIN_LINK_PATTERN = java.util.regex.Pattern
			.compile("https?://v\\.douyin\\.com/[a-zA-Z0-9_-]+/?");

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
	 * 从给定 MP4 地址下载并上传到 OSS。mp4 内容存于 mp4_url/{md5(mp4_url)}/；若传入 raw_url，则再建
	 * raw_url/{md5(raw_url)}/ 仅存指向该 mp4 目录的说明。
	 * 先查 mp4 目录是否完整，再查（若传了 raw_url）raw 目录是否完整；命中则直接返回，否则下载并写入两处。
	 *
	 * @param mp4Url    MP4 下载地址（必填）
	 * @param rawUrl    原始地址（可选），传入则以 md5(raw_url) 为目录名，目录内只存指向 mp4 目录的说明
	 * @param ossBucket OSS 桶名（可选）
	 * @return 对象：success、message、cached、bucket、path（视频在 OSS 上的路径，不含域名）
	 */
	@Tool(description = "从 MP4 地址下载并上传到 OSS。mp4 存于 mp4_url/{md5}/；可传 raw_url，则建 raw_url/{md5}/ 仅存指向 mp4 目录的说明。先查缓存再决定是否下载。直接返回结果对象。")
	public Map<String, Object> downloadMp4AndUploadToOss(
			@ToolParam(required = true, description = "MP4 文件的下载地址") String mp4Url,
			@ToolParam(required = false, description = "原始地址；传入则以该地址的 MD5 为目录名，目录内只存指向 mp4 目录的说明") String rawUrl,
			@ToolParam(required = false, description = "OSS 桶名，不填则使用配置文件中的 oss.bucket-name") String ossBucket) {
		if (StrUtil.isBlank(mp4Url)) {
			return toResult(false, "mp4Url 不能为空", null, null, false);
		}
		String bucket = StrUtil.isNotBlank(ossBucket) ? ossBucket.trim() : ossBucketName;
		if (StrUtil.isBlank(bucket) || StrUtil.isBlank(ossEndpoint) || StrUtil.isBlank(ossAccessKeyId)
				|| StrUtil.isBlank(ossAccessKeySecret)) {
			return toResult(false,
					"OSS 未配置完整，请在 application.properties 或环境变量中配置 oss.endpoint、oss.access-key-id、oss.access-key-secret、oss.bucket-name",
					null, null, false);
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
				return toResult(true, "命中缓存", bucket, videoKey, true);
			}
			// mp4 目录不完整：若传了 raw_url，看 raw 目录是否存在且指向的 mp4 目录是否完整，都完整则直接返回该视频 URL
			if (hasRawUrl && ossClient.doesObjectExist(bucket, rawTargetKey)) {
				String pointedMp4Ref = readOssObjectUtf8(ossClient, bucket, rawTargetKey);
				if (StrUtil.isNotBlank(pointedMp4Ref)) {
					String pointedUrlTxt = pointedMp4Ref.trim() + "/" + CACHE_URL_TXT;
					String pointedVideoKey = pointedMp4Ref.trim() + "/" + CACHE_VIDEO_MP4;
					if (cacheExists(ossClient, bucket, pointedUrlTxt, pointedVideoKey)) {
						return toResult(true, "命中缓存（通过 raw_url 指向）", bucket, pointedVideoKey, true);
					}
				}
			}
			ResponseEntity<Resource> response = restTemplate.getForEntity(URI.create(mp4Url.trim()), Resource.class);
			if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
				return toResult(false, "下载失败：HTTP " + response.getStatusCode() + "，或响应体为空", null, null, false);
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

			return toResult(true, "上传成功", bucket, videoKey, false);
		} catch (Exception e) {
			return toResult(false, "失败: " + e.getMessage(), null, null, false);
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
	 * 根据原始地址（raw_url）查询 OSS 中是否已存在对应视频。先查 raw_url/{md5}/target.txt，再查指向的 mp4
	 * 目录是否完整。
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
		if (StrUtil.isBlank(bucket) || StrUtil.isBlank(ossEndpoint) || StrUtil.isBlank(ossAccessKeyId)
				|| StrUtil.isBlank(ossAccessKeySecret)) {
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

	private static Map<String, Object> toResultExists(boolean success, boolean exists, String message, String bucket,
			String path) {
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

	private static Map<String, Object> toResult(boolean success, String message, String bucket, String path,
			boolean cached) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("success", success);
		map.put("message", message);
		map.put("cached", cached);
		if (bucket != null) {
			map.put("bucket", bucket);
		}
		if (path != null) {
			map.put("path", path);
		}
		return map;
	}

	/**
	 * 根据 bucket 和 path 拼接 OSS 公网访问地址（与 Dify 中 [CODE] 解析历史下载记录 一致：bucket.oss-cn-shanghai.aliyuncs.com）。
	 * 若 endpoint 已包含 region（如 oss-cn-shanghai.aliyuncs.com），则使用该 host。
	 */
	private String buildOssPublicUrl(String bucket, String path) {
		if (StrUtil.isBlank(ossEndpoint) || StrUtil.isBlank(bucket) || StrUtil.isBlank(path)) {
			return null;
		}
		String host = ossEndpoint.replaceFirst("^https?://", "").trim();
		return "https://" + bucket + "." + host + "/" + path;
	}

	/**
	 * 一体化 MCP 工具：用户输入抖音分享链接，输出上传到 OSS 后的公网地址。
	 * 流程：检测抖音链接 → 查 OSS 是否已有该 raw_url 对应视频 → 若无则调用 Chromium 抓取页内视频地址 → 下载并上传 OSS → 返回 OSS 地址。
	 *
	 * @param douyinShareLink 抖音分享链接（如 https://v.douyin.com/xxx），必填
	 * @param ossBucket       OSS 桶名，不填则使用配置文件中的 oss.bucket-name
	 * @return 包含 success、message、ossUrl（成功时为 OSS 公网地址）、path、bucket 等
	 */
	@Tool(description = "输入抖音分享链接，先查 OSS 是否已有该视频；若无则用 Playwright 打开页面抓取视频地址并下载上传到 OSS，最终输出上传到 OSS 中的公网地址。")
	public Map<String, Object> parseDouyinLinkAndGetOssUrl(
			@ToolParam(required = true, description = "抖音分享链接，例如 https://v.douyin.com/xxxxx") String douyinShareLink,
			@ToolParam(required = false, description = "OSS 桶名，不填则使用配置文件中的 oss.bucket-name") String ossBucket) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("success", false);
		out.put("message", "");
		out.put("ossUrl", (String) null);

		String query = StrUtil.isNotBlank(douyinShareLink) ? douyinShareLink.trim() : "";
		java.util.regex.Matcher matcher = DOUYIN_LINK_PATTERN.matcher(query);
		String detectedUrl = null;
		if (matcher.find()) {
			String raw = matcher.group(0).trim();
			detectedUrl = raw.replaceAll("[\\u4e00-\\u9fa5\\s]+$", "").replaceAll("[^\\w\\/\\?\\&\\.\\=\\-\\_]+$", "");
		}
		if (StrUtil.isBlank(detectedUrl)) {
			out.put("message", "未检测到有效的抖音分享链接，请提供如 https://v.douyin.com/xxxxx 格式的链接");
			return out;
		}

		String bucket = StrUtil.isNotBlank(ossBucket) ? ossBucket.trim() : ossBucketName;
		if (StrUtil.isBlank(bucket) || StrUtil.isBlank(ossEndpoint) || StrUtil.isBlank(ossAccessKeyId)
				|| StrUtil.isBlank(ossAccessKeySecret)) {
			out.put("message", "OSS 未配置完整，请配置 oss.endpoint、oss.access-key-id、oss.access-key-secret、oss.bucket-name");
			return out;
		}

		// 1) 是否历史已下载
		Map<String, Object> existsResult = existsVideoByRawUrl(detectedUrl, bucket);
		Boolean exists = existsResult != null ? (Boolean) existsResult.get("exists") : null;
		if (Boolean.TRUE.equals(exists)) {
			String path = (String) existsResult.get("path");
			if (StrUtil.isNotBlank(path)) {
				String ossUrl = buildOssPublicUrl(bucket, path);
				out.put("success", true);
				out.put("message", "命中 OSS 缓存");
				out.put("ossUrl", ossUrl);
				out.put("path", path);
				out.put("bucket", bucket);
				return out;
			}
		}

		// 2) 用 Playwright 打开抖音页抓取 video source 的 src（aweme 视频地址）
		String mp4Url = null;
		try {
			mp4Url = scrapeDouyinVideoUrlWithPlaywright(detectedUrl);
		} catch (Exception e) {
			out.put("message", "Playwright 抓取失败: " + e.getMessage());
			return out;
		}
		if (StrUtil.isBlank(mp4Url)) {
			out.put("message", "解析抖音页面未得到视频地址，请确认链接有效且页面内存在 video source");
			return out;
		}

		// 3) 下载并上传 OSS
		Map<String, Object> uploadResult = downloadMp4AndUploadToOss(mp4Url, detectedUrl, bucket);
		Boolean uploadOk = uploadResult != null ? (Boolean) uploadResult.get("success") : null;
		if (!Boolean.TRUE.equals(uploadOk)) {
			out.put("message", StrUtil.nullToEmpty((String) (uploadResult != null ? uploadResult.get("message") : null)));
			return out;
		}
		String path = (String) uploadResult.get("path");
		String ossUrl = buildOssPublicUrl(bucket, path);
		out.put("success", true);
		out.put("message", "已下载并上传到 OSS");
		out.put("ossUrl", ossUrl);
		out.put("path", path);
		out.put("bucket", bucket);
		return out;
	}

	/**
	 * 用 Playwright 启动 Chromium 打开抖音分享页，等待 video source 出现并取其 src（aweme 视频地址）。
	 *
	 * @param pageUrl 抖音分享链接（如 https://v.douyin.com/xxx）
	 * @return 视频地址（以 https://www.douyin.com/aweme/ 开头），未找到则 null
	 */
	private static String scrapeDouyinVideoUrlWithPlaywright(String pageUrl) {
		try (Playwright playwright = Playwright.create()) {
			Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
			try {
				Page page = browser.newPage();
				page.setDefaultNavigationTimeout(30_000);
				page.setDefaultTimeout(20_000);
				page.navigate(pageUrl);
				// 等待 video source 出现（抖音页内视频源）
				page.waitForSelector("video source");
				String src = page.getAttribute("video source", "src");
				if (StrUtil.isNotBlank(src) && src.startsWith("https://www.douyin.com/aweme/")) {
					return src.trim();
				}
				return null;
			} finally {
				browser.close();
			}
		}
	}

	public static void main(String[] args) {
		McpService client = new McpService();
		System.out.println(client.helloWorld());
	}
}