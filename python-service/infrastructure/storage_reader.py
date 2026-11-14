import os
import time
from pathlib import Path
from typing import Protocol

import oss2

from storage import normalize_object_key


class StorageConfigurationError(RuntimeError):
    pass


class StoredObjectNotFoundError(FileNotFoundError):
    pass


class StoredObjectTooLargeError(ValueError):
    pass


class StoredObjectReadError(RuntimeError):
    pass

#`Protocol` 叫协议类（类型协议），定义一份契约：任何类，只要拥有一个符合下面签名的`read`实例方法，
#这个类的实例就自动是`ObjectReader`类型
class ObjectReader(Protocol):
    def read(self, storage_key: str) -> bytes: ...


class LocalObjectReader:
    #upload_root：本地存储的根目录，prefix：对象键的前缀，max_bytes：对象的最大字节数
    def __init__(self, upload_root: Path, prefix: str, max_bytes: int) -> None:
        self._root = upload_root.expanduser().resolve()
        self._prefix = prefix
        self._max_bytes = max_bytes

    def read(self, storage_key: str) -> bytes:
        #校验 + 清洗存储 key，防止路径穿越攻击，统一生成安全的相对 object key
        object_key = normalize_object_key(self._prefix, storage_key)
        #把标准化后的 `object_key`，拼接在根目录 `self._root` 后面，得到**文件绝对路径**。
        try:
            target = self._root.joinpath(*object_key.split("/")).resolve()
        except (OSError, RuntimeError) as exc:
            raise StoredObjectReadError("storage_key path cannot be verified") from exc
        #判断 `target` 这个路径，是否在 `self._root` 目录的内部
        if not target.is_relative_to(self._root):
            raise StoredObjectReadError("storage_key escapes UPLOAD_ROOT")
        #判断 `target` 这个路径，是否指向一个文件
        if not target.is_file():
            raise StoredObjectNotFoundError("stored document does not exist")
        #判断 `target` 这个文件，其大小是否超过 `self._max_bytes` 配置的最大字节数
        #`stat()`：**提前拦截**
        if target.stat().st_size > self._max_bytes:
            raise StoredObjectTooLargeError("stored document exceeds the configured size limit")
        try:
            #`len(data)`：**兜底拦截**
            data = target.read_bytes()
        except OSError as exc:
            raise StoredObjectReadError("failed to read stored document") from exc
            #以阻止这个超限的二进制数据继续交给上层
        if len(data) > self._max_bytes:
            raise StoredObjectTooLargeError("stored document exceeds the configured size limit")
        return data


class OssObjectReader:
    def __init__(
        self,
        bucket: object,
        prefix: str,
        max_bytes: int,
        max_retries: int,#最大重试次数
        *,
        #前面的参数，可以用位置传参；
        #后面所有的参数，强制只能用关键字传参，不能按位置顺序传
        retry_delay_seconds: float = 0.1,
    ) -> None:
        self._bucket = bucket
        self._prefix = prefix
        self._max_bytes = max_bytes
        self._max_retries = max_retries
        self._retry_delay_seconds = retry_delay_seconds

    def read(self, storage_key: str) -> bytes:
        object_key = normalize_object_key(self._prefix, storage_key)
        for attempt in range(self._max_retries + 1):
            try:
                #`head_object`：**只请求文件元信息，不下载文件内容**
                metadata = self._bucket.head_object(object_key)
                if metadata.content_length > self._max_bytes:
                    raise StoredObjectTooLargeError(
                        "stored document exceeds the configured size limit"
                    )
                    #下载OSS文件二进制内容到内存变量data
                data = self._bucket.get_object(object_key).read()
                if len(data) > self._max_bytes:
                    raise StoredObjectTooLargeError(
                        "stored document exceeds the configured size limit"
                    )
                return data
                #`NoSuchKey`：OSS 上没有这个文件
            except oss2.exceptions.NoSuchKey as exc:
                raise StoredObjectNotFoundError("stored document does not exist") from exc
                #OSS 服务端返回错误（5xx 类错误）
            except oss2.exceptions.ServerError as exc:
                if not self._retryable_status(exc.status) or attempt == self._max_retries:
                    raise StoredObjectReadError("OSS failed to read stored document") from exc
                #客户端网络错误，断连、超时
            except oss2.exceptions.RequestError as exc:
                if attempt == self._max_retries:
                    raise StoredObjectReadError("OSS failed to read stored document") from exc
                #通用 OSS 错误（权限错误、参数错误等）
            except oss2.exceptions.OssError as exc:
                raise StoredObjectReadError("OSS rejected the stored document read") from exc
            #每次重试等待时间翻倍
            time.sleep(self._retry_delay_seconds * (2**attempt))

        raise StoredObjectReadError("OSS failed to read stored document")

    @staticmethod
    def _retryable_status(status: int | None) -> bool:
        return status == 429 or status is not None and status >= 500


def create_object_reader_from_env() -> ObjectReader:
    #选择存储后端：`local`（本地磁盘） / `oss`（阿里云对象存储）
    backend = os.getenv("STORAGE_BACKEND", "").strip().lower()
    #读取环境变量 `OSS_KEY_PREFIX`；没有配置时，默认值 `doctrail`
    prefix = os.getenv("OSS_KEY_PREFIX", "doctrail")
    #从环境变量 `UPLOAD_MAX_FILE_SIZE_MB` 读取上传文件大小上限（以 MB 为单位）
    max_bytes = _max_bytes_from_env()

    if backend == "local":
        return LocalObjectReader(
        #`os.getenv("环境变量名", 默认值)`：读取操作系统环境变量，没有配置这个环境变量，就使用默认值：`"./uploads"`
            Path(os.getenv("UPLOAD_ROOT", "./uploads")),
            prefix,
            max_bytes,
        )
    if backend == "oss":
        bucket_name = _required_env("OSS_BUCKET")#OSS 存储空间名称
        endpoint = _required_env("OSS_ENDPOINT")#OSS 访问域名
        access_key_id = _required_env("OSS_ACCESS_KEY_ID")#OSS 访问密钥 ID
        access_key_secret = _required_env("OSS_ACCESS_KEY_SECRET")
        connect_timeout = _duration_seconds("OSS_CONNECT_TIMEOUT", "10s")
        read_timeout = _duration_seconds("OSS_READ_TIMEOUT", "60s")
        max_retries = _integer_env("OSS_MAX_RETRIES", 3, minimum=0)

        auth = oss2.Auth(access_key_id, access_key_secret)
        bucket = oss2.Bucket(
            auth,
            endpoint,
            bucket_name,
            connect_timeout=(connect_timeout, read_timeout),
        )
        return OssObjectReader(bucket, prefix, max_bytes, max_retries)

    raise StorageConfigurationError("STORAGE_BACKEND must be explicitly set to local or oss")


def _max_bytes_from_env() -> int:
    #默认值 20，限制最小值不能小于 1
    max_megabytes = _integer_env("UPLOAD_MAX_FILE_SIZE_MB", 20, minimum=1)
    if max_megabytes > 50:
        raise StorageConfigurationError("UPLOAD_MAX_FILE_SIZE_MB must not exceed 50")
    return max_megabytes * 1024 * 1024


def _integer_env(name: str, default: int, *, minimum: int) -> int:
    raw_value = os.getenv(name, str(default)).strip()
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise StorageConfigurationError(f"{name} must be an integer") from exc
    if value < minimum:
        raise StorageConfigurationError(f"{name} must be at least {minimum}")
    return value


def _duration_seconds(name: str, default: str) -> float:
    raw_value = os.getenv(name, default).strip().lower()
    #倍率
    multiplier = 1.0
    if raw_value.endswith("ms"):
        raw_value = raw_value[:-2]
        multiplier = 0.001
    elif raw_value.endswith("s"):
        raw_value = raw_value[:-1]
    try:
        #把剩下的数字字符串转浮点数，乘以倍率，得到总秒数
        value = float(raw_value) * multiplier
    except ValueError as exc:
        raise StorageConfigurationError(f"{name} must be a duration such as 10s") from exc
    if value <= 0:
        raise StorageConfigurationError(f"{name} must be positive")
    return value


def _required_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise StorageConfigurationError(f"{name} must be configured when STORAGE_BACKEND=oss")
    return value
