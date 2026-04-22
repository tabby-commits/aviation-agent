#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import io
import json
import re
import sys
import time
import unicodedata
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, Optional
from urllib import error, parse, request

DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_BATCH_SIZE = 50
DEFAULT_MAX_BATCH_BYTES = 5 * 1024 * 1024
DEFAULT_TIMEOUT_SECONDS = 900
DEFAULT_MAX_RETRIES = 1
DEFAULT_RETRY_BACKOFF_SECONDS = 2.0
DEFAULT_ENCODING = "auto"
COMMON_ENCODINGS = ("utf-8-sig", "utf-8", "cp1252", "gb18030", "latin-1")


@dataclass
class MarkdownDocument:
    row_number: int
    filename: str
    title: str
    markdown_bytes: bytes


@dataclass
class UploadResult:
    batch_id: Optional[str]
    status: str
    total_count: int
    message: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Stream a news CSV, build Markdown documents in memory, "
            "and upload them to the existing knowledge-base batch API."
        )
    )
    parser.add_argument("--csv-path", required=True, help="Path to the source CSV file.")
    parser.add_argument("--kb-id", required=True, help="Knowledge base id.")
    parser.add_argument(
        "--base-url",
        default=DEFAULT_BASE_URL,
        help=f"Backend base URL. Default: {DEFAULT_BASE_URL}",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=DEFAULT_BATCH_SIZE,
        help=f"Maximum Markdown files per batch. Default: {DEFAULT_BATCH_SIZE}",
    )
    parser.add_argument(
        "--max-batch-bytes",
        type=int,
        default=DEFAULT_MAX_BATCH_BYTES,
        help=(
            "Flush a batch when the accumulated Markdown payload reaches this many bytes. "
            f"Default: {DEFAULT_MAX_BATCH_BYTES}"
        ),
    )
    parser.add_argument(
        "--timeout-seconds",
        type=int,
        default=DEFAULT_TIMEOUT_SECONDS,
        help=f"HTTP request timeout per batch. Default: {DEFAULT_TIMEOUT_SECONDS}",
    )
    parser.add_argument(
        "--max-retries",
        type=int,
        default=DEFAULT_MAX_RETRIES,
        help=(
            "Maximum attempts per batch request. Default: 1. "
            "Increase carefully because the upload endpoint is not idempotent."
        ),
    )
    parser.add_argument(
        "--retry-backoff-seconds",
        type=float,
        default=DEFAULT_RETRY_BACKOFF_SECONDS,
        help=f"Base sleep between retries. Default: {DEFAULT_RETRY_BACKOFF_SECONDS}",
    )
    parser.add_argument(
        "--encoding",
        default=DEFAULT_ENCODING,
        help=(
            "CSV file encoding. Use 'auto' to probe common encodings. "
            f"Default: {DEFAULT_ENCODING}"
        ),
    )
    parser.add_argument(
        "--start-row",
        type=int,
        default=1,
        help="First CSV data row to process (1-based, header excluded). Default: 1",
    )
    parser.add_argument(
        "--row-limit",
        type=int,
        default=0,
        help="Maximum number of CSV data rows to process after start-row. Default: 0 (all rows).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Parse the CSV and build batches without calling the upload API.",
    )
    parser.add_argument("--url-column", default="url", help="CSV column for article URL. Default: url")
    parser.add_argument("--title-column", default="title", help="CSV column for title. Default: title")
    parser.add_argument("--date-column", default="date_text", help="CSV column for publish date. Default: date_text")
    parser.add_argument("--body-column", default="body", help="CSV column for article body. Default: body")
    args = parser.parse_args()

    if args.batch_size <= 0:
        parser.error("--batch-size must be > 0")
    if args.max_batch_bytes <= 0:
        parser.error("--max-batch-bytes must be > 0")
    if args.timeout_seconds <= 0:
        parser.error("--timeout-seconds must be > 0")
    if args.max_retries <= 0:
        parser.error("--max-retries must be > 0")
    if args.retry_backoff_seconds < 0:
        parser.error("--retry-backoff-seconds must be >= 0")
    if args.start_row <= 0:
        parser.error("--start-row must be > 0")
    if args.row_limit < 0:
        parser.error("--row-limit must be >= 0")
    return args


def set_csv_field_size_limit() -> None:
    limit = sys.maxsize
    while True:
        try:
            csv.field_size_limit(limit)
            return
        except OverflowError:
            limit = limit // 10


def resolve_encoding(csv_path: Path, requested_encoding: str) -> str:
    if requested_encoding.lower() != "auto":
        print(f"[info] using CSV encoding {requested_encoding}")
        return requested_encoding

    errors: list[str] = []
    for encoding_name in COMMON_ENCODINGS:
        try:
            with csv_path.open("r", encoding=encoding_name, newline="") as handle:
                handle.read(16384)
            print(f"[info] using CSV encoding {encoding_name}")
            return encoding_name
        except UnicodeDecodeError as exc:
            errors.append(f"{encoding_name}: {exc}")

    joined_errors = "; ".join(errors)
    raise ValueError(f"Could not decode CSV with common encodings: {joined_errors}")


def collapse_whitespace(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def normalize_body(value: str) -> str:
    return value.replace("\r\n", "\n").replace("\r", "\n").strip()


def read_column(row: Dict[str, str], column: str) -> str:
    value = row.get(column)
    return value.strip() if value else ""


def derive_title(title: str, body: str, url: str, row_number: int) -> str:
    normalized_title = collapse_whitespace(title)
    if normalized_title:
        return normalized_title

    body_preview = collapse_whitespace(body)
    if body_preview:
        return body_preview[:80].rstrip()

    normalized_url = collapse_whitespace(url)
    if normalized_url:
        return normalized_url

    return f"news-{row_number:06d}"


def slugify_filename(title: str, row_number: int) -> str:
    normalized = unicodedata.normalize("NFKD", title).encode("ascii", "ignore").decode("ascii")
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", normalized.lower()).strip("-")
    slug = slug[:60].strip("-")
    if not slug:
        slug = "news"
    return f"news-{row_number:06d}-{slug}.md"


def render_markdown(title: str, url: str, date_text: str, body: str) -> bytes:
    lines = [f"# {title}", ""]
    if url:
        lines.append(f"Source: {url}")
    if date_text:
        lines.append(f"Date: {date_text}")
    if url or date_text:
        lines.append("")
    if body:
        lines.append(body)
    else:
        lines.append("No article body was provided.")
    markdown = "\n".join(lines).strip() + "\n"
    return markdown.encode("utf-8")


def build_document(row: Dict[str, str], args: argparse.Namespace, row_number: int) -> Optional[MarkdownDocument]:
    url = read_column(row, args.url_column)
    title_value = read_column(row, args.title_column)
    date_text = read_column(row, args.date_column)
    body = normalize_body(read_column(row, args.body_column))

    if not any([url, title_value, date_text, body]):
        return None

    title = derive_title(title_value, body, url, row_number)
    filename = slugify_filename(title, row_number)
    markdown_bytes = render_markdown(title, url, date_text, body)
    return MarkdownDocument(
        row_number=row_number,
        filename=filename,
        title=title,
        markdown_bytes=markdown_bytes,
    )


def iter_documents(csv_path: Path, args: argparse.Namespace) -> Iterable[Optional[MarkdownDocument]]:
    set_csv_field_size_limit()
    encoding_name = resolve_encoding(csv_path, args.encoding)
    with csv_path.open("r", encoding=encoding_name, newline="") as handle:
        reader = csv.DictReader(handle)
        if not reader.fieldnames:
            raise ValueError("CSV header is missing.")

        required_columns = [
            args.url_column,
            args.title_column,
            args.date_column,
            args.body_column,
        ]
        missing_columns = [column for column in required_columns if column not in reader.fieldnames]
        if missing_columns:
            raise ValueError(f"CSV is missing required columns: {', '.join(missing_columns)}")

        processed_rows = 0
        for data_row_number, row in enumerate(reader, start=1):
            if data_row_number < args.start_row:
                continue
            if args.row_limit and processed_rows >= args.row_limit:
                break

            processed_rows += 1
            document = build_document(row, args, data_row_number)
            yield document


def human_bytes(size: int) -> str:
    units = ["B", "KiB", "MiB", "GiB"]
    value = float(size)
    for unit in units:
        if value < 1024 or unit == units[-1]:
            return f"{value:.1f} {unit}"
        value /= 1024
    return f"{size} B"


def build_batch_payload(documents: Iterable[MarkdownDocument]) -> tuple[bytes, str]:
    boundary = f"----JChatMindBatch{uuid.uuid4().hex}"
    payload = io.BytesIO()
    for document in documents:
        payload.write(f"--{boundary}\r\n".encode("utf-8"))
        payload.write(
            (
                f'Content-Disposition: form-data; name="files"; '
                f'filename="{document.filename}"\r\n'
            ).encode("utf-8")
        )
        payload.write(b"Content-Type: text/markdown; charset=utf-8\r\n\r\n")
        payload.write(document.markdown_bytes)
        payload.write(b"\r\n")
    payload.write(f"--{boundary}--\r\n".encode("utf-8"))
    return payload.getvalue(), boundary


def parse_upload_response(payload: Dict[str, object]) -> UploadResult:
    code = payload.get("code")
    message = str(payload.get("message") or "")
    if code != 200:
        raise RuntimeError(f"Upload API returned code={code}, message={message}")

    data = payload.get("data")
    if not isinstance(data, dict):
        raise RuntimeError("Upload API response did not include a data object.")

    batch_id = data.get("batchId")
    status = str(data.get("status") or "")
    total_count = int(data.get("totalCount") or 0)
    batch_message = str(data.get("message") or message)
    return UploadResult(
        batch_id=str(batch_id) if batch_id is not None else None,
        status=status,
        total_count=total_count,
        message=batch_message,
    )


def should_retry(exc: Exception) -> bool:
    if isinstance(exc, error.HTTPError):
        return exc.code >= 500
    return True


def upload_batch(documents: list[MarkdownDocument], args: argparse.Namespace, batch_index: int) -> UploadResult:
    if args.dry_run:
        print(
            f"[dry-run] batch={batch_index} docs={len(documents)} "
            f"bytes={human_bytes(sum(len(doc.markdown_bytes) for doc in documents))} "
            f"rows={documents[0].row_number}-{documents[-1].row_number}"
        )
        return UploadResult(batch_id=None, status="DRY_RUN", total_count=len(documents), message="dry run")

    payload, boundary = build_batch_payload(documents)
    endpoint = (
        args.base_url.rstrip("/")
        + "/api/documents/upload/batch?"
        + parse.urlencode({"kbId": args.kb_id})
    )
    payload_size = human_bytes(len(payload))

    last_error: Optional[Exception] = None
    for attempt in range(1, args.max_retries + 1):
        request_obj = request.Request(endpoint, data=payload, method="POST")
        request_obj.add_header("Accept", "application/json")
        request_obj.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
        request_obj.add_header("Content-Length", str(len(payload)))

        try:
            with request.urlopen(request_obj, timeout=args.timeout_seconds) as response:
                response_payload = json.load(response)
            result = parse_upload_response(response_payload)
            if result.status and result.status != "COMPLETED":
                raise RuntimeError(
                    f"Batch {batch_index} finished with status={result.status}, "
                    f"message={result.message}"
                )

            print(
                f"[uploaded] batch={batch_index} docs={len(documents)} payload={payload_size} "
                f"status={result.status or 'UNKNOWN'} batchId={result.batch_id or '-'}"
            )
            return result
        except Exception as exc:
            last_error = exc
            retryable = attempt < args.max_retries and should_retry(exc)
            detail = exc.read().decode("utf-8", "replace") if isinstance(exc, error.HTTPError) else str(exc)
            print(
                f"[error] batch={batch_index} attempt={attempt}/{args.max_retries} "
                f"retryable={str(retryable).lower()} detail={detail}",
                file=sys.stderr,
            )
            if not retryable:
                break
            sleep_seconds = args.retry_backoff_seconds * (2 ** (attempt - 1))
            if sleep_seconds > 0:
                time.sleep(sleep_seconds)

    raise RuntimeError(f"Batch {batch_index} failed after {args.max_retries} attempt(s): {last_error}")


def flush_batch(
    documents: list[MarkdownDocument],
    args: argparse.Namespace,
    batch_index: int,
) -> UploadResult:
    if not documents:
        raise ValueError("flush_batch received an empty document list")
    return upload_batch(documents, args, batch_index)


def main() -> int:
    args = parse_args()
    csv_path = Path(args.csv_path).expanduser().resolve()
    if not csv_path.exists():
        print(f"CSV file does not exist: {csv_path}", file=sys.stderr)
        return 1

    total_docs = 0
    skipped_rows = 0
    batch_index = 0
    total_markdown_bytes = 0
    batch_documents: list[MarkdownDocument] = []
    batch_bytes = 0

    try:
        for document in iter_documents(csv_path, args):
            if document is None:
                skipped_rows += 1
                continue
            document_size = len(document.markdown_bytes)
            if document_size > args.max_batch_bytes:
                print(
                    f"[warn] row={document.row_number} document={human_bytes(document_size)} "
                    f"exceeds --max-batch-bytes={human_bytes(args.max_batch_bytes)}; uploading alone."
                )

            should_flush_first = batch_documents and (
                len(batch_documents) >= args.batch_size
                or batch_bytes + document_size > args.max_batch_bytes
            )
            if should_flush_first:
                batch_index += 1
                flush_batch(batch_documents, args, batch_index)
                total_docs += len(batch_documents)
                total_markdown_bytes += batch_bytes
                batch_documents = []
                batch_bytes = 0

            batch_documents.append(document)
            batch_bytes += document_size

            if len(batch_documents) >= args.batch_size or batch_bytes >= args.max_batch_bytes:
                batch_index += 1
                flush_batch(batch_documents, args, batch_index)
                total_docs += len(batch_documents)
                total_markdown_bytes += batch_bytes
                batch_documents = []
                batch_bytes = 0
        if batch_documents:
            batch_index += 1
            flush_batch(batch_documents, args, batch_index)
            total_docs += len(batch_documents)
            total_markdown_bytes += batch_bytes

    except (ValueError, UnicodeError) as exc:
        print(f"Input error: {exc}", file=sys.stderr)
        return 1
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(
        f"[done] docs={total_docs} batches={batch_index} "
        f"markdownBytes={human_bytes(total_markdown_bytes)} skippedRows={skipped_rows} "
        f"mode={'dry-run' if args.dry_run else 'upload'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
