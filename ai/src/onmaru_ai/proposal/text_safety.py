from __future__ import annotations

import re

_ASCII_SCHEME_TOKEN = re.compile(r"(?<![a-zA-Z0-9+.-])(?P<label>[a-zA-Z][a-zA-Z0-9+.-]*):")
_PROSE_LABELS = frozenset({"Reason", "Data", "File", "Intent", "Geo"})
_URI_STRUCTURE = re.compile(r"[/?:#@+()=]")
_UNSAFE_PROPOSAL_TEXT = re.compile(
    r"//|www\.|"
    r"(?:[^\W_][\w-]*\.)+[^\W_\d][\w-]*(?:[/:?#]|\b)|"
    r"(?:\d{1,3}\.){3}\d{1,3}(?:[/:?#]|\b)|"
    r"[\[\]<>`#*_]|~~|"
    r"(?:^|\n)\s{0,3}(?:[-+]\s|\d+[.)]\s|(?:=+|-{2,})\s*(?:\n|$))|"
    r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]",
    re.IGNORECASE,
)


def _is_allowed_prose_label(match: re.Match[str], value: str) -> bool:
    remainder = value[match.end() :]
    if match.group("label") not in _PROSE_LABELS:
        return False
    if not remainder or not remainder[0].isspace() or remainder.splitlines() != [remainder]:
        return False
    payload = remainder.strip()
    return bool(payload) and _URI_STRUCTURE.search(payload) is None


def _contains_uri_scheme(value: str) -> bool:
    return any(
        not _is_allowed_prose_label(match, value) for match in _ASCII_SCHEME_TOKEN.finditer(value)
    )


def is_safe_proposal_text(value: str) -> bool:
    return (
        bool(value.strip())
        and not _contains_uri_scheme(value)
        and _UNSAFE_PROPOSAL_TEXT.search(value) is None
    )
