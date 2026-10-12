"""
Multi-Model Smart Router for CodeMesh v2.0.

Analyzes input intent and routes inference requests to specialized LLMs
(CodeLlama for coding, Mistral for reasoning/math, Llama3 for general conversation).
"""

from __future__ import annotations

import logging
import re
from typing import Any, Dict, List, Optional
import aiohttp

logger = logging.getLogger("codemesh.scheduler.router")
if not logger.handlers:
    logging.basicConfig(level=logging.INFO)


class ModelRouter:
    """
    Intelligent query router with keyword heuristics and model capability matching.
    """

    MODELS: Dict[str, Dict[str, Any]] = {
        "codellama:7b": {
            "specialty": "code",
            "keywords": [
                "write", "debug", "function", "class", "error", "fix",
                "code", "implement", "python", "javascript", "api",
                "rust", "cpp", "c++", "c#", "html", "css", "sql",
                "compiler", "exception", "async", "await", "refactor",
                "syntax", "regex", "git", "docker", "script",
            ],
        },
        "llama3:8b": {
            "specialty": "general",
            "keywords": [
                "explain", "what", "how", "why", "tell", "describe",
                "help", "learn", "summary", "translate", "story",
                "rewrite", "draft", "email", "poem", "essay",
            ],
        },
        "mistral:7b": {
            "specialty": "reasoning",
            "keywords": [
                "compare", "analyze", "logic", "math", "calculate",
                "reason", "evaluate", "optimize", "benchmark", "deduce",
                "puzzle", "proof", "strategy", "probability", "tradeoff",
            ],
        },
    }

    DEFAULT_MODEL: str = "llama3:8b"

    def __init__(self, ollama_url: str = "http://localhost:11434") -> None:
        self.ollama_url = ollama_url.rstrip("/")

    def route(self, messages: list, preferred_model: Optional[str] = None) -> str:
        """
        Determine the best model for this query.
        If user explicitly specified a known model (other than 'auto' or empty), use it.
        Otherwise, auto-route via keyword classification.
        """
        if preferred_model and preferred_model.lower() not in ("auto", "none", ""):
            # Match directly or by prefix
            pref_clean = preferred_model.strip().lower()
            for model_name in self.MODELS:
                if pref_clean == model_name.lower() or pref_clean == model_name.split(":")[0].lower():
                    logger.debug("User preferred model honoured: %s", model_name)
                    return model_name
            # If user provided a specific custom tag, return as specified
            return preferred_model

        # Extract text from latest user messages
        combined_text = self._extract_recent_user_text(messages)
        classified_model = self._classify_query(combined_text)
        logger.info("Auto-routed query to model: %s", classified_model)
        return classified_model

    async def get_available_models(self) -> List[str]:
        """Query Ollama /api/tags for loaded models."""
        endpoint = f"{self.ollama_url}/api/tags"
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(endpoint, timeout=aiohttp.ClientTimeout(total=5.0)) as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        models = [m.get("name") for m in data.get("models", []) if m.get("name")]
                        return models
        except Exception as exc:
            logger.warning("Could not retrieve model tags from %s: %s", endpoint, exc)

        return list(self.MODELS.keys())

    def _classify_query(self, text: str) -> str:
        """
        Keyword-based classification. Scores matching tokens and returns top model name.
        """
        if not text:
            return self.DEFAULT_MODEL

        text_lower = text.lower()
        words = set(re.findall(r"\b[a-z0-9_+#]+\b", text_lower))

        scores: Dict[str, int] = {}
        for model_name, info in self.MODELS.items():
            keywords = info.get("keywords", [])
            # Count keyword hits
            score = sum(1 for kw in keywords if kw in words or (len(kw) > 4 and kw in text_lower))
            scores[model_name] = score

        best_model = max(scores, key=lambda k: scores[k])
        if scores[best_model] > 0:
            return best_model

        return self.DEFAULT_MODEL

    def _extract_recent_user_text(self, messages: list) -> str:
        """Aggregates content from user messages for semantic routing."""
        user_texts = []
        for msg in reversed(messages):
            if isinstance(msg, dict) and msg.get("role") == "user":
                user_texts.append(msg.get("content", ""))
                if len(user_texts) >= 2:
                    break
        return " ".join(reversed(user_texts))
