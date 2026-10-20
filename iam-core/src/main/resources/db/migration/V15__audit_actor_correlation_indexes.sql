-- Complement the time/resource/type cursor indexes from V13; propagated to attached/future months.
CREATE INDEX event_log_actor_cursor_idx ON iam_audit.event_log(actor, occurred_at DESC, id DESC);
CREATE INDEX event_log_correlation_cursor_idx ON iam_audit.event_log(correlation_id, occurred_at DESC, id DESC);
