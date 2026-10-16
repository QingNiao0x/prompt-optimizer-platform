{
    "url": str,          # 原始 URL（保留凭据，供调用方定位）
    "status": str,       # 见下表
    "title": str | None, # 成功时为标题文本，否则 None
    "error": str | None, # 失败时为脱敏后的错误描述，否则 None
}
