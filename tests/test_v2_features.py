import asyncio
import unittest
from backend.scheduler.adaptive_quantum import AdaptiveQuantumManager
from backend.scheduler.speculative_warmer import SpeculativeWarmer
from backend.rag.search_engine import LocalOfflineRAG


class TestCodeMeshV2Features(unittest.TestCase):

    def test_adaptive_quantum_scaling(self):
        """Verifies that quantum scales down with increased concurrency."""
        aq = AdaptiveQuantumManager(base_quantum=512, min_quantum=128, max_quantum=1024)
        
        # 1 user
        q1 = aq.compute_quantum(active_users_count=1, queue_depth=0, priority_level=2)
        # 4 users
        q4 = aq.compute_quantum(active_users_count=4, queue_depth=0, priority_level=2)
        # 8 users
        q8 = aq.compute_quantum(active_users_count=8, queue_depth=0, priority_level=2)

        self.assertGreater(q1, q4, "Quantum for 1 user should be greater than 4 users")
        self.assertGreater(q4, q8, "Quantum for 4 users should be greater than 8 users")
        self.assertGreaterEqual(q8, 128, "Quantum should not drop below min_quantum (128)")

    def test_priority_and_starvation_burst(self):
        """Verifies that CRITICAL priority and starved jobs receive quantum bonuses."""
        aq = AdaptiveQuantumManager(base_quantum=512, min_quantum=128, max_quantum=1024)
        
        q_norm = aq.compute_quantum(active_users_count=2, queue_depth=1, priority_level=2, wait_time_seconds=0.0)
        q_crit = aq.compute_quantum(active_users_count=2, queue_depth=1, priority_level=0, wait_time_seconds=0.0)
        q_starved = aq.compute_quantum(active_users_count=2, queue_depth=1, priority_level=2, wait_time_seconds=20.0)

        self.assertGreater(q_crit, q_norm, "Critical task should receive priority multiplier")
        self.assertGreater(q_starved, q_norm, "Starved task should receive anti-starvation burst bonus")

    def test_rag_bm25_search(self):
        """Verifies local zero-dependency BM25 retrieval."""
        rag = LocalOfflineRAG()
        rag.add_document("doc-1", "FastAPI Server", "FastAPI backend handles REST and SSE streaming token slices.", "backend/main.py")
        rag.add_document("doc-2", "ESP8266 WiFi Bridge", "NodeMCU microcontroller serves the LLM-Bridge access point.", "firmware/codemesh_v2.ino")
        rag.add_document("doc-3", "Monaco Editor Component", "Monaco code editor with syntax highlighting and offline themes.", "frontend/index.html")
        rag.build_index()

        results = rag.search("WiFi NodeMCU bridge", top_k=2)
        self.assertTrue(len(results) > 0)
        self.assertEqual(results[0]["id"], "doc-2")

        code_results = rag.search("FastAPI SSE stream", top_k=1)
        self.assertTrue(len(code_results) > 0)
        self.assertEqual(code_results[0]["id"], "doc-1")

    def test_speculative_fallback_selection(self):
        """Verifies fallback model switching when primary is overloaded."""
        warmer = SpeculativeWarmer(fallback_model="llama3:8b")
        chosen_normal = warmer.select_model_with_fallback("codellama:7b", is_overloaded=False)
        chosen_overloaded = warmer.select_model_with_fallback("codellama:7b", is_overloaded=True)

        self.assertEqual(chosen_normal, "codellama:7b")
        self.assertEqual(chosen_overloaded, "llama3:8b")


if __name__ == "__main__":
    unittest.main()
