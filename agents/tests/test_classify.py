import json
from dataclasses import dataclass
from unittest.mock import patch

from graph.nodes.classify import classify_node


@dataclass
class FakeResponse:
    content: str


def _state(subject="Grocery receipt", sender="shop@example.com", path="/tmp/receipt.jpg"):
    return {"subject": subject, "sender": sender, "raw_storage_path": path}


class TestClassifyNode:
    def test_valid_response_returns_document_type(self):
        fake = FakeResponse(content=json.dumps({"document_type": "receipt"}))
        with patch("graph.llm.ChatOpenAI.invoke",return_value=fake):
            result = classify_node(_state())
        assert result["document_type"] == "receipt"
        assert "error" not in result

    def test_unrecognized_document_type_falls_back_to_unknown(self):
        fake = FakeResponse(content=json.dumps({"document_type": "boarding_pass"}))
        with patch("graph.llm.ChatOpenAI.invoke",return_value=fake):
            result = classify_node(_state())
        assert result["document_type"] == "unknown"

    def test_llm_failure_degrades_to_unknown_with_error(self):
        with patch("graph.llm.ChatOpenAI.invoke",side_effect=RuntimeError("boom")):
            result = classify_node(_state())
        assert result["document_type"] == "unknown"
        assert "boom" in result["error"]

    def test_malformed_json_degrades_to_unknown_with_error(self):
        fake = FakeResponse(content="not json")
        with patch("graph.llm.ChatOpenAI.invoke",return_value=fake):
            result = classify_node(_state())
        assert result["document_type"] == "unknown"
        assert result["error"] is not None
