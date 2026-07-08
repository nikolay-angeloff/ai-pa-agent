// Mirrors facade DTOs — com.expense.facade.expense.dto.*

export interface ExpenseView {
  id: string;
  merchant: string | null;
  amount: number;
  currency: string;
  expenseDate: string; // ISO date, e.g. "2026-07-01"
  category: string | null;
  confidence: number | null;
  score: number | null; // set by /search (semantic), always null from the plain list endpoint
}

export interface CategorySummary {
  category: string;
  total: number;
  count: number;
}

export interface SummaryResponse {
  grandTotal: number;
  byCategory: CategorySummary[];
}

// Mirrors com.expense.facade.extraction.dto.ExtractionResult
export interface ExtractionResult {
  documentType: string | null;
  merchant: string | null;
  amount: number | null;
  currency: string | null;
  date: string | null; // "YYYY-MM-DD"
  confidence: number | null;
}

// Mirrors com.expense.facade.extraction.dto.DocumentReviewView
export interface DocumentReviewView {
  id: string;
  subject: string | null;
  sender: string | null;
  receivedAt: string; // ISO instant
  extractedFields: ExtractionResult | null;
}

// Mirrors com.expense.facade.extraction.dto.ApproveRequest — send undefined
// to accept as-is, or the FULL corrected set (the agent replaces fields
// wholesale on resume, it does not merge partial corrections).
export interface ApproveRequest {
  documentType: string | null;
  merchant: string | null;
  amount: number | null;
  currency: string | null;
  date: string | null;
}

// Mirrors com.expense.facade.extraction.dto.ExtractionDetail
export interface ExtractionDetail {
  documentId: string;
  status: string;
  result: ExtractionResult | null;
}
