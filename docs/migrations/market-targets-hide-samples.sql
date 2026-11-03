-- Run against the intended production database after reviewing the affected rows.
-- Keep IDs and historical news/session links; only remove samples from the active market list.
UPDATE market_targets
SET is_active=false, updated_at=CURRENT_TIMESTAMP
WHERE calendar_code='SAMPLE' AND data_symbol='SAMPLE'
  AND code IN ('SAMPLE_US', 'SAMPLE_KR') AND is_active=true;
