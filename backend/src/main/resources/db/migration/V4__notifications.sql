CREATE TABLE t_notification (
  notification_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  recipient_id VARCHAR(50) NOT NULL REFERENCES m_user(user_id) ON DELETE CASCADE,
  event_key VARCHAR(200) NOT NULL,
  event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('FILE_UPLOAD','DATA_IMPORT','DATA_UPDATE','DATA_DELETE','OPERATION_FAILED')),
  message VARCHAR(1000) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  read_at TIMESTAMPTZ,
  UNIQUE(recipient_id,event_key)
);
CREATE INDEX ix_notification_recipient_history ON t_notification(recipient_id,notification_id DESC);
CREATE INDEX ix_notification_unread ON t_notification(recipient_id) WHERE read_at IS NULL;
