#!/usr/bin/env python3
"""
warc_truncated.py - list the truncated records of one WARC file.

Usage:   python3 warc_truncated.py FILE.warc[.gz] > truncated.tsv

Output:  one line per truncated record, tab separated, no header line:
             url <TAB> expected size <TAB> actual size <TAB> missing size      (all sizes in bytes)

A response record is "truncated" when the payload stored in the WARC is SHORTER than the Content-Length that the web
server announced in its own HTTP headers:  expected = announced Content-Length,  actual = bytes in the WARC,
missing = expected - actual.

What is checked, and what is not:
  * Only WARC-Type "response" records with an HTTP Content-Length header are checked.
  * Responses with Transfer-Encoding: chunked have no announced length, so their size cannot be checked.
  * 1xx, 204 and 304 responses are skipped (they announce a length but carry no body).
  * A response to a HEAD request also announces a length but has no body. This cannot be seen in the response
    record itself, so such records would be listed by mistake (crawls that send HEAD requests should filter the output).
  * Records the crawler itself marked "WARC-Truncated" but that announce no length are counted on stderr. Add
    --include-unknown to list them too, with "-" as expected and missing size.
  * A one-line summary is written to stderr (redirect it away with 2>/dev/null).

Plain .warc files are read quickly (the script jumps from record to record); .warc.gz files must be decompressed to be
read. Python 3 standard library only.
"""
import gzip
import re
import sys


def clean_url(url):
    if url.startswith("<") and url.endswith(">"):     # some writers wrap WARC-Target-URI in angle brackets
        url = url[1:-1]
    return url.replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D")


def scan(path, include_unknown):
    f = gzip.open(path, "rb") if path.endswith(".gz") else open(path, "rb")
    scanned = truncated = unknown = 0
    while True:
        line = f.readline()
        if not line:
            break
        if not line.startswith(b"WARC/"):
            continue
        warc = {}
        while True:
            h = f.readline()
            if h in (b"\r\n", b"\n", b""):
                break
            k, _, v = h.decode("utf-8", "replace").partition(":")
            warc[k.strip().lower()] = v.strip()
        length = int(warc.get("content-length") or 0)
        block_end = f.tell() + length
        if warc.get("warc-type") == "response":
            scanned += 1
            head = f.read(min(length, 262144))
            sep = head.find(b"\r\n\r\n")
            if sep >= 0:
                payload_len = length - (sep + 4)
                lines = head[:sep].decode("latin-1").split("\r\n")
                m = re.match(r"HTTP/\S+\s+(\d{3})", lines[0])
                status = int(m.group(1)) if m else 0
                http = {}
                for ln in lines[1:]:
                    k, _, v = ln.partition(":")
                    http.setdefault(k.strip().lower(), v.strip())
                chunked = "chunked" in http.get("transfer-encoding", "").lower()
                announced = http.get("content-length", "")
                expected = int(announced) if (announced.isdigit() and not chunked) else None
                has_body = not (100 <= status < 200 or status in (204, 304))
                url = clean_url(warc.get("warc-target-uri", ""))
                if has_body and expected is not None and payload_len < expected:
                    truncated += 1
                    print(f"{url}\t{expected}\t{payload_len}\t{expected - payload_len}")
                elif has_body and expected is None and warc.get("warc-truncated"):
                    unknown += 1
                    if include_unknown:
                        print(f"{url}\t-\t{payload_len}\t-")
        f.seek(block_end)
    if include_unknown:
        note = f" + {unknown:,} marked WARC-Truncated by the crawler without an announced length (listed with '-' as expected and missing size)"
        print(f"{scanned:,} response records scanned, {truncated:,} truncated with known sizes{note}; {truncated + unknown:,} lines written", file=sys.stderr)
    else:
        note = f"; {unknown:,} more were marked WARC-Truncated by the crawler but announce no length (--include-unknown lists them)" if unknown else ""
        print(f"{scanned:,} response records scanned, {truncated:,} truncated{note}", file=sys.stderr)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        sys.exit(__doc__)
    try:
        scan(args[0], "--include-unknown" in sys.argv)
    except BrokenPipeError:                                   # e.g. piped into head
        sys.stderr.close()