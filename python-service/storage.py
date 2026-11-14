
import re


# 校验 + 清洗存储 key，防止路径穿越攻击，统一生成安全的相对 object key，给本地存储 / 对象存储（OSS）使用

#当 storage_key 或者 prefix 不安全的时候抛出
class StorageKeyError(ValueError):
    """Raised when a relative storage key or prefix is unsafe."""


# 预编译正则，匹配 Windows 绝对路径
_WINDOWS_ABSOLUTE = re.compile(r"^[A-Za-z]:/.*")

#校验并规范化业务传入的原始 storage_key（对应 Java normalizeRelative）
def normalize_relative_key(value: str) -> str:
    #isinstance(object, classinfo)---判断一个对象是不是某个类型
    #只有变量是`str`类型，才能调用 `.strip()`；如果`value`是`None`、int，调用`value.strip()`会直接报错
    if not isinstance(value, str) or not value.strip():
        raise StorageKeyError("storage_key must not be blank")
    # 去掉首尾空格
    value = value.strip()
#     禁止绝对路径、Windows 反斜杠、Windows 绝对路径
    if value.startswith("/") or "\\" in value or _WINDOWS_ABSOLUTE.fullmatch(value):
        raise StorageKeyError("storage_key must be a relative key")
#   re.sub(正则, 替换成什么, 原字符串)
    collapsed = re.sub(r"/{2,}", "/", value)
    parts = collapsed.split("/")
#     `any( ... )`：生成器表达式，只要**任意一个片段满足条件，整体返回 True**
    if any(part in {"", ".", ".."} for part in parts):
        raise StorageKeyError("storage_key contains an invalid path segment")
    return "/".join(parts)


def normalize_object_key(prefix: str, storage_key: str) -> str:
    if not isinstance(prefix, str) or not prefix.strip():
        raise StorageKeyError("OSS_KEY_PREFIX must not be blank")
    normalized_prefix = re.sub(r"/{2,}", "/", prefix.strip()).strip("/")
    if not normalized_prefix or _WINDOWS_ABSOLUTE.fullmatch(normalized_prefix):
        raise StorageKeyError("OSS_KEY_PREFIX must be a relative prefix")
    prefix_parts = normalized_prefix.split("/")
    if any(part in {"", ".", ".."} or "\\" in part for part in prefix_parts):
        raise StorageKeyError("OSS_KEY_PREFIX contains an invalid path segment")
    return f"{normalized_prefix}/{normalize_relative_key(storage_key)}"
