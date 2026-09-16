from __future__ import annotations

import re

_UNSAFE_PROPOSAL_TEXT = re.compile(
    r"(?a:\b[a-z][a-z0-9+.-]*:)|//|www\.|"
    r"(?:[^\W_][\w-]*\.)+[^\W_][\w-]*(?:[/:?#]|\b)|"
    r"(?:\d{1,3}\.){3}\d{1,3}(?:[/:?#]|\b)|"
    r"[\[\]<>`#*_]|~~|"
    r"(?:^|\n)\s{0,3}(?:[-+]\s|\d+[.)]\s|(?:=+|-{2,})\s*(?:\n|$))|"
    r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]",
    re.IGNORECASE,
)


def is_safe_proposal_text(value: str) -> bool:
    return bool(value.strip()) and _UNSAFE_PROPOSAL_TEXT.search(value) is None
