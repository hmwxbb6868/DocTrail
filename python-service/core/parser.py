from dataclasses import dataclass
from io import BytesIO
from pathlib import PurePosixPath
from typing import Literal
from zipfile import BadZipFile, ZipFile

from docx import Document
from docx.opc.exceptions import PackageNotFoundError
from pypdf import PdfReader
from pypdf.errors import PdfReadError


"""
 文档解析器：自动根据后缀选择对应的解析逻辑，提取文档里面的文字；解析成功返回 `ParsedDocument` 对象；
 """
class UnsupportedDocumentTypeError(ValueError):
    pass


class EmptyDocumentError(ValueError):
    pass


class DocumentParseError(ValueError):
    pass

# `@dataclass` 是 Python 装饰器，用来快速创建**数据载体类（数据模型），
#自动生成 `__init__`、`__repr__` 等样板代码
#`frozen=True` → 不可变（只读）对象，对象一旦创建完成，**不能修改里面的属性**。
@dataclass(frozen=True)
class ParsedDocument:
    text: str
    document_type: Literal["pdf", "docx", "text", "markdown"]
    unit_count: int


class DocumentParser:
    def parse(self, storage_key: str, data: bytes) -> ParsedDocument:
        #解析路径字符串
        #2. `.suffix`：路径对象的属性：拿到文件后缀（扩展名），包含小数点 `.`
        suffix = PurePosixPath(storage_key).suffix.lower()
        if suffix == ".pdf":
            return self._parse_pdf(data)
        if suffix == ".docx":
            return self._parse_docx(data)
        if suffix == ".txt":
            return self._parse_text(data, "text")
        if suffix == ".md":
            return self._parse_text(data, "markdown")
        raise UnsupportedDocumentTypeError("document extension is not supported")

    def _parse_pdf(self, data: bytes) -> ParsedDocument:
        if not data.lstrip().startswith(b"%PDF-"):
            raise UnsupportedDocumentTypeError("file content does not match the .pdf extension")
        try:
            reader = PdfReader(BytesIO(data))
            if reader.is_encrypted and reader.decrypt("") == 0:
                raise DocumentParseError("encrypted PDF documents are not supported")
            pages = [(page.extract_text() or "").strip() for page in reader.pages]
        except DocumentParseError:
            raise
        except (PdfReadError, OSError, ValueError) as exc:
            raise DocumentParseError("PDF document could not be parsed") from exc
        return self._finish("\n\n".join(page for page in pages if page), "pdf", len(pages))

    def _parse_docx(self, data: bytes) -> ParsedDocument:
        if not self._is_docx(data):
            raise UnsupportedDocumentTypeError("file content does not match the .docx extension")
        try:
            document = Document(BytesIO(data))
            blocks = [paragraph.text.strip() for paragraph in document.paragraphs]
            for table in document.tables:
                for row in table.rows:
                    blocks.append("\t".join(cell.text.strip() for cell in row.cells))
        except (BadZipFile, PackageNotFoundError, KeyError, ValueError) as exc:
            raise DocumentParseError("DOCX document could not be parsed") from exc
        text = "\n".join(block for block in blocks if block)
        return self._finish(text, "docx", max(len(document.paragraphs), 1))

    def _parse_text(
        self,
        data: bytes,
        document_type: Literal["text", "markdown"],
    ) -> ParsedDocument:
        if data.lstrip().startswith(b"%PDF-") or self._is_docx(data):
            raise UnsupportedDocumentTypeError("file content does not match the text extension")
        if b"\x00" in data:
            raise UnsupportedDocumentTypeError("binary content is not accepted as text")
        try:
            text = data.decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            raise UnsupportedDocumentTypeError("text documents must use UTF-8 encoding") from exc
        return self._finish(text, document_type, max(text.count("\n") + 1, 1))

    @staticmethod
    def _is_docx(data: bytes) -> bool:
        if not data.startswith(b"PK"):
            return False
        try:
            with ZipFile(BytesIO(data)) as archive:
                names = set(archive.namelist())
        except BadZipFile:
            return False
        return "[Content_Types].xml" in names and "word/document.xml" in names

    @staticmethod
    def _finish(
        text: str,
        document_type: Literal["pdf", "docx", "text", "markdown"],
        unit_count: int,
    ) -> ParsedDocument:
        normalized = text.replace("\r\n", "\n").replace("\r", "\n").strip()
        if not normalized:
            raise EmptyDocumentError("document contains no extractable text")
        return ParsedDocument(normalized, document_type, unit_count)
