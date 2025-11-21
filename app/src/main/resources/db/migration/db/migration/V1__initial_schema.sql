-- Users table
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Seed data
INSERT INTO users (name, email) VALUES 
    ('Alice Smith', 'alice@example.com'),
    ('Bob Jones', 'bob@example.com'),
    ('Charlie Brown', 'charlie@example.com');

-- API polling state
CREATE TABLE api_poll_state (
    id SERIAL PRIMARY KEY,
    api_name VARCHAR(100) NOT NULL UNIQUE,
    last_poll_time TIMESTAMP,
    last_success_time TIMESTAMP,
    last_error TEXT,
    poll_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Compliance issues
CREATE TABLE compliance_issues (
    id SERIAL PRIMARY KEY,
    issue_type VARCHAR(100) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    description TEXT NOT NULL,
    affected_entity_id INTEGER,
    affected_entity_type VARCHAR(50),
    detected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    alert_sent_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'open'
);

CREATE INDEX idx_compliance_status ON compliance_issues(status);
CREATE INDEX idx_compliance_detected ON compliance_issues(detected_at);