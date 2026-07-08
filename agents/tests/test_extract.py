import json
from dataclasses import dataclass
from unittest.mock import patch

from graph.nodes.extract import extract_node


@dataclass
class FakeResponse:
    content: str


def _write_receipt(tmp_path):
    path = tmp_path / "receipt.txt"
    path.write_text("Kaufland, total 42.50 BGN, 2026-06-01")
    return str(path)


class TestExtractNode:
    def test_missing_file_requires_hitl_without_calling_llm(self, tmp_path):
        state = {"raw_storage_path": str(tmp_path / "does-not-exist.txt")}
        with patch("graph.llm.ChatOpenAI.invoke") as mock_invoke:
            result = extract_node(state)
        mock_invoke.assert_not_called()
        assert result["hitl_required"] is True
        assert result["confidence"] == 0.0
        assert result["error"] is not None

    def test_high_confidence_response_skips_hitl(self, tmp_path):
        path = _write_receipt(tmp_path)
        fake = FakeResponse(content=json.dumps({
            "merchant": "Kaufland", "amount": 42.5, "currency": "BGN",
            "date": "2026-06-01", "confidence": 0.95,
        }))
        with patch("graph.llm.ChatOpenAI.invoke", return_value=fake):
            result = extract_node({"raw_storage_path": path})
        assert result["hitl_required"] is False
        assert result["extracted_fields"]["merchant"] == "Kaufland"
        assert result["confidence"] == 0.95

    def test_low_confidence_response_requires_hitl(self, tmp_path):
        path = _write_receipt(tmp_path)
        fake = FakeResponse(content=json.dumps({
            "merchant": None, "amount": None, "currency": None,
            "date": None, "confidence": 0.3,
        }))
        with patch("graph.llm.ChatOpenAI.invoke", return_value=fake):
            result = extract_node({"raw_storage_path": path})
        assert result["hitl_required"] is True  # 0.3 < CONFIDENCE_THRESHOLD (0.75)

    def test_confidence_is_clamped_to_valid_range(self, tmp_path):
        path = _write_receipt(tmp_path)
        fake = FakeResponse(content=json.dumps({
            "merchant": "X", "amount": 1, "currency": "BGN",
            "date": "2026-06-01", "confidence": 1.7,
        }))
        with patch("graph.llm.ChatOpenAI.invoke", return_value=fake):
            result = extract_node({"raw_storage_path": path})
        assert result["confidence"] == 1.0

    def test_llm_failure_requires_hitl_with_error(self, tmp_path):
        path = _write_receipt(tmp_path)
        with patch("graph.llm.ChatOpenAI.invoke", side_effect=RuntimeError("boom")):
            result = extract_node({"raw_storage_path": path})
        assert result["hitl_required"] is True
        assert result["confidence"] == 0.0
        assert "boom" in result["error"]
