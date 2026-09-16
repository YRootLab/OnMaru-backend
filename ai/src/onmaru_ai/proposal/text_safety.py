from __future__ import annotations

import re

_KNOWN_URI_SCHEME = re.compile(
    r"(?<![a-z0-9+.-])"
    r"(?:javascript|vbscript|data|file|tel|sms|mailto|https?|ftps?|sftp|ssh|wss?|"
    r"blob|urn|geo|intent):",
    re.IGNORECASE,
)
_IMMEDIATE_PAYLOAD_URI_SCHEME = re.compile(
    r"(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*:(?!\s)",
    re.IGNORECASE,
)
_UNSAFE_PROPOSAL_TEXT = re.compile(
    r"//|www\.|"
    r"(?:[^\W_][\w-]*\.)+[^\W_\d][\w-]*(?:[/:?#]|\b)|"
    r"(?:\d{1,3}\.){3}\d{1,3}(?:[/:?#]|\b)|"
    r"[\[\]<>`#*_]|~~|"
    r"(?:^|\n)\s{0,3}(?:[-+]\s|\d+[.)]\s|(?:=+|-{2,})\s*(?:\n|$))|"
    r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]",
    re.IGNORECASE,
)


def _contains_uri_scheme(value: str) -> bool:
    return (
        _KNOWN_URI_SCHEME.search(value) is not None
        or _IMMEDIATE_PAYLOAD_URI_SCHEME.search(value) is not None
    )


def is_safe_proposal_text(value: str) -> bool:
    return (
        bool(value.strip())
        and not _contains_uri_scheme(value)
        and _UNSAFE_PROPOSAL_TEXT.search(value) is None
    )
