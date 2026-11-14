from io import BytesIO

import pytest
from docx import Document
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

from core.parser import DocumentParser, EmptyDocumentError, UnsupportedDocumentTypeError


def _pdf_bytes(text: str) -> bytes:
    writer = PdfWriter()
    page = writer.add_blank_page(width=300, height=300)
    font = DictionaryObject(
        {
            NameObject("/Type"): NameObject("/Font"),
            NameObject("/Subtype"): NameObject("/Type1"),
            NameObject("/BaseFont"): NameObject("/Helvetica"),
        }
    )
    resources = DictionaryObject(
        {
            NameObject("/Font"): DictionaryObject(
                {NameObject("/F1"): writer._add_object(font)}
            )
        }
    )
    content = DecodedStreamObject()
    content.set_data(f"BT /F1 12 Tf 72 200 Td ({text}) Tj ET".encode("ascii"))
    page[NameObject("/Resources")] = resources
    page[NameObject("/Contents")] = writer._add_object(content)
    output = BytesIO()
    writer.write(output)
    return output.getvalue()


def _docx_bytes(text: str) -> bytes:
    document = Document()
    document.add_paragraph(text)
    output = BytesIO()
    document.save(output)
    return output.getvalue()


@pytest.mark.parametrize(
    ("kind", "storage_key", "expected_type", "expected_text"),
    [
        ("pdf", "docs/sample.pdf", "pdf", "Hello PDF"),
        ("docx", "docs/sample.docx", "docx", "Hello DOCX"),
        ("text", "docs/sample.txt", "text", "纯文本内容"),
        ("markdown", "docs/sample.md", "markdown", "# Markdown 内容"),
    ],
)
def test_parser_extracts_supported_documents(
    kind: str,
    storage_key: str,
    expected_type: str,
    expected_text: str,
) -> None:
    data = {
        "pdf": lambda: _pdf_bytes("Hello PDF"),
        "docx": lambda: _docx_bytes("Hello DOCX"),
        "text": lambda: "纯文本内容".encode(),
        "markdown": lambda: "# Markdown 内容".encode(),
    }[kind]()
    parsed = DocumentParser().parse(storage_key, data)

    assert parsed.document_type == expected_type
    assert expected_text in parsed.text
    assert parsed.unit_count >= 1


def test_parser_rejects_extension_content_mismatch() -> None:
    with pytest.raises(UnsupportedDocumentTypeError, match="does not match"):
        DocumentParser().parse("docs/fake.txt", _pdf_bytes("not text"))


def test_parser_rejects_legacy_doc_and_empty_text() -> None:
    with pytest.raises(UnsupportedDocumentTypeError, match="extension"):
        DocumentParser().parse("docs/legacy.doc", b"legacy")
    with pytest.raises(EmptyDocumentError, match="no extractable text"):
        DocumentParser().parse("docs/empty.md", b"  \n")
