from datetime import date, timedelta

from graph.nodes.insight import _merchant_outliers, _spend_spike


def _expense(merchant, amount, days_ago, currency="BGN"):
    return {
        "merchant": merchant,
        "amount": amount,
        "currency": currency,
        "expenseDate": (date.today() - timedelta(days=days_ago)).isoformat(),
    }


class TestMerchantOutliers:
    def test_flags_amount_far_above_merchant_history(self):
        # NOTE: by_merchant is built from ALL expenses including the recent one
        # being judged — the recent expense contributes to its own baseline,
        # which dampens the z-score. 5 tight baseline points + one extreme
        # outlier is needed to still clear the threshold under that effect.
        expenses = [
            _expense("Kaufland", 38, 60),
            _expense("Kaufland", 40, 50),
            _expense("Kaufland", 42, 45),
            _expense("Kaufland", 39, 35),
            _expense("Kaufland", 41, 30),
            _expense("Kaufland", 300, 1),  # way above the ~40 baseline
        ]
        result = _merchant_outliers(expenses, recent_cutoff=date.today() - timedelta(days=7))
        assert len(result) == 1
        assert "Kaufland" in result[0]
        assert "300.00" in result[0]

    def test_no_flag_with_insufficient_history(self):
        # Only 2 total points for this merchant (baseline + the recent one
        # itself) — insight.py requires len(history) >= 3 before judging
        # "normal", regardless of how extreme the amount is.
        expenses = [
            _expense("NewShop", 40, 10),
            _expense("NewShop", 500, 1),
        ]
        result = _merchant_outliers(expenses, recent_cutoff=date.today() - timedelta(days=7))
        assert result == []

    def test_no_flag_when_all_amounts_identical(self):
        # Every expense (including the recent one) is the same amount ->
        # stdev == 0 -> the node must skip rather than divide by zero.
        expenses = [
            _expense("Coffee", 5, 60),
            _expense("Coffee", 5, 45),
            _expense("Coffee", 5, 30),
            _expense("Coffee", 5, 1),
        ]
        result = _merchant_outliers(expenses, recent_cutoff=date.today() - timedelta(days=7))
        assert result == []

    def test_ignores_expenses_outside_recent_window(self):
        expenses = [
            _expense("Kaufland", 40, 60),
            _expense("Kaufland", 42, 45),
            _expense("Kaufland", 38, 30),
            _expense("Kaufland", 250, 20),  # outlier amount, but outside the recent window
        ]
        result = _merchant_outliers(expenses, recent_cutoff=date.today() - timedelta(days=7))
        assert result == []

    def test_no_flag_for_normal_amount(self):
        expenses = [
            _expense("Kaufland", 40, 60),
            _expense("Kaufland", 42, 45),
            _expense("Kaufland", 38, 30),
            _expense("Kaufland", 41, 1),
        ]
        result = _merchant_outliers(expenses, recent_cutoff=date.today() - timedelta(days=7))
        assert result == []


class TestSpendSpike:
    def test_flags_spike_above_ratio_threshold(self):
        today = date.today()
        expenses = [_expense("A", 20, d) for d in range(8, 90, 7)]  # ~20/week baseline
        expenses += [_expense("B", 200, 1)]  # this week is way above baseline
        result = _spend_spike(expenses, today)
        assert len(result) == 1
        assert "200.00" in result[0]
        assert "x" in result[0]

    def test_no_flag_under_threshold(self):
        today = date.today()
        expenses = [_expense("A", 20, d) for d in range(8, 90, 7)]
        expenses += [_expense("B", 22, 1)]  # in line with baseline
        result = _spend_spike(expenses, today)
        assert result == []

    def test_no_baseline_data_returns_empty(self):
        today = date.today()
        expenses = [_expense("A", 500, 1)]  # only recent spend, no baseline history
        result = _spend_spike(expenses, today)
        assert result == []
