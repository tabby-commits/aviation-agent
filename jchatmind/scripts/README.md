# CSV News Importer

`import_news_csv_to_kb.py` streams a CSV file, builds Markdown documents in memory, and uploads them to the existing `POST /api/documents/upload/batch` endpoint.

## What it does

- Reads the CSV incrementally instead of loading the whole file into memory.
- Converts each news row into one Markdown document in memory.
- Sends Markdown documents in small multipart batches.
- Avoids creating local temporary `.md` files before upload.
- Supports a `--dry-run` mode for parsing and batching validation.

## Default CSV mapping

- `url` -> source URL
- `title` -> Markdown heading
- `date_text` -> date line
- `body` -> Markdown body

Generated Markdown format:

```markdown
# {title}

Source: {url}
Date: {date_text}

{body}
```

## Requirements

- Python 3.9+
- Running backend that exposes `http://localhost:8080/api/documents/upload/batch`

## Dry run

```powershell
python .\scripts\import_news_csv_to_kb.py `
  --dry-run `
  --csv-path ".\data\documents\f25be007-bcdf-4b2a-93c3-89f5a02fd377\46e3912e-eab1-471a-a726-397caa130f3d\launch-archive-merged.csv" `
  --kb-id your-kb-id `
  --row-limit 100
```

## Real upload

```powershell
python .\scripts\import_news_csv_to_kb.py `
  --csv-path ".\data\documents\f25be007-bcdf-4b2a-93c3-89f5a02fd377\46e3912e-eab1-471a-a726-397caa130f3d\launch-archive-merged.csv" `
  --kb-id your-kb-id `
  --batch-size 50 `
  --max-batch-bytes 5242880
```

## Useful options

- `--base-url` to point at a different backend address.
- `--encoding` to force a specific CSV encoding, or leave it as `auto`.
- `--start-row` and `--row-limit` for incremental imports.
- `--batch-size` and `--max-batch-bytes` to tune memory usage.
- `--max-retries` for transient failures.

## Notes

- The script does not create temporary Markdown files on the client side.
- The script defaults to `--encoding auto` and probes common encodings such as `utf-8`, `cp1252`, and `gb18030`.
- The backend still stores the uploaded Markdown files under its document storage directory because that is how the existing upload API works.
- The batch upload API is not idempotent. Automatic retries can create duplicate documents if the server processed a batch before the client saw the error. The script defaults to `--max-retries 1` for that reason.

## Future work

- If CSV import becomes a long-term product path, add native CSV parsing on the backend and write chunks directly instead of bridging through Markdown uploads.
- Native CSV import would also be the right place to switch embeddings from `title only` to `title + summary/body`.
