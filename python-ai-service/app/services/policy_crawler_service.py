from __future__ import annotations

import asyncio
import logging
import re
import time
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urljoin, urlparse
from urllib.robotparser import RobotFileParser

import httpx
import trafilatura

from app.core.settings import Settings
from app.models.schemas import PolicyCrawlArticle, PolicyCrawlData, PolicyCrawlRequest

log = logging.getLogger(__name__)

USER_AGENT = "SmartWorksitePolicyCrawler/1.0"
HTML_CONTENT_TYPES = {"text/html", "application/xhtml+xml", "application/xml", "text/xml"}
# 附件与媒体资源：政策原文常以附件形式发布，本期只识别并跳过，不做解析
ATTACHMENT_EXTENSIONS = {
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar", "7z", "tar", "gz",
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico",
    "mp3", "mp4", "avi", "wmv", "flv", "mov", "wav", "csv", "txt", "ofd", "wps", "et",
}
PAGE_TYPES = {"AUTO", "LIST", "DETAIL"}
_META_CHARSET_RE = re.compile(rb"""<meta[^>]+charset\s*=\s*["']?\s*([\w-]+)""", re.I)
_TITLE_SEPARATORS = ("_", "|", " - ")
_MIN_CONTENT_CHARS = 50


class PolicyCrawlSkip(Exception):
    """预期内跳过：附件、robots.txt 禁止、非 HTML 内容。不计为失败。"""


class PolicyCrawlFailure(Exception):
    """真实失败：网络错误、HTTP 错误、正文抽取不到。必须显式上报。"""


class _TagTextExtractor(HTMLParser):
    """只取指定标签的纯文本，用于标题回退。"""

    def __init__(self, tag: str):
        super().__init__(convert_charrefs=True)
        self.tag = tag
        self.depth = 0
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]):
        if tag.lower() == self.tag:
            self.depth += 1

    def handle_endtag(self, tag: str):
        if tag.lower() == self.tag and self.depth > 0:
            self.depth -= 1

    def handle_data(self, data: str):
        if self.depth > 0 and not self.parts:
            text = re.sub(r"\s+", " ", data).strip()
            if text:
                self.parts.append(text)


class _LinkExtractor(HTMLParser):
    def __init__(self, base_url: str):
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.current_href: str | None = None
        self.current_text: list[str] = []
        self.links: list[tuple[str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]):
        if tag.lower() != "a":
            return
        attrs_map = {key.lower(): value for key, value in attrs if key}
        href = attrs_map.get("href")
        if href:
            self.current_href = urljoin(self.base_url, href)
            self.current_text = []

    def handle_data(self, data: str):
        if self.current_href:
            text = re.sub(r"\s+", " ", data).strip()
            if text:
                self.current_text.append(text)

    def handle_endtag(self, tag: str):
        if tag.lower() == "a" and self.current_href:
            title = " ".join(self.current_text).strip()
            if title:
                self.links.append((self.current_href, title))
            self.current_href = None
            self.current_text = []


class _RobotsGate:
    """按 host 缓存 robots.txt。取不到时按允许处理，符合 robots 约定。"""

    def __init__(self, respect: bool, ttl_seconds: float = 3600.0):
        self._respect = respect
        self._ttl = ttl_seconds
        self._cache: dict[str, tuple[float, RobotFileParser | None]] = {}

    async def allowed(self, client: httpx.AsyncClient, url: str) -> bool:
        if not self._respect:
            return True
        parsed = urlparse(url)
        origin = f"{parsed.scheme}://{parsed.netloc}"
        cached = self._cache.get(origin)
        now = time.monotonic()
        if cached is None or now - cached[0] > self._ttl:
            parser = await self._load(client, origin)
            self._cache[origin] = (now, parser)
        else:
            parser = cached[1]
        if parser is None:
            return True
        return parser.can_fetch(USER_AGENT, url)

    async def _load(self, client: httpx.AsyncClient, origin: str) -> RobotFileParser | None:
        try:
            response = await client.get(f"{origin}/robots.txt", headers={"User-Agent": USER_AGENT})
        except httpx.HTTPError as ex:
            log.info("robots.txt unavailable, treating as allowed, origin=%s, error=%s", origin, ex)
            return None
        if response.status_code >= 400:
            return None
        parser = RobotFileParser()
        try:
            parser.parse(response.text.splitlines())
        except Exception as ex:  # robots.txt 格式异常不应阻断采集
            log.info("robots.txt parse failed, treating as allowed, origin=%s, error=%s", origin, ex)
            return None
        return parser


class _HostRateLimiter:
    """同 host 串行并保持最小请求间隔。"""

    def __init__(self, delay_seconds: float):
        self._delay = max(delay_seconds, 0.0)
        self._last: dict[str, float] = {}

    async def wait(self, url: str) -> None:
        if self._delay <= 0:
            return
        host = urlparse(url).netloc
        last = self._last.get(host)
        if last is not None:
            remaining = self._delay - (time.monotonic() - last)
            if remaining > 0:
                await asyncio.sleep(remaining)
        self._last[host] = time.monotonic()


def _sniff_meta_charset(raw: bytes) -> str | None:
    match = _META_CHARSET_RE.search(raw[:4096])
    return match.group(1).decode("ascii", errors="ignore") if match else None


def _decode_html(response: httpx.Response) -> str:
    """解码优先级：HTTP 头 charset → <meta charset> → utf-8。修复只在 meta 中声明编码的站点乱码。"""
    raw = response.content
    for encoding in (response.charset_encoding, _sniff_meta_charset(raw), "utf-8"):
        if not encoding:
            continue
        try:
            return raw.decode(encoding)
        except (LookupError, UnicodeDecodeError, ValueError):
            continue
    return raw.decode("utf-8", errors="replace")


def _tag_text(html: str, tag: str) -> str | None:
    extractor = _TagTextExtractor(tag)
    try:
        extractor.feed(html)
    except Exception:
        return None
    return extractor.parts[0] if extractor.parts else None


def _clean_title(value: str | None) -> str | None:
    """剥除站点后缀，例如「通知_政策文件_首都之窗_北京市人民政府门户网站」→「通知」。"""
    if not value:
        return None
    title = re.sub(r"<[^>]+>", "", value)
    title = re.sub(r"\s+", " ", title).strip()
    if not title:
        return None
    for separator in _TITLE_SEPARATORS:
        if separator in title:
            head = title.split(separator)[0].strip()
            if len(head) >= 8:
                title = head
                break
    return title or None


def _extract_date(text: str) -> str | None:
    match = re.search(r"(20\d{2})[-年/.](\d{1,2})[-月/.](\d{1,2})", text)
    if not match:
        return None
    year, month, day = match.groups()
    if not (1 <= int(month) <= 12 and 1 <= int(day) <= 31):
        return None
    return f"{int(year):04d}-{int(month):02d}-{int(day):02d}"


def _extract_policy_no(text: str) -> str | None:
    # 只在正文开头找，避免命中文中引用的其他文号
    match = re.search(r"([一-龥]{1,12}〔20\d{2}〕\d+号)", text[:2000])
    return match.group(1) if match else None


def _url_extension(url: str) -> str | None:
    path = urlparse(url).path
    match = re.search(r"\.(\w{1,5})$", path)
    return match.group(1).lower() if match else None


def _same_site(base_host: str, candidate_host: str) -> bool:
    return base_host.removeprefix("www.").lower() == candidate_host.removeprefix("www.").lower()


def _is_index_path(url: str) -> bool:
    path = urlparse(url).path
    return path in ("", "/") or path.endswith("/") or re.search(r"/index\.\w+$", path) is not None


class PolicyCrawlerService:
    def __init__(self, settings: Settings | None = None):
        settings = settings or Settings()
        self._timeout = float(settings.policy_crawl_timeout_seconds)
        self._max_articles = int(settings.policy_crawl_max_articles)
        self._min_article_chars = int(settings.policy_crawl_min_article_chars)
        self._max_detail_links = int(settings.policy_crawl_max_detail_links)
        self._robots = _RobotsGate(bool(settings.policy_crawl_respect_robots))
        self._limiter = _HostRateLimiter(float(settings.policy_crawl_delay_seconds))

    async def crawl(self, request: PolicyCrawlRequest) -> tuple[PolicyCrawlData, dict[str, Any]]:
        page_type = (request.pageType or "AUTO").upper()
        if page_type not in PAGE_TYPES:
            raise PolicyCrawlFailure(f"pageType must be one of {sorted(PAGE_TYPES)}")
        async with httpx.AsyncClient(
            follow_redirects=True,
            timeout=self._timeout,
            limits=httpx.Limits(max_connections=2, max_keepalive_connections=2),
            headers={"User-Agent": USER_AGENT},
        ) as client:
            try:
                final_url, html = await self._fetch(client, request.url)
            except PolicyCrawlSkip as ex:
                # 政策源地址本身不可抓取属于配置错误，必须显式失败，不能静默跳过
                raise PolicyCrawlFailure(f"policy source url is not crawlable: {ex}") from ex
            body = self._extract_body(html, final_url)
            links = self._extract_article_links(html, final_url)
            resolved = self._resolve_page_type(page_type, body, links)
            if resolved == "DETAIL":
                article = self._build_article(html, final_url, request.url, body=body)
                usage = {"provider": "HTTPX+TRAFILATURA", "pageType": "DETAIL",
                         "fetched": 1, "failed": 0, "skipped": 0}
                return PolicyCrawlData(fetchedCount=1, failedCount=0, skippedCount=0,
                                       message="policy detail page crawled", articles=[article]), usage
            return await self._crawl_list(client, links, final_url)

    async def _crawl_list(self, client: httpx.AsyncClient, links: list[tuple[str, str]],
                          list_url: str) -> tuple[PolicyCrawlData, dict[str, Any]]:
        articles: list[PolicyCrawlArticle] = []
        failed = 0
        skipped = 0
        errors: list[str] = []
        for url, fallback_title in links[: self._max_articles]:
            try:
                article_url, article_html = await self._fetch(client, url)
                articles.append(self._build_article(article_html, article_url, fallback_title))
            except PolicyCrawlSkip as ex:
                skipped += 1
                log.info("policy article skipped, url=%s, reason=%s", url, ex)
            except PolicyCrawlFailure as ex:
                failed += 1
                errors.append(f"{url}: {ex}")
                log.warning("policy article failed, url=%s, reason=%s", url, ex)
        if not articles:
            raise PolicyCrawlFailure(
                f"no policy article extracted from list page {list_url}; "
                f"candidates={len(links)}, failed={failed}, skipped={skipped}"
                + (f", firstError={errors[0]}" if errors else "")
            )
        message = f"policy list crawled, fetched={len(articles)}, failed={failed}, skipped={skipped}"
        usage = {"provider": "HTTPX+TRAFILATURA", "pageType": "LIST",
                 "fetched": len(articles), "failed": failed, "skipped": skipped}
        return PolicyCrawlData(fetchedCount=len(articles), failedCount=failed, skippedCount=skipped,
                               message=message, articles=articles), usage

    async def _fetch(self, client: httpx.AsyncClient, url: str) -> tuple[str, str]:
        if not await self._robots.allowed(client, url):
            raise PolicyCrawlSkip(f"robots.txt disallows {url}")
        await self._limiter.wait(url)
        try:
            response = await client.get(url)
        except httpx.HTTPError as ex:
            raise PolicyCrawlFailure(f"request failed: {type(ex).__name__}: {ex}") from ex
        if response.status_code >= 400:
            raise PolicyCrawlFailure(f"http status {response.status_code}")
        content_type = (response.headers.get("content-type") or "").split(";")[0].strip().lower()
        if content_type and content_type not in HTML_CONTENT_TYPES:
            raise PolicyCrawlSkip(f"non-html content-type: {content_type}")
        return str(response.url), _decode_html(response)

    def _resolve_page_type(self, requested: str, body: str | None, links: list[tuple[str, str]]) -> str:
        if requested in ("LIST", "DETAIL"):
            return requested
        # 链接过多的页面一定是栏目页，即使 trafilatura 抽出了摘要文本
        if len(links) > self._max_detail_links:
            return "LIST"
        if body and len(body) >= self._min_article_chars:
            return "DETAIL"
        if links:
            return "LIST"
        raise PolicyCrawlFailure(
            "page has neither extractable article body nor in-site article links; "
            "the page is likely JavaScript-rendered or the url is wrong"
        )

    def _extract_body(self, html: str, url: str) -> str | None:
        try:
            return trafilatura.extract(
                html, url=url, favor_precision=True, include_comments=False, include_tables=True
            )
        except Exception as ex:
            log.warning("trafilatura extract failed, url=%s, error=%s", url, ex)
            return None

    def _extract_article_links(self, html: str, base_url: str) -> list[tuple[str, str]]:
        extractor = _LinkExtractor(base_url)
        extractor.feed(html)
        base_host = urlparse(base_url).netloc
        seen: set[str] = set()
        links: list[tuple[str, str]] = []
        for url, title in extractor.links:
            if url in seen or not self._looks_like_article_url(url, base_host):
                continue
            seen.add(url)
            links.append((url, title[:256]))
        return links

    def _looks_like_article_url(self, url: str, base_host: str) -> bool:
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https"):
            return False
        if not _same_site(base_host, parsed.netloc):
            return False
        extension = _url_extension(url)
        if extension in ATTACHMENT_EXTENSIONS:
            return False
        if _is_index_path(url):
            return False
        lowered = parsed.path.lower()
        return lowered.endswith((".html", ".htm", ".shtml")) or re.search(r"/20\d{2}", lowered) is not None

    def _build_article(self, html: str, url: str, fallback_title: str,
                       body: str | None = None) -> PolicyCrawlArticle:
        content = body if body is not None else self._extract_body(html, url)
        if not content or len(content.strip()) < _MIN_CONTENT_CHARS:
            raise PolicyCrawlFailure(
                f"extracted content too short ({len(content.strip()) if content else 0} chars)"
            )
        content = content.strip()
        metadata = self._extract_metadata(html, url)
        title = self._resolve_title(html, metadata, fallback_title)
        publish_date = getattr(metadata, "date", None) or _extract_date(content)
        return PolicyCrawlArticle(
            title=title,
            url=url,
            summary=content[:300],
            content=content,
            publishDate=publish_date,
            category=None,
            policyNo=_extract_policy_no(content),
            sourceName=getattr(metadata, "sitename", None),
        )

    def _extract_metadata(self, html: str, url: str):
        try:
            return trafilatura.extract_metadata(html, default_url=url)
        except Exception as ex:
            log.info("trafilatura metadata failed, url=%s, error=%s", url, ex)
            return None

    def _resolve_title(self, html: str, metadata, fallback_title: str) -> str:
        candidates = [
            getattr(metadata, "title", None),
            _tag_text(html, "h1"),
            _tag_text(html, "title"),
            fallback_title,
        ]
        for candidate in candidates:
            cleaned = _clean_title(candidate)
            if cleaned:
                return cleaned[:256]
        return fallback_title[:256]
