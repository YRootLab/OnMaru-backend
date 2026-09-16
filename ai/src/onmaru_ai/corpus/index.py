from __future__ import annotations

import hashlib
from collections.abc import Iterable
from dataclasses import dataclass
from math import sqrt
from typing import TYPE_CHECKING, Protocol

if TYPE_CHECKING:
    from .sync import CorpusDocument, InMemoryCorpusStore


class CorpusIndexingError(RuntimeError):
    """The complete revision could not be converted into searchable chunks."""


class TextChunker(Protocol):
    def split(self, text: str) -> tuple[str, ...]: ...


class EmbeddingProvider(Protocol):
    def embed(self, text: str) -> tuple[float, ...]: ...


@dataclass(frozen=True)
class CorpusChunk:
    revision_id: str
    document_id: str
    source_id: str
    chunk_id: str
    text: str
    embedding: tuple[float, ...]


class FixedWordChunker:
    def __init__(self, *, max_words: int) -> None:
        if max_words <= 0:
            raise ValueError("max_words must be positive")
        self._max_words = max_words

    def split(self, text: str) -> tuple[str, ...]:
        words = text.split()
        return tuple(
            " ".join(words[index : index + self._max_words])
            for index in range(0, len(words), self._max_words)
        )


class DeterministicEmbeddingProvider:
    """A local deterministic embedding profile for the private corpus baseline."""

    dimensions = 16

    def embed(self, text: str) -> tuple[float, ...]:
        digest = hashlib.sha256(text.encode()).digest()
        return tuple((byte - 127.5) / 127.5 for byte in digest[: self.dimensions])


class CorpusIndexService:
    def __init__(self, chunker: TextChunker, embeddings: EmbeddingProvider) -> None:
        self._chunker = chunker
        self._embeddings = embeddings

    def build(self, documents: Iterable[CorpusDocument]) -> tuple[CorpusChunk, ...]:
        chunks: list[CorpusChunk] = []
        for document in documents:
            document_chunks = self._chunker.split(document.text)
            if not document_chunks:
                raise CorpusIndexingError("CORPUS_DOCUMENT_EMPTY")
            for index, text in enumerate(document_chunks):
                embedding = self._embeddings.embed(text)
                if not embedding:
                    raise CorpusIndexingError("CORPUS_EMBEDDING_EMPTY")
                chunks.append(
                    CorpusChunk(
                        revision_id=document.revision_id,
                        document_id=document.document_id,
                        source_id=document.source_id,
                        chunk_id=f"{document.document_id}:{index}",
                        text=text,
                        embedding=embedding,
                    )
                )
        return tuple(chunks)


class EvidenceRetriever:
    def __init__(self, store: InMemoryCorpusStore, embeddings: EmbeddingProvider) -> None:
        self._store = store
        self._embeddings = embeddings

    def retrieve(
        self,
        query_text: str,
        *,
        revision_id: str,
        candidate_allowlist: frozenset[str],
        limit: int = 10,
    ) -> tuple[CorpusChunk, ...]:
        if limit <= 0 or not query_text.strip() or not candidate_allowlist:
            return ()
        if self._store.active_revision_id != revision_id:
            return ()

        query_embedding = self._embeddings.embed(query_text)
        candidates = [
            chunk
            for chunk in self._store.active_chunks
            if chunk.revision_id == revision_id and chunk.source_id in candidate_allowlist
        ]
        ranked = sorted(
            candidates,
            key=lambda chunk: (
                -self._cosine_similarity(query_embedding, chunk.embedding),
                chunk.document_id,
                chunk.chunk_id,
            ),
        )
        return tuple(ranked[:limit])

    @staticmethod
    def _cosine_similarity(left: tuple[float, ...], right: tuple[float, ...]) -> float:
        if len(left) != len(right):
            raise CorpusIndexingError("CORPUS_EMBEDDING_DIMENSION_MISMATCH")
        magnitude = sqrt(
            sum(value * value for value in left) * sum(value * value for value in right)
        )
        if magnitude == 0:
            return 0.0
        return sum(a * b for a, b in zip(left, right, strict=True)) / magnitude
