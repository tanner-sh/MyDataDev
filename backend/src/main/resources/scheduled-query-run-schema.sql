CREATE TABLE scheduled_query_run (
    id VARCHAR(36) PRIMARY KEY,
    task_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    task_name VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL,
    message VARCHAR(1000),
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    file_name VARCHAR(240),
    file_path VARCHAR(2000),
    file_size BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_scheduled_run_task FOREIGN KEY (task_id) REFERENCES scheduled_query(id) ON DELETE CASCADE
);
CREATE INDEX idx_scheduled_run_task ON scheduled_query_run(task_id, started_at);
CREATE INDEX idx_scheduled_run_status ON scheduled_query_run(status, connection_id);
