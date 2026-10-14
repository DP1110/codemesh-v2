"""
Offline Semantic & Lexical RAG Engine for CodeMesh.

Zero-dependency local BM25 + keyword search over local code files,
markdown project notes, and documentation for offline context augmentation.
"""

import math
import os
import re
from typing import List, Dict, Any, Tuple


class LocalOfflineRAG:
    """Pure-Python BM25 indexer & retriever for offline codebases."""

    def __init__(self, k1: float = 1.5, b: float = 0.75):
        self.k1 = k1
        self.b = b
        self.documents: List[Dict[str, Any]] = []
        self.doc_lengths: List[int] = []
        self.avg_doc_len: float = 0.0
        self.doc_freqs: Dict[str, int] = {}
        self.idf: Dict[str, float] = {}

    def _tokenize(self, text: str) -> List[str]:
        """Splits code and prose into normalized lowercase tokens."""
        # Split on symbols, camelCase, snake_case
        tokens = re.findall(r"[A-Za-z0-9_]{2,}", text.lower())
        return tokens

    def add_document(self, doc_id: str, title: str, content: str, filepath: str = ""):
        """Adds a document or code snippet to the index."""
        tokens = self._tokenize(f"{title} {content}")
        doc = {
            "id": doc_id,
            "title": title,
            "content": content,
            "filepath": filepath,
            "tokens": tokens,
        }
        self.documents.append(doc)

    def build_index(self):
        """Builds term frequencies, document frequencies, and BM25 IDF weights."""
        num_docs = len(self.documents)
        if num_docs == 0:
            return

        total_len = 0
        self.doc_lengths = []
        self.doc_freqs.clear()
        self.idf.clear()

        for doc in self.documents:
            t_len = len(doc["tokens"])
            self.doc_lengths.append(t_len)
            total_len += t_len
            seen_terms = set(doc["tokens"])
            for term in seen_terms:
                self.doc_freqs[term] = self.doc_freqs.get(term, 0) + 1

        self.avg_doc_len = total_len / float(num_docs)

        # Precompute IDF for each vocabulary term
        for term, freq in self.doc_freqs.items():
            # Standard Lucene/BM25 IDF formula
            self.idf[term] = math.log(1.0 + (num_docs - freq + 0.5) / (freq + 0.5))

    def search(self, query: str, top_k: int = 3) -> List[Dict[str, Any]]:
        """Searches indexed documents and returns ranked results with BM25 score."""
        query_tokens = self._tokenize(query)
        if not query_tokens or not self.documents:
            return []

        scores: List[Tuple[int, float]] = []

        for idx, doc in enumerate(self.documents):
            score = 0.0
            doc_len = self.doc_lengths[idx]
            token_counts: Dict[str, int] = {}
            for t in doc["tokens"]:
                token_counts[t] = token_counts.get(t, 0) + 1

            for q_term in query_tokens:
                if q_term not in self.idf:
                    continue
                tf = token_counts.get(q_term, 0)
                if tf == 0:
                    continue

                idf_val = self.idf[q_term]
                # BM25 term weighting formula
                numerator = tf * (self.k1 + 1.0)
                denominator = tf + self.k1 * (1.0 - self.b + self.b * (doc_len / (self.avg_doc_len or 1.0)))
                score += idf_val * (numerator / denominator)

            if score > 0.0:
                scores.append((idx, score))

        # Sort descending by score
        scores.sort(key=lambda x: x[1], reverse=True)

        results = []
        for idx, s in scores[:top_k]:
            doc_info = dict(self.documents[idx])
            doc_info.pop("tokens", None)
            doc_info["score"] = round(s, 4)
            results.append(doc_info)

        return results
