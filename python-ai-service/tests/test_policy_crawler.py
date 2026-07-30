import asyncio

import httpx
import pytest
import respx

from app.core.settings import Settings
from app.models.schemas import PolicyCrawlRequest
from app.services.policy_crawler_service import (
    PolicyCrawlerService,
    PolicyCrawlFailure,
    PolicyCrawlSkip,
    _clean_title,
    _decode_html,
)

BASE = "https://example.gov.cn"

# 模拟真实政府站点详情页：面包屑、元信息标签、UI 控件、正文、页脚
DETAIL_HTML = """
<html><head>
  <meta charset="utf-8">
  <title>关于印发《建筑施工安全生产管理办法》的通知_政策文件_示例之窗_示例市人民政府门户网站</title>
</head><body>
  <div class="nav"><a href="/gongkai/index.html">政务公开</a> &gt;
       <a href="/zhengce/">政策服务</a> &gt; <a href="/zhengce/zhengcefagui/">政策文件</a></div>
  <div class="doc-info">[主题分类] 建设 [发文机构] 示例市住建局 [发布日期] 2026-07-28</div>
  <div class="tools">PDF格式下载 收藏 取消收藏 字号： 大 中 小</div>
  <div id="mainText">
    <h1>关于印发《建筑施工安全生产管理办法》的通知</h1>
    <p>建安〔2026〕15号</p>
    <p>各有关单位：</p>
    <p>为规范本市建筑施工安全生产管理，压实企业主体责任，保障施工现场作业安全，
       依据《中华人民共和国安全生产法》《建设工程安全生产管理条例》等法律法规，
       结合本市实际，制定《建筑施工安全生产管理办法》，现印发给你们，请遵照执行。</p>
    <p>第一条 本办法适用于本市行政区域内的房屋建筑和市政基础设施工程施工活动。</p>
    <p>第二条 施工单位应当建立健全安全生产责任制，加强危大工程、高处作业和
       临边洞口的安全管理，配齐安全防护设施并定期检查。</p>
    <p>特此通知。</p>
    <p>示例市住房和城乡建设局</p>
    <p>2026年7月27日</p>
  </div>
  <div class="footer">版权所有 示例市人民政府 ICP备00000000号 网站地图 联系我们</div>
</body></html>
"""

LIST_HTML = """
<html><head><meta charset="utf-8"><title>政策文件_示例之窗</title></head><body>
  <div class="nav"><a href="/gongkai/index.html">政务公开</a></div>
  <ul>
    <li><a href="/zhengce/zhengcefagui/202607/t20260728_1001.html">关于印发《建筑施工安全生产管理办法》的通知</a></li>
    <li><a href="/zhengce/zhengcefagui/202607/t20260727_1002.html">扬尘治理专项行动提示</a></li>
    <li><a href="/zhengce/zhengcefagui/202607/P020260726001.docx">附件：申请表（公民版）</a></li>
    <li><a href="https://other.example.com/ad/202607/promo.html">广告：装修贷款优惠</a></li>
    <li><a href="/zhengce/zhengcefagui/">栏目首页</a></li>
  </ul>
</body></html>
"""

SECOND_DETAIL_HTML = DETAIL_HTML.replace("建筑施工安全生产管理办法", "扬尘治理专项行动方案").replace(
    "建安〔2026〕15号", "建环〔2026〕7号")

JS_SHELL_HTML = "<html><head><title>示例政府网</title></head><body><p>点击跳转</p></body></html>"


def make_service(**overrides):
    settings = Settings(policy_crawl_delay_seconds=0.0, policy_crawl_respect_robots=False, **overrides)
    return PolicyCrawlerService(settings)


def html_response(body: str) -> httpx.Response:
    return httpx.Response(200, text=body, headers={"content-type": "text/html; charset=utf-8"})


def crawl(service: PolicyCrawlerService, url: str, page_type: str | None = None):
    request = PolicyCrawlRequest(projectId=1, sourceId=1, url=url, pageType=page_type)
    return asyncio.run(service.crawl(request))


def fetch(service: PolicyCrawlerService, url: str):
    async def run():
        async with httpx.AsyncClient(follow_redirects=True) as client:
            return await service._fetch(client, url)

    return asyncio.run(run())


# ---------- 正文抽取质量（CRAWLER-006 / 007） ----------

@respx.mock
def test_detail_page_extracts_body_without_navigation_noise():
    url = f"{BASE}/zhengce/zhengcefagui/202607/t20260728_1001.html"
    respx.get(url).mock(return_value=html_response(DETAIL_HTML))

    data, usage = crawl(make_service(), url, page_type="DETAIL")

    assert data.fetchedCount == 1
    article = data.articles[0]
    # 正文必须在
    assert "为规范本市建筑施工安全生产管理" in article.content
    assert "特此通知" in article.content
    # 导航、面包屑、UI 控件、页脚必须被剔除
    for noise in ["政务公开", "PDF格式下载", "取消收藏", "网站地图", "ICP备", "版权所有"]:
        assert noise not in article.content, f"噪声未剔除: {noise}"
    # 标题剥除站点后缀
    assert article.title == "关于印发《建筑施工安全生产管理办法》的通知"
    assert article.policyNo == "建安〔2026〕15号"
    assert article.publishDate is not None
    assert usage["pageType"] == "DETAIL"


# ---------- 列表页链接筛选（问题 2 / 4） ----------

def test_link_filter_excludes_attachments_cross_domain_and_index_pages():
    links = make_service()._extract_article_links(LIST_HTML, f"{BASE}/zhengce/zhengcefagui/")
    urls = [url for url, _ in links]

    assert urls == [
        f"{BASE}/zhengce/zhengcefagui/202607/t20260728_1001.html",
        f"{BASE}/zhengce/zhengcefagui/202607/t20260727_1002.html",
    ]
    # 附件不得进入候选（此前 .docx 因路径含 /202607 被当成文章）
    assert not any(u.endswith(".docx") for u in urls)
    # 跨域广告链接不得进入候选
    assert not any("other.example.com" in u for u in urls)
    # 栏目首页/索引页不得进入候选（此前 gongkai/index.html 被当成文章）
    assert not any(u.endswith("/") or "index.html" in u for u in urls)


@respx.mock
def test_list_page_crawls_articles_from_candidates():
    list_url = f"{BASE}/zhengce/zhengcefagui/"
    respx.get(list_url).mock(return_value=html_response(LIST_HTML))
    respx.get(f"{BASE}/zhengce/zhengcefagui/202607/t20260728_1001.html").mock(
        return_value=html_response(DETAIL_HTML))
    respx.get(f"{BASE}/zhengce/zhengcefagui/202607/t20260727_1002.html").mock(
        return_value=html_response(SECOND_DETAIL_HTML))

    data, usage = crawl(make_service(), list_url)

    assert usage["pageType"] == "LIST"
    assert data.fetchedCount == 2
    assert data.failedCount == 0
    titles = [article.title for article in data.articles]
    assert "关于印发《建筑施工安全生产管理办法》的通知" in titles
    assert "关于印发《扬尘治理专项行动方案》的通知" in titles


@respx.mock
def test_list_page_records_failed_count_without_hiding_errors():
    """单篇失败必须计数上报，不得静默 continue。"""
    list_url = f"{BASE}/zhengce/zhengcefagui/"
    respx.get(list_url).mock(return_value=html_response(LIST_HTML))
    respx.get(f"{BASE}/zhengce/zhengcefagui/202607/t20260728_1001.html").mock(
        return_value=html_response(DETAIL_HTML))
    respx.get(f"{BASE}/zhengce/zhengcefagui/202607/t20260727_1002.html").mock(
        return_value=httpx.Response(500))

    data, usage = crawl(make_service(), list_url)

    assert data.fetchedCount == 1
    assert data.failedCount == 1
    assert usage["failed"] == 1
    assert "failed=1" in data.message


# ---------- 非 HTML 内容必须跳过而非入库（问题 2） ----------

@respx.mock
def test_non_html_content_type_is_skipped_not_indexed():
    url = f"{BASE}/zhengce/zhengcefagui/202607/P020260726001.docx"
    respx.get(url).mock(return_value=httpx.Response(
        200, content=b"PK\x03\x04\x00\x00docProps/app.xml",
        headers={"content-type": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}))

    with pytest.raises(PolicyCrawlSkip, match="non-html content-type"):
        fetch(make_service(), url)


@respx.mock
def test_list_page_reports_failure_when_every_candidate_fails():
    list_url = f"{BASE}/zhengce/zhengcefagui/"
    respx.get(list_url).mock(return_value=html_response(LIST_HTML))
    respx.get(f"{BASE}/zhengce/zhengcefagui/202607/t20260728_1001.html").mock(
        return_value=httpx.Response(500))
    respx.get(f"{BASE}/zhengce/zhengcefagui/202607/t20260727_1002.html").mock(
        return_value=httpx.Response(404))

    with pytest.raises(PolicyCrawlFailure, match="no policy article extracted"):
        crawl(make_service(), list_url)


# ---------- 抓不到内容必须显式失败，不得伪造成功（问题 3） ----------

@respx.mock
def test_javascript_shell_page_fails_instead_of_reporting_success():
    """此前中国政府网 JS 壳页会回退抓列表页自身，产出 21 字并报 SUCCESS。"""
    url = f"{BASE}/zhengce/zuixin.htm"
    respx.get(url).mock(return_value=html_response(JS_SHELL_HTML))

    with pytest.raises(PolicyCrawlFailure, match="neither extractable article body nor"):
        crawl(make_service(), url)


@respx.mock
def test_source_url_returning_attachment_fails_explicitly():
    """政策源地址本身不可抓时必须失败，不能静默跳过。"""
    url = f"{BASE}/zhengce/file.pdf"
    respx.get(url).mock(return_value=httpx.Response(
        200, content=b"%PDF-1.7", headers={"content-type": "application/pdf"}))

    with pytest.raises(PolicyCrawlFailure, match="policy source url is not crawlable"):
        crawl(make_service(), url)


# ---------- robots.txt ----------

@respx.mock
def test_robots_disallow_blocks_fetch():
    respx.get(f"{BASE}/robots.txt").mock(return_value=httpx.Response(
        200, text="User-agent: *\nDisallow: /zhengce/\n"))
    url = f"{BASE}/zhengce/zhengcefagui/202607/t20260728_1001.html"
    service = PolicyCrawlerService(Settings(policy_crawl_delay_seconds=0.0, policy_crawl_respect_robots=True))

    with pytest.raises(PolicyCrawlSkip, match="robots.txt disallows"):
        fetch(service, url)


@respx.mock
def test_missing_robots_txt_is_treated_as_allowed():
    respx.get(f"{BASE}/robots.txt").mock(return_value=httpx.Response(404))
    url = f"{BASE}/zhengce/zhengcefagui/202607/t20260728_1001.html"
    respx.get(url).mock(return_value=html_response(DETAIL_HTML))
    service = PolicyCrawlerService(Settings(policy_crawl_delay_seconds=0.0, policy_crawl_respect_robots=True))

    final_url, html = fetch(service, url)

    assert final_url == url
    assert "建筑施工安全生产管理办法" in html


# ---------- 编码 ----------

def test_decode_html_prefers_header_charset():
    response = httpx.Response(200, content="建筑施工".encode("gb2312"),
                              headers={"content-type": "text/html; charset=gb2312"})
    assert "建筑施工" in _decode_html(response)


def test_decode_html_falls_back_to_meta_charset():
    """HTTP 头不带 charset、仅在 meta 中声明 gb2312 的站点不得乱码。"""
    body = '<html><head><meta charset="gb2312"></head><body>建筑施工安全</body></html>'
    response = httpx.Response(200, content=body.encode("gb2312"),
                              headers={"content-type": "text/html"})
    assert "建筑施工安全" in _decode_html(response)


# ---------- 标题清洗 ----------

@pytest.mark.parametrize("raw,expected", [
    ("关于印发《建筑施工安全生产管理办法》的通知_政策文件_示例之窗", "关于印发《建筑施工安全生产管理办法》的通知"),
    ("扬尘治理专项行动提示 | 示例市住建局", "扬尘治理专项行动提示"),
    ("短标题_栏目", "短标题_栏目"),
])
def test_clean_title_strips_site_suffix(raw, expected):
    assert _clean_title(raw) == expected


# ---------- 页面类型判定（问题 1） ----------

def test_auto_page_type_prefers_detail_when_body_is_substantial():
    service = make_service()
    body = "各有关单位：" + "为规范本市建筑施工安全生产管理，压实企业主体责任。" * 20
    # 详情页侧栏通常只有少量站内链接
    assert service._resolve_page_type("AUTO", body, [("u1", "t1"), ("u2", "t2")]) == "DETAIL"


def test_auto_page_type_prefers_list_when_link_dense():
    service = make_service()
    body = "各有关单位：" + "为规范本市建筑施工安全生产管理。" * 30
    links = [(f"u{i}", f"t{i}") for i in range(100)]
    # 链接过多必定是栏目页，即使抽出了摘要文本
    assert service._resolve_page_type("AUTO", body, links) == "LIST"


def test_auto_page_type_fails_when_page_has_no_body_and_no_links():
    with pytest.raises(PolicyCrawlFailure, match="neither extractable article body nor"):
        make_service()._resolve_page_type("AUTO", "点击跳转", [])
