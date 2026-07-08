from graph.nodes.query import _fmt_amount, _format_summary_answer


class TestFmtAmount:
    def test_formats_numeric_value_to_two_decimals(self):
        assert _fmt_amount(42) == "42.00"
        assert _fmt_amount(42.5) == "42.50"
        assert _fmt_amount("42.5") == "42.50"

    def test_falls_back_to_str_for_non_numeric(self):
        assert _fmt_amount(None) == "None"
        assert _fmt_amount("n/a") == "n/a"


class TestFormatSummaryAnswer:
    def test_empty_category_breakdown(self):
        answer = _format_summary_answer({"grandTotal": 0, "byCategory": []}, "2026-01-01", "2026-01-31")
        assert "Няма разходи" in answer
        assert "2026-01-01" in answer and "2026-01-31" in answer

    def test_breakdown_sorted_by_total_descending(self):
        data = {
            "grandTotal": 150,
            "byCategory": [
                {"category": "groceries", "total": 50, "count": 3},
                {"category": "transport", "total": 100, "count": 2},
            ],
        }
        answer = _format_summary_answer(data, "2026-01-01", "2026-01-31")
        lines = answer.splitlines()
        transport_idx = next(i for i, l in enumerate(lines) if "transport" in l)
        groceries_idx = next(i for i, l in enumerate(lines) if "groceries" in l)
        assert transport_idx < groceries_idx  # higher total (100) listed first
        assert "150.00" in answer

    def test_missing_category_label_falls_back(self):
        data = {"grandTotal": 10, "byCategory": [{"category": None, "total": 10, "count": 1}]}
        answer = _format_summary_answer(data, "2026-01-01", "2026-01-31")
        assert "некатегоризирано" in answer
